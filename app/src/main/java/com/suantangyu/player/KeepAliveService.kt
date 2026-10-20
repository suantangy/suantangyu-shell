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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
 * 单向同步，无回环。音频本身仍由 WebView 网页的 <audio> 播放。
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
                if (playing) {
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
                override fun onPlay() = sendControl("toggle")
                override fun onPause() = sendControl("toggle")
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
        try {
            tickHandler.removeCallbacks(tickRunnable)
        } catch (_: Exception) {
        }
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
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
                val action = when (intent.action) {
                    ACTION_TOGGLE -> "toggle"
                    ACTION_NEXT -> "next"
                    else -> "prev"
                }
                sendControl(action)
            }
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
            val state = if (playing) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
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
