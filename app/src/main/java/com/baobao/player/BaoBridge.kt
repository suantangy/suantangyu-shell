package com.baobao.player

import android.content.Intent
import android.webkit.JavascriptInterface

/**
 * 网页 → 原生的桥。网页里调用 window.BaoBus.postTrack / postState
 * （接线方法见 web/bridge-snippet.js）。
 */
class BaoBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun postTrack(title: String, artist: String, cover: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_UPDATE_TRACK
            putExtra("title", title)
            putExtra("artist", artist)
            putExtra("cover", cover)
        }
        activity.startService(intent)
    }

    @JavascriptInterface
    fun postState(playing: Boolean) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_UPDATE_STATE
            putExtra("playing", playing)
        }
        activity.startService(intent)
    }

    /** 播放进度（毫秒），网页每秒上报一次，服务只更新会话状态不重发通知 */
    @JavascriptInterface
    fun postProgress(positionMs: Long, durationMs: Long) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_UPDATE_PROGRESS
            putExtra("position", positionMs)
            putExtra("duration", durationMs)
        }
        activity.startService(intent)
    }

    /** 锁屏歌词行；空字符串表示恢复显示歌名 */
    @JavascriptInterface
    fun postLyric(line: String, sub: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_UPDATE_LYRIC
            putExtra("line", line)
            putExtra("sub", sub)
        }
        activity.startService(intent)
    }
}
