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
import android.media.audiofx.Equalizer
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONArray
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
        const val ACTION_BRAIN_CMD = "com.suantangyu.player.BRAIN_CMD"
        const val ACTION_BRAIN_SLEEP = "com.suantangyu.player.BRAIN_SLEEP"
        const val ACTION_DOWNLOAD = "com.suantangyu.player.DOWNLOAD"
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
                            armedItems.isEmpty() && !sleepStopParked
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

    // 3.43.0：倍速进脑存档——RATE 到达即落盘、服务 onCreate 读回；
    // 此前只存内存，服务一重启就掉回 1.0（既定捎带项）
    private fun persistRate() {
        try {
            java.io.File(filesDir, "rate_state.txt").writeText(pendingRate.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadPersistedRate() {
        try {
            val f = java.io.File(filesDir, "rate_state.txt")
            if (f.exists()) {
                val v = f.readText().trim().toFloatOrNull()
                if (v != null && v in 0.25f..4.0f) pendingRate = v
            }
        } catch (_: Exception) {
        }
    }

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
    // 单脑第 3 步：queueAheadSongs（网页上报的后续清单副本）已退役，补仓改吃脑队列
    // 单脑第 0 步（3.6.0）：影子队列快照（只存不用）与原文去重
    private var shadowSnapshotJson: String = ""
    private var shadowQueueKeys: Set<String> = emptySet()
    private var shadowCurKey: String = ""
    private var shadowMode: String = ""
    private var queueMode: String = "seq"
    private var engine: LxEngine? = null
    private val resolveExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    // 3.7.0：换源专用车道——2026-11-04 实测换源解析被补仓批次堵了 33 秒才轮上，物理隔离
    private val swapExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    // 3.9.0 壳原生下载：独立车道（大文件 IO，不挡解析/换源/起播）；索引落盘
    // dl_index.json（key → {file,size,name,artist,ts}）——名单在网页、文件真相在壳
    private val dlExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var dlIndex: JSONObject? = null
    // 3.3.1 诊断：引擎日志内存环 + 计数器。断播点与 hello 时各推一份状态，
    // 页面重开（postHello）即可在 toast 里看到断播前的最后日志与计数。
    private val engineLogLock = Object()
    private val engineLogRing = ArrayDeque<String>()
    private var engineOkCount = 0
    // 3.3.6：播放出错计数（被自救盖过去的断也要有明账，进计数行）
    private var engineErrCount = 0
    // 3.3.5 出错自救：连续跳过计数（真出声即清零）
    private var errSkipCount = 0
    // 救援状态（3.13.0 阶梯拆并）：原「重试→换源→跳过」阶梯撤除——同址
    // 重试实测零救回、重解析 6/6 无新链；报错改为直接强制换源重解析，
    // 形从落雪（报错→钻回取址链换源→跳过）。以下三旗供 12 秒帽看门狗
    // 与完成回调抢答、及停摆接播守卫共用
    private var rescueInFlight = false
    private var rescueSettled = true // 收账标记：帽看门狗与完成回调谁先到谁收
    private var rescueStartMs: Long = 0L // 救援开工时刻（守卫判在飞用）
    // 回链防循环记账：回链发动过的歌 key——会话收工后这首再出错
    // 不二次发动、直接跳过（真出声后 onIsPlayingChanged 清）；会话
    // 在岗期间候选没出声又死走 rescueContinue 续走，不经这条
    private var rescueDoneKey = ""
    // 3.18.0 回链会话（甲案：一次发动、内部续走）：发动后回链不交差，
    // 换上的址没出声又死就把死址追加进名单、同一次里接着往链后面走，
    // 直到某个址真正出声、链走空、或 12 秒总预算到点才收工。会话代次
    // 防迟到的取址结果串进新会话；rescueDoneKey 仍记「这首已发动过」
    private var rescueSessKey = ""
    private var rescueSessGen = 0
    private var rescueSessDesc: JSONObject? = null
    private var rescueSessName = ""
    private var rescueSessPlat = ""
    private val rescueSessBans = mutableSetOf<String>()
    private var rescueSessDeadlineMs = 0L
    private var rescueSessAwaitingUrl = ""
    private var rescueSessCandHost = ""
    private var rescueSessCandViaQq = false
    private var rescueSessTries = 0
    // 3.14.0 会话锁：播放意图在时服务自持 WakeLock+WifiLock，不再只靠
    // 播放器那把「在播才持」的锁——熄屏深睡会把网络分段挂起（2026-11-06
    // 实证：省电无限制开着仍被掐、2001 簇发、亮屏即恢复）。watchdogTick
    // 每轮对账补漏，意图灭（暂停/停播）即放
    private var sessionWakeLock: PowerManager.WakeLock? = null
    private var sessionWifiLock: android.net.wifi.WifiManager.WifiLock? = null
    // 3.14.0 网络停车场：网络类报错先探网，全网不通就停在当前曲等恢复
    // （看门狗每 20 秒重拉一次，出声即走），不再在没网的窗口里跳歌烧
    // 队列；3.21.0 起不限时（cheng 拍板）：歌没错、错的是网，不定死
    // 数——想播就一直等到网回来，每 10 分钟留一行等待日志兜底
    private var netParkKey = ""
    private var netParkTitle = ""
    private var netParkSinceMs: Long = 0L
    private var netParkLastTryMs: Long = 0L
    private var netParkWaitLoggedMin = 0
    private var netProbeInFlight = false
    // 解析停车场（3.38.0 立，3.41.0 探针定案）：解析全灭（本平
    // 台全档多源＋QQ 兜底全走空）即停——cheng 2026-11-10 定案：
    // 一首在架的歌全灭，本身就是脚本/平台管线整体故障的硬证据
    // （歌是平台自己列出来的、QQ 曲库又全），再往下跳歌验的还
    // 是同一批坏管线，纯烧时间与电。停在目标歌上等管线恢复，
    // 新意图/切歌/暂停即作废，与网络停车场同规矩。探针双轨独
    // 立（3.41.0，cheng 定案）：A 线自探目标（10 秒首探、之后
    // 每 60 秒一轮、不限轮），探到即原地续播；B 线自纠定性
    // （20 秒探上一首、80 秒探下一首），任一探活而目标未恢复
    // 即撤销定罪、跳过此歌，两轮皆空即退场、只剩 A 线继续等
    private var pipeParkKey = ""
    private var pipeParkTitle = ""
    private var pipeParkFromStart = false
    private var pipeParkSinceMs: Long = 0L
    private var pipeParkLastTryMs: Long = 0L
    private var pipeParkWaitLoggedMin = 0
    private var pipeProbeBusy = false
    // 自纠出口：A 线探针轮数、停车来源（翻案要用）与已用参照
    private var pipeParkProbeCount = 0
    private var pipeParkCause = ""
    private var pipeParkRefUsedKey = ""
    // B 线（自纠）自己的表：首探 +20 秒、次探 +80 秒，共两轮
    private var pipeParkRefLastTryMs: Long = 0L
    private var pipeParkRefCount = 0
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
            " 队列剩 " + brainAheadWindow().size + " 跑道 " + armedItems.size +
            " 源 " + configSources.size + " 出错 " + e
    }

    private fun lxEngine(): LxEngine {
        var e = engine
        if (e == null) {
            e = LxEngine { m ->
                engineLog(m)
                // 脚本日志分段（3.12.0 建，3.12.1 改暂存）：解析段进行中，
                // 脚本自己的行（[源名] 开头）与脚本解析报错行记进当前段；
                // 段先暂存、歌真正起播才落盘，后台日志那份照旧全量
                if (m.startsWith("[") || m.contains("解析报错")) {
                    ScriptSeg.line(m)
                }
            }
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

    private val scriptLogLock = Any()

    /** 脚本日志落盘（3.12.0 建，3.12.1 改）：把一行已盖戳的原文 append
     * 进独立文件 lx_script.log——内容由 ScriptSeg 在歌曲起播落盘时逐行
     * 喂入，本函数只管文件；与后台日志同源但互不影响，后台日志的
     * 记录方式不改。上限 512KB、写满滚动保留约 384KB（同后台策略） */
    private fun scriptFileAppend(line: String) {
        synchronized(scriptLogLock) {
            try {
                val f = java.io.File(filesDir, "lx_script.log")
                if (f.length() > 512 * 1024) {
                    try {
                        val lines = f.readLines(Charsets.UTF_8)
                        var bytes = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
                        var drop = 0
                        while (drop < lines.size && bytes > 384 * 1024) {
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
        }
    }

    /** 单脑第 3 步：补仓窗口 = 脑队列从当前下标往后现算（取代网页上报的
     * 后续清单副本 dropQueueAheadThrough 那套——切歌后窗口按下标自动跟上） */
    private fun brainAheadWindow(): List<org.json.JSONObject> {
        if (brainQueue.isEmpty()) return emptyList()
        val idx = brainQueueKeys.indexOf(brainCurrentKey())
        if (idx < 0) return emptyList()
        val out = ArrayList<org.json.JSONObject>()
        var i = idx + 1
        while (out.size < 24) {
            if (i >= brainQueue.size) i = 0
            if (i == idx) break
            out.add(brainQueue[i])
            i++
        }
        return out
    }

    /**
     * 跑道见底时点名解析：armedItems 只剩 ≤1 且网页超过 15s 没补仓（多半是
     * 熄屏冻结），壳自己解析清单前 3 首回填。网页活着时它总在切歌瞬间补仓，
     * 条件天然不成立，不会与网页抢解析。
     */
    private fun dlDir(): java.io.File {
        val d = java.io.File(filesDir, "downloads")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun dlIndexLoad(): JSONObject {
        var idx = dlIndex
        if (idx == null) {
            idx = try {
                val f = java.io.File(filesDir, "dl_index.json")
                if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            dlIndex = idx
        }
        return idx
    }

    private fun dlIndexSave() {
        try {
            java.io.File(filesDir, "dl_index.json")
                .writeText(dlIndexLoad().toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun dlMd5(s: String): String {
        return try {
            val d = java.security.MessageDigest.getInstance("MD5")
                .digest(s.toByteArray(Charsets.UTF_8))
            d.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            Integer.toHexString(s.hashCode())
        }
    }

    /** 本地已下载文件（没有返回 null）：脑起播本地优先与补仓跳过都查它 */
    private fun localFileForKey(key: String): java.io.File? {
        if (key.isEmpty()) return null
        val e = dlIndexLoad().optJSONObject(key) ?: return null
        if (e.optBoolean("bad")) return null // 播放失败已标记：不再试本地，免得每点必先摔一跤
        val fn = e.optString("file")
        if (fn.isEmpty()) return null
        val f = java.io.File(dlDir(), fn)
        return if (f.exists() && f.length() > 0) f else null
    }

    private fun deleteLocalDownload(key: String) {
        val idx = dlIndexLoad()
        val e = idx.optJSONObject(key) ?: return
        try {
            java.io.File(dlDir(), e.optString("file")).delete()
        } catch (_: Exception) {
        }
        idx.remove(key)
        dlIndexSave()
    }

    /**
     * 壳原生下载执行（dlExec 车道）：流式拉文件进私有目录，登记索引
     * （80 上限，超限删最老，文件索引一起删），完工回执网页并打日志；
     * 系统 ≥29 再复制一份到「下载/酸汤鱼音乐」（见 exportDownloadCopy）。
     */
    private fun runDownload(
        key: String, url: String, name: String, artist: String, filename: String
    ) {
        var ok = false
        var size = 0L
        var part: java.io.File? = null
        try {
            val ext = when {
                url.contains("flac", true) || filename.endsWith(".flac", true) -> "flac"
                url.contains("m4a", true) || url.contains(".mp4", true) -> "m4a"
                else -> "mp3"
            }
            // 3.9.4 只升不降：本地已存着更好的（localFileForKey 已排除标记坏的
            // 和文件丢失的），这次只拿到更差的就不许覆盖——3.9.3 的「新来换旧」
            // 少了这道门，cheng 实测：第一次存的无损被第二次的普通版顶掉了
            val oldLocal = localFileForKey(key)
            if (oldLocal != null) {
                fun qRank(e: String) = when (e.lowercase()) {
                    "flac" -> 0
                    "m4a" -> 1
                    else -> 2
                }
                val oldExt = oldLocal.name.substringAfterLast('.', "")
                if (qRank(ext) > qRank(oldExt)) {
                    engineLog("引擎：下载未覆盖 " + name + "（本地已是无损版，这次只有普通版）")
                    pushSync(
                        JSONObject().put("t", "dl").put("key", key)
                            .put("ok", true).put("kept", true)
                            .put("size", oldLocal.length())
                    )
                    return
                }
            }
            val fname = dlMd5(key) + "." + ext
            val dest = java.io.File(dlDir(), fname)
            part = java.io.File(dlDir(), fname + ".part")
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Referer", "https://m.858600.xyz/")
            conn.connect()
            if (conn.responseCode !in 200..299) throw Exception("http " + conn.responseCode)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part).use { out -> input.copyTo(out) }
            }
            size = part.length()
            if (size < 1000) throw Exception("文件过小")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw Exception("改名失败")
            val idx = dlIndexLoad()
            // 3.9.3：换格式重下时旧文件要删——文件名带扩展名，.flac 换成 .mp3
            // 后旧那份没人管会成孤儿白占盘（2026-11-05 黄昏实测 35MB 孤儿）
            val oldDlFile = idx.optJSONObject(key)?.optString("file") ?: ""
            idx.put(
                key, JSONObject()
                    .put("file", fname).put("size", size)
                    .put("name", name).put("artist", artist)
                    .put("ts", System.currentTimeMillis())
            )
            if (oldDlFile.isNotEmpty() && oldDlFile != fname) {
                try {
                    java.io.File(dlDir(), oldDlFile).delete()
                } catch (_: Exception) {
                }
            }
            // 上限 80 与网页名单同数：只增不删会吃满盘，超限按时间删最老
            while (idx.length() > 80) {
                var oldestKey: String? = null
                var oldestTs = Long.MAX_VALUE
                val kit = idx.keys()
                while (kit.hasNext()) {
                    val k = kit.next()
                    val ts = idx.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
                    if (ts < oldestTs) {
                        oldestTs = ts
                        oldestKey = k
                    }
                }
                val victim = oldestKey ?: break
                try {
                    java.io.File(
                        dlDir(), idx.optJSONObject(victim)?.optString("file") ?: ""
                    ).delete()
                } catch (_: Exception) {
                }
                idx.remove(victim)
            }
            dlIndexSave()
            ok = true
            engineLog(
                "引擎：下载完成 " + name + "（" +
                    String.format("%.1f", size / 1048576.0) + " MB）key=" + key
            )
            exportDownloadCopy(dest, filename, ext, name)
        } catch (e: Exception) {
            try {
                part?.delete()
            } catch (_: Exception) {
            }
            engineLog(
                "引擎：下载失败 " + name + "（" + (e.message ?: "网络错误").take(60) + "）"
            )
        }
        pushSync(
            JSONObject().put("t", "dl").put("key", key)
                .put("ok", ok).put("size", size)
        )
    }

    /**
     * 把下好的真文件复制一份到系统「下载/酸汤鱼音乐」（API 29+ MediaStore，
     * 无需存储权限）——对齐落雪一派：文件看得见、拷得走。导出的只是拷贝，
     * 播放账本只认私有目录那份；导出失败只记日志，不算下载失败。
     */
    private fun exportDownloadCopy(
        src: java.io.File, filename: String, ext: String, songName: String
    ) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        try {
            val mime = when (ext) {
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                else -> "audio/mpeg"
            }
            // 导出前按「歌」清旧：同主干名（歌名 - 歌手，不看扩展名）的旧副本
            // 先删再导——系统「下载/酸汤鱼音乐」里一首永远只留一份。3.9.2 只按
            // 同名删，管不住 flac/mp3 换格式后并排两份（cheng 2026-11-05 实见）
            try {
                val dlUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val stem = filename.substringBeforeLast('.', filename)
                val likeArg = stem
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%"
                contentResolver.query(
                    dlUri,
                    arrayOf(
                        android.provider.MediaStore.Downloads._ID,
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.provider.MediaStore.Downloads.DISPLAY_NAME
                    ),
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ? ESCAPE '\\'",
                    arrayOf(likeArg),
                    null
                )?.use { c ->
                    val idCol = c.getColumnIndex(android.provider.MediaStore.Downloads._ID)
                    val pathCol =
                        c.getColumnIndex(android.provider.MediaStore.Downloads.RELATIVE_PATH)
                    val nameCol =
                        c.getColumnIndex(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val rp = if (pathCol >= 0) c.getString(pathCol) ?: "" else ""
                        val dn = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                        if (rp.trimEnd('/') == "Download/酸汤鱼音乐" && idCol >= 0 &&
                            dn.substringBeforeLast('.', dn) == stem
                        ) {
                            contentResolver.delete(
                                android.content.ContentUris.withAppendedId(dlUri, c.getLong(idCol)),
                                null, null
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(
                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    "Download/酸汤鱼音乐"
                )
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("insert null")
            val out = contentResolver.openOutputStream(uri)
                ?: throw Exception("openOutputStream null")
            out.use { o -> src.inputStream().use { input -> input.copyTo(o) } }
            val done = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            }
            contentResolver.update(uri, done, null, null)
            engineLog("引擎：已导出到下载文件夹 " + songName)
        } catch (_: Exception) {
            engineLog("引擎：导出失败 " + songName + "（不影响 App 内播放）")
        }
    }

    private fun maybeEngineResolve() {
        val wantPlay = playing ||
            (try {
                player?.playWhenReady
            } catch (_: Exception) {
                null
            } == true)
        if (!nativeActive || !wantPlay || engineBusy) return
        if (brainSleepAfterTrack) return
        // 3.21.0：停车期间（已证全网断）补仓暂停——问也白问，只会每
        // 十几秒刷一批解析失败、白耗电波；网回来停车解除后自动恢复
        if (netParkKey.isNotEmpty()) return
        // 3.38.0：解析停车期间同理暂停补仓——管线已判全灭，补仓只
        // 会对着坏管线反复走空；停车解除（探到恢复）后自动恢复
        if (pipeParkKey.isNotEmpty()) return
        if (configSources.isEmpty() || !brainActive) return
        if (armedItems.size > 1) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastArmAtMs < 15000) return
        if (armedItems.isNotEmpty() && now - lastEngineAtMs < 10000) return
        val aheadWin = brainAheadWindow()
        if (aheadWin.isEmpty() && brainMode != "single") return
        val armedKeys = armedItems.map { it.key }.toSet()
        val ordered =
            if (brainMode == "shuffle" && brainActive) {
                // 单脑第 1 步：随机补仓优先没播过的，一轮不重复从补仓就开始守
                val fresh = aheadWin.filter {
                    !brainPlayed.contains(LxResolver.keyOfJson(it))
                }
                val rest = aheadWin.filter {
                    brainPlayed.contains(LxResolver.keyOfJson(it))
                }
                fresh.shuffled() + rest.shuffled()
            } else if (brainMode == "shuffle") aheadWin.shuffled() else aheadWin
        val todo = ArrayList<org.json.JSONObject>()
        for (s in ordered) {
            if (todo.size >= 3) break
            val k = LxResolver.keyOfJson(s)
            if (k.isEmpty() || armedKeys.contains(k)) continue
            // 3.9.0：已下载到本地的歌不占补仓解析，起播时脑子直接播文件
            if (localFileForKey(k) != null) continue
            if (s.optString("name").isEmpty()) continue
            todo.add(s)
        }
        if (todo.isEmpty()) return
        // 单脑第 4 步（K1）：单曲模式跑道只供「当前曲重复项」——desiredUpcoming
        // 见 repeat 即清空播放器后续队列、逼接播走 ENDED 的脑内重复；不囤后续
        // 曲（囤了也轮不到，重复项才是单曲跑道的唯一货物）。
        var step4RepeatKey = ""
        if (brainMode == "single") {
            val ci = brainQueueKeys.indexOf(brainCurrentKey())
            val cs = if (ci >= 0) brainQueue[ci] else null
            val ck = if (cs != null) LxResolver.keyOfJson(cs) else ""
            todo.clear()
            if (cs != null && ck.isNotEmpty() && !armedKeys.contains(ck) &&
                cs.optString("name").isNotEmpty()
            ) {
                todo.add(cs)
                step4RepeatKey = ck
            }
            if (todo.isEmpty()) return
        }
        engineBusy = true
        lastEngineAtMs = now
        synchronized(engineLogLock) { engineBatchCount++ }
        resolveExec.execute {
            var okN = 0
            var fN = 0
            try {
                for (song in todo) {
                    val sKey4 = LxResolver.keyOfJson(song)
                    val reuse4 =
                        if (step4RepeatKey.isNotEmpty() && sKey4 == step4RepeatKey) {
                            val lf = localFileForKey(step4RepeatKey)
                            if (lf != null) "file://" + lf.absolutePath
                            else if (nativeKey == step4RepeatKey &&
                                nativeUrl.startsWith("http")
                            ) nativeUrl else ""
                        } else ""
                    val url = if (reuse4.isNotEmpty()) reuse4 else LxResolver.resolve(
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
                        step4RepeatKey.isNotEmpty() &&
                            LxResolver.keyOfJson(song) == step4RepeatKey, true
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
                                rescueNotInFlight() && netParkKey.isEmpty() &&
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
        checkBrainSleepDeadline()
        updateSessionLocks()
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
        // 网络停车场值班：停着的歌每 20 秒重拉一次探活，出声由
        // onIsPlayingChanged 收账；用户暂停就散场；3.21.0 起不限时，
        // 不再有「等满 N 分钟放弃」——想播就一直等，每等满 10 分钟
        // 留一行等待日志（防探针误判时闷头干等、日志上没人看得出）。
        // 切歌后当前项已不是停着的歌 → 停车作废：旧代码值班只认当前
        // 序号、不认歌，切走后会对新歌每 20 秒从头重拉，上限 3 分钟
        // 时窗口小没出事，不限时后必须堵。值班期间看门狗其它处置让路
        if (netParkKey.isNotEmpty()) {
            if (!wantPlay) {
                clearNetPark()
            } else {
                val curId = try {
                    p.currentMediaItem?.mediaId ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (curId.isNotEmpty() && curId != netParkKey) {
                    clearNetPark()
                } else {
                    val nowE = SystemClock.elapsedRealtime()
                    val waitedBucket =
                        ((nowE - netParkSinceMs) / 600_000L).toInt()
                    if (waitedBucket > netParkWaitLoggedMin) {
                        netParkWaitLoggedMin = waitedBucket
                        engineLog(
                            "引擎：仍在等网络恢复 " + netParkTitle +
                                "（已等 " + (waitedBucket * 10) + " 分钟）"
                        )
                    }
                    if (nowE - netParkLastTryMs >= 20_000L) {
                        netParkLastTryMs = nowE
                        try {
                            p.seekTo(p.currentMediaItemIndex, 0)
                            p.prepare()
                            p.play()
                        } catch (_: Exception) {
                        }
                    }
                    return
                }
            }
        }
        // 解析停车场值班（3.38.0 立，3.41.0 双轨）：停在全灭的
        // 目标歌上，双轨探针各跑各的表（A 线自探目标、B 线自纠
        // 定性，见 probePipeParkTarget/probePipeParkRef）；无播
        // 放意图（用户暂停）或目标已被移出队列即作废；每等满 10
        // 分钟留一行等待日志。值班期间看门狗其它处置让路（同网
        // 络停车场）
        if (pipeParkKey.isNotEmpty()) {
            if (!wantPlay || !brainQueueKeys.contains(pipeParkKey)) {
                voidPipePark()
            } else {
                val nowP = SystemClock.elapsedRealtime()
                // 等待日志不限轮：A 线不限轮地等下去，每 10 分
                // 钟一行对账（与网络停车场同规矩）
                val waitedBucketP =
                    ((nowP - pipeParkSinceMs) / 600_000L).toInt()
                if (waitedBucketP > pipeParkWaitLoggedMin) {
                    pipeParkWaitLoggedMin = waitedBucketP
                    engineLog(
                        "引擎：仍在等管线恢复 " + pipeParkTitle +
                            "（已等 " + (waitedBucketP * 10) +
                            " 分钟）"
                    )
                }
                // A 先 B 后：同 tick 两条线都到期也先探目标，B
                // 永远踩在 A 的探针之后约 10 秒（A 10/70/… 秒、
                // B 20/80 秒），翻案时手里的目标证据不超过约 10
                // 秒旧；pipeProbeBusy 是两条线共用的串行闸（脚
                // 本引擎只有一条线，执行层照旧排队）
                if (!pipeProbeBusy &&
                    nowP - pipeParkLastTryMs >= 60_000L
                ) {
                    probePipeParkTarget()
                }
                if (!pipeProbeBusy && pipeParkRefCount < 2 &&
                    nowP - pipeParkRefLastTryMs >= 60_000L
                ) {
                    probePipeParkRef()
                }
                return
            }
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
                advanceAfterEnd()
            } else if (watchdogEndedStall == 1 || watchdogEndedStall >= 3) {
                if (armedItems.isEmpty() && brainActive) {
                    brainEndedAdvance()
                } else if (armedItems.isEmpty()) {
                    acquireGraceWakeLock()
                    pushSync(JSONObject().put("t", "ended"))
                } else {
                    cancelPendingAdvance()
                    advanceAfterEnd()
                }
                if (watchdogEndedStall >= 3) watchdogEndedStall = 1
            }
            return
        }
        watchdogEndedStall = 0
        // 3.5.5：IDLE 停摆兜底——看门狗第二击把播放器停到 IDLE 后，若跑道
        // 里已有歌（无论谁放的），此前无人接播、只能干等网页醒来。此处与
        // 补仓落袋的停摆接播互为兜底：还想播、跑道非空、播放器空闲 → 接播。
        if (st == Player.STATE_IDLE && wantPlay && netParkKey.isEmpty() &&
            rescueNotInFlight() && armedItems.isNotEmpty()
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
        noteBrainStarted(item.key)
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
        val sleepStop = brainSleepAfterTrack
        applyAdvance(item, playIt = false)
        if (sleepStop) fireBrainSleepStop()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // phase-3：播放列表原生接播在这里记账（服务自发 seek 接播同理）
            handlePlaylistTransition(mediaItem, reason)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // 3.24.0：音频会话变化时重建系统均衡器并重应用音效状态
            eqAttach(audioSessionId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!nativeActive) return
            playing = isPlaying
            if (isPlaying) {
                errSkipCount = 0
                // 3.18.0：回链候选真正出声才记「回链成功」并收工关会话
                // （3.17.0 记在拿到新址时——《半壶纱》那种拿到址没出声
                // 又死的也被记成成功，日后数自救占比会虚高）；别的歌
                // 出声则会话作废静默关
                if (rescueSessKey.isNotEmpty()) {
                    val sk = try {
                        player?.currentMediaItem?.mediaId ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    if (sk == rescueSessKey &&
                        rescueSessAwaitingUrl.isNotEmpty()
                    ) {
                        engineLog(
                            "引擎：回链成功 " + rescueSessName + " → " +
                                rescueSessCandHost +
                                if (rescueSessCandViaQq) "（QQ 兜底）"
                                else "（本平台）"
                        )
                    }
                    closeRescueSession()
                }
                rescueDoneKey = ""
                rescueInFlight = false
                rescueSettled = true
                if (netParkKey.isNotEmpty()) {
                    engineLog("引擎：网络已恢复，继续播放 " + title)
                    clearNetPark()
                }
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
                    if (brainActive) {
                        // 单脑第 1 步：播完接续归脑子算（跑道命中/现解析都在脑内闭环）
                        brainEndedAdvance()
                    } else if (armedItems.isNotEmpty()) {
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
            // 本地文件播出错：不进网络阶梯（重试/换源治不了本地文件），回在线
            // 路径把当前曲重走一遍。但只标记不删——出错未必是文件坏了（3.9.0
            // 的 file:// 拒收就是播放器配置问题、文件是好的，误删是二次伤害）；
            // 标记后本地查询自动跳过这首，重新下载会覆盖条目、标记自愈
            if (nativeUrl.startsWith("file:") && nativeKey.isNotEmpty() &&
                localFileForKey(nativeKey) != null
            ) {
                val healKey = nativeKey
                try {
                    val idx = dlIndexLoad()
                    idx.optJSONObject(healKey)?.put("bad", true)
                    dlIndexSave()
                } catch (_: Exception) {
                }
                engineLog("引擎：本地文件播放失败，已标记跳过 " + title)
                brainStartKey = healKey
                tickHandler.postDelayed({
                    brainOpGen++
                    executeBrainAdvance(healKey, "start", 0)
                }, 300)
                return
            }
            // 3.3.5：错误留痕进引擎日志 + 壳内自救。解析成功不等于流能播，
            // 某首直链拉流时死了（403/过期），不能停等冻着的页面来救
            if (!rescueInFlight && !netProbeInFlight) {
                val p = player
                val curKey = try {
                    p?.currentMediaItem?.mediaId ?: ""
                } catch (_: Exception) {
                    ""
                }
                // 3.21.0：停车期间的重拉失败（网络类）不逐条记日志、
                // 不计出错数——不限时后一晚能刷近千条假账；入场与恢复
                // 已各记一行，中间靠看门狗每 10 分钟的等待行对账
                val parkRetry = curKey.isNotEmpty() &&
                    curKey == netParkKey &&
                    (error.errorCode == 2001 || error.errorCode == 2002)
                if (!parkRetry) {
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
                }
                // 报错即救——死址作废、原歌钻回解析链重走（报错
                // 回链）；3.18.0 起一次发动内部续走：换上的址没出声
                // 又死，把死址追加作废在同一次里接着走（rescueSessKey
                // 在岗）；会话已收工的歌再出错才直接跳过
                // （rescueDoneKey 记账，真出声后才清）
                if (p != null) {
                    when {
                        parkRetry -> {
                            // 停车等待中看门狗重拉又失败：不重复探网，
                            // 续等下一轮重拉（日志/计数已在上面免掉）
                            netParkLastTryMs =
                                android.os.SystemClock.elapsedRealtime()
                        }
                        curKey.isNotEmpty() && curKey == netParkKey -> {
                            // 停车中报了非网络类错（如 3003）：网多半
                            // 已经半通、坏的是文件本身——退出停车，按
                            // 文件死走报错回链，别在停车场里死等
                            clearNetPark()
                            rescueByReResolve(p, curKey, nativeUrl)
                        }
                        error.errorCode == 2001 || error.errorCode == 2002 -> {
                            // 网络类报错先探网（3.14.0）：全网不通就停车
                            // 保歌，别在没网的窗口里换源/跳歌白烧队列；
                            // 网通（只是单个站死）照常走报错回链
                            val k2 = curKey
                            val u2 = nativeUrl
                            val t2 = title
                            val a2 = artist
                            netProbeInFlight = true
                            swapExec.execute {
                                val up = qqSearchReachable(t2, a2)
                                tickHandler.post {
                                    netProbeInFlight = false
                                    val pp2 = player
                                    if (pp2 == null || !nativeActive ||
                                        nativeUrl != u2
                                    ) {
                                        return@post
                                    }
                                    if (up) {
                                        if (k2.isNotEmpty() &&
                                            k2 == rescueSessKey
                                        ) {
                                            // 3.18.0：回链候选撞网络类报错
                                            // 但网是通的——当候选死了续走
                                            rescueContinue(pp2, k2)
                                        } else if (k2.isNotEmpty() &&
                                            k2 == rescueDoneKey
                                        ) {
                                            doSkipAfterError(pp2)
                                        } else {
                                            rescueByReResolve(pp2, k2, u2)
                                        }
                                    } else {
                                        netParkKey = k2
                                        netParkTitle = t2
                                        netParkSinceMs = android.os
                                            .SystemClock.elapsedRealtime()
                                        netParkLastTryMs = netParkSinceMs
                                        netParkWaitLoggedMin = 0
                                        engineLog(
                                            "引擎：网络中断，停在这首等恢复 " + t2
                                        )
                                    }
                                }
                            }
                        }
                        curKey.isNotEmpty() && curKey == rescueSessKey -> {
                            // 3.18.0 甲案：回链换上的址没出声又死——不
                            // 算新发动，死址追加作废、同一次里续走
                            rescueContinue(p, curKey)
                        }
                        curKey.isNotEmpty() && curKey == rescueDoneKey -> {
                            doSkipAfterError(p)
                        }
                        else -> {
                            rescueByReResolve(p, curKey, nativeUrl)
                        }
                    }
                }
            }
        }
    }

    /** 会话锁对账（3.14.0）：想播就持锁、意图灭就放锁。watchdogTick
     * 每轮调一次，生命周期边沿漏了也会在 10 秒内补齐/回收 */
    private fun updateSessionLocks() {
        val want = nativeActive &&
            (try { player?.playWhenReady } catch (_: Exception) { null } == true)
        if (want) {
            try {
                if (sessionWakeLock?.isHeld != true) {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    val wl = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "Suantangyu:Session"
                    )
                    wl.setReferenceCounted(false)
                    wl.acquire()
                    sessionWakeLock = wl
                }
            } catch (_: Exception) {
            }
            try {
                if (sessionWifiLock?.isHeld != true) {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE)
                        as android.net.wifi.WifiManager
                    @Suppress("DEPRECATION")
                    val fl = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "Suantangyu:Session"
                    )
                    fl.setReferenceCounted(false)
                    fl.acquire()
                    sessionWifiLock = fl
                }
            } catch (_: Exception) {
            }
        } else {
            releaseSessionLocks()
        }
    }

    private fun releaseSessionLocks() {
        try {
            if (sessionWakeLock?.isHeld == true) sessionWakeLock?.release()
        } catch (_: Exception) {
        }
        sessionWakeLock = null
        try {
            if (sessionWifiLock?.isHeld == true) sessionWifiLock?.release()
        } catch (_: Exception) {
        }
        sessionWifiLock = null
    }

    /** 探网（3.14.0）：拿当前歌去 QQ 搜一下——能拿到任何回应（含空
     * 结果）就是网通；硬失败（fetchJson 返回 null）才算全网不通 */
    private fun qqSearchReachable(name: String, singer: String): Boolean {
        val kw = (name + " " + singer).trim()
        if (kw.isEmpty()) return true
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" +
            android.net.Uri.encode(kw) +
            "&format=json&p=1&n=1&new_json=1&cr=1&t=0"
        return try {
            LxHttp.fetchJson(
                url,
                headers = mapOf("Referer" to "https://y.qq.com/"),
                timeoutSec = 5
            ) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun clearNetPark() {
        netParkKey = ""
        netParkTitle = ""
        netParkSinceMs = 0L
        netParkLastTryMs = 0L
        netParkWaitLoggedMin = 0
    }

    private fun clearPipePark() {
        pipeParkKey = ""
        pipeParkTitle = ""
        pipeParkFromStart = false
        pipeParkProbeCount = 0
        pipeParkCause = ""
        pipeParkRefUsedKey = ""
        pipeParkRefCount = 0
        pipeParkSinceMs = 0L
        pipeParkLastTryMs = 0L
        pipeParkRefLastTryMs = 0L
        pipeParkWaitLoggedMin = 0
        pipeProbeBusy = false
    }

    /** 停车作废（用户改了主意/目标没了）：点播停的车若终究没播
     * 成，脚本段照 failBrainStart 的规矩落盘，别查无此歌 */
    private fun voidPipePark() {
        val k = pipeParkKey
        val fromStart = pipeParkFromStart
        clearPipePark()
        if (k.isNotEmpty() && fromStart) ScriptSeg.flushFor(k)
    }

    /** 进解析停车场：记账＋留痕＋通知页面（pipark 不是失败回执，
     * 页面不走老路，只显示状态等 advanced） */
    private fun enterPipePark(key: String, title: String, cause: String) {
        if (key.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        pipeParkKey = key
        pipeParkTitle = title.ifEmpty { brainNameOf(key) }
        pipeParkFromStart = cause == "start"
        pipeParkCause = cause
        pipeParkProbeCount = 0
        pipeParkRefUsedKey = ""
        pipeParkRefCount = 0
        pipeParkSinceMs = now
        // 双轨各自回拨计时（看门狗一律按 60 秒间隔判定）：A 线
        // lastTry 回拨 50 秒 → 首探落在 +10 秒，其后以探针起点
        // 计每 60 秒一轮、不限轮；B 线回拨 40 秒 → 首探（上一
        // 首）落在 +20 秒，第二轮（下一首）在首探起点 +60 秒，
        // 即 +80 秒，两轮走完退场
        pipeParkLastTryMs = now - 50_000L
        pipeParkRefLastTryMs = now - 40_000L
        pipeParkWaitLoggedMin = 0
        engineLog(
            "引擎：解析全灭，疑似音源/平台整体故障，暂停跳歌 " +
                pipeParkTitle + "（恢复后自动续播）"
        )
        pushSync(JSONObject().put("t", "pipark").put("key", key))
    }

    /** A 线·目标自探（3.41.0 双轨之一）：只管目标歌自己——
     * 整条链重走一次（探的是整条管线，不是单条腿——单腿活不
     * 等于这首歌有救），拿到地址即原地起播收场；仍走空就继续
     * 停、不限轮，探空不逐条记日志（进场行与每 10 分钟等待行
     * 已够对账，重探自身的解析段照常进脚本日志渠道） */
    private fun probePipeParkTarget() {
        val key = pipeParkKey
        if (key.isEmpty() || pipeProbeBusy) return
        val di = brainQueueKeys.indexOf(key)
        val desc = if (di >= 0) brainQueue[di] else null
        if (desc == null || configSources.isEmpty()) return
        pipeProbeBusy = true
        pipeParkLastTryMs = SystemClock.elapsedRealtime()
        pipeParkProbeCount++
        val gen = brainOpGen
        resolveExec.execute {
            val url = try {
                LxResolver.resolve(
                    desc, configSources, configQuality, configAutoDown,
                    lxEngine()
                ) { m -> engineLog(m) }
            } catch (_: Exception) {
                null
            }
            tickHandler.post {
                pipeProbeBusy = false
                if (pipeParkKey != key) return@post
                if (gen != brainOpGen) return@post
                if (!url.isNullOrEmpty()) {
                    val t2 = desc.optString("name")
                    engineLog("引擎：管线恢复，续播 " + t2)
                    if (key == brainStartKey) brainStartKey = ""
                    clearPipePark()
                    val item = ArmedItem(
                        url, key,
                        t2, desc.optString("artist"),
                        desc.optString("cover")
                            .ifEmpty { desc.optString("img") },
                        false, true
                    )
                    applyAdvance(item, playIt = true)
                }
                // 仍空：定罪维持，等下一轮（60 秒后）
            }
        }
    }

    /** B 线·自纠定性（3.41.0 双轨之二；3.39.0 自纠出口的独立
     * 化）：只管回答「管线死了没」，共两轮——第 1 轮探上一首
     * （最后真正起播过的那首，校准过的样本；回链停车时它就是
     * 目标自己，自动落空），第 2 轮探下一首（续播落点）；先选
     * 的不可用（是目标自己/不在队列/上一轮用过）就拿另一首
     * 顶，两轮不重复用同一首参照。任一轮参照出地址而目标还没
     * 恢复 = 管线无恙、独这首无货（冤案），撤销定罪、跳过此
     * 歌续播；两轮皆空 = 定罪维持，自纠退场，之后只剩 A 线继
     * 续等。翻案只凭阳性证据（参照真出货），真故障时参照同样
     * 出不来，不会误触发 */
    private fun probePipeParkRef() {
        val key = pipeParkKey
        if (key.isEmpty() || pipeProbeBusy) return
        if (pipeParkRefCount >= 2) return
        val di = brainQueueKeys.indexOf(key)
        val desc = if (di >= 0) brainQueue[di] else null
        if (desc == null || configSources.isEmpty()) return
        pipeParkRefLastTryMs = SystemClock.elapsedRealtime()
        pipeParkRefCount++
        // 参照歌：上一首 = 此刻脑内当前键（最后真正起播过的那
        // 首）；下一首按现行模式算（脑内当前键临时换成目标算）
        var refKey = ""
        var refDesc: org.json.JSONObject? = null
        run {
            val savedCur = brainCurKey
            brainCurKey = key
            val nk = computeBrainNextKey(true)
            brainCurKey = savedCur
            val order = if (pipeParkRefCount == 1)
                listOf(savedCur, nk) else listOf(nk, savedCur)
            for (cand in order) {
                if (cand != null && cand.isNotEmpty() &&
                    cand != key && cand != pipeParkRefUsedKey
                ) {
                    val ri = brainQueueKeys.indexOf(cand)
                    if (ri >= 0) {
                        refKey = cand
                        refDesc = brainQueue[ri]
                        break
                    }
                }
            }
        }
        if (refKey.isNotEmpty()) pipeParkRefUsedKey = refKey
        val rd = refDesc
        if (rd == null) {
            // 本轮无可用参照（队列太短/参照就是目标自己）：这一
            // 轮算过，下一轮再看；两轮都无参照即退场
            if (pipeParkRefCount >= 2) {
                engineLog(
                    "引擎：自纠两轮皆空，管线定罪维持，自纠退场 " +
                        desc.optString("name") + "（目标自探继续）"
                )
            }
            return
        }
        pipeProbeBusy = true
        val rk = refKey
        val gen = brainOpGen
        resolveExec.execute {
            val refUrl = try {
                LxResolver.resolve(
                    rd, configSources, configQuality, configAutoDown,
                    lxEngine()
                ) { m -> engineLog(m) }
            } catch (_: Exception) {
                null
            }
            tickHandler.post {
                pipeProbeBusy = false
                if (pipeParkKey != key) return@post
                if (gen != brainOpGen) return@post
                if (!refUrl.isNullOrEmpty()) {
                    // 翻案：参照当场出货、目标仍未恢复——管线无
                    // 恙，死的只是这首。撤销定罪，跳过它往下续播
                    val t2 = desc.optString("name")
                    val rt = rd.optString("name")
                    engineLog(
                        "引擎：撤销定罪 " + t2 + "（管线无恙：参照 " +
                            rt + " 可解析，是这首无货）→ 跳过此歌"
                    )
                    val cause0 = pipeParkCause
                    // 下一首按撤销时刻的队列现算；与本轮参照一致
                    // 就直接用探到的地址起播，不浪费这一探
                    val savedCur2 = brainCurKey
                    brainCurKey = key
                    val nk2 = computeBrainNextKey(true)
                    brainCurKey = savedCur2
                    when {
                        nk2 != null && nk2 == rk -> {
                            if (key == brainStartKey) brainStartKey = ""
                            clearPipePark()
                            val item = ArmedItem(
                                refUrl!!, rk,
                                rt, rd.optString("artist"),
                                rd.optString("cover")
                                    .ifEmpty { rd.optString("img") },
                                false, true
                            )
                            applyAdvance(item, playIt = true)
                        }
                        nk2 != null && nk2 != key -> {
                            clearPipePark()
                            if (cause0 == "start") {
                                // 点播来源：走正规起播入口，终点账
                                // （brainStartKey/失败回执）才对得上
                                brainPlayKey(nk2)
                            } else {
                                executeBrainAdvance(nk2, cause0, 0)
                            }
                        }
                        cause0 == "start" -> {
                            // 无处可跳（队列只剩这首）：停车解除，
                            // 点播给终点回执，页面照旧提示
                            clearPipePark()
                            failBrainStart(t2)
                        }
                        else -> clearPipePark()
                    }
                } else if (pipeParkRefCount >= 2) {
                    // 两轮皆空：上一首、下一首参照都验过，管线定
                    // 罪维持——自纠退场，之后只剩 A 线继续等恢复
                    engineLog(
                        "引擎：自纠两轮皆空，管线定罪维持，自纠退场 " +
                            desc.optString("name") + "（目标自探继续）"
                    )
                }
                // 第 1 轮空：定罪维持，等第 2 轮（首探起点 +60 秒）
            }
        }
    }

    /** 回链收工分流（3.38.0）：整场没拿到过一把候选（一把都没
     * 有）= 链什么都给不出来，属管线全灭——关会话进解析停车场自
     * 探恢复，不再跳过这首；拿到过候选、只是装上一把死一把 =
     * 文件级死亡，照旧关会话按出错跳过收账 */
    private fun rescueExhausted(p: ExoPlayer) {
        if (rescueSessTries == 0 && rescueSessKey.isNotEmpty()) {
            val pk = rescueSessKey
            val pn = rescueSessName
            closeRescueSession()
            rescueInFlight = false
            rescueSettled = true
            enterPipePark(pk, pn, "ended")
        } else {
            closeRescueSession()
            rescueInFlight = false
            rescueSettled = true
            doSkipAfterError(p)
        }
    }

    /** 关闭回链会话（3.18.0）：清会话账面。发动记账 rescueDoneKey
     * 不在这里清——它管的是「这首已发动过一次」，只在真出声时清 */
    private fun closeRescueSession() {
        rescueSessKey = ""
        rescueSessDesc = null
        rescueSessName = ""
        rescueSessPlat = ""
        rescueSessBans.clear()
        rescueSessDeadlineMs = 0L
        rescueSessAwaitingUrl = ""
        rescueSessCandHost = ""
        rescueSessCandViaQq = false
        rescueSessTries = 0
    }

    /** 报错回链（3.17.0 定案；3.18.0 改为一次发动内部续走——cheng
     * 2026-11-07 拍板甲案）：播放报错（文件死的那类）当取链失败处理：
     * 刚死的地址作废，原歌钻回解析链整条重走（逐档降级、多源串行、
     * 链内 QQ 兜底、源冷却全继承）。3.17.0 是拿到第一个新址就交差，
     * 新址没出声又死只能跳过（2026-11-07 19:04《半壶纱》实测：新址
     * 与原址是同一份死文件的不同门牌，换门进来还是死的）；3.18.0 起
     * 回链发动后不交差：换上的址由播放器当场试播，没出声又死就把它
     * 追加进死址名单、在同一次发动里接着往链后面走，直到某个址真正
     * 出声（出声点记「回链成功」）、链走空、或 12 秒总预算到点才收工
     * 跳过。仍是一首只发动一次（rescueDoneKey 记账，出声清），续走
     * 不算新发动、预算仍是发动时那一本账。 */
    private fun rescueByReResolve(p: ExoPlayer, curKey: String, curUrl: String) {
        if (!nativeActive || !p.playWhenReady) return
        // 查档：脑队列直查（队列即档案，无登记册副本）
        var desc: org.json.JSONObject? = null
        var effKey = curKey
        if (curKey.isNotEmpty()) {
            val ci = brainQueueKeys.indexOf(curKey)
            if (ci >= 0) desc = brainQueue[ci]
        }
        if (desc == null) {
            // 编号对不上时按同名档案再找一次（同旧兜底）；同名多条时
            // 优先歌手对得上的那条
            var foundKey = ""
            var byName: org.json.JSONObject? = null
            for (bi in brainQueue.indices) {
                val k = brainQueueKeys[bi]
                val v = brainQueue[bi]
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
                effKey = foundKey
                engineLog("引擎：按名找回档案 " + title + "（key=" + foundKey + "）")
            }
        }
        if (desc == null) {
            // 没档案就没法重解析——把原因记明，别再静默跳过（3.4.2）；
            // 编号原文、档案总数、同名档案编号一并记下供对账（3.5.2）
            var regKey = ""
            for (k in brainQueueKeys) {
                if (k.endsWith("|" + title)) {
                    regKey = k
                    break
                }
            }
            engineLog(
                "引擎：无描述符，回链跳过 " + title +
                    " key=" + curKey +
                    " map=" + brainQueueKeys.size +
                    (if (regKey.isNotEmpty()) " reg=" + regKey else "")
            )
            doSkipAfterError(p)
            return
        }
        if (configSources.isEmpty()) {
            doSkipAfterError(p)
            return
        }
        // 开会话：死址名单从原址起记，12 秒总预算从此刻起一本账。
        // 会话看门狗只管「到点仍没装上任何候选」的干等——候选装上后，
        // 它的生死由出声（记成功）或再报错（续走）收账，看门狗不抢答
        val dd = desc
        closeRescueSession()
        rescueSessGen++
        rescueSessKey = effKey
        rescueSessDesc = dd
        rescueSessName = dd.optString("name").ifEmpty { title }
        rescueSessPlat = dd.optString("source").ifEmpty { "qq" }
        if (curUrl.isNotEmpty()) rescueSessBans.add(curUrl)
        rescueSessDeadlineMs =
            android.os.SystemClock.elapsedRealtime() + 12000L
        rescueDoneKey = effKey
        engineLog("引擎：报错回链 " + rescueSessName + "（死址作废，重走解析链）")
        val sessGen = rescueSessGen
        val sessKey = effKey
        val firstDeadUrl = curUrl
        tickHandler.postDelayed({
            val pp = player
            if (rescueSessGen == sessGen && rescueSessKey == sessKey &&
                rescueSessAwaitingUrl.isEmpty() && pp != null &&
                nativeActive && pp.playWhenReady &&
                nativeUrl == firstDeadUrl
            ) {
                engineLog("引擎：回链无果 " + rescueSessName + "（12 秒超时）")
                rescueExhausted(pp)
            }
        }, 12000L)
        rescueWalkStep(p)
    }

    /** 回链会话里走一步解析链（3.18.0）：拿剩余预算 + 累积死址名单
     * 重走整条链——链再交回任何一个死址都由解析器当场毙掉，于是这一
     * 步天然从上一步停下的地方往后面走（多源顺序、逐档降级、链内 QQ
     * 兜底、源冷却全继承）；拿到新址就装上试播，交由出声（记「回链
     * 成功」）或再报错（rescueContinue 续走）收账 */
    private fun rescueWalkStep(p: ExoPlayer) {
        val dd = rescueSessDesc
        if (dd == null || rescueSessKey.isEmpty()) {
            closeRescueSession()
            rescueInFlight = false
            rescueSettled = true
            return
        }
        val remain = rescueSessDeadlineMs -
            android.os.SystemClock.elapsedRealtime()
        if (remain < 1000L) {
            engineLog("引擎：回链无果 " + rescueSessName + "（12 秒超时）")
            rescueExhausted(p)
            return
        }
        val sessGen = rescueSessGen
        val sessKey = rescueSessKey
        val name = rescueSessName
        val plat = rescueSessPlat
        val bans = rescueSessBans.toSet()
        val stepBaseUrl = nativeUrl
        rescueInFlight = true
        rescueSettled = false
        rescueStartMs = android.os.SystemClock.elapsedRealtime()
        swapExec.execute {
            var candViaQq = false
            val newUrl = try {
                val prov = LxResolver.resolveProv(
                    dd, configSources, configQuality, configAutoDown,
                    lxEngine(), remain, bans
                ) { m -> engineLog(m) }
                if (prov != null) {
                    candViaQq = prov.viaQq
                    prov.url
                } else {
                    ""
                }
            } catch (_: Exception) {
                ""
            }
            tickHandler.post {
                val pp = player
                if (pp == null || !nativeActive ||
                    rescueSessGen != sessGen || rescueSessKey != sessKey ||
                    nativeUrl != stepBaseUrl
                ) {
                    // 会话已换代/已关、或歌已被切走接管：迟到结果丢弃
                    rescueInFlight = false
                    rescueSettled = true
                    return@post
                }
                rescueInFlight = false
                rescueSettled = true
                if (newUrl.isNotEmpty()) {
                    rescueSessTries++
                    val host3 = try {
                        java.net.URI(newUrl).host ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    // 成色按解析出身记（3.36.0）：候选出自链内 QQ 兜底
                    // 就是兜底、出自本平台链就是本平台。旧法按域名含
                    // qqmusic 猜——念心 tx.php 的 mcp 域名被误标成本
                    // 平台（2026-11-09 助手据此误报本平台自救），废弃
                    val viaQq = candViaQq
                    rescueSessCandHost = host3
                    rescueSessCandViaQq = viaQq
                    rescueSessAwaitingUrl = newUrl
                    engineLog(
                        "引擎：回链试播 " + name + " → " + host3 +
                            (if (viaQq) "（QQ 兜底）" else "（本平台）") +
                            (if (rescueSessTries > 1)
                                "（第" + rescueSessTries + "把）" else "")
                    )
                    try {
                        val item = mediaItemFor(
                            ArmedItem(
                                newUrl, sessKey,
                                name, dd.optString("artist"),
                                dd.optString("cover"), false, true
                            )
                        )
                        val idx3 = pp.currentMediaItemIndex
                        pp.replaceMediaItem(idx3, item)
                        // 播放列表里同歌的其它副本（单曲循环预置）一并
                        // 换新址，免得轮到副本又踩同一个死址
                        try {
                            for (i2 in 0 until pp.mediaItemCount) {
                                if (i2 != idx3 &&
                                    pp.getMediaItemAt(i2).mediaId == sessKey
                                ) {
                                    pp.replaceMediaItem(i2, item)
                                }
                            }
                        } catch (_: Exception) {
                        }
                        nativeUrl = newUrl
                        nativeKey = sessKey
                        pp.seekTo(idx3, 0)
                        pp.prepare()
                        pp.play()
                        // 跑道存货里同歌副本同步换新址（账面与播放器一致）
                        for (i3 in armedItems.indices) {
                            if (armedItems[i3].key == sessKey &&
                                armedItems[i3].url != newUrl
                            ) {
                                armedItems[i3] =
                                    armedItems[i3].copy(url = newUrl)
                            }
                        }
                        ScriptSeg.flushFor(sessKey)
                    } catch (_: Exception) {
                        closeRescueSession()
                        doSkipAfterError(pp)
                    }
                } else {
                    engineLog("引擎：回链无果 " + name)
                    rescueExhausted(pp)
                }
            }
        }
    }

    /** 回链候选没出声又死（3.18.0 续走入口）：当前址追加进死址名单、
     * 在同一次发动里接着走下一步——不算新发动，预算仍是会话那本账 */
    private fun rescueContinue(p: ExoPlayer, curKey: String) {
        if (rescueSessKey.isEmpty() || curKey != rescueSessKey) {
            // 会话不在岗（不该走到这）：按新发动走，别把歌晾着
            rescueByReResolve(p, curKey, nativeUrl)
            return
        }
        if (nativeUrl.isNotEmpty()) rescueSessBans.add(nativeUrl)
        rescueSessAwaitingUrl = ""
        engineLog("引擎：回链新址又死 " + rescueSessName + "（作废续走）")
        rescueWalkStep(p)
    }

    /** 跳过出错的这首、接上下一首；连续跳 3 首仍败才停手记断播点 */
    private fun doSkipAfterError(p: ExoPlayer) {
        rescueInFlight = false
        rescueSettled = true
        if (!nativeActive) return
        if (!p.playWhenReady) return
        errSkipCount++
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

    // 3.35.0 构造址预验探针：走生产 Cronet 单例，只问一件事——这条构造
    // 址是不是空 302（3xx 且无 Location，即「该档无货」，2026-11-09 矩阵
    // 实证）。302 带 Location＝有货（跳转即交付）；2xx＝有货；超时/失败/
    // 4xx/5xx＝不知道，一律放行（坏相位不误杀）。请求头与正片一致。
    private val constructProbeExec = java.util.concurrent.Executors.newCachedThreadPool()

    private fun probeConstructDead(url: String): Boolean {
        val engine = obtainCronetEngine() ?: return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val dead = java.util.concurrent.atomic.AtomicBoolean(false)
        var req: org.chromium.net.UrlRequest? = null
        val cb =
            object : org.chromium.net.UrlRequest.Callback() {
                override fun onRedirectReceived(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo,
                    newLocationUrl: String
                ) {
                    dead.set(newLocationUrl.isEmpty())
                    latch.countDown()
                    request.cancel()
                }

                override fun onResponseStarted(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo
                ) {
                    // 走到这里的 3xx 必然没有可跟随的 Location（有的话先进
                    // onRedirectReceived），即空跳转；2xx/4xx/5xx 都放行
                    dead.set(info.httpStatusCode in 300..399)
                    latch.countDown()
                    request.cancel()
                }

                override fun onReadCompleted(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo,
                    byteBuffer: java.nio.ByteBuffer
                ) {
                    latch.countDown()
                    request.cancel()
                }

                override fun onSucceeded(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo
                ) {
                    latch.countDown()
                }

                override fun onFailed(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo?,
                    error: org.chromium.net.CronetException
                ) {
                    latch.countDown()
                }

                override fun onCanceled(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo?
                ) {
                    latch.countDown()
                }
            }
        return try {
            req =
                engine.newUrlRequestBuilder(url, cb, constructProbeExec)
                    .addHeader("User-Agent", shellUserAgent())
                    .addHeader("Referer", "https://m.858600.xyz/")
                    .addHeader("Range", "bytes=0-0")
                    .addHeader("Accept", "*/*")
                    .disableCache()
                    .build()
            req?.start()
            if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                try { req?.cancel() } catch (_: Exception) {}
                false
            } else {
                dead.get()
            }
        } catch (e: Exception) {
            false
        }
    }

    // 3.42.0 LX 验档探针：走生产 Cronet 单例，只问一件事——这条地址
    // 的文件本体是不是铁证有损（MP3 的 ID3/帧同步头、m4a 的 ftyp
    // 盒）。fLaC 等无损头、认不出的格式、超时/失败/非 2xx 一律「不
    // 是铁证有损」放行（坏相位不误杀，与构造预验同一脾气）。跟随跳
    // 转到真文件，只读开头几个字节就收工，不下整文件。
    private fun probeLossyFile(url: String): Boolean {
        val engine = obtainCronetEngine() ?: return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val lossy = java.util.concurrent.atomic.AtomicBoolean(false)
        var req: org.chromium.net.UrlRequest? = null
        val cb =
            object : org.chromium.net.UrlRequest.Callback() {
                override fun onRedirectReceived(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo,
                    newLocationUrl: String
                ) {
                    try {
                        request.followRedirect()
                    } catch (e: Exception) {
                        latch.countDown()
                    }
                }

                override fun onResponseStarted(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo
                ) {
                    if (info.httpStatusCode !in 200..299) {
                        latch.countDown()
                        request.cancel()
                        return
                    }
                    try {
                        request.read(java.nio.ByteBuffer.allocateDirect(1024))
                    } catch (e: Exception) {
                        latch.countDown()
                    }
                }

                override fun onReadCompleted(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo,
                    byteBuffer: java.nio.ByteBuffer
                ) {
                    try {
                        byteBuffer.flip()
                        val n = minOf(byteBuffer.remaining(), 16)
                        if (n >= 4) {
                            val b = ByteArray(n)
                            byteBuffer.get(b)
                            lossy.set(isProvenLossyHead(b))
                        }
                    } catch (_: Exception) {
                    }
                    latch.countDown()
                    request.cancel()
                }

                override fun onSucceeded(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo
                ) {
                    latch.countDown()
                }

                override fun onFailed(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo?,
                    error: org.chromium.net.CronetException
                ) {
                    latch.countDown()
                }

                override fun onCanceled(
                    request: org.chromium.net.UrlRequest,
                    info: org.chromium.net.UrlResponseInfo?
                ) {
                    latch.countDown()
                }
            }
        return try {
            req =
                engine.newUrlRequestBuilder(url, cb, constructProbeExec)
                    .addHeader("User-Agent", shellUserAgent())
                    .addHeader("Referer", "https://m.858600.xyz/")
                    .addHeader("Range", "bytes=0-1023")
                    .addHeader("Accept", "*/*")
                    .disableCache()
                    .build()
            req?.start()
            if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                try { req?.cancel() } catch (_: Exception) {}
                false
            } else {
                lossy.get()
            }
        } catch (e: Exception) {
            false
        }
    }

    // 文件头铁证判定：只认「绝不可能是无损」的开头，其余一律不算。
    // MP3=ID3 标签或帧同步头（0xFF + 高 3 位全 1，含 ADTS AAC）；
    // m4a/MP4 容器=第 5 字节起的 ftyp 盒（此生态里装的都是 AAC）。
    private fun isProvenLossyHead(b: ByteArray): Boolean {
        fun at(i: Int): Int = b[i].toInt() and 0xFF
        fun str(i: Int, s: String): Boolean {
            if (b.size < i + s.length) return false
            for (j in s.indices) {
                if (b[i + j] != s[j].code.toByte()) return false
            }
            return true
        }
        if (str(0, "fLaC")) return false
        if (str(0, "ID3")) return true
        if (b.size >= 8 && str(4, "ftyp")) return true
        if (b.size >= 2 && at(0) == 0xFF && (at(1) and 0xE0) == 0xE0) return true
        return false
    }

    // ===== 3.34.0 探针矩阵（一次性取证） =====
    // 背景：3.33 通道换 Cronet 后，app 播念心网易仍多拿空 302、最终落 QQ，
    // 而同机浏览器网页端同址整天直出流——服务端在按请求形态分人。嫌疑三项：
    // cookie（壳引擎冷启无证）/ UA（壳是 WebView 的）/ Referer（壳写死
    // m.858600.xyz，网页是 yinyue-bfq.pages.dev）。本矩阵在每次服务生命内
    // 第一首 nianxinxz 歌起播时，对同一地址发只取头的对照请求，一轮日志
    // 隔离变量：A 壳脸独立新引擎冷→温（验 cookie 次访放行）、B 浏览器脸
    // 冷→温、C 只换 Referer、D 只换 UA、W App 内网页 fetch（含其 cookie）。
    // 探针不跟随重定向、禁用缓存；Set-Cookie 只记名与 Domain/Path、不记值。
    // 行为改动仅一处中性项：Cronet 引擎改单例（obtainCronetEngine），
    // 否则 cookie 存活无从观察；UA/Referer/存储一律不动。
    @Volatile
    private var cronetEngine: org.chromium.net.CronetEngine? = null

    @Volatile
    private var cronetEngineFailed = false

    private fun obtainCronetEngine(): org.chromium.net.CronetEngine? {
        cronetEngine?.let { return it }
        if (cronetEngineFailed) return null
        synchronized(this) {
            cronetEngine?.let { return it }
            if (cronetEngineFailed) return null
            return try {
                val e = org.chromium.net.CronetEngine.Builder(this)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .build()
                cronetEngine = e
                engineLog(
                    "引擎：播放通道 Cronet（Chromium）就绪 " +
                        try {
                            e.versionString
                        } catch (_: Exception) {
                            ""
                        }
                )
                e
            } catch (ex: Exception) {
                cronetEngineFailed = true
                engineLog("引擎：Cronet 初始化失败，回落 HttpURLConnection 通道（" + ex.javaClass.simpleName + "）")
                null
            }
        }
    }

    private fun shellUserAgent(): String =
        try {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android)"
        }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val ua = shellUserAgent()
        // 请求头尽量模拟网页 <audio> 直连时的样子，降低 CDN 因 UA/Referer 拦截的概率
        // 3.33.0：播放通道换 Chromium 网络栈（Cronet 内嵌版）——即 3.28.0 的
        // 通道改动单独加回（3.32.0 整树回滚时撤下；不含 3.27 探针与解析探针）。
        // HttpURLConnection 通道对 mcp.nianxinxz.com 会静默挂死（2026-11-09
        // 两度实锤：缓冲无进展、0 字节、无任何错误，app 内网易全灭而浏览器
        // 同址能播）；Cronet 与浏览器同栈。建不起来时回落原通道。
        val cronetEng = obtainCronetEngine()
        val upstreamFactory: DataSource.Factory = if (cronetEng != null) {
            androidx.media3.datasource.cronet.CronetDataSource.Factory(
                cronetEng,
                java.util.concurrent.Executors.newCachedThreadPool()
            )
                .setUserAgent(ua)
                .setDefaultRequestProperties(mapOf("Referer" to "https://m.858600.xyz/"))
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(mapOf("Referer" to "https://m.858600.xyz/"))
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // 数据源必须按协议分发：DefaultDataSource 认 file:// 等本地协议，
        // http(s) 才走上面的上游工厂。此前直接把 http 工厂设给媒体源工厂，
        // file:// 被当场拒收——
        // 3.9.0 原生下载的本地文件就是这样播不了的（2026-11-05 美人鱼抓现行）
        val dataSourceFactory =
            androidx.media3.datasource.DefaultDataSource.Factory(this, upstreamFactory)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
            )
            .setAudioAttributes(attrs, true) // 播放器自管音频焦点（抢断/恢复/降音量）
            .setHandleAudioBecomingNoisy(true) // 拔耳机自动暂停
            .setWakeMode(C.WAKE_MODE_NETWORK) // 播网络流时持 WakeLock + WifiLock，熄屏不睡
            .build()
        p.addListener(playerListener)
        player = p
        eqAttach(p.audioSessionId)
        return p
    }

    // —— 3.24.0 音效：安卓系统 Equalizer 挂 ExoPlayer 音频会话 ——
    // 段数/中心频率/可调范围全由设备硬件定，这里只读不编：freq 存
    // getCenterFreq 原值（毫赫兹），levels/min/max 存毫分贝原值。
    // 状态真相在 eq_state.json：桥 getEqState 原样回给网页；网页的
    // 每次调节经 cmdEq 送回来即时应用并重写该文件。构造抛异常
    // （设备不支持）时只记 available=false，其余全线 no-op。
    private var eq: Equalizer? = null
    private var eqSessionId: Int = 0
    private var eqAvailable: Boolean = false
    private var eqEnabled: Boolean = false
    private var eqPreset: Int = -1

    /** 播放器音频会话就绪/变化时（重）建 Equalizer 并重应用已存状态 */
    private fun eqAttach(sessionId: Int) {
        if (sessionId == 0) return
        if (eq != null && eqSessionId == sessionId) return
        try {
            eq?.release()
        } catch (_: Exception) {
        }
        eq = null
        try {
            val e = Equalizer(0, sessionId)
            eq = e
            eqSessionId = sessionId
            eqAvailable = true
            eqApplySaved(e)
        } catch (_: Exception) {
            eq = null
            eqAvailable = false
        }
        eqWriteState()
    }

    /** 把 eq_state.json 里存的 {enabled,preset,levels} 应用到新建的 Equalizer：
     * preset>=0 用 usePreset（实际电平落盘时回读）；自定义则逐段 setBandLevel */
    private fun eqApplySaved(e: Equalizer) {
        try {
            val f = java.io.File(filesDir, "eq_state.json")
            if (f.exists()) {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                eqEnabled = o.optBoolean("enabled", false)
                eqPreset = o.optInt("preset", -1)
                if (eqPreset in 0 until e.numberOfPresets.toInt()) {
                    e.usePreset(eqPreset.toShort())
                } else {
                    eqPreset = -1
                    val lv = o.optJSONArray("levels")
                    if (lv != null) {
                        val n = minOf(e.numberOfBands.toInt(), lv.length())
                        for (b in 0 until n) {
                            try {
                                e.setBandLevel(b.toShort(), lv.optInt(b, 0).toShort())
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            e.setEnabled(eqEnabled)
        } catch (_: Exception) {
        }
    }

    /** 完整状态（可用性/开关/预设/各段元数据/当前电平）落盘 eq_state.json，
     * 并推 {t:"eqstate"} 让网页开着面板时能跟着刷新 */
    private fun eqWriteState() {
        try {
            val o = JSONObject()
            val e = eq
            if (e == null || !eqAvailable) {
                o.put("available", false)
            } else {
                o.put("available", true)
                o.put("enabled", eqEnabled)
                o.put("preset", eqPreset)
                val presets = JSONArray()
                for (i in 0 until e.numberOfPresets.toInt()) {
                    presets.put(e.getPresetName(i.toShort()))
                }
                o.put("presets", presets)
                val range = e.bandLevelRange
                val bands = JSONArray()
                val levels = JSONArray()
                for (b in 0 until e.numberOfBands.toInt()) {
                    bands.put(
                        JSONObject()
                            .put("freq", e.getCenterFreq(b.toShort()))
                            .put("min", range[0].toInt())
                            .put("max", range[1].toInt())
                    )
                    levels.put(e.getBandLevel(b.toShort()).toInt())
                }
                o.put("bands", bands)
                o.put("levels", levels)
            }
            java.io.File(filesDir, "eq_state.json")
                .writeText(o.toString(), Charsets.UTF_8)
            pushSync(JSONObject().put("t", "eqstate"))
        } catch (_: Exception) {
        }
    }

    /** 网页音效命令（经 BRAIN_CMD 的 "eq"）：{"enabled":bool} /
     * {"preset":int} / {"band":int,"level":int}——立即应用并落盘；
     * 动了单段即转自定义（preset=-1） */
    private fun handleEqCmd(json: String) {
        if (eq == null) {
            val p = player ?: return
            eqAttach(p.audioSessionId)
        }
        val e = eq ?: return
        try {
            val o = JSONObject(json)
            when {
                o.has("enabled") -> {
                    eqEnabled = o.optBoolean("enabled", false)
                    try {
                        e.setEnabled(eqEnabled)
                    } catch (_: Exception) {
                    }
                }
                o.has("preset") -> {
                    val pr = o.optInt("preset", -1)
                    if (pr in 0 until e.numberOfPresets.toInt()) {
                        try {
                            e.usePreset(pr.toShort())
                            eqPreset = pr
                        } catch (_: Exception) {
                        }
                    }
                }
                o.has("band") -> {
                    val b = o.optInt("band", -1)
                    if (b in 0 until e.numberOfBands.toInt()) {
                        try {
                            e.setBandLevel(
                                b.toShort(), o.optInt("level", 0).toShort()
                            )
                            eqPreset = -1
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        eqWriteState()
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
        if (key.isNotEmpty()) noteBrainStarted(key)
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
        loadPersistedRate()
        createChannel()
        ScriptSeg.fileSink = { s -> scriptFileAppend(s) }
        restoreBrainState()
        // 3.35.0：给解析器装构造址预验钩子（实现见 obtainCronetEngine 旁）
        LxResolver.constructDeadCheck = { url -> probeConstructDead(url) }
        // 3.42.0：给解析器装 LX 验档钩子（实现见 obtainCronetEngine 旁）
        LxResolver.lossyCheck = { url -> probeLossyFile(url) }

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
        releaseSessionLocks()
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
        try {
            dlExec.shutdownNow()
        } catch (_: Exception) {
        }
        stopWatchdog()
        try {
            tickHandler.removeCallbacks(tickRunnable)
        } catch (_: Exception) {
        }
        try {
            eq?.release()
        } catch (_: Exception) {
        }
        eq = null
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
                persistRate()
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
                if (brainActive) {
                    // 单脑第 4 步：脑子在场，跑道单供应——网页预置整段忽略
                    // （页面新版已停发；此为防旧页面/残余调用冲掉脑存货的
                    // 保险；也不更新 lastArmAtMs，免得补仓门控被假信号推迟）
                } else {
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
                        // 单脑第 3 步：预置自带的档案（字段 s）不再登记——
                        // 队列即档案，预置只作跑道（网址+编号）
                    }
                } catch (_: Exception) {
                }
                // phase-3：新预置清单同步进播放器队列，接播改由播放器内部完成
                syncUpcomingToPlayer()
                }
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
                    LxResolver.clearCooldowns()
                    lxEngine().configure(configSources.filter { it.isScript() })
                    engineLog("引擎：已接收音源配置 " + configSources.size + " 个源")
                }
            }
            // ACTION_QUEUE_AHEAD 已退役（单脑第 3 步）：队列真相只走快照
            // ACTION_CURRENT_SONG 已退役（单脑第 3 步）：当前曲档案在快照队列里
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
                        feedBrainFromSnapshot(o)
                        // 单脑第 3 步：快照即 operational 输入，影子对账只留
                        // 「当前一致」绊线（不一致 = 交接异常，值得一行取证）
                        val ckT = brainAliases[shadowCurKey] ?: shadowCurKey
                        val agree = if (nativeKey.isEmpty()) "—"
                            else if (ckT == nativeKey) "是" else "否"
                        engineLog("引擎：影子队列 同步 " + n + " 首 当前一致 " + agree + " 模式 " + shadowMode)
                        // 跑道修剪接替原 ahead 处理器：壳解析回填的预置若已
                        // 不在新队列里，撤掉防原生接播播错歌
                        val beforeN = armedItems.size
                        armedItems.removeAll {
                            it.viaResolver && !brainQueueKeys.contains(it.key)
                        }
                        if (armedItems.size != beforeN) syncUpcomingToPlayer()
                    } catch (_: Exception) {
                    }
                }
            }
            ACTION_DOWNLOAD -> {
                // 3.9.0 壳原生下载：网页递来当场解析好的地址，壳在下载车道拉文件存盘
                try {
                    val o = JSONObject(intent.getStringExtra("json") ?: "{}")
                    val dKey = o.optString("key")
                    val dUrl = o.optString("url")
                    if (dKey.isNotEmpty() && dUrl.startsWith("http")) {
                        val dName = o.optString("name")
                        val dArtist = o.optString("artist")
                        val dFile = o.optString("filename").ifEmpty { dName + ".mp3" }
                        dlExec.execute { runDownload(dKey, dUrl, dName, dArtist, dFile) }
                    }
                } catch (_: Exception) {
                }
            }
            ACTION_BRAIN_CMD -> {
                // 单脑第 1 步：页面上下首指令。脑子未就绪（没喂过 v2 快照）时
                // 回退老路遥控页面，保证按钮永远有效
                when (intent.getStringExtra("cmd")) {
                    "next" -> if (brainActive) brainManualNext() else sendControl("next")
                    "prev" -> if (brainActive) brainManualPrev() else sendControl("prev")
                    "playkey" -> brainPlayKey(intent.getStringExtra("key").orEmpty())
                    "eq" -> handleEqCmd(intent.getStringExtra("json").orEmpty())
                }
            }
            ACTION_BRAIN_SLEEP -> {
                // 单脑第 3 步：睡眠定时归脑子计（熄屏页面冻结不再拖延到点执行）
                val mins = intent.getIntExtra("mins", 0)
                val after = intent.getBooleanExtra("afterTrack", false)
                if (after) {
                    brainSleepAfterTrack = true
                    brainSleepUntilMs = 0L
                    // 跑道撤空：播放器队列里排着的后续项会被它内部顺接、
                    // 根本不经 ENDED 闸口（3.10.0 实测漏停）——设定当下就撤，
                    // 本首播完必然落到 STATE_ENDED 由闸口收账
                    armedItems.clear()
                    syncUpcomingToPlayer()
                    engineLog("引擎：睡眠定时已设（播完本首）")
                } else if (mins > 0) {
                    brainSleepAfterTrack = false
                    brainSleepUntilMs = System.currentTimeMillis() + mins * 60000L
                    engineLog("引擎：睡眠定时已设 " + mins + " 分钟")
                } else {
                    brainSleepAfterTrack = false
                    brainSleepUntilMs = 0L
                    engineLog("引擎：睡眠定时已取消")
                }
                persistBrainSoon()
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
                if (brainSleepUntilMs > 0L || brainSleepAfterTrack) {
                    pushSync(
                        JSONObject().put("t", "sleepstate")
                            .put("until", brainSleepUntilMs)
                            .put("afterTrack", brainSleepAfterTrack)
                    )
                }
                // 3.10.3 会话恢复：脑子有存档队列时，把整条队列+当前曲递给
                // 刚打开的页面画出来（主流 App 的重开姿势——队列在、当前曲
                // 在、暂停不出声；曲内进度不记）。页面自己已有队列时自会忽略
                try {
                    if (brainQueue.isNotEmpty() && brainCurKey.isNotEmpty()) {
                        val qa = JSONArray()
                        for (qs in brainQueue) qa.put(qs)
                        pushSync(
                            JSONObject().put("t", "restore")
                                .put("queue", qa)
                                .put("curKey", brainCurKey)
                                .put("mode", brainMode)
                        )
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

    // ================= 单脑第 1 步（3.7.0）：脑子接管切歌 =================
    // 队列/当前键/模式/已播名单由服务独占（快照 v2 喂入、落盘可恢复）；
    // 切歌（播完接续、通知栏上下首、页面上下首指令）一律脑内计算，
    // 跑道退化为脑内队列的渲染：命中跑道直接接播，未命中现解析。
    private var brainQueue: ArrayList<org.json.JSONObject> = ArrayList()
    private var brainQueueKeys: ArrayList<String> = ArrayList()
    private var brainCurKey: String = ""
    private var brainMode: String = "list"
    private var brainPlayed: LinkedHashSet<String> = LinkedHashSet()
    // 单脑第 3 步：换源别名表（旧编号 → 新编号）——治「换源后页面还拿旧编号
    // 说话」的窗口期：点播指令翻译、快照摄入以脑内新档案为准；随脑状态持久化
    private var brainAliases: LinkedHashMap<String, String> = LinkedHashMap()
    // 单脑第 3 步：睡眠定时进脑——分钟到点时刻（墙钟毫秒，0=未设）/ 播完本首标
    private var brainSleepUntilMs: Long = 0L
    private var brainSleepAfterTrack: Boolean = false
    // 睡眠落闸后的有意停泊：播放器停在 ENDED+跑道空是用户要的结果，
    // 不是断播——断播点探测（ENDED 空跑道 10 秒）须认这个标记让行
    private var sleepStopParked: Boolean = false
    private var brainHistory: ArrayList<String> = ArrayList()
    private var brainActive: Boolean = false
    private var brainAdvanceBusy: Boolean = false
    private var brainStartBusy: Boolean = false
    private var brainOpGen: Int = 0 // 意图代次：新意图一来，旧解析落地作废（与落雪 gettingUrlId 同构）
    private var brainStartKey: String = "" // 正在办的起播键（终点回执 startfailed 按它回）
    private var brainIntendedKey: String = "" // 3.47.0：手动切歌想去的目标——快速连点时「当前在播」还没跟上，用它接着往后算；歌出声即清
    private var brainIntendedCause: String = "" // 3.47.0：想去的目标是哪种切歌来的（next/prev），补解析时原样带回
    private var brainSaveRunnable: Runnable? = null

    private fun feedBrainFromSnapshot(o: org.json.JSONObject) {
        if (o.optInt("v", 1) < 2) return
        val arr = o.optJSONArray("queue") ?: return
        val qs = ArrayList<org.json.JSONObject>()
        val ks = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val sj = arr.optJSONObject(i) ?: continue
            var k = LxResolver.keyOfJson(sj)
            if (k.isEmpty()) continue
            var desc = sj
            // 单脑第 3 步：别名槽位以脑内新档案为准——换源后页面还没采纳
            // 新歌时，它发来的旧档案不许把脑内已落账的新档案盖回去
            val aliasT = brainAliases[k]
            if (aliasT != null && aliasT != k) {
                val ai = brainQueueKeys.indexOf(aliasT)
                if (ai >= 0) {
                    desc = brainQueue[ai]
                    k = aliasT
                }
            }
            if (ks.contains(k)) continue
            qs.add(desc)
            ks.add(k)
        }
        if (qs.isEmpty()) return
        brainQueue = qs
        brainQueueKeys = ks
        val newMode = o.optString("mode").ifEmpty { "list" }
        if (newMode != brainMode) {
            // 第四步补洞（3.11.2）：模式一变，跑道里按旧模式囤的存货立即
            // 清掉——播放器顺接不看模式，囤着旧货时切到单曲也会先把旧货
            // 播完才轮到重复（网页时代靠 refreshArm 重预置整表替换跑道，
            // 页面退出补给后这一步没人做，2026-11-06 仪表日志实证）
            val beforeArmed = armedItems.size
            armedItems.removeAll { !it.repeat }
            if (armedItems.size != beforeArmed) syncUpcomingToPlayer()
            engineLog(
                "引擎：模式变更 " + brainMode + " → " + newMode +
                    "（清跑道存货 " + (beforeArmed - armedItems.size) + " 首）"
            )
            brainMode = newMode
        }
        if (brainCurKey.isEmpty()) {
            val ck = o.optString("curKey")
            brainCurKey = brainAliases[ck] ?: ck
        }
        brainActive = true
        persistBrainSoon()
    }

    /** 脑子认定的当前键：原生在播键优先（且须在队列内），否则用脑内记账 */
    private fun brainCurrentKey(): String {
        val nk = nativeKey
        if (nk.isNotEmpty() && brainQueueKeys.contains(nk)) return nk
        if (brainCurKey.isNotEmpty() && brainQueueKeys.contains(brainCurKey)) return brainCurKey
        return nk
    }

    /** 每有一首真正开始播（页面起播/原生接播/脑内切歌/回链成功都经此）记一笔 */
    private fun noteBrainStarted(key: String) {
        if (key.isEmpty()) return
        // 3.47.0：歌真正出声了，手动切歌的意图已兑现（或被别的起播顶掉），清掉
        brainIntendedKey = ""
        brainIntendedCause = ""
        sleepStopParked = false
        brainCurKey = key
        brainPlayed.add(key)
        if (brainHistory.lastOrNull() != key) {
            brainHistory.add(key)
            while (brainHistory.size > 200) brainHistory.removeAt(0)
        }
        persistBrainSoon()
        // 3.12.1：这首的脚本日志段在此刻才落盘显示（预置段没播到不出现）
        ScriptSeg.flushFor(key)
    }

    private fun computeBrainNextKey(manual: Boolean, baseKey: String? = null): String? {
        if (brainQueueKeys.isEmpty()) return null
        val cur = baseKey?.takeIf { it.isNotEmpty() && brainQueueKeys.contains(it) } ?: brainCurrentKey()
        val idx = brainQueueKeys.indexOf(cur)
        if (brainMode == "single" && !manual) {
            return if (idx >= 0) cur else brainQueueKeys.first()
        }
        if (brainMode == "shuffle" && brainQueueKeys.size > 1) {
            var cands = brainQueueKeys.filter { it != cur && !brainPlayed.contains(it) }
            if (cands.isEmpty()) {
                engineLog("引擎：脑切歌 随机名单新一轮")
                brainPlayed.clear()
                if (cur.isNotEmpty()) brainPlayed.add(cur)
                cands = brainQueueKeys.filter { it != cur }
            }
            if (cands.isEmpty()) return cur
            return cands.random()
        }
        if (idx < 0) return brainQueueKeys.first()
        return brainQueueKeys[(idx + 1) % brainQueueKeys.size]
    }

    private fun computeBrainPrevKey(baseKey: String? = null): String? {
        if (brainQueueKeys.isEmpty()) return null
        val cur = baseKey?.takeIf { it.isNotEmpty() && brainQueueKeys.contains(it) } ?: brainCurrentKey()
        if (brainMode == "shuffle") {
            // 落雪式：按已播历史往回走（随机模式的上一首是「刚听过的那首」）
            val pos = brainHistory.lastIndexOf(cur)
            if (pos > 0) {
                for (i in pos - 1 downTo 0) {
                    val k = brainHistory[i]
                    if (k != cur && brainQueueKeys.contains(k)) return k
                }
            }
        }
        val idx = brainQueueKeys.indexOf(cur)
        if (idx < 0) return brainQueueKeys.last()
        return brainQueueKeys[(idx - 1 + brainQueueKeys.size) % brainQueueKeys.size]
    }

    private fun brainNameOf(key: String): String {
        val i = brainQueueKeys.indexOf(key)
        if (i >= 0) return brainQueue[i].optString("name")
        val aliased = brainAliases[key]
        if (aliased != null) {
            val j = brainQueueKeys.indexOf(aliased)
            if (j >= 0) return brainQueue[j].optString("name")
        }
        return key.substringAfter("|")
    }

    /** 脑内切歌执行：跑道命中直接接播；未命中用脑内描述符现解析；解析全灭即停，进解析停车场自探恢复（3.38.0，不再顺延跳歌） */
    private fun executeBrainAdvance(targetKey: String, cause: String, depth: Int) {
        val fromKey = brainCurrentKey()
        engineLog("引擎：脑切歌 " + cause + " → " + brainNameOf(targetKey) + "（" + brainMode + "）" + if (cause == "start") " key=" + targetKey else "")
        cancelPendingAdvance()
        // 「目标即当前」捷径只在原生确实装着这首时成立：冷启动时脑内当前键是被
        // 快照预种的，播放器里什么都没有，走捷径会把点播变成一次假对齐（第 2 步实读抓出）
        if (targetKey.isNotEmpty() && targetKey == fromKey && nativeActive && nativeKey == targetKey) {
            // 单曲重播 / 队列只剩这首：跳回 0 点重播，照旧发 repeat 同步
            try {
                player?.seekTo(0)
                player?.play()
            } catch (_: Exception) {
            }
            pushSync(
                JSONObject().put("t", "advanced").put("key", targetKey)
                    .put("url", nativeUrl).put("repeat", true)
            )
            noteBrainStarted(targetKey)
            return
        }
        // 3.9.0：本地已下载优先——有文件就不联网解析；排在跑道命中之前，
        // 跑道里预解析的在线版不能顶掉用户下好的文件
        val lf = localFileForKey(targetKey)
        if (lf != null) {
            val di2 = brainQueueKeys.indexOf(targetKey)
            val d2 = if (di2 >= 0) brainQueue[di2] else null
            var cov = d2?.optString("cover") ?: ""
            if (cov.isEmpty()) cov = d2?.optString("img") ?: ""
            val item = ArmedItem(
                "file://" + lf.absolutePath, targetKey,
                (d2?.optString("name") ?: "").ifEmpty { brainNameOf(targetKey) },
                d2?.optString("artist") ?: "",
                cov, false, true
            )
            engineLog("引擎：本地播放 " + item.title + " key=" + item.key)
            if (cause == "start" && targetKey == brainStartKey) brainStartKey = ""
            armedItems.removeAll { it.key == targetKey }
            applyAdvance(item, playIt = true)
            return
        }
        val ai = armedItems.indexOfFirst { it.key == targetKey }
        if (ai >= 0) {
            val item = armedItems[ai]
            repeat(ai + 1) { if (armedItems.isNotEmpty()) armedItems.removeAt(0) }
            applyAdvance(item, playIt = true)
            return
        }
        val di = brainQueueKeys.indexOf(targetKey)
        val desc = if (di >= 0) brainQueue[di] else null
        if (desc == null || configSources.isEmpty()) {
            // 无描述符/无音源：退回旧路兜底（跑道队首或宽限等页面）；
            // 起播无跑道可退时必须给终点回执，不许把点播晾在静默里
            if (armedItems.isNotEmpty()) doArmedAdvance()
            else if (cause == "start") failBrainStart(brainNameOf(targetKey))
            else scheduleArmedAdvance()
            return
        }
        // 单脑第 2 步：起播（cause=start）走 swapExec 优先车道，不排补仓的队；
        // 其余切歌仍走 resolveExec。两条车道各自 busy，落地回主线程串行执行。
        val isStart = cause == "start"
        if (isStart) {
            brainStartBusy = true
        } else {
            if (brainAdvanceBusy) return
            brainAdvanceBusy = true
        }
        val gen = brainOpGen
        val exec = if (isStart) swapExec else resolveExec
        exec.execute {
            val url = try {
                LxResolver.resolve(
                    desc, configSources, configQuality, configAutoDown, lxEngine()
                ) { m -> engineLog(m) }
            } catch (_: Exception) {
                null
            }
            tickHandler.post {
                if (isStart) brainStartBusy = false else brainAdvanceBusy = false
                // 意图代次已变（用户又点了别的）：整跳作废，终点由新意图负责
                if (gen != brainOpGen) {
                    // 3.47.0：快速连点攒下的意图——在飞的解析被新点作废，但最新想去
                    // 的那首还没排上解析，在这里给它补一轮（只补最新的一首，不攒多轮）
                    if (!isStart && brainIntendedKey.isNotEmpty()
                        && brainQueueKeys.contains(brainIntendedKey)
                        && brainIntendedKey != brainCurrentKey()
                    ) {
                        val pk = brainIntendedKey
                        val pc = if (brainIntendedCause == "prev") "prev" else "next"
                        executeBrainAdvance(pk, pc, 0)
                    }
                    return@post
                }
                // 起播在原生未激活时正是冷启动常态（全天第一首），不能按切歌的作废规矩丢掉
                if (!nativeActive && !isStart) return@post
                // 期间用户/页面已换歌：这一跳作废，别把人家的新歌顶掉
                if (brainCurrentKey() != fromKey) return@post
                if (!url.isNullOrEmpty()) {
                    if (isStart && targetKey == brainStartKey) brainStartKey = ""
                    val item = ArmedItem(
                        url, targetKey,
                        desc.optString("name"), desc.optString("artist"),
                        desc.optString("cover").ifEmpty { desc.optString("img") },
                        false, true
                    )
                            applyAdvance(item, playIt = true)
                } else {
                    engineLog("引擎：脑切歌解析失败 " + brainNameOf(targetKey))
                    // 3.38.0：解析全灭即停（cheng 定案）——链已走完
                    // 本平台全档多源＋QQ 兜底，全空即管线整体故障的
                    // 硬证据，不再顺延跳歌（往下验的还是同一批坏管
                    // 线）；有跑道存货也不先播，顺序不乱、恢复后仍
                    // 从这首起。停进解析停车场，看门狗自探恢复
                    enterPipePark(
                        targetKey,
                        desc.optString("name")
                            .ifEmpty { brainNameOf(targetKey) },
                        cause
                    )
                }
            }
        }
    }

    /** 睡眠分钟定时到点检查（看门狗 10 秒粒度）：到点暂停并通知页面清显示；
     * 没在播也照常收账，免得页面留着过期的定时显示 */
    private fun checkBrainSleepDeadline() {
        if (brainSleepUntilMs <= 0L) return
        if (System.currentTimeMillis() < brainSleepUntilMs) return
        brainSleepUntilMs = 0L
        persistBrainSoon()
        val p = player
        if (p != null && nativeActive) {
            try {
                p.pause()
            } catch (_: Exception) {
            }
            playing = false
        }
        engineLog("引擎：睡眠定时到点，已暂停")
        pushSync(JSONObject().put("t", "sleepfired"))
    }

    /** 「播完本首」落闸：销账、暂停、通知页面（ENDED 闸口与跑道顺接兜底共用） */
    private fun fireBrainSleepStop() {
        brainSleepAfterTrack = false
        sleepStopParked = true
        persistBrainSoon()
        try {
            player?.pause()
        } catch (_: Exception) {
        }
        playing = false
        engineLog("引擎：睡眠定时（本首后）已停")
        pushSync(JSONObject().put("t", "sleepfired"))
    }

    private fun brainEndedAdvance() {
        // 单脑第 3 步：「播完本首」睡眠闸——到站不接播，落停并通知页面
        if (brainSleepAfterTrack) {
            fireBrainSleepStop()
            return
        }
        if (brainAdvanceBusy) return
        val t = computeBrainNextKey(false)
        if (t == null) {
            if (armedItems.isNotEmpty()) doArmedAdvance() else scheduleArmedAdvance()
            return
        }
        executeBrainAdvance(t, "ended", 0)
    }

    private fun brainManualNext() {
        clearNetPark()
        voidPipePark()
        brainSleepAfterTrack = false
        brainOpGen++
        // 3.47.0：快速连点时「当前在播」还没跟上新目标，从上次想去的接着往后算
        val base = brainIntendedKey.takeIf { it.isNotEmpty() && brainQueueKeys.contains(it) } ?: brainCurrentKey()
        val t = computeBrainNextKey(true, base) ?: return
        brainIntendedKey = t
        brainIntendedCause = "next"
        executeBrainAdvance(t, "next", 0)
    }

    private fun brainManualPrev() {
        clearNetPark()
        voidPipePark()
        brainSleepAfterTrack = false
        brainOpGen++
        // 3.47.0：快速连点时「当前在播」还没跟上新目标，从上次想去的接着往后算
        val base = brainIntendedKey.takeIf { it.isNotEmpty() && brainQueueKeys.contains(it) } ?: brainCurrentKey()
        val t = computeBrainPrevKey(base) ?: return
        brainIntendedKey = t
        brainIntendedCause = "prev"
        executeBrainAdvance(t, "prev", 0)
    }

    /**
     * 单脑第 2 步：网页点播起播入口。三分支——无档案回执失败（网页走老路）、
     * 点的正是当前在播这首只补对齐同步不重启、其余按编号起播。
     * 起播的每一跳都必须落到终点（成功 advanced 或 failBrainStart 回执），
     * 不许把点播晾在静默里；被新意图代次作废的跳不推终点，新意图自带终点。
     */
    private fun brainPlayKey(keyIn: String) {
        if (keyIn.isEmpty()) return
        // 3.47.0：点播优先，之前手动切歌攒的意图作废
        brainIntendedKey = ""
        brainIntendedCause = ""
        clearNetPark()
        voidPipePark()
        brainSleepAfterTrack = false
        // 单脑第 3 步：陈旧页面拿换源前旧编号点播 → 翻译成脑内新编号再执行
        val key = brainAliases[keyIn] ?: keyIn
        val di = brainQueueKeys.indexOf(key)
        val desc = if (di >= 0) brainQueue[di] else null
        if (desc == null) {
            engineLog("引擎：脑起播无档案 " + key.substringAfter("|"))
            pushSync(JSONObject().put("t", "startfailed").put("key", keyIn))
            return
        }
        if (key == brainCurrentKey() && nativeActive) {
            pushSync(
                JSONObject().put("t", "advanced").put("key", key)
                    .put("url", nativeUrl).put("repeat", false)
            )
            return
        }
        brainOpGen++
        brainStartKey = key
        executeBrainAdvance(key, "start", 0)
    }

    /** 起播终点回执：脑子明确办不了，网页收到后走老路自救 */
    private fun failBrainStart(name: String) {
        val k = brainStartKey
        brainStartKey = ""
        engineLog("引擎：脑起播失败 " + name + "（无可用接续）")
        // 3.12.1：点播终点失败——这首虽没播成，但它就是用户点的「当前」，
        // 段照样落盘，不然「为什么点不播」在脚本日志里查无此歌
        if (k.isNotEmpty()) ScriptSeg.flushFor(k)
        if (k.isNotEmpty()) {
            pushSync(JSONObject().put("t", "startfailed").put("key", k))
        }
    }

    /** 播完接播的统一入口：脑子活着归脑子算，没活着走 3.6 以前的老路 */
    private fun advanceAfterEnd() {
        if (brainActive) brainEndedAdvance() else doArmedAdvance()
    }

    /**
     * 3.5.5 踢子守卫补丁（3.7.0）：换源未收账且开工未满 12 秒 = 救援在飞，
     * 两处停摆接播都不许抢（2026-11-04 晚实测 4 次换源全被踢子抢先跳走，
     * 根因是待救标记在救援派出后即清、踢子看不见换源）。
     * 15 秒上限防收账旗异常卡死把踢子永久锁死（回链自身预算 12 秒）。
     */
    private fun rescueNotInFlight(): Boolean {
        if (rescueSettled) return true
        if (rescueStartMs <= 0L) return true
        return SystemClock.elapsedRealtime() - rescueStartMs > 15000L
    }

    private fun persistBrainSoon() {
        brainSaveRunnable?.let {
            try {
                tickHandler.removeCallbacks(it)
            } catch (_: Exception) {
            }
        }
        val r = Runnable { saveBrainState() }
        brainSaveRunnable = r
        try {
            tickHandler.postDelayed(r, 500)
        } catch (_: Exception) {
        }
    }

    private fun saveBrainState() {
        if (!brainActive) return
        try {
            val o = JSONObject()
            o.put("queue", org.json.JSONArray(brainQueue))
            o.put("curKey", brainCurKey)
            o.put("mode", brainMode)
            o.put("played", org.json.JSONArray(ArrayList(brainPlayed)))
            o.put("history", org.json.JSONArray(brainHistory))
            o.put("aliases", JSONObject(brainAliases as Map<*, *>))
            val sl = JSONObject()
            sl.put("until", brainSleepUntilMs)
            sl.put("afterTrack", brainSleepAfterTrack)
            o.put("sleep", sl)
            o.put("savedAt", System.currentTimeMillis())
            java.io.File(filesDir, "brain_state.json")
                .writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /** 服务重启恢复账本：只恢复队列/当前/模式/已播名单，不自动起播（播放意图随 hello/起播回来） */
    private fun restoreBrainState() {
        try {
            val f = java.io.File(filesDir, "brain_state.json")
            if (!f.exists()) return
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("queue") ?: return
            val qs = ArrayList<org.json.JSONObject>()
            val ks = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val sj = arr.optJSONObject(i) ?: continue
                val k = LxResolver.keyOfJson(sj)
                if (k.isEmpty()) continue
                qs.add(sj)
                ks.add(k)
            }
            if (qs.isEmpty()) return
            brainQueue = qs
            brainQueueKeys = ks
            brainCurKey = o.optString("curKey")
            brainMode = o.optString("mode").ifEmpty { "list" }
            brainPlayed = LinkedHashSet()
            val pa = o.optJSONArray("played")
            if (pa != null) {
                for (i in 0 until pa.length()) brainPlayed.add(pa.optString(i))
            }
            brainHistory = ArrayList()
            val ha = o.optJSONArray("history")
            if (ha != null) {
                for (i in 0 until ha.length()) brainHistory.add(ha.optString(i))
            }
            brainAliases = LinkedHashMap()
            val alo = o.optJSONObject("aliases")
            if (alo != null) {
                val kit2 = alo.keys()
                while (kit2.hasNext()) {
                    val kk = kit2.next()
                    brainAliases[kk] = alo.optString(kk)
                }
            }
            val slo = o.optJSONObject("sleep")
            brainSleepUntilMs = slo?.optLong("until", 0L) ?: 0L
            brainSleepAfterTrack = slo?.optBoolean("afterTrack", false) ?: false
            if (brainSleepUntilMs in 1..System.currentTimeMillis()) {
                // 服务没了的这段时间里到点已过：按已执行处理，不补停
                brainSleepUntilMs = 0L
            }
            brainActive = true
            val nm = if (brainCurKey.isNotEmpty()) brainNameOf(brainCurKey) else ""
            val curPart = if (nm.isNotEmpty()) " 当前 " + nm else ""
            engineLog("引擎：脑状态恢复 队列 " + qs.size + " 首" + curPart)
        } catch (_: Exception) {
        }
    }

    private fun sendControl(action: String) {
        // 单脑第 1 步：脑子活着时上下首由脑内计算，不再遥控页面
        // （页面冻住时通知栏/锁屏按钮照样有效，这正是本步要切断的缝）
        if ((action == "next" || action == "prev") && brainActive && nativeActive) {
            tickHandler.post {
                if (action == "next") brainManualNext() else brainManualPrev()
            }
            return
        }
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
