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
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
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
        val artist: String, val cover: String, val repeat: Boolean
    )

    private val armedItems = mutableListOf<ArmedItem>()
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

    // —— 3.1.11 报错自救：播放器报错后停进 IDLE，旧行为只把 error 同步
    // 给网页；熄屏时网页冻住收不到，就永久停死。降级播放拿到的多是兜底
    // 档/换源签发的地址，403/过期高发，所以这个洞在降级时最明显。自救
    // 逻辑见 scheduleErrorRescue：宽限等网页接管，无人接管才跳预置。
    /** 连续自救跳歌次数（真出声或网页接管即清零）；上限防坏地址连环跳 */
    private var errorRescueCount = 0
    // 3.1.15：页面预置做深到 5 首后放宽——跑道里连着几首坏地址时能一路
    // 跳过去，不再跳两次就见底（真出声/网页接管仍会清零计数）
    private val MAX_ERROR_RESCUE = 4
    /** 最近一次 onPlayerError 的时刻与当时在播地址（看门狗兜底判定用） */
    private var lastErrorAtMs = 0L
    private var lastErrorUrl: String = ""
    private var pendingErrorRescue: Runnable? = null

    // —— 3.1.13 预缓存：播放走 SimpleCache（200MB LRU 滚动淘汰），预置
    // 一到就用 CacheWriter 后台把每首开头约 1.5MB 拉进缓存——起播头一
    // 段直接读本地，不再赌切歌瞬间那条兜底 CDN 的实时速度（形态②病
    // 根）。只下开头不下整首：省流量；中后段仍边播边下、边下边进缓存。
    private var httpFactory: DefaultHttpDataSource.Factory? = null
    private var audioCache: SimpleCache? = null
    private val PRELOAD_BYTES = 1_500_000L
    private val prefetchExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "audio-prefetch").apply { isDaemon = true }
        }
    private val prefetchSeen =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // —— 3.1.13 卡住不终局：跑道见底、缓冲卡死且无货可跳时不再 stop()
    // 定终局（旧兜底还没记 lastErrorAtMs，停进 IDLE 后看门狗各分支全
    // 绕开、永不自恢复）。改为进入孤儿重试：记账、保住播放意图、约每
    // 30s 整条重拉一次并补发 error 喊网页；网络恢复或网页解冻任一发
    // 生即可自活，真出声/网页接管/用户暂停即退出。
    private var orphanStall = false
    private var orphanLastRetryMs = 0L
    private var orphanPos = 0L

    // —— 3.1.14 防 pause 毒化：当前曲是否已真正出过声。页面切歌前摇的
    // pause/stop 在深冻下迟到无上限，30s 保护窗（3.1.12）仍被击穿——
    // 改为不看时间：首次出声前，页面来的 pause/stop 一律不认（那永远
    // 是它自己的前摇，不是用户意图）。用户暂停走通知/耳机/锁屏（直
    // 达播放器并打 userHold 标）与出声后的页面暂停，均不受影响。
    private var soundedCurrent = false
    /** 用户亲手暂停（会话回调/通知按钮）的标记，看门狗反毒化判定用 */
    private var userHold = false
    /** 本首已被看门狗反毒化恢复过一次（防与其它意图拉锯） */
    private var poisonRescued = false
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

    /**
     * 整条重拉当前项（换一条新连接，只作用于当前项，队列里的预置项原
     * 样保留；mediaId 沿用当前项，接播记账的 key 身份不断）；队列为空
     * 时整条重装。phase-3.1 起播阶梯与 3.1.13 孤儿重试共用此救法——
     * 3.0 的 stop+seek+prepare 在多项列表下实测救不回卡住的起播。
     */
    private fun reloadCurrentItem(keepPos: Long) {
        val p = player ?: return
        try {
            if (p.mediaItemCount == 0) {
                if (nativeUrl.isEmpty()) return
                p.setMediaItem(
                    MediaItem.Builder().setUri(nativeUrl).setMediaId(nativeUrl).build()
                )
                p.prepare()
                p.play()
                return
            }
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
            orphanStall = false
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
            // 3.1.14 反毒化兜底：当前曲还没出声、又不是用户亲手暂停，
            // 却没有播放意图——意图是被非用户路径按掉的（入口拦截漏网
            // 时在此兜底）。每首只恢复一次，防拉锯。
            if (nativeActive && !soundedCurrent && !userHold && !poisonRescued) {
                poisonRescued = true
                try {
                    p.play()
                } catch (_: Exception) {
                }
            }
            watchdogStall = 0
            watchdogEndedStall = 0
            orphanStall = false
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
        // 3.1.11 报错自救兜底：onPlayerError 调度的自救若没跑成（宽限内
        // 状态抖动被拦下等），IDLE 且有近期报错、手里还有预置时在这里
        // 补跳一次（计数与 onPlayerError 路共用，连跳上限不变）。
        if (st == Player.STATE_IDLE && armedItems.isNotEmpty() &&
            errorRescueCount < MAX_ERROR_RESCUE &&
            lastErrorAtMs > 0L &&
            SystemClock.elapsedRealtime() - lastErrorAtMs < 30_000L
        ) {
            errorRescueCount++
            cancelPendingAdvance()
            doArmedAdvance()
            return
        }
        // 3.1.13 孤儿重试进行中（跑道见底卡死）：期间网页若醒来补上了
        // 预置且自救额度还在，优先跳歌续播；否则约每 30s 整条重拉一
        // 次并补发 error 喊网页，直到出声/接管/用户暂停（上方分支与
        // 健康分支已处理退出）。不再像旧行为那样停死等人亮屏。
        if (orphanStall) {
            if (armedItems.isNotEmpty() && errorRescueCount < MAX_ERROR_RESCUE) {
                orphanStall = false
                errorRescueCount++
                cancelPendingAdvance()
                doArmedAdvance()
                return
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - orphanLastRetryMs >= 30_000L) {
                orphanLastRetryMs = nowMs
                lastErrorAtMs = nowMs
                reloadCurrentItem(orphanPos)
                pushSync(
                    JSONObject().put("t", "error").put("code", -7)
                        .put("http", 0).put("msg", "stall orphan retry")
                )
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
        // 缓冲无进展不计卡住，宽限过后走阶梯（3.1.12：重拉、再重拉、
        // 仍不行有预置则跳歌；3.1.13：无预置改入孤儿重试、不再停掉交网页）。
        if (lastAdvanceAtMs > 0L &&
            SystemClock.elapsedRealtime() - lastAdvanceAtMs < 20_000L
        ) {
            watchdogStall = 0
            return
        }
        watchdogStall++
        if (watchdogStall <= 2) {
            // 3.1.12：卡住前两次都整条重拉（原为一次）——滴漏流换一条新
            // 连接经常就能活，一次机会不够。只换当前项整条重拉（换一条
            // 新连接），中途卡住的先 seek 回原位置。phase-3.1 改用 replaceMediaItem：给当前项换
            // 一份全新的媒体源，取 2.7 setMediaItem 整条重装的救法语义（那
            // 一版 43 首靠它反复救活），但只作用于当前项——队列里排着的
            // 预置项原样保留；mediaId 沿用当前项的，接播记账的 key 身份
            // 不断。3.0 的 stop+seek+prepare 在多项列表下实测没把卡住的
            // 起播救回来（《洛神》彻底没声，cheng 2026-10-23 确认）。
            reloadCurrentItem(pos)
        } else {
            // 3.1.12：两次重拉仍不出声——手里有预置就跳下一首续播（计入
            // 3.1.11 报错自救的连跳上限，防坏队列连环跳）；3.1.13：没预
            // 置或上限已满不再 stop() 交网页（熄屏网页冻住接不了手、且
            // 旧兜底停进 IDLE 后无人再管），改为记账进入孤儿重试慢速自
            // 救，别让一首滴漏流堵死整条队列。
            watchdogStall = 0
            if (armedItems.isNotEmpty() && errorRescueCount < MAX_ERROR_RESCUE) {
                errorRescueCount++
                cancelPendingAdvance()
                doArmedAdvance()
                return
            }
            orphanStall = true
            orphanLastRetryMs = SystemClock.elapsedRealtime()
            orphanPos = pos
            lastErrorAtMs = orphanLastRetryMs
            lastErrorUrl = nativeUrl
            pushSync(
                JSONObject().put("t", "error").put("code", -7)
                    .put("http", 0).put("msg", "stall watchdog: buffering made no progress")
            )
        }
    }

    /**
     * 接播后保护窗：预置接播刚完成、新曲还没起播的 30s 内（3.1.12 由
     * 10s 延长：熄屏深冻下网页解冻送达前摇可能远超 10s，越窗落到未出声
     * 的新曲上按停后无人 resume），网页迟到的切歌前摇（pause / stop /
     * disarm）不是用户意图，而是它自己没走完的切歌流程——照单全收会
     * 把刚接上的新曲按停/卸载，而它随后的 PLAY_URL 又可能永远不来
     * （phase-2.6 复测卡 0:00 的候选成因之一）。保护窗内一律忽略；
     * 网页接管记账（PLAY_URL / ARM_NEXT 到达）即提前解除。窗口只在
     * 「新曲未出声」（未 playing 且位置为 0）时成立，歌已响后用户暂停
     * 不受影响。
     */
    private fun inAdvanceGuard(): Boolean {
        if (lastAdvanceAtMs <= 0L || playing) return false
        if (positionMs > 0L) return false
        return SystemClock.elapsedRealtime() - lastAdvanceAtMs < 30_000L
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

    private fun cancelErrorRescue() {
        pendingErrorRescue?.let {
            try {
                graceHandler.removeCallbacks(it)
            } catch (_: Exception) {
            }
        }
        pendingErrorRescue = null
        // 宽限锁与待接播共用一个：还有待接播挂着时别把它的锁放掉
        if (pendingAdvance == null) releaseGraceWakeLock()
    }

    /**
     * 3.1.11 报错自救调度：报错后先等 4s——网页醒着会自己重试/换源，
     * PLAY_URL 一到即取消自救并清零计数（cancelErrorRescue 在该指令处
     * 调用）；4s 后仍无人接管、手里有预置，才由服务自己跳到下一首
     * 预置续播（走 doArmedAdvance 的统一接播记账，网页醒来照常对齐）。
     * 等待期间持宽限 WakeLock，防 CPU 睡死把计时冻住（同宽限接播）。
     */
    private fun scheduleErrorRescue() {
        if (errorRescueCount >= MAX_ERROR_RESCUE) return
        if (armedItems.isEmpty()) return
        val wantPlay = try {
            player?.playWhenReady
        } catch (_: Exception) {
            null
        }
        if (wantPlay != true) return
        cancelErrorRescue()
        acquireGraceWakeLock()
        val r = Runnable { runErrorRescue() }
        pendingErrorRescue = r
        graceHandler.postDelayed(r, 4000)
    }

    private fun runErrorRescue() {
        pendingErrorRescue = null
        if (pendingAdvance == null) releaseGraceWakeLock()
        if (!nativeActive) return
        if (errorRescueCount >= MAX_ERROR_RESCUE) return
        if (armedItems.isEmpty()) return
        // 网页已接管（换了新地址重试/切歌）就不插手
        if (nativeUrl != lastErrorUrl) return
        val p = player ?: return
        val wantPlay = try {
            p.playWhenReady
        } catch (_: Exception) {
            false
        }
        if (!wantPlay) return
        val st = try {
            p.playbackState
        } catch (_: Exception) {
            Player.STATE_IDLE
        }
        // 网页的重试已让播放器重新缓冲/就绪，自救让路
        if (st != Player.STATE_IDLE) return
        errorRescueCount++
        cancelPendingAdvance()
        doArmedAdvance()
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
        applyAdvance(item, playIt = true)
    }

    /**
     * 接播记账（宽限接播与播放列表原生接播共用）：换曲目信息、打保护窗
     * 时间戳、发 advanced 同步并在 1s / 3s 后各补发一次（页面冻醒时首条
     * 同步可能被吞）。playIt=true 是老路：服务自己装载新曲；playIt=false
     * 是 phase-3 原生接播：播放器已经自己切到了这首，只补状态对齐。
     */
    private fun applyAdvance(item: ArmedItem, playIt: Boolean) {
        // 3.1.14：新曲接管——出声标记/用户暂停标记/反毒化额度全部复位
        soundedCurrent = false
        userHold = false
        poisonRescued = false
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
            playNative(item.url)
            // 宽限接播是整表装载，剩下的预置曲目重新排进播放器队列
            syncUpcomingToPlayer()
        } else {
            nativeUrl = item.url
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
        applyAdvance(item, playIt = false)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // phase-3：播放列表原生接播在这里记账（服务自发 seek 接播同理）
            handlePlaylistTransition(mediaItem, reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!nativeActive) return
            // 3.1.11：真出声了，自救连跳计数清零
            if (isPlaying) {
                errorRescueCount = 0
                // 3.1.13：孤儿重试自活成功，退出
                orphanStall = false
                // 3.1.14：本首已真正出声，页面 pause 自此恢复正常效力
                soundedCurrent = true
            }
            playing = isPlaying
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
            // 3.1.11：记下这次报错并调度自救（宽限后网页没接管才跳预置）
            lastErrorAtMs = SystemClock.elapsedRealtime()
            lastErrorUrl = nativeUrl
            scheduleErrorRescue()
        }
    }

    /** UA/Referer 与网页 <audio> 直连一致的 HTTP 工厂（播放与预拉共用） */
    @Synchronized
    private fun ensureHttpFactory(): DefaultHttpDataSource.Factory {
        httpFactory?.let { return it }
        val ua = try {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android)"
        }
        // 请求头尽量模拟网页 <audio> 直连时的样子，降低 CDN 因 UA/Referer 拦截的概率
        val f = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://m.858600.xyz/"))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        httpFactory = f
        return f
    }

    /** 播放/预拉共用的磁盘缓存（200MB LRU 滚动淘汰） */
    @Synchronized
    private fun ensureAudioCache(): SimpleCache {
        audioCache?.let { return it }
        val c = SimpleCache(
            File(cacheDir, "audio_cache"),
            LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024),
            StandaloneDatabaseProvider(this)
        )
        audioCache = c
        return c
    }

    /** 播放数据源：先读缓存，未命中走网络、顺手写入缓存；缓存出错则直连兜底 */
    private fun cacheDataSourceFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(ensureAudioCache())
            .setUpstreamDataSourceFactory(ensureHttpFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * 3.1.13 预缓存：预置到达时后台（单线程、按队列顺序）把每首开头约
     * 1.5MB 拉进缓存。已缓存够/已拉过的地址跳过；失败静默——预拉只是
     * 抢跑，拉不到不影响正常播放路径。
     */
    private fun prefetchArmedHeads() {
        val urls = armedItems.filter { !it.repeat }.map { it.url }
        for (url in urls) {
            if (!url.startsWith("http")) continue
            if (prefetchSeen.size > 64) prefetchSeen.clear()
            if (!prefetchSeen.add(url)) continue
            prefetchExecutor.execute {
                try {
                    val cache = ensureAudioCache()
                    if (cache.getCachedLength(url, 0, PRELOAD_BYTES) >= PRELOAD_BYTES) {
                        return@execute
                    }
                    val spec = DataSpec.Builder()
                        .setUri(android.net.Uri.parse(url))
                        .setPosition(0)
                        .setLength(PRELOAD_BYTES)
                        .build()
                    CacheWriter(
                        cacheDataSourceFactory().createDataSource(), spec, null, null
                    ).cache()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                // 3.1.13：播放走缓存通道——预置预拉的开头直接读本地
                DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory())
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

    private fun playNative(url: String) {
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
        nativeActive = true
        playing = false
        // 3.1.14：新装载的曲目，出声标记等复位
        soundedCurrent = false
        userHold = false
        poisonRescued = false
        positionMs = 0
        anchorPosMs = 0
        anchorAtMs = SystemClock.elapsedRealtime()
        try {
            p.setPlaybackSpeed(pendingRate)
        } catch (_: Exception) {
        }
        p.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(url).build())
        p.prepare()
        p.play()
        startWatchdog()
        updateSessionState()
        updateNotification()
    }

    private fun stopNative() {
        cancelPendingAdvance()
        cancelErrorRescue()
        errorRescueCount = 0
        orphanStall = false
        soundedCurrent = false
        userHold = false
        poisonRescued = false
        lastErrorAtMs = 0L
        lastErrorUrl = ""
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
                        userHold = false
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
                    // 3.1.14：通知/耳机/锁屏的暂停是用户亲手意图，打标后
                    // 看门狗反毒化不会跟它抢
                    if (nativeActive) {
                        userHold = true
                        player?.pause()
                    } else sendControl("toggle")
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
                // 3.1.11：报错自救同理取消并清零（网页的重试/切歌优先）
                cancelErrorRescue()
                errorRescueCount = 0
                // 3.1.13：网页接管，孤儿重试退出
                orphanStall = false
                // 3.1.14：网页接管播放，用户暂停标记一并清除
                userHold = false
                lastAdvancedKey = ""
                lastAdvancedUrl = ""
                lastAdvanceAtMs = 0L
                val url = intent.getStringExtra("url").orEmpty()
                // 网页自己点播的正是预置队首（它醒着主动切的歌）→ 该预置已被
                // 它自己消费，别留着让队列同步/宽限接播再播一遍同一首
                if (url.isNotEmpty() && armedItems.firstOrNull()?.url == url) {
                    armedItems.removeAt(0)
                }
                if (url.isNotEmpty()) playNative(url)
            }
            ACTION_NATIVE_PAUSE -> {
                // 宽限期（已播完、等接播）内到达的 pause 多半是网页切歌前摇：
                // 它收到 ended 后跑自己的 next()/play()，开头先 pause 再清 src——
                // 若在此刻把待接播取消、预置又随后的 stop 清掉，网页的解析再在
                // 后台卡住，就再没有东西能接上（phase-2.5 实测失效的主因之一）。
                // 真正的用户暂停发生在播放中（无待接播），不受影响；播完状态下
                // 单独 pause 也没有实际意义，保留待接播，让 PLAY_URL 来接管。
                if (pendingAdvance == null) cancelPendingAdvance()
                // 3.1.14：当前曲还没真正出声前，页面 pause 一律不认——不
                // 再只看保护窗时间：深冻下前摇迟到无上限，30s 窗（3.1.12）
                // 仍会被击穿，按停后看门狗又把「无播放意图」当用户暂停、
                // 全线自救罢工（3.1.11–3.1.13 复测的终端形态）。出声后
                // 页面暂停恢复正常（走保护窗老逻辑）。
                if (!soundedCurrent) {
                    // 未出声：忽略这次页面 pause
                } else if (!inAdvanceGuard()) player?.pause()
            }
            ACTION_NATIVE_RESUME -> {
                if (nativeActive) {
                    userHold = false
                    val p = player
                    if (p != null && p.playbackState == Player.STATE_IDLE && nativeUrl.isNotEmpty()) {
                        // 出错后播放器回到 IDLE，单独 play() 救不回来，整条重装
                        playNative(nativeUrl)
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
                // 3.1.14：未出声前页面 stop 同样不认（与 NATIVE_PAUSE 同一毒化路径）
                if (pendingAdvance == null && !inAdvanceGuard() &&
                    (!nativeActive || soundedCurrent)
                ) stopNative()
            }
            ACTION_ARM_NEXT -> {
                // 网页重新预置（或接管记账）：以新清单为准。
                // 但宽限期内（已播完正等待接播）不要取消待接播计时——doArmedAdvance
                // 在触发时才读清单，换上新清单即可；取消计时等于白等一首歌的时间。
                if (pendingAdvance == null) cancelPendingAdvance()
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
                    }
                } catch (_: Exception) {
                }
                // phase-3：新预置清单同步进播放器队列，接播改由播放器内部完成
                syncUpcomingToPlayer()
                // 3.1.13：预置一到就后台预拉每首开头进缓存，起播不赌实时流速
                prefetchArmedHeads()
            }
            ACTION_DISARM_NEXT -> {
                // 宽限期内的 disarm 同样多来自网页切歌失败后的收尾（played=false 分支），
                // 不是用户的"播完停"意图（那个意图在设定的当下、播放中就已发出，
                // 待接播为空，不受影响）——有待接播时忽略，保住 fallback。
                if (pendingAdvance == null && !inAdvanceGuard()) {
                    cancelPendingAdvance()
                    armedItems.clear()
                    // 预置已撤，播放器队列里排着的后续项一并撤下，防它照旧接播
                    syncUpcomingToPlayer()
                }
            }
            ACTION_HELLO -> {
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
                        userHold = false
                        p.play()
                    } else if (p.isPlaying || p.playWhenReady) {
                        // 3.1.14：通知按钮暂停 = 用户亲手意图，打标
                        userHold = true
                        p.pause()
                    } else {
                        userHold = false
                        p.play()
                    }
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
