package com.suantangyu.player

import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface

/**
 * 网页 → 原生的桥。网页里调用 window.BaoBus.postTrack / postState
 * （接线方法见 web/bridge-snippet.js）。
 */
class BaoBridge(private val activity: MainActivity) {

    /** 设置页只显示壳版本：网页通过它读取壳的 versionName（3.1.1 起），网页自身版本不再显示 */
    @JavascriptInterface
    fun getShellVersion(): String {
        return BuildConfig.VERSION_NAME
    }

    /** 检查更新发现新版本时：用系统浏览器打开 Release 下载页（WebView 自身不处理 APK 下载） */
    @JavascriptInterface
    fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
        }
    }

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

    // —— phase-2 原生播放：网页把解析好的流地址交给服务里的 ExoPlayer 播 ——
    // 网页侧检测到 postPlayUrl 存在才启用原生垫片；老壳没有这些方法，网页自动回退 <audio>。

    /** 播放一条流（新装载并起播；同一条已在播则只同步状态，不重启） */
    @JavascriptInterface
    fun postPlayUrl(url: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_PLAY_URL
            putExtra("url", url)
        }
        activity.startService(intent)
    }

    @JavascriptInterface
    fun postNativePause() {
        activity.startService(
            Intent(activity, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_NATIVE_PAUSE
            }
        )
    }

    @JavascriptInterface
    fun postNativeResume() {
        activity.startService(
            Intent(activity, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_NATIVE_RESUME
            }
        )
    }

    @JavascriptInterface
    fun postNativeSeek(positionMs: Long) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_NATIVE_SEEK
            putExtra("pos", positionMs)
        }
        activity.startService(intent)
    }

    @JavascriptInterface
    fun postNativeRate(rate: Float) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_NATIVE_RATE
            putExtra("rate", rate)
        }
        activity.startService(intent)
    }

    /** 网页清掉 src 时调用：卸载当前流 */
    @JavascriptInterface
    fun postNativeStop() {
        activity.startService(
            Intent(activity, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_NATIVE_STOP
            }
        )
    }

    /** 网页（重）加载时调用：服务把当前原生播放状态整包同步回网页（__baoSync hello） */
    @JavascriptInterface
    fun postHello() {
        activity.startService(
            Intent(activity, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_HELLO
            }
        )
    }

    // —— phase-2.5 预置续播：网页把接下来 1~2 首的流提前交给服务，
    // 当前曲播完而网页没接上（熄屏被冻/解析卡住）时，服务按预置自己接播。 ——

    /** 预置接播清单（JSON 数组：[{u,k,t,a,c,r}]，空数组等于取消预置） */
    @JavascriptInterface
    fun postArmNext(json: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_ARM_NEXT
            putExtra("json", json)
        }
        activity.startService(intent)
    }

    /** 取消预置接播（如设置了"播完本首停"） */
    @JavascriptInterface
    fun postDisarmNext() {
        activity.startService(
            Intent(activity, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_DISARM_NEXT
            }
        )
    }
}
