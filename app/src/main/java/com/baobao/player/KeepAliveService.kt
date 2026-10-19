package com.baobao.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务：拿住 mediaPlayback 类型的前台服务身份，
 * 进程优先级拉满（熄屏/切后台不被杀），通知栏常驻媒体控制条。
 *
 * 注意：音频本身仍由 WebView 网页的 <audio> 播放，服务只负责保活和通知栏。
 * 因此"从最近任务划掉 App"仍会停播——要连划掉都不停，需要把解码搬进
 * 服务用 ExoPlayer 播，那是二期的大改（要重写音频管线）。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "bao_playback"
        const val NOTIFY_ID = 1
        const val ACTION_UPDATE_TRACK = "com.baobao.player.UPDATE_TRACK"
        const val ACTION_UPDATE_STATE = "com.baobao.player.UPDATE_STATE"
        const val ACTION_TOGGLE = "com.baobao.player.TOGGLE"
        const val ACTION_NEXT = "com.baobao.player.NEXT"
        const val ACTION_PREV = "com.baobao.player.PREV"
    }

    private var title: String = "宝宝巴士"
    private var artist: String = "准备播放"
    private var playing: Boolean = false

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_TRACK -> {
                title = intent.getStringExtra("title").orEmpty().ifEmpty { "宝宝巴士" }
                artist = intent.getStringExtra("artist").orEmpty()
                updateNotification()
            }
            ACTION_UPDATE_STATE -> {
                playing = intent.getBooleanExtra("playing", false)
                updateNotification()
            }
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
                // 通知栏按钮 → 广播给 MainActivity → 转发进网页
                val action = when (intent.action) {
                    ACTION_TOGGLE -> "toggle"
                    ACTION_NEXT -> "next"
                    else -> "prev"
                }
                sendBroadcast(
                    Intent(MainActivity.ACTION_CONTROL).putExtra("action", action)
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "宝宝巴士后台播放保活与通知栏控制" }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist.ifEmpty { "宝宝巴士" })
            .setContentIntent(contentIntent())
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
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
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFY_ID, buildNotification())
    }
}
