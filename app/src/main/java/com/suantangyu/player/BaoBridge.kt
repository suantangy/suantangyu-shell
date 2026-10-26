package com.suantangyu.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    /** App 内更新（3.1.4 起）：壳自己下 APK 并调起安装，不跳浏览器。
     * apkUrl 为直链（如 gh-proxy 的 releases/download/.../app-debug.apk），releaseUrl 为 Release 页（下载失败时回落用）。
     * 首次需用户在设置里给本 App 开“允许安装未知应用”，未开时只跳设置、请用户再点一次下载，不静默失败。 */
    @JavascriptInterface
    fun downloadAndInstall(apkUrl: String, releaseUrl: String) {
        val apk = apkUrl.trim()
        val rel = releaseUrl.trim().ifEmpty {
            // 从直链推导 Release 页：.../releases/download/<tag>/app-debug.apk → .../releases/tag/<tag>
            apk.replace("/releases/download/", "/releases/tag/").removeSuffix("/app-debug.apk")
        }
        if (apk.isEmpty()) {
            openExternalUrl(rel)
            return
        }
        // 未授权安装未知应用：跳设置，用户开完回来再点一次下载
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.runOnUiThread {
                try {
                    Toast.makeText(activity, "请开启允许安装未知应用，开启后请再次点击下载", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:" + activity.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    openExternalUrl(rel)
                }
            }
            return
        }
        activity.runOnUiThread {
            try { Toast.makeText(activity, "正在下载更新…", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
        Thread {
            // 先试传入的地址（一般是 gh-proxy 直链），失败再试去掉 gh-proxy 前缀的直连地址
            val candidates = mutableListOf(apk)
            val ghPrefix = "https://gh-proxy.com/"
            if (apk.startsWith(ghPrefix)) candidates.add(apk.removePrefix(ghPrefix))
            var downloaded: File? = null
            for (candidate in candidates) {
                try {
                    val file = File(activity.cacheDir, "update.apk")
                    if (file.exists()) file.delete()
                    val conn = URL(candidate).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                    conn.connect()
                    if (conn.responseCode !in 200..299) continue
                    conn.inputStream.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    // APK 约 5~6MB，小于 1MB 基本是错误页/空文件，不算成功
                    if (file.exists() && file.length() > 1_000_000) {
                        downloaded = file
                        break
                    }
                } catch (_: Exception) {
                }
            }
            val file = downloaded
            if (file == null) {
                activity.runOnUiThread {
                    try { Toast.makeText(activity, "下载失败，已改用浏览器下载", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    openExternalUrl(rel)
                }
                return@Thread
            }
            activity.runOnUiThread {
                try {
                    Toast.makeText(activity, "下载完成，正在安装…", Toast.LENGTH_SHORT).show()
                    val uri = FileProvider.getUriForFile(
                        activity,
                        activity.packageName + ".fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    openExternalUrl(rel)
                }
            }
        }.start()
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
