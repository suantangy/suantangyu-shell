package com.suantangyu.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台保活服务：拿住 mediaPlayback 类型的前台服务身份，
 * 进程优先级拉满（熄屏/切后台不被杀），通知栏常驻媒体控制条。
 *
 * 关键：创建一个真正的 MediaSessionCompat 并把它的 token 挂到通知上，
 * 系统（通知栏媒体卡片、灵动岛胶囊、锁屏播放器、蓝牙/耳机线控）才认得
 * "有音乐在播"。没有 session token 就只是一条普通通知。
 *
 * 状态全部由网页经 BaoBus 上报（曲名/歌手/封面/播放状态/进度/歌词行），
 * 单向同步，无回环。
 *
 * phase-2（原生播放）：网页解析出流地址后经 postPlayUrl 交给服务里的
 * ExoPlayer 解码，音频不再经过 WebView（熄屏被限流/冻结掐断的根源）。
 * 播放器状态经 __baoSync 同步回网页；老网页没有垫片时仍走 <audio> 上报的老路。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "bao_playback_v2"
        const val NOTIFY_ID = 1
        const val ACTION_UPDATE_TRACK = "com.suantangyu.player.UPDATE_TRACK"
        const val ACTION_UPDATE_STATE = "com.suantangyu.player.UPDATE_STATE"
        const val ACTION_UPDATE_PROGRESS = "com.suantangyu.player.UPDATE_PROGRESS"
        const val ACTION_UPDATE_LYRIC = "com.suantangyu.player.UPDATE_LYRIC"
        const val ACTION_TOGGLE = "com.suantangyu.player.TOGGLE"
        const val ACTION_NEXT = "com.suantangyu.player.NEXT"
        const val ACTION_PREV = "com.suantangyu.player.PREV"
        // phase-2 原生播放（网页垫片 → ExoPlayer）
        const val ACTION_PLAY_URL = "com.suantangyu.player.PLAY_URL"
        const val ACTION_NATIVE_PAUSE = "com.suantangyu.player.NATIVE_PAUSE"
        const val ACTION_NATIVE_RESUME = "com.suantangyu.player.NATIVE_RESUME"
        const val ACTION_NATIVE_SEEK = "com.suantangyu.player.NATIVE_SEEK"
        const val ACTION_NATIVE_RATE = "com.suantangyu.player.NATIVE_RATE"
        const val ACTION_NATIVE_STOP = "com.suantangyu.player.NATIVE_STOP"
        const val ACTION_HELLO = "com.suantangyu.player.HELLO"
        // phase-2.5 预置续播（网页提前把后续曲目交给服务，播完自动接播）
        const val ACTION_ARM_NEXT = "com.suantangyu.player.ARM_NEXT"
        const val ACTION_DISARM_NEXT = "com.suantangyu.player.DISARM_NEXT"
        // 3.3.0 原生解析：网页把音源配置与后续队列交给服务，服务用 QuickJS
        // 引擎自己解析回填预置跑道，界面页熄屏冻结时连播不再断链
        const val ACTION_SOURCES_CONFIG = "com.suantangyu.player.SOURCES_CONFIG"
        const val ACTION_QUEUE_AHEAD = "com.suantangyu.player.QUEUE_AHEAD"
        const val ACTION_CURRENT_SONG = "com.suantangyu.player.CURRENT_SONG"
        // 单脑第 0 步（3.6.0）：完整队列快照（影子队列，只收存对账）
        const val ACTION_QUEUE_SNAPSHOT = "com.suantangyu.player.QUEUE_SNAPSHOT"
    }

    private var title: String = "酸汤鱼音乐"
    private var artist: String = "准备播放"
    private var playing: Boolean = false
    private var positionMs: Long = 0L
    /** 上次重发通知时间（进度刷新节流用） */
    private var lastNotifyMs: Long = 0L
    private var durationMs: Long = 0L

    /**
     * 进度自走表：网页退后台后它的 1s 定时器会被 Chromium 节流，进度报不上来。
     * 壳是原生前台服务，定时器不受网页节流影响——用"锚点"（网页最后报的位置+系统时间）
     * 自己每秒推算当前位置并刷会话/通知；网页偶尔报上来的进度只当校准锚点。
     */
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var anchorPosMs: Long = 0L
    private var anchorAtMs: Long = 0L
    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                if (nativeActive && player != null) {
                    // 原生模式：位置直接读播放器（真实值），每秒对齐一次锚点并同步给网页
                    val prevSec = positionMs / 1000
                    syncAnchorFromPlayer()
                    // 3.1.1：进度过 85% 或剩余 <25s 时再同步一次队列——补 ARM
                    // 到达时播放器 IDLE / 索引无效导致同步提前 return 的缝；
                    // 前缀对上即 return，成本低；只同步已预置清单，不重新解析地址。
                    if (durationMs > 15000 && positionMs > 0 &&
                        (positionMs * 100 / durationMs >= 85 || durationMs - positionMs < 25000)
                    ) {
                        try {
                            syncUpcomingToPlayer()
                        } catch (_: Exception) {
                        }
                    }
                    // 3.3.0：跑道见底且网页不补仓时，壳自己解析后续歌曲回填
                    try {
                        maybeEngineResolve()
                    } catch (_: Exception) {
                    }
                    // 3.3.1 诊断：播放器 ENDED 且跑道为空持续 10 秒 = 断播点，
                    // 把当时的引擎计数记进日志环，页面重开时随 hello 推出
                    try {
                        val pNow = player
                        if (pNow != null && pNow.playbackState == Player.STATE_ENDED &&
                            armedItems.isEmpty()
                        ) {
                            val nNow = android.os.SystemClock.elapsedRealtime()
                            if (stallSinceMs == 0L) stallSinceMs = nNow
                            if (nNow - stallSinceMs > 10000 && !stallLogged) {
                                stallLogged = true
                                engineLog("引擎：断播点 " + engineCounters())
                            }
                        } else {
                            stallSinceMs = 0L
                            stallLogged = false
                        }
                    } catch (_: Exception) {
                    }
                    if (playing && positionMs / 1000 != prevSec) {
                        updateSessionState()
                        updateNotification()
                        pushSync(
                            JSONObject()
                                .put("t", "pos")
                                .put("pos", positionMs)
                                .put("dur", durationMs)
                        )
                    }
                } else if (playing) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    var cur = anchorPosMs + (now - anchorAtMs)
                    if (durationMs > 0 && cur > durationMs) cur = durationMs
                    if (cur < 0) cur = 0
                    // 秒变了才刷，避免同一秒重复重发通知
                    if (cur / 1000 != positionMs / 1000) {
                        positionMs = cur
                        updateSessionState()
                        updateNotification()
                    }
                }
            } catch (_: Exception) {
            }
            tickHandler.postDelayed(this, 1000)
        }
    }

    /** 锁屏歌词行（网页上报）；非空时卡片标题显示歌词，歌名/歌手降为副标题 */
    private var lyricLine: String = ""
    private var lyricSub: String = ""

    private var coverUrl: String = ""
    private var coverBitmap: Bitmap? = null

    // —— phase-2 原生播放：ExoPlayer 住在服务里，音频解码不再经过 WebView ——
    // 网页只负责用音源脚本解析出流地址，postPlayUrl 交过来；播放/暂停/进度/
    // 结束/出错全部以播放器为准，再经 __baoSync 同步回网页驱动它的 UI 与切歌逻辑。
    private var player: ExoPlayer? = null
    private var nativeUrl: String = ""
    private var nativeKey: String = "" // 当前曲编号（建项/重装沿用，阶梯查档案的凭据）
    /** 播放器里装着网页交给我们的流（此后页面上报的 state/progress 只是回声，忽略） */
    private var nativeActive: Boolean = false
    private var pendingRate: Float = 1f

    // —— phase-2.5 预置续播：网页提前把接下来 1~2 首的流地址预置给服务。
    // 当前曲播完时先等一个宽限期（网页醒着会自己切歌，PLAY_URL 一到就取消）；
    // 宽限期满网页还没接上（熄屏被冻/解析卡住），服务按预置自己接播下一首，
    // 音乐不断档；接播后经 advanced 同步通知网页补记账对齐队列。
    // —— phase-3（2026-10-23，2.7 复测连播 43 首后第 44 首仍断链后的结构改）：
    // 预置曲目同时排进 ExoPlayer 自己的播放列表（syncUpcomingToPlayer），
    // 接播由播放器在当前曲结束瞬间内部完成——不经 ENDED、不等宽限计时、
    // WakeLock 不释放、页面不在场也能接；宽限接播只保留作单曲循环与
    // 未能入队情形的兜底，看门狗另扩管 ENDED 无人接播（watchdogTick）。
    private data class ArmedItem(
        val url: String, val key: String, val title: String,
        val artist: String, val cover: String, val repeat: Boolean,
        val viaResolver: Boolean = false
    )

    private val armedItems = mutableListOf<ArmedItem>()

    // —— 3.3.0 原生解析（QuickJS）：网页交来音源配置（SOURCES_CONFIG）与后续
    // 队列清单（QUEUE_AHEAD）；预置跑道见底且网页迟迟不补仓（熄屏冻结）时，
    // 壳在解析线程里自己解析后续歌曲回填跑道。引擎日志同时落盘与推给网页
    // （enginelog 同步，网页以 toast 呈现），便于真机定位。
    private var lastArmAtMs: Long = 0L
    private var lastEngineAtMs: Long = 0L
    private var engineBusy: Boolean = false
    private var configSources: List<LxResolver.SrcCfg> = emptyList()
    private var configQuality: String = "master"
    private var configAutoDown: Boolean = true
    private var lastSourcesConfigJson: String = ""
    private var queueAheadSongs: List<org.json.JSONObject> = emptyList()
    // 单脑第 0 步（3.6.0）：影子队列快照（只存不用）与原文去重
    private var shadowSnapshotJson: String = ""
    private var shadowQueueKeys: Set<String> = emptySet()
    private var shadowCurKey: String = ""
    private var shadowMode: String = ""
    private var queueMode: String = "seq"
    private var engine: LxEngine? = null
    private val resolveExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    // 3.3.1 诊断：引擎日志内存环 + 计数器。断播点与 hello 时各推一份状态，
    // 页面重开（postHello）即可在 toast 里看到断播前的最后日志与计数。
    private val engineLogLock = Object()
    private val engineLogRing = ArrayDeque<String>()
    private var engineOkCount = 0
    // 3.3.6：播放出错计数（被自救盖过去的断也要有明账，进计数行）
    private var engineErrCount = 0
    // 3.3.5 出错自救：连续跳过计数（真出声即清零）与待执行标记（同一次错误只排一次）
    private var errSkipCount = 0
    private var errRescuePending = false
    // 3.3.7 同曲原地重试状态：按 URL 记账，每首最多重试 2 次（真出声/换曲清零）
    private var errRetryUrl = ""
    private var errRetryCount = 0
    // 描述符按 key 留存（QUEUE_AHEAD/点播/预置交来时存），阶梯换源时取用
    private val descriptorByKey = LinkedHashMap<String, org.json.JSONObject>()
    // 3.4.0 阶梯（cheng 议定；3.5.3 瘦身：重解析台阶实测 6/6 无新链、撤）：
    // 每集只换源一次；换源新曲 key 记账（算同一集延续）；待网页同步的换源记录
    private var errEpisodeKey = ""
    private var errQqNewKey = ""
    private var errQqUsed = false
    private var errSwapSettled = true // 换源收账标记（3.5.4：超时看门狗与完成回调抢答用）
    private var pendingSwapFor = ""
    private var pendingSwapSong: org.json.JSONObject? = null
    private var engineFailCount = 0
    private var engineBatchCount = 0
    private var stallSinceMs: Long = 0L
    private var stallLogged = false

    private fun engineCounters(): String {
        val ok: Int
        val f: Int
        val b: Int
        val e: Int
        synchronized(engineLogLock) {
            ok = engineOkCount
            f = engineFailCount
            b = engineBatchCount
            e = engineErrCount
        }
        return "批次 " + b + " 成功 " + ok + " 失败 " + f +
            " 队列剩 " + queueAheadSongs.size + " 跑道 " + armedItems.size +
            " 源 " + configSources.size + " 出错 " + e
    }

    private fun lxEngine(): LxEngine {
        var e = engine
        if (e == null) {
            e = LxEngine { m -> engineLog(m) }
            engine = e
        }
        return e
    }

    private fun engineLog(msg: String) {
        // 3.5.0：每行带时间戳（HH:mm:ss）——复盘时能对出事发时段与时长（cheng 定）
        val line = try {
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) + " " + msg
        } catch (_: Exception) {
            msg
        }
        synchronized(engineLogLock) {
            engineLogRing.addLast(line)
            while (engineLogRing.size > 14) engineLogRing.removeFirst()
        }
        try {
            val f = java.io.File(filesDir, "lx_engine.log")
            // 3.5.1：上限提到 1MB；写满时滚动保留——只丢最老的一段
            // （删到剩约 768KB），不再整文件删光（旧实现 256KB 写满即
            // 整表删除，前半夜的记录会整段消失，查案时日志像断了一截）
            if (f.length() > 1024 * 1024) {
                try {
                    val lines = f.readLines(Charsets.UTF_8)
                    var bytes = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
                    var drop = 0
                    while (drop < lines.size && bytes > 768 * 1024) {
                        bytes -= lines[drop].toByteArray(Charsets.UTF_8).size + 1
                        drop++
                    }
                    if (drop > 0) {
                        f.writeText(
                            lines.drop(drop).joinToString("\n") + "\n",
                            Charsets.UTF_8
                        )
                    }
                } catch (_: Exception) {
                }
            }
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
        try {
            pushSync(JSONObject().put("t", "enginelog").put("msg", line))
        } catch (_: Exception) {
        }
    }

    /** 队列清单推进：某首已成为当前曲时，它及之前的条目从清单摘除 */
    private fun dropQueueAheadThrough(key: String) {
        if (key.isEmpty() || queueAheadSongs.isEmpty()) return
        val idx = queueAheadSongs.indexOfFirst { LxResolver.keyOfJson(it) == key }
        if (idx < 0) return
        queueAheadSongs = ArrayList(queueAheadSongs.drop(idx + 1))
    }

    /**
     * 跑道见底时点名解析：armedItems 只剩 ≤1 且网页超过 15s 没补仓（多半是
     * 熄屏冻结），壳自己解析清单前 3 首回填。网页活着时它总在切歌瞬间补仓，
     * 条件天然不成立，不会与网页抢解析。
     */
    private fun maybeEngineResolve() {
        val wantPlay = playing ||
            (try {
                player?.playWhenReady
            } catch (_: Exception) {
                null
            } == true)
        if (!nativeActive || !wantPlay || engineBusy) return
        if (configSources.isEmpty() || queueAheadSongs.isEmpty()) return
        if (armedItems.size > 1) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastArmAtMs < 15000) return
        if (armedItems.isNotEmpty() && now - lastEngineAtMs < 10000) return
        val armedKeys = armedItems.map { it.key }.toSet()
        val ordered =
            if (queueMode == "shuffle") queueAheadSongs.shuffled() else queueAheadSongs
        val todo = ArrayList<org.json.JSONObject>()
        for (s in ordered) {
            if (todo.size >= 3) break
            val k = LxResolver.keyOfJson(s)
            if (k.isEmpty() || armedKeys.contains(k)) continue
            if (s.optString("name").isEmpty()) continue
            todo.add(s)
        }
        if (todo.isEmpty()) return
        engineBusy = true
        lastEngineAtMs = now
        synchronized(engineLogLock) { engineBatchCount++ }
        resolveExec.execute {
            var okN = 0
            var fN = 0
            try {
                for (song in todo) {
                    val url = LxResolver.resolve(
                        song, configSources, configQuality, configAutoDown, lxEngine()
                    ) { m -> engineLog(m) }
                    if (url.isNullOrEmpty()) {
                        fN++
                        engineLog("引擎解析失败：" + song.optString("name"))
                        continue
                    }
                    okN++
                    val item = ArmedItem(
                        url, LxResolver.keyOfJson(song),
                        song.optString("name"), song.optString("artist"),
                        song.optString("cover").ifEmpty { song.optString("img") },
                        false, true
                    )
                    tickHandler.post {
                        if (armedItems.size < 8 && armedItems.none { it.key == item.key }) {
                            armedItems.add(item)
                            syncUpcomingToPlayer()
                            // 3.5.5：停摆接播——歌补回来了、播放器却已停摆
                            // （看门狗停机到 IDLE、或 ENDED 后无人接）时，只放上
                            // 跑道没人踢就永远没声（2026-11-04 Nevada 断播：
                            // 补仓全数落袋、跑道 3 首，无人接播）。有播放意图
                            // 且跑道非空就直接接播队首；阶梯正在救当前曲时不抢。
                            val pp = player
                            if (nativeActive && pp != null && pp.playWhenReady &&
                                !errRescuePending &&
                                (pp.playbackState == Player.STATE_IDLE ||
                                    pp.playbackState == Player.STATE_ENDED)
                            ) {
                                val first = armedItems.firstOrNull()
                                if (first != null) {
                                    engineLog("引擎：停摆接播 " + first.title + "（补仓落袋时播放器已停摆）")
                                    doArmedAdvance()
                                }
                            }
                        }
                    }
                    engineLog(
                        "引擎解析成功：" + song.optString("name") + " → " +
                            url.substringAfter("://").substringBefore("/")
                    )
                }
                synchronized(engineLogLock) {
                    engineOkCount += okN
                    engineFailCount += fN
                }
                engineLog("引擎批次完成 " + engineCounters())
            } catch (_: Exception) {
            } finally {
                tickHandler.post { engineBusy = false }
            }
        }
    }
    /** 最近一次由预置接播的曲目 key/url（hello 时告诉网页，网页据此对齐） */
    private var lastAdvancedKey: String = ""
    private var lastAdvancedUrl: String = ""
    private val graceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAdvance: Runnable? = null

    /**
     * 宽限期专用 WakeLock：当前曲播完后 ExoPlayer 停了，它自带的网络 WakeLock
     * 随之释放，CPU 一睡，主线程上的 2.5s 宽限计时（uptime 时钟）也跟着冻住，
     * 接播要等下一次亮屏才触发——这正是"播完卡住、亮屏才发现没接上"的一个闸口。
     * 调度接播时单独持锁，接到播/取消/超时自动释放（最长 60s 兜底）。
     */
    private var graceWakeLock: PowerManager.WakeLock? = null

    private fun acquireGraceWakeLock() {
        try {
            if (graceWakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Suantangyu:GraceAdvance")
            wl.setReferenceCounted(false)
            wl.acquire(60_000L)
            graceWakeLock = wl
        } catch (_: Exception) {
        }
    }

    private fun releaseGraceWakeLock() {
        try {
            if (graceWakeLock?.isHeld == true) graceWakeLock?.release()
        } catch (_: Exception) {
        }
        graceWakeLock = null
    }

    // —— phase-2.7 起播看门狗 + 接播后保护窗 ——
    // 2.6 复测的新故障形态：预置接播成功（新曲已装载、时长已知），但新曲的流
    // 卡在 0:00 永远进不了播放——且无人察觉：流滴漏式卡缓冲不报错（读超时
    // 20s 只管彻底断线），网页迟到的切歌前摇（pause / 清 src 的 stop）又可能
    // 在接播后落到新曲上把它按停/卸载，而网页自己的切歌流程卡在解析上，
    // 后续的 PLAY_URL 永远不来。两条卡法共同的缺口是：装载后没人确认"真的
    // 出声了"。看门狗每 10s 采样一次：有播放意图却不在播、位置也没推进，
    // 先整条重拉一次（换新连接），仍卡就停掉播放器并给网页发 error 同步，
    // 交垫片重试 / 页面换源降级链接管，不再无限期静默。
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchdogRunning = false
    private var watchdogStall = 0
    private var watchdogLastPos = -1L
    /** phase-3：ENDED 后无人接播的连续采样计数（与缓冲卡住分开计） */
    private var watchdogEndedStall = 0
    /** 最近一次预置接播的时刻（elapsedRealtime）；0 = 无 */
    private var lastAdvanceAtMs = 0L
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                watchdogTick()
            } catch (_: Exception) {
            }
            if (watchdogRunning) watchdogHandler.postDelayed(this, 10_000)
        }
    }

    private fun startWatchdog() {
        watchdogStall = 0
        watchdogLastPos = -1L
        watchdogEndedStall = 0
        if (watchdogRunning) return
        watchdogRunning = true
        watchdogHandler.postDelayed(watchdogRunnable, 10_000)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        watchdogStall = 0
        watchdogLastPos = -1L
        watchdogEndedStall = 0
        try {
            watchdogHandler.removeCallbacks(watchdogRunnable)
        } catch (_: Exception) {
        }
    }

    private fun watchdogTick() {
        val p = player
        if (!nativeActive || p == null || nativeUrl.isEmpty()) {
            watchdogStall = 0
            watchdogLastPos = -1L
            return
        }
        val pos = try {
            p.currentPosition.coerceAtLeast(0)
        } catch (_: Exception) {
            0L
        }
        // 在播、或位置在推进 = 健康
        if (p.isPlaying || pos > watchdogLastPos + 250) {
            watchdogStall = 0
            watchdogEndedStall = 0
            watchdogLastPos = pos
            return
        }
        watchdogLastPos = pos
        val wantPlay = try {
            p.playWhenReady
        } catch (_: Exception) {
            false
        }
        val st = try {
            p.playbackState
        } catch (_: Exception) {
            Player.STATE_IDLE
        }
        // 用户暂停（无播放意图）不干预
        if (!wantPlay) {
            watchdogStall = 0
            watchdogEndedStall = 0
            return
        }
        // phase-3：ENDED 后无人接播也归看门狗管。此前这条链断了没人察觉——
        // 宽限接播没触发（计时被冻/预置没赶上）、页面又冻着收不到 ended，
        // 就永远停在满格。正常情况下宽限接播 2.5s 就完成、播放列表原生接播
        // 更是即时，10s 采样点上还停在 ENDED 即异常：手里有预置就立刻强制
        // 接播（先撤掉可能还挂着的宽限计时，防它稍后重复接一首）；没有预置
        // 就持锁防 CPU 睡死并补发 ended，给页面一次自己接上的机会，之后
        // 每约 30s 再补发一次，不无限静默也不狂发。
        if (st == Player.STATE_ENDED) {
            watchdogStall = 0
            watchdogEndedStall++
            if (watchdogEndedStall == 1 && armedItems.isNotEmpty()) {
                cancelPendingAdvance()
                doArmedAdvance()
            } else if (watchdogEndedStall == 1 || watchdogEndedStall >= 3) {
                if (armedItems.isEmpty()) {
                    acquireGraceWakeLock()
                    pushSync(JSONObject().put("t", "ended"))
                } else {
                    cancelPendingAdvance()
                    doArmedAdvance()
                }
                if (watchdogEndedStall >= 3) watchdogEndedStall = 1
            }
            return
        }
        watchdogEndedStall = 0
        // 3.5.5：IDLE 停摆兜底——看门狗第二击把播放器停到 IDLE 后，若跑道
        // 里已有歌（无论谁放的），此前无人接播、只能干等网页醒来。此处与
        // 补仓落袋的停摆接播互为兜底：还想播、跑道非空、播放器空闲 → 接播。
        if (st == Player.STATE_IDLE && wantPlay && !errRescuePending &&
            armedItems.isNotEmpty()
        ) {
            val first = armedItems.firstOrNull()
            if (first != null) {
                engineLog("引擎：停摆接播 " + first.title + "（看门狗巡检发现播放器空闲）")
                cancelPendingAdvance()
                doArmedAdvance()
            }
            return
        }
        // 非缓冲态（已停/空闲）不归缓冲看门狗管
        if (st != Player.STATE_BUFFERING) {
            watchdogStall = 0
            return
        }
        // phase-3.1：接播后的起播宽限——慢网下母带首缓冲常超 10s，原首击
        // 线会把「慢但活着」的起播也打断重拉、越救越糟。接播后 20s 内
        // 缓冲无进展不计卡住，宽限过后恢复原节奏（首击重拉、再击报错）。
        if (lastAdvanceAtMs > 0L &&
            SystemClock.elapsedRealtime() - lastAdvanceAtMs < 20_000L
        ) {
            watchdogStall = 0
            return
        }
        watchdogStall++
        if (watchdogStall == 1) {
            // 3.5.5：看门狗动作留痕——此前两击都不写引擎日志，缓冲挂死在
            // 日志里毫无踪影（2026-11-04 Nevada 断播复盘的记录盲区）
            engineLog("引擎：看门狗重拉 " + title + "（缓冲无进展）")
            // 第一次卡住：只换当前项整条重拉（换一条新连接），中途卡住的
            // 先 seek 回原位置。phase-3.1 改用 replaceMediaItem：给当前项换
            // 一份全新的媒体源，取 2.7 setMediaItem 整条重装的救法语义（那
            // 一版 43 首靠它反复救活），但只作用于当前项——队列里排着的
            // 预置项原样保留；mediaId 沿用当前项的，接播记账的 key 身份
            // 不断。3.0 的 stop+seek+prepare 在多项列表下实测没把卡住的
            // 起播救回来（《洛神》彻底没声，cheng 2026-10-23 确认）。
            val keepPos = pos
            try {
                val idx = p.currentMediaItemIndex
                val mid = p.getMediaItemAt(idx).mediaId.ifEmpty { nativeUrl }
                p.replaceMediaItem(
                    idx,
                    MediaItem.Builder().setUri(nativeUrl).setMediaId(mid).build()
                )
                if (keepPos > 2000) p.seekTo(keepPos)
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            } catch (_: Exception) {
            }
        } else if (watchdogStall >= 2) {
            // 第二次仍卡：停掉播放器（网页重试须走整条装载，别在坏连接上复用），
            // 发 error 同步交网页的重试/换源降级链处理
            watchdogStall = 0
            engineLog("引擎：看门狗停机 " + title + "（缓冲无进展，错误已交网页）")
            try {
                p.stop()
            } catch (_: Exception) {
            }
            playing = false
            updateSessionState()
            updateNotification()
            pushSync(
                JSONObject().put("t", "error").put("code", -7)
                    .put("http", 0).put("msg", "stall watchdog: buffering made no progress")
            )
        }
    }

    /**
     * 接播后保护窗：预置接播刚完成、新曲还没起播的 10s 内，网页迟到的切歌
     * 前摇（pause / stop / disarm）不是用户意图，而是它自己没走完的切歌流程
     * ——照单全收会把刚接上的新曲按停/卸载，而它随后的 PLAY_URL 又可能永远
     * 不来（phase-2.6 复测卡 0:00 的候选成因之一）。保护窗内一律忽略；
     * 网页接管记账（PLAY_URL / ARM_NEXT 到达）即提前解除。
     */
    private fun inAdvanceGuard(): Boolean {
        if (lastAdvanceAtMs <= 0L || playing) return false
        if (positionMs > 0L) return false
        return SystemClock.elapsedRealtime() - lastAdvanceAtMs < 10_000L
    }

    private fun cancelPendingAdvance() {
        pendingAdvance?.let {
            try {
                graceHandler.removeCallbacks(it)
            } catch (_: Exception) {
            }
        }
        pendingAdvance = null
        releaseGraceWakeLock()
    }

    private fun scheduleArmedAdvance() {
        if (armedItems.isEmpty()) return
        cancelPendingAdvance()
        acquireGraceWakeLock()
        val r = Runnable { doArmedAdvance() }
        pendingAdvance = r
        graceHandler.postDelayed(r, 2500)
    }

    private fun doArmedAdvance() {
        pendingAdvance = null
        releaseGraceWakeLock()
        if (!nativeActive) return
        val item = armedItems.firstOrNull() ?: return
        if (item.repeat) {
            // 单曲循环预置：不消费，跳回 0 点继续这首（网页醒来会重新预置/对齐）
            try {
                player?.seekTo(0)
                player?.play()
            } catch (_: Exception) {
            }
            pushSync(
                JSONObject().put("t", "advanced").put("key", item.key)
                    .put("url", nativeUrl).put("repeat", true)
            )
            return
        }
        armedItems.removeAt(0)
        dropQueueAheadThrough(item.key)
        applyAdvance(item, playIt = true)
    }

    /**
     * 接播记账（宽限接播与播放列表原生接播共用）：换曲目信息、打保护窗
     * 时间戳、发 advanced 同步并在 1s / 3s 后各补发一次（页面冻醒时首条
     * 同步可能被吞）。playIt=true 是老路：服务自己装载新曲；playIt=false
     * 是 phase-3 原生接播：播放器已经自己切到了这首，只补状态对齐。
     */
    private fun applyAdvance(item: ArmedItem, playIt: Boolean) {
        // 先把曲目信息换上，锁屏卡片立刻是对的，不必等网页醒来
        if (item.title.isNotEmpty()) title = item.title
        artist = item.artist
        lyricLine = ""
        lyricSub = ""
        updateSessionMetadata()
        if (item.cover != coverUrl) {
            coverUrl = item.cover
            coverBitmap = null
            if (item.cover.isNotEmpty()) {
                Thread { downloadCover(item.cover) }.start()
            }
        }
        lastAdvancedKey = item.key
        lastAdvancedUrl = item.url
        lastAdvanceAtMs = SystemClock.elapsedRealtime()
        if (playIt) {
            playNative(item.url, item.key)
            // 宽限接播是整表装载，剩下的预置曲目重新排进播放器队列
            syncUpcomingToPlayer()
        } else {
            nativeUrl = item.url
            nativeKey = item.key
            positionMs = 0
            anchorPosMs = 0
            anchorAtMs = SystemClock.elapsedRealtime()
            durationMs = 0
            watchdogStall = 0
            watchdogEndedStall = 0
            watchdogLastPos = -1L
            syncAnchorFromPlayer()
            updateSessionState()
            updateNotification()
        }
        val advJson = JSONObject().put("t", "advanced").put("key", item.key)
            .put("url", item.url).put("repeat", false)
        pushSync(advJson)
        for (delayMs in longArrayOf(1000L, 3000L)) {
            graceHandler.postDelayed({
                if (nativeActive && nativeUrl == item.url && lastAdvancedKey == item.key) {
                    pushSync(advJson)
                }
            }, delayMs)
        }
    }

    private fun mediaItemFor(item: ArmedItem): MediaItem =
        MediaItem.Builder().setUri(item.url)
            .setMediaId(item.key.ifEmpty { item.url }).build()

    /** 能进播放器队列的预置曲目：含单曲循环预置时返回空（循环不入队，走 ENDED 老路） */
    private fun desiredUpcoming(): List<ArmedItem> =
        if (armedItems.any { it.repeat }) emptyList() else armedItems.toList()

    /**
     * phase-3 核心：把预置曲目同步成 ExoPlayer 播放列表里排在当前曲之后
     * 的真实队列项。当前曲播完时播放器内部直接切到下一项——不经 ENDED、
     * 不等宽限计时、播放器也不释放网络 WakeLock，页面在不在场都不影响
     * 接播；页面醒来收到 advanced 同步后补记账、再经 ARM_NEXT 续排队尾。
     * 同步方式为最长公共前缀对齐：已排好且与新清单一致的前段原样保留
     * （保住播放器对它们的预加载），只换后面的部分。
     */
    private fun syncUpcomingToPlayer() {
        val p = player ?: return
        if (!nativeActive || nativeUrl.isEmpty()) return
        try {
            if (p.playbackState == Player.STATE_IDLE) return
            val cur = p.currentMediaItemIndex
            val count = p.mediaItemCount
            if (cur < 0 || cur >= count) return
            val desired = desiredUpcoming()
            val existingIds = ArrayList<String>()
            for (i in cur + 1 until count) {
                existingIds.add(p.getMediaItemAt(i).mediaId)
            }
            val desiredIds = desired.map { it.key.ifEmpty { it.url } }
            var prefix = 0
            while (prefix < existingIds.size && prefix < desiredIds.size &&
                existingIds[prefix] == desiredIds[prefix]
            ) {
                prefix++
            }
            // phase-3.1：前缀按 key 对齐上了，但同一首歌的签名 URL 可能已被
            // 页面换新——队列里还排着旧 URL 的话，接播播的是旧地址（可能已
            // 过期）。原地把该项换成新 URL，不动排队位置与其它项的预加载。
            for (i in 0 until prefix) {
                try {
                    val queuedUri =
                        p.getMediaItemAt(cur + 1 + i).localConfiguration?.uri?.toString()
                    if (queuedUri != null && queuedUri != desired[i].url) {
                        p.replaceMediaItem(cur + 1 + i, mediaItemFor(desired[i]))
                    }
                } catch (_: Exception) {
                }
            }
            if (prefix == existingIds.size && prefix == desiredIds.size) return
            if (count > cur + 1 + prefix) {
                p.removeMediaItems(cur + 1 + prefix, count)
            }
            if (desiredIds.size > prefix) {
                p.addMediaItems(
                    cur + 1 + prefix,
                    desired.subList(prefix, desired.size).map { mediaItemFor(it) }
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 播放列表原生接播的记账入口：播放器内部切到下一项（AUTO），或看门狗
     * 在 ENDED 卡死后把播放器 seek 到排队中的下一项（SEEK）时触发。切到的
     * 项对得上预置清单才处理（网页自己换歌走 PLAY_URL，是 PLAYLIST_CHANGED，
     * 不在这里）；对得上的按位置消费（含被跳过的），再走统一接播记账。
     */
    private fun handlePlaylistTransition(mediaItem: MediaItem?, reason: Int) {
        if (!nativeActive) return
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
        ) {
            return
        }
        val mid = try {
            mediaItem?.mediaId.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val uri = try {
            mediaItem?.localConfiguration?.uri?.toString()
        } catch (_: Exception) {
            null
        }
        // phase-3.1：身份统一按 mediaId（= 预置 key）对齐。同一首歌页面会
        // 重复解析出新签名 URL（refreshArm / prefetchNextUrl），按 URL 匹配
        // 会找不到（3.0 实测 idx=-1 → 不发 advanced、预置不消费，后续 ENDED
        // 宽限兜底还会拿过期预置重播）；key 在一首歌的生命周期内不变。
        // mediaId 缺失或没对上时才退回 URL 匹配。
        var idx = if (mid.isNotEmpty()) {
            armedItems.indexOfFirst { it.key.ifEmpty { it.url } == mid }
        } else {
            -1
        }
        if (idx < 0 && uri != null) {
            idx = armedItems.indexOfFirst { it.url == uri }
        }
        if (idx < 0) return
        val item = armedItems[idx]
        if (item.repeat) return
        repeat(idx + 1) { if (armedItems.isNotEmpty()) armedItems.removeAt(0) }
        dropQueueAheadThrough(item.key)
        applyAdvance(item, playIt = false)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // phase-3：播放列表原生接播在这里记账（服务自发 seek 接播同理）
            handlePlaylistTransition(mediaItem, reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!nativeActive) return
            playing = isPlaying
            if (isPlaying) {
                errSkipCount = 0
                errRetryCount = 0
                errRetryUrl = ""
                errEpisodeKey = ""
                errQqNewKey = ""
                errQqUsed = false
                errSwapSettled = true
            }
            syncAnchorFromPlayer()
            updateSessionState()
            updateNotification()
            pushSyncState()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!nativeActive) return
            when (state) {
                Player.STATE_READY -> {
                    syncAnchorFromPlayer()
                    pushSync(
                        JSONObject().put("t", "ready").put("dur", playerDurationMs())
                    )
                }
                Player.STATE_BUFFERING -> {
                    pushSync(JSONObject().put("t", "buffering"))
                    // 会话状态如实报缓冲中，别让锁屏把"在缓冲"显示成"已暂停"
                    updateSessionState()
                    updateNotification()
                }
                Player.STATE_ENDED -> {
                    playing = false
                    syncAnchorFromPlayer()
                    if (durationMs > 0) {
                        positionMs = durationMs
                        anchorPosMs = durationMs
                        anchorAtMs = SystemClock.elapsedRealtime()
                    }
                    updateSessionState()
                    updateNotification()
                    pushSync(JSONObject().put("t", "ended"))
                    // 3.1.1：ENDED 立即接播——不再等 2.5s 宽限（熄屏下宽限计时
                    // 本身可能被冻住）；有预置直接接播，无预置走原宽限路
                    // （armed 为空时 scheduleArmedAdvance 直接 return）。
                    // 代价：醒着的页面自己的 next 会被抢先一步，PLAY_URL /
                    // ARM_NEXT 到达仍可接管（接播保护窗 + 接管记账不变）。
                    if (armedItems.isNotEmpty()) {
                        cancelPendingAdvance()
                        doArmedAdvance()
                    } else {
                        scheduleArmedAdvance()
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!nativeActive) return
            // 顺着 cause 链找 HTTP 状态码，网页垫片打日志/判失败要用
            var httpCode = 0
            var c: Throwable? = error
            while (c != null) {
                if (c is HttpDataSource.InvalidResponseCodeException) {
                    httpCode = c.responseCode
                    break
                }
                c = c.cause
            }
            pushSync(
                JSONObject()
                    .put("t", "error")
                    .put("code", error.errorCode)
                    .put("http", httpCode)
                    .put("msg", (error.message ?: "").take(120))
            )
            // 3.3.5：错误留痕进引擎日志 + 壳内自救。解析成功不等于流能播，
            // 某首直链拉流时死了（403/过期），不能停等冻着的页面来救
            if (!errRescuePending) {
                errRescuePending = true
                engineErrCount++
                val host = try {
                    java.net.URI(nativeUrl).host ?: ""
                } catch (_: Exception) {
                    ""
                }
                engineLog(
                    "引擎：播放出错 " + title + " code=" + error.errorCode +
                        (if (httpCode > 0) " http=" + httpCode else "") +
                        (if (host.isNotEmpty()) " host=" + host else "")
                )
                // 3.3.9（cheng 定）：两次重试等待统一 1.5s——故障窗以分钟计，
                // 等再久也熬不过去，只徒增静默；1.5s 足够接住短暂抖动
                val delay = 1500L
                tickHandler.postDelayed({
                    errRescuePending = false
                    errorSkipRescue()
                }, delay)
            }
        }
    }

    /** 出错自救：跳过死掉的这首、接上下一首，永不停摆（落雪式处理）；
     * 连续跳 3 次仍出错才停手并留断播点记录。用户已暂停时不自作主张。 */
    private fun errorSkipRescue() {
        val p = player ?: return
        if (!nativeActive) return
        if (!p.playWhenReady) return
        val curUrl = nativeUrl
        // 第一级：同一首原地重试。code=2001 这类连接层故障多是时间窗问题，
        // 歌本身没坏，重拉同一条流多半就活——跳歌是最后手段，不是第一反应
        if (curUrl.isEmpty() || curUrl != errRetryUrl || errRetryCount < 2) {
            if (curUrl != errRetryUrl) {
                errRetryUrl = curUrl
                errRetryCount = 0
            }
            errRetryCount++
            engineLog("引擎：出错原地重试 " + title + "（第" + errRetryCount + "次）")
            try {
                p.seekTo(p.currentMediaItemIndex, 0)
                p.prepare()
                p.play()
            } catch (_: Exception) {
            }
            return
        }
        // 集号对齐：切到别的歌 → 换源记账重新起算；
        // 换源换上的新曲（errQqNewKey）算同一集延续，不重置
        val curKey = try {
            p.currentMediaItem?.mediaId ?: ""
        } catch (_: Exception) {
            ""
        }
        if (curKey.isNotEmpty() && curKey != errEpisodeKey && curKey != errQqNewKey) {
            errEpisodeKey = curKey
            errQqUsed = false
        }
        // 第二级：换源（非 QQ 曲换 QQ；QQ 曲在 QQ 内换版本）；再下级才是跳过
        tryQqSwapOrSkip(p, curKey, curUrl)
    }

    /** 第二级换源：非 QQ 曲搜 QQ 同名换上；QQ 曲在 QQ 内换一个不同版本。
     * 搜索/解析在解析线程跑，换链回主线程执行；无果则落到跳过兜底。
     * 每集只换一次；没描述符的歌不走这级（亮屏点播由网页自己的链救）。 */
    private fun tryQqSwapOrSkip(p: ExoPlayer, curKey: String, curUrl: String) {
        var desc = if (curKey.isNotEmpty()) descriptorByKey[curKey] else null
        if (desc == null) {
            // 3.5.3 兜底：编号对不上时按同名档案再找一次——典型是网页经
            // postPlayUrl 起播的歌、播放项编号只剩网址（3.5.2 抓现行），
            // 档案明明在册就不该瞎；同名多条时优先歌手对得上的那条
            var foundKey = ""
            var byName: org.json.JSONObject? = null
            for ((k, v) in descriptorByKey) {
                if (k.endsWith("|" + title)) {
                    if (byName == null) {
                        byName = v
                        foundKey = k
                    }
                    if (v.optString("artist") == artist) {
                        byName = v
                        foundKey = k
                        break
                    }
                }
            }
            if (byName != null) {
                desc = byName
                engineLog("引擎：按名找回档案 " + title + "（key=" + foundKey + "）")
            }
        }
        if (desc == null) {
            // 没档案就没法重解析/换源——把原因记明，别再静默跳过（3.4.2）
            // 3.5.2 诊断抓现行：把当时的编号原文、档案总数、同名档案的编号
            // 一并记下——下次复发一锤定音：reg 在而 key 不同 = 两边编号分歧；
            // reg 不在 = 根本没入册（被挤掉或从未登记），map 大小佐证挤掉说
            var regKey = ""
            for (k in descriptorByKey.keys) {
                if (k.endsWith("|" + title)) {
                    regKey = k
                    break
                }
            }
            engineLog(
                "引擎：无描述符，阶梯跳过 " + title +
                    " key=" + curKey +
                    " map=" + descriptorByKey.size +
                    (if (regKey.isNotEmpty()) " reg=" + regKey else "")
            )
            doSkipAfterError(p)
            return
        }
        if (configSources.isEmpty() || errQqUsed) {
            doSkipAfterError(p)
            return
        }
        errQqUsed = true
        errSwapSettled = false
        val name = desc.optString("name").ifEmpty { title }
        val singer = desc.optString("artist")
        val plat = desc.optString("source").ifEmpty { "qq" }
        engineLog("引擎：换源搜索 " + name)
        // 3.5.4（cheng 定）：换源全程 5 秒封顶——到点还没成，记超时并跳过，
        // 不让喘不过气的接口把静默拖到分钟级；迟到的结果由收账标记 +
        // nativeUrl 守卫丢弃（已跳走的不复活；断播点没跳成的仍可被迟到救兵救）
        val swapStartMs = android.os.SystemClock.elapsedRealtime()
        tickHandler.postDelayed({
            val pp = player
            if (!errSwapSettled && pp != null && nativeActive && pp.playWhenReady && nativeUrl == curUrl) {
                errSwapSettled = true
                engineLog("引擎：换源无果 " + name + "（5 秒超时）")
                doSkipAfterError(pp)
            }
        }, 5000L)
        resolveExec.execute {
            val remainMs = 5000L - (android.os.SystemClock.elapsedRealtime() - swapStartMs)
            val alt = searchQqAlt(desc, name, singer, plat, (remainMs / 1000L).toInt().coerceIn(2, 8))
            val newUrl = if (alt != null) {
                try {
                    LxResolver.resolve(
                        alt, configSources, configQuality, configAutoDown,
                        lxEngine(),
                        (5000L - (android.os.SystemClock.elapsedRealtime() - swapStartMs)).coerceAtLeast(1000L)
                    ) { m -> engineLog(m) }?.toString() ?: ""
                } catch (_: Exception) {
                    ""
                }
            } else {
                ""
            }
            tickHandler.post {
                val pp = player
                if (pp == null || !nativeActive || nativeUrl != curUrl) {
                    return@post
                }
                errSwapSettled = true
                if (alt != null && newUrl.isNotEmpty()) {
                    val newKey = LxResolver.keyOfJson(alt)
                    descriptorByKey[newKey] = alt
                    val host3 = try {
                        java.net.URI(newUrl).host ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    engineLog("引擎：换源成功 " + alt.optString("name") + " → " + host3)
                    pendingSwapFor = curKey
                    pendingSwapSong = alt
                    errQqNewKey = newKey
                    try {
                        pushSync(
                            JSONObject().put("t", "qqswap")
                                .put("forKey", curKey)
                                .put("song", alt)
                        )
                    } catch (_: Exception) {
                    }
                    title = alt.optString("name").ifEmpty { title }
                    artist = alt.optString("artist").ifEmpty { artist }
                    try {
                        updateNotification()
                    } catch (_: Exception) {
                    }
                    try {
                        val idx3 = pp.currentMediaItemIndex
                        pp.replaceMediaItem(
                            idx3,
                            mediaItemFor(
                                ArmedItem(
                                    newUrl, newKey,
                                    alt.optString("name"), alt.optString("artist"),
                                    alt.optString("cover"), false, true
                                )
                            )
                        )
                        nativeUrl = newUrl
                        nativeKey = newKey
                        pp.seekTo(idx3, 0)
                        pp.prepare()
                        pp.play()
                    } catch (_: Exception) {
                        doSkipAfterError(pp)
                    }
                } else {
                    engineLog("引擎：换源无果 " + name)
                    doSkipAfterError(pp)
                }
            }
        }
    }

    /** QQ 搜索挑一首可换的：非 QQ 曲取第一条可用结果；QQ 曲取第一个不同
     * songmid 的版本（同版本在第 2 步已重解析过，换了等于没换，故不取）。 */
    private fun searchQqAlt(
        orig: org.json.JSONObject, name: String, singer: String, plat: String,
        timeoutSec: Int = 8
    ): org.json.JSONObject? {
        val kw = (name + " " + singer).trim()
        if (kw.isEmpty()) return null
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" +
            android.net.Uri.encode(kw) +
            "&format=json&p=1&n=10&new_json=1&cr=1&t=0"
        val data = try {
            LxHttp.fetchJson(
                url,
                headers = mapOf("Referer" to "https://y.qq.com/"),
                timeoutSec = timeoutSec
            )
        } catch (_: Exception) {
            null
        } ?: return null
        val list = data.optJSONObject("data")?.optJSONObject("song")
            ?.optJSONArray("list") ?: return null
        val origMid = orig.optString("songmid")
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val built = buildQqDescriptor(item) ?: continue
            if (plat != "qq") return built
            if (built.optString("songmid") != origMid) return built
        }
        return null
    }

    /** QQ 搜索结果 → 描述符（与网页 normSong 同字段；封面由 albummid 拼） */
    private fun buildQqDescriptor(item: org.json.JSONObject): org.json.JSONObject? {
        val mid = item.optString("songmid").ifEmpty { item.optString("mid") }
        val nm = item.optString("songname").ifEmpty { item.optString("name") }
        if (mid.isEmpty() || nm.isEmpty()) return null
        val singers = item.optJSONArray("singer")
        val names = ArrayList<String>()
        if (singers != null) {
            for (j in 0 until singers.length()) {
                val sn = singers.optJSONObject(j)?.optString("name") ?: ""
                if (sn.isNotEmpty()) names.add(sn)
            }
        }
        val artistStr = names.joinToString("/")
        val amid = item.optString("albummid")
        val album = item.optString("albumname")
        val cover = if (amid.isNotEmpty()) {
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + amid + ".jpg"
        } else {
            ""
        }
        val o = org.json.JSONObject()
        o.put("source", "qq")
        o.put("songmid", mid)
        o.put("mid", mid)
        o.put("id", mid)
        o.put("name", nm)
        o.put("songname", nm)
        o.put("artist", artistStr)
        o.put("singer", artistStr)
        o.put("singername", artistStr)
        o.put("album", album)
        o.put("albumName", album)
        o.put("albumname", album)
        o.put("albummid", amid)
        o.put("albumMid", amid)
        o.put("cover", cover)
        o.put("img", cover)
        o.put("interval", item.optString("interval"))
        return o
    }

    /** 跳过出错的这首、接上下一首；连续跳 3 首仍败才停手记断播点 */
    private fun doSkipAfterError(p: ExoPlayer) {
        if (!nativeActive) return
        if (!p.playWhenReady) return
        errSkipCount++
        errRetryUrl = ""
        errRetryCount = 0
        if (errSkipCount > 3) {
            engineLog("引擎：断播点（连续出错） " + engineCounters())
            return
        }
        val idx = try {
            p.currentMediaItemIndex
        } catch (_: Exception) {
            0
        }
        val count = try {
            p.mediaItemCount
        } catch (_: Exception) {
            0
        }
        when {
            idx + 1 < count -> {
                engineLog("引擎：出错跳过，接下一首（第" + errSkipCount + "次）")
                try {
                    p.seekTo(idx + 1, 0)
                    p.prepare()
                    p.play()
                } catch (_: Exception) {
                }
            }
            armedItems.isNotEmpty() -> {
                engineLog("引擎：出错跳过，跑道接管（第" + errSkipCount + "次）")
                doArmedAdvance()
            }
            else -> {
                engineLog("引擎：出错且无下一首可接 " + engineCounters())
            }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val ua = try {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android)"
        }
        // 请求头尽量模拟网页 <audio> 直连时的样子，降低 CDN 因 UA/Referer 拦截的概率
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://m.858600.xyz/"))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
            )
            .setAudioAttributes(attrs, true) // 播放器自管音频焦点（抢断/恢复/降音量）
            .setHandleAudioBecomingNoisy(true) // 拔耳机自动暂停
            .setWakeMode(C.WAKE_MODE_NETWORK) // 播网络流时持 WakeLock + WifiLock，熄屏不睡
            .build()
        p.addListener(playerListener)
        player = p
        return p
    }

    private fun playerDurationMs(): Long {
        val d = try {
            player?.duration ?: C.TIME_UNSET
        } catch (_: Exception) {
            C.TIME_UNSET
        }
        return if (d == C.TIME_UNSET || d < 0) -1 else d
    }

    /** 把进度锚点对齐到播放器真实位置（原生模式下锚点以播放器为准） */
    private fun syncAnchorFromPlayer() {
        val p = player ?: return
        try {
            positionMs = p.currentPosition.coerceAtLeast(0)
            val d = playerDurationMs()
            if (d > 0) durationMs = d
            anchorPosMs = positionMs
            anchorAtMs = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
        }
    }

    /** 服务 → 网页：window.__baoSync(json)，网页垫片据此发事件/对齐状态 */
    private fun pushSync(o: JSONObject) {
        val inst = MainActivity.instance ?: return
        inst.runJsOnPage("window.__baoSync && window.__baoSync(" + o.toString() + ")")
    }

    private fun pushSyncState() {
        pushSync(
            JSONObject()
                .put("t", "state")
                .put("playing", playing)
                .put("pos", positionMs)
                .put("dur", durationMs)
        )
    }

    private fun playNative(url: String, key: String = "") {
        val p = ensurePlayer()
        if (nativeActive && url == nativeUrl && p.playbackState != Player.STATE_IDLE) {
            // 同一条流已装载（多半是网页重载后回来接管）：不重启，只起播+同步；
            // 已播完（ENDED）时单独 play() 不会从头播，先跳回 0
            if (p.playbackState == Player.STATE_ENDED) {
                try {
                    p.seekTo(0)
                } catch (_: Exception) {
                }
            }
            p.play()
            syncAnchorFromPlayer()
            pushSyncState()
            val d = playerDurationMs()
            if (d > 0) pushSync(JSONObject().put("t", "ready").put("dur", d))
            return
        }
        nativeUrl = url
        nativeKey = key
        nativeActive = true
        playing = false
        positionMs = 0
        anchorPosMs = 0
        anchorAtMs = SystemClock.elapsedRealtime()
        try {
            p.setPlaybackSpeed(pendingRate)
        } catch (_: Exception) {
        }
        p.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(key.ifEmpty { url }).build())
        p.prepare()
        p.play()
        startWatchdog()
        updateSessionState()
        updateNotification()
    }

    private fun stopNative() {
        cancelPendingAdvance()
        stopWatchdog()
        lastAdvanceAtMs = 0L
        armedItems.clear()
        lastAdvancedKey = ""
        lastAdvancedUrl = ""
        try {
            player?.stop()
            player?.clearMediaItems()
        } catch (_: Exception) {
        }
        nativeUrl = ""
        nativeKey = ""
        nativeActive = false
        if (playing) {
            playing = false
            updateSessionState()
            updateNotification()
        }
    }

    private lateinit var mediaSession: MediaSessionCompat

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // —— 媒体会话：系统级播放器卡片/灵动岛/锁屏/线控都认这个 ——
        mediaSession = MediaSessionCompat(this, "BaoBusSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                // 原生模式下播放/暂停直达播放器（不绕网页，网页死了也管用）；
                // 上一首/下一首的队列在网页手里，仍转发给网页
                override fun onPlay() {
                    if (nativeActive) {
                        val p = player
                        // 播完后按播放：ExoPlayer 在 ENDED 单独 play() 不重启，先跳回 0
                        if (p != null && p.playbackState == Player.STATE_ENDED) {
                            try {
                                p.seekTo(0)
                            } catch (_: Exception) {
                            }
                        }
                        p?.play()
                    } else sendControl("toggle")
                }

                override fun onPause() {
                    if (nativeActive) player?.pause() else sendControl("toggle")
                }

                override fun onSkipToNext() = sendControl("next")
                override fun onSkipToPrevious() = sendControl("prev")
            })
            isActive = true
        }
        updateSessionMetadata()
        updateSessionState()
        // 启动进度自走表（壳自己的 1s 滴答，后台不受网页定时器节流影响）
        tickHandler.postDelayed(tickRunnable, 1000)

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFY_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFY_ID, notification)
        }
    }

    override fun onDestroy() {
        cancelPendingAdvance()
        try {
            engine?.shutdown()
        } catch (_: Exception) {
        }
        engine = null
        try {
            resolveExec.shutdownNow()
        } catch (_: Exception) {
        }
        stopWatchdog()
        try {
            tickHandler.removeCallbacks(tickRunnable)
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        nativeActive = false
        try {
            mediaSession.isActive = false
            mediaSession.release()
        } catch (_: Exception) {
        }
        try {
            coverBitmap?.recycle()
        } catch (_: Exception) {
        }
        coverBitmap = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_TRACK -> {
                title = intent.getStringExtra("title").orEmpty().ifEmpty { "酸汤鱼音乐" }
                artist = intent.getStringExtra("artist").orEmpty()
                // 切歌：歌词覆盖清掉，回到显示歌名；进度锚点归零
                lyricLine = ""
                lyricSub = ""
                positionMs = 0L
                anchorPosMs = 0L
                anchorAtMs = SystemClock.elapsedRealtime()
                val newCover = intent.getStringExtra("cover").orEmpty()
                updateSessionMetadata()
                updateSessionState()
                updateNotification()
                if (newCover != coverUrl) {
                    coverUrl = newCover
                    coverBitmap = null
                    if (newCover.isNotEmpty()) {
                        Thread { downloadCover(newCover) }.start()
                    }
                }
            }
            ACTION_UPDATE_STATE -> {
                // 原生模式下播放器是唯一真相，页面上报只是回声（垫片状态本就源自播放器）
                if (nativeActive) return START_STICKY
                val nowPlaying = intent.getBooleanExtra("playing", false)
                val nowRt = SystemClock.elapsedRealtime()
                if (playing && !nowPlaying) {
                    // 暂停：把自走表推算的当前位置冻结下来
                    var cur = anchorPosMs + (nowRt - anchorAtMs)
                    if (durationMs > 0 && cur > durationMs) cur = durationMs
                    if (cur < 0) cur = 0
                    positionMs = cur
                    anchorPosMs = cur
                }
                if (!playing && nowPlaying) {
                    // 恢复播放：从当前冻结位置重新起锚
                    anchorPosMs = positionMs
                }
                anchorAtMs = nowRt
                playing = nowPlaying
                updateSessionState()
                updateNotification()
            }
            ACTION_UPDATE_PROGRESS -> {
                if (nativeActive) return START_STICKY
                // 网页报上来的真实进度 = 校准锚点，覆盖自走表推算
                positionMs = intent.getLongExtra("position", 0L)
                durationMs = intent.getLongExtra("duration", 0L)
                anchorPosMs = positionMs
                anchorAtMs = SystemClock.elapsedRealtime()
                updateSessionState()
                // 节流：距离上次通知不到 1s 就跳过（tick 自走表本身 1s 一次，网页报上来也 1s 一次）
                if (SystemClock.uptimeMillis() - lastNotifyMs >= 1000) {
                    updateNotification()
                }
            }
            ACTION_UPDATE_LYRIC -> {
                lyricLine = intent.getStringExtra("line").orEmpty()
                lyricSub = intent.getStringExtra("sub").orEmpty()
                updateSessionMetadata()
                updateNotification()
            }
            // —— phase-2 原生播放指令（网页垫片发来） ——
            ACTION_PLAY_URL -> {
                // 网页自己接上了：预置接播取消，记账权回到网页
                cancelPendingAdvance()
                lastAdvancedKey = ""
                lastAdvancedUrl = ""
                lastAdvanceAtMs = 0L
                val url = intent.getStringExtra("url").orEmpty()
                // 网页自己点播的正是预置队首（它醒着主动切的歌）→ 该预置已被
                // 它自己消费，别留着让队列同步/宽限接播再播一遍同一首
                if (url.isNotEmpty() && armedItems.firstOrNull()?.url == url) {
                    armedItems.removeAt(0)
                }
                if (url.isNotEmpty()) playNative(url, intent.getStringExtra("key").orEmpty())
            }
            ACTION_NATIVE_PAUSE -> {
                // 宽限期（已播完、等接播）内到达的 pause 多半是网页切歌前摇：
                // 它收到 ended 后跑自己的 next()/play()，开头先 pause 再清 src——
                // 若在此刻把待接播取消、预置又随后的 stop 清掉，网页的解析再在
                // 后台卡住，就再没有东西能接上（phase-2.5 实测失效的主因之一）。
                // 真正的用户暂停发生在播放中（无待接播），不受影响；播完状态下
                // 单独 pause 也没有实际意义，保留待接播，让 PLAY_URL 来接管。
                if (pendingAdvance == null) cancelPendingAdvance()
                // 接播后保护窗内同理：网页迟到的切歌前摇 pause 会把刚接上的
                // 新曲按停（playWhenReady=false），而它自己的 PLAY_URL 又可能
                // 卡在解析上永远不来——新曲就此卡在 0:00 静默。保护窗内忽略。
                if (!inAdvanceGuard()) player?.pause()
            }
            ACTION_NATIVE_RESUME -> {
                if (nativeActive) {
                    val p = player
                    if (p != null && p.playbackState == Player.STATE_IDLE && nativeUrl.isNotEmpty()) {
                        // 出错后播放器回到 IDLE，单独 play() 救不回来，整条重装
                        playNative(nativeUrl, nativeKey)
                    } else if (p != null && p.playbackState == Player.STATE_ENDED) {
                        // 播完后网页/用户再按播放：先跳回 0 再播（ENDED 单独 play() 不重启）
                        cancelPendingAdvance()
                        try {
                            p.seekTo(0)
                        } catch (_: Exception) {
                        }
                        p.play()
                    } else {
                        p?.play()
                    }
                }
            }
            ACTION_NATIVE_SEEK -> {
                if (nativeActive) {
                    try {
                        player?.seekTo(intent.getLongExtra("pos", 0L))
                    } catch (_: Exception) {
                    }
                    syncAnchorFromPlayer()
                    pushSyncState()
                }
            }
            ACTION_NATIVE_RATE -> {
                pendingRate = intent.getFloatExtra("rate", 1f)
                try {
                    player?.setPlaybackSpeed(pendingRate)
                } catch (_: Exception) {
                }
            }
            ACTION_NATIVE_STOP -> {
                // 同 NATIVE_PAUSE：宽限期内网页切歌前摇会先 removeAttribute("src")
                // 触发一次 stop——照单全收就会把播放器卸载、预置清单清空，
                // fallback 被网页自己的前摇亲手拆掉。有待接播时忽略这次 stop：
                // 网页随后送达的 PLAY_URL 会整条换装（等效于 stop 后重播），
                // 若 PLAY_URL 最终没来，待接播按预置接上，音乐不断档。
                // 接播后保护窗同样适用：迟到的清 src 会把刚接上的新曲卸载，
                // 而网页的重装请求可能永远不来——保护窗内忽略这次 stop。
                if (pendingAdvance == null && !inAdvanceGuard()) stopNative()
            }
            ACTION_ARM_NEXT -> {
                // 网页重新预置（或接管记账）：以新清单为准。
                // 但宽限期内（已播完正等待接播）不要取消待接播计时——doArmedAdvance
                // 在触发时才读清单，换上新清单即可；取消计时等于白等一首歌的时间。
                if (pendingAdvance == null) cancelPendingAdvance()
                lastArmAtMs = android.os.SystemClock.elapsedRealtime()
                lastAdvancedKey = ""
                lastAdvancedUrl = ""
                lastAdvanceAtMs = 0L
                armedItems.clear()
                try {
                    val arr = org.json.JSONArray(intent.getStringExtra("json") ?: "[]")
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val u = o.optString("u")
                        if (u.isEmpty()) continue
                        armedItems.add(
                            ArmedItem(
                                u, o.optString("k"), o.optString("t"),
                                o.optString("a"), o.optString("c"),
                                // 网页发的是数字 0/1，optBoolean 不认数字，一并兼容
                                o.optBoolean("r", false) || o.optInt("r", 0) == 1
                            )
                        )
                        // 3.5.1：预置自带完整档案（字段 s）就按预置自己的编号
                        // 入册——档案跟着歌走，出错阶梯不再依赖队列上报窗口
                        val dk = o.optString("k")
                        val ds = o.optJSONObject("s")
                        if (dk.isNotEmpty() && ds != null) {
                            descriptorByKey.remove(dk)
                            descriptorByKey[dk] = ds
                        }
                    }
                } catch (_: Exception) {
                }
                while (descriptorByKey.size > 96) {
                    val kit = descriptorByKey.keys.iterator()
                    if (!kit.hasNext()) break
                    descriptorByKey.remove(kit.next())
                }
                // phase-3：新预置清单同步进播放器队列，接播改由播放器内部完成
                syncUpcomingToPlayer()
            }
            ACTION_DISARM_NEXT -> {
                // 宽限期内的 disarm 同样多来自网页切歌失败后的收尾（played=false 分支），
                // 不是用户的"播完停"意图（那个意图在设定的当下、播放中就已发出，
                // 待接播为空，不受影响）——有待接播时忽略，保住 fallback。
                if (pendingAdvance == null && !inAdvanceGuard()) {
                    cancelPendingAdvance()
                    armedItems.clear()
                    queueAheadSongs = emptyList()
                    // 预置已撤，播放器队列里排着的后续项一并撤下，防它照旧接播
                    syncUpcomingToPlayer()
                }
            }
            ACTION_SOURCES_CONFIG -> {
                val json = intent.getStringExtra("json") ?: ""
                if (json.isNotEmpty() && json != lastSourcesConfigJson) {
                    lastSourcesConfigJson = json
                    try {
                        val cfg = JSONObject(json)
                        configQuality = cfg.optString("quality").ifEmpty { "master" }
                        configAutoDown = cfg.optBoolean("autoDowngrade", true)
                    } catch (_: Exception) {
                    }
                    configSources = LxResolver.parseSources(json)
                    lxEngine().configure(configSources.filter { it.isScript() })
                    engineLog("引擎：已接收音源配置 " + configSources.size + " 个源")
                }
            }
            ACTION_QUEUE_AHEAD -> {
                queueMode = intent.getStringExtra("mode") ?: queueMode
                val raw = intent.getStringExtra("json") ?: "[]"
                val list = ArrayList<org.json.JSONObject>()
                try {
                    val arr = org.json.JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list.add(o)
                    }
                } catch (_: Exception) {
                }
                queueAheadSongs = list
                // 3.3.8：描述符按 key 留存，出错重解析同一首时用（上限 96 防膨胀）
                for (s in list) {
                    val k = LxResolver.keyOfJson(s)
                    if (k.isNotEmpty()) {
                        // 3.5.1：remove+put 刷新位置——还在窗口里的歌保持年轻，
                        // 免得长队列滑动时被 96 上限从队头挤掉
                        descriptorByKey.remove(k)
                        descriptorByKey[k] = s
                    }
                }
                while (descriptorByKey.size > 96) {
                    val kit = descriptorByKey.keys.iterator()
                    if (!kit.hasNext()) break
                    descriptorByKey.remove(kit.next())
                }
                // 队列变了：壳解析回填的预置若已不在新队列里，撤掉防播错歌
                val before = armedItems.size
                armedItems.removeAll {
                    it.viaResolver && list.none { s -> LxResolver.keyOfJson(s) == it.key }
                }
                if (armedItems.size != before) syncUpcomingToPlayer()
            }
            ACTION_CURRENT_SONG -> {
                // 3.4.2：当前曲描述符登记（预告清单只含后续曲，当前曲是盲区——
                // 点播直达/进程重开接管的当前曲没描述符时，出错阶梯第 2/3 级被静默跳过）
                try {
                    val o = org.json.JSONObject(intent.getStringExtra("json") ?: "{}")
                    val k = LxResolver.keyOfJson(o)
                    if (k.isNotEmpty()) {
                        // remove+put 刷新位置，免得长会话里被 96 上限淘汰掉正在播的这首
                        descriptorByKey.remove(k)
                        descriptorByKey[k] = o
                        while (descriptorByKey.size > 96) {
                            val kit = descriptorByKey.keys.iterator()
                            if (!kit.hasNext()) break
                            descriptorByKey.remove(kit.next())
                        }
                    }
                } catch (_: Exception) {
                }
            }
            ACTION_QUEUE_SNAPSHOT -> {
                // 单脑第 0 步（3.6.0）：影子队列——完整队列快照只收存对账，
                // 不参与任何播放决策（第 1 步起才由脑子接管）。与自家账本
                // （档案表 ∪ 跑道 ∪ 当前原生键）对一次，偏差打进引擎日志：
                // 看的是缺失能否随下一份快照归零、当前键是否一致。
                val raw = intent.getStringExtra("json") ?: ""
                if (raw.isNotEmpty() && raw != shadowSnapshotJson) {
                    shadowSnapshotJson = raw
                    try {
                        val o = org.json.JSONObject(raw)
                        val arr = o.optJSONArray("queue")
                        val keys = HashSet<String>()
                        var n = 0
                        if (arr != null) {
                            n = arr.length()
                            for (i in 0 until arr.length()) {
                                val sj = arr.optJSONObject(i) ?: continue
                                val k = LxResolver.keyOfJson(sj)
                                if (k.isNotEmpty()) keys.add(k)
                            }
                        }
                        shadowQueueKeys = keys
                        shadowCurKey = o.optString("curKey")
                        shadowMode = o.optString("mode")
                        val known = HashSet<String>()
                        known.addAll(descriptorByKey.keys)
                        for (item in armedItems) {
                            if (item.key.isNotEmpty()) known.add(item.key)
                        }
                        if (nativeKey.isNotEmpty()) known.add(nativeKey)
                        val missing = known.filter { !keys.contains(it) }
                        val agree = if (nativeKey.isEmpty()) "—"
                            else if (shadowCurKey == nativeKey) "是" else "否"
                        var line = "引擎：影子队列 同步 " + n + " 首 已知 " + known.size +
                            " 缺失 " + missing.size + " 当前一致 " + agree
                        if (missing.isNotEmpty()) {
                            line += " 缺失例：" + missing.first().substringAfter("|")
                        }
                        engineLog(line)
                    } catch (_: Exception) {
                    }
                }
            }
            ACTION_HELLO -> {
                // 3.5.0：hello 不再倒日志尾巴——完整日志在网页「后台日志」面板里看；
                // 这里只在有「新的」断播点时推一条提醒（按行内容去重，同一条不重复推）
                try {
                    var tail: List<String>
                    synchronized(engineLogLock) { tail = ArrayList(engineLogRing) }
                    if (tail.none { it.contains("断播点") }) {
                        try {
                            val f = java.io.File(filesDir, "lx_engine.log")
                            if (f.exists()) {
                                tail = f.readLines(Charsets.UTF_8).takeLast(60)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    val stall = tail.lastOrNull { it.contains("断播点") }
                    if (stall != null) {
                        val prefs = getSharedPreferences(
                            "diag", android.content.Context.MODE_PRIVATE
                        )
                        if (prefs.getString("stall_push_sig", "") != stall) {
                            prefs.edit().putString("stall_push_sig", stall).apply()
                            pushSync(
                                JSONObject().put("t", "enginelog").put("msg", stall)
                            )
                        }
                    }
                } catch (_: Exception) {
                }
                // 3.4.0：换源记录重推——页面冻住时 qqswap 没送达的话，
                // 借这次 hello 补推给刚醒的页面（页面按 forKey 核对，不符自会忽略）；
                // 当前曲已不是换源那首/原曲时，记录作废清掉
                try {
                    val ps = pendingSwapSong
                    if (ps != null) {
                        val curId = try {
                            player?.currentMediaItem?.mediaId ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        if (curId == errQqNewKey || curId == pendingSwapFor) {
                            pushSync(
                                JSONObject().put("t", "qqswap")
                                    .put("forKey", pendingSwapFor)
                                    .put("song", ps)
                            )
                        } else if (curId.isNotEmpty()) {
                            pendingSwapSong = null
                            pendingSwapFor = ""
                        }
                    }
                } catch (_: Exception) {
                }
                if (nativeActive) syncAnchorFromPlayer()
                pushSync(
                    JSONObject()
                        .put("t", "hello")
                        .put("active", nativeActive)
                        .put("url", nativeUrl)
                        .put("playing", playing)
                        .put("pos", positionMs)
                        .put("dur", durationMs)
                        // 当前这条若是预置接播的，把它的 key 告诉网页用于对齐队列
                        .put(
                            "advKey",
                            if (nativeUrl == lastAdvancedUrl) lastAdvancedKey else ""
                        )
                )
            }
            ACTION_TOGGLE -> {
                // 原生模式：通知按钮直达播放器；否则走老路转发网页
                if (nativeActive && player != null) {
                    val p = player!!
                    if (p.playbackState == Player.STATE_ENDED) {
                        // 已播完：playWhenReady 仍是 true 会误判成"暂停"，这里直接重播
                        cancelPendingAdvance()
                        try {
                            p.seekTo(0)
                        } catch (_: Exception) {
                        }
                        p.play()
                    } else if (p.isPlaying || p.playWhenReady) p.pause() else p.play()
                } else {
                    sendControl("toggle")
                }
            }
            ACTION_NEXT -> sendControl("next")
            ACTION_PREV -> sendControl("prev")
            // 耳机/蓝牙媒体按钮走这里
            Intent.ACTION_MEDIA_BUTTON -> {
                MediaButtonReceiver.handleIntent(mediaSession, intent)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 通知栏按钮 / 锁屏 / 灵动岛 / 线控 → 网页的 window.__baoCtl。
     * Activity 活着就直调（不经过广播）；极端情况下 Activity 已死才走广播兜底。
     */
    private fun sendControl(action: String) {
        val js = "window.__baoCtl && window.__baoCtl('$action')"
        val inst = MainActivity.instance
        if (inst != null) {
            inst.runJsOnPage(js)
        } else {
            sendBroadcast(Intent(MainActivity.ACTION_CONTROL).putExtra("action", action))
        }
    }

    /** 卡片上实际显示的标题/副标题：有歌词行时显示歌词 */
    private fun showTitle(): String =
        if (lyricLine.isNotEmpty()) lyricLine else title

    private fun showArtist(): String =
        if (lyricLine.isNotEmpty()) {
            lyricSub.ifEmpty { artist.ifEmpty { "酸汤鱼音乐" } }
        } else {
            artist.ifEmpty { "酸汤鱼音乐" }
        }

    private fun updateSessionMetadata() {
        try {
            val b = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, showTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, showArtist())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            coverBitmap?.let {
                if (!it.isRecycled) b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            }
            mediaSession.setMetadata(b.build())
        } catch (_: Exception) {
        }
    }

    private fun updateSessionState() {
        try {
            // 原生模式且有播放意图却卡在缓冲时如实报 BUFFERING——只报 PAUSED
            // 会让锁屏显示成"被暂停了"，误导判断（2.6 复测截图就是这样）
            val np = if (nativeActive) player else null
            val isBuffering = np != null && !playing &&
                (try { np.playWhenReady } catch (_: Exception) { false }) &&
                (try { np.playbackState } catch (_: Exception) { -1 }) == Player.STATE_BUFFERING
            val state = when {
                playing -> PlaybackStateCompat.STATE_PLAYING
                isBuffering -> PlaybackStateCompat.STATE_BUFFERING
                else -> PlaybackStateCompat.STATE_PAUSED
            }
            // 播放中速度 1.0：系统按最后上报位置自动推进进度条；暂停速度 0 定格
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(state, positionMs, if (playing) 1f else 0f)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    /** 后台线程拉封面，到手后刷新会话和通知 */
    private fun downloadCover(url: String) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode in 200..299) {
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                if (url == coverUrl && bmp != null) {
                    coverBitmap = bmp
                    updateSessionMetadata()
                    updateNotification()
                }
            }
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "播放控制",
                // DEFAULT：锁屏上可见；LOW 会被部分系统藏起
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "酸汤鱼音乐后台播放保活与通知栏控制" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, KeepAliveService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        // 三个按钮用不同 requestCode，避免 PendingIntent 互相覆盖
        val code = when (action) {
            ACTION_PREV -> 11
            ACTION_TOGGLE -> 12
            else -> 13
        }
        return PendingIntent.getService(this, code, intent, flags)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 21, intent, flags)
    }

    private fun buildNotification(): Notification {
        val playPauseIcon = if (playing) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(showTitle())
            .setContentText(showArtist())
            .setContentIntent(contentIntent())
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            // 锁屏上可见内容
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous, "上一首",
                actionIntent(ACTION_PREV)
            )
            .addAction(playPauseIcon, "播放/暂停", actionIntent(ACTION_TOGGLE))
            .addAction(
                android.R.drawable.ic_media_next, "下一首",
                actionIntent(ACTION_NEXT)
            )
            .setStyle(
                MediaStyle()
                    // 关键：把媒体会话 token 挂上，系统才认这是媒体播放
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        coverBitmap?.let {
            if (!it.isRecycled) b.setLargeIcon(it)
        }
        return b.build()
    }

    private fun updateNotification() {
        try {
            lastNotifyMs = SystemClock.uptimeMillis()
            notificationManager.notify(NOTIFY_ID, buildNotification())
        } catch (_: Exception) {
        }
    }
}
