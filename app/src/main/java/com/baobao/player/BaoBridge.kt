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
}
