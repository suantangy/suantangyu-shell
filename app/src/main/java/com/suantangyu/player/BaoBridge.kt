package com.suantangyu.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.ContextCompat
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

    /** 单脑第 4 步能力探针：跑道归脑子单供应（3.11.0 起）。老壳无此方法，页面据此保持旧补给行为 */
    @JavascriptInterface
    fun brainOwnsRunway(): Boolean {
        return true
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

    /** 3.5.3：同 postPlayUrl，但带上歌曲编号——壳建播放项时拿编号当身份证，
     * 阶梯才查得到这首歌的档案（只传网址时网页起播的歌阶梯全瞎，3.5.2 抓现行） */
    @JavascriptInterface
    fun postPlayUrlK(url: String, key: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_PLAY_URL
            putExtra("url", url)
            putExtra("key", key)
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

    // —— 3.3.0 原生解析：网页把音源配置与后续队列交给服务里的 QuickJS 引擎 ——

    /** 音源配置（JSON：{quality, autoDowngrade, sources:[{id,name,type,url,script,platforms,jsUrl,fromFile}]}） */
    @JavascriptInterface
    fun postSourcesConfig(json: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_SOURCES_CONFIG
            putExtra("json", json)
        }
        activity.startService(intent)
    }

    /** 后续队列清单——单脑第 3 步已退役为空实现：队列真相只走
     * postQueueSnapshot（老网页调它无副作用，新网页不再调） */
    @JavascriptInterface
    fun postQueueAhead(mode: String, json: String) {
    }

    /** 后台日志（3.5.0）：网页「后台日志」面板打开时现读日志文件尾（3.5.1 起最近 600 行） */
    @JavascriptInterface
    fun getEngineLog(): String {
        return try {
            val f = java.io.File(activity.filesDir, "lx_engine.log")
            if (!f.exists()) {
                ""
            } else {
                f.readLines(Charsets.UTF_8).takeLast(600).joinToString("\n")
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 脚本日志（3.12.0）：壳内解析分段记录（lx_script.log 尾 600 行）——
     * 解析在脑子，脚本日志跟着脑子；网页面板有此桥才显示（纯浏览器不留） */
    @JavascriptInterface
    fun getScriptLog(): String {
        return try {
            val f = java.io.File(activity.filesDir, "lx_script.log")
            if (!f.exists()) {
                ""
            } else {
                f.readLines(Charsets.UTF_8).takeLast(600).joinToString("\n")
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 音效（3.24.0）：壳里系统均衡器的完整状态——网页打开音效面板时
     * 同步现读 eq_state.json 原样返回；文件不存在（播放器还没建过
     * Equalizer）返回 {"available":false} */
    @JavascriptInterface
    fun getEqState(): String {
        return try {
            val f = java.io.File(activity.filesDir, "eq_state.json")
            if (!f.exists()) {
                "{\"available\":false}"
            } else {
                f.readText(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            "{\"available\":false}"
        }
    }

    /** 音效命令（3.24.0）：载荷原样透传给服务的 "eq" 命令——
     * {"enabled":bool} / {"preset":int} / {"band":int,"level":int}，
     * 服务即时应用到系统 Equalizer 并重写 eq_state.json */
    @JavascriptInterface
    fun cmdEq(json: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_BRAIN_CMD
            putExtra("cmd", "eq")
            putExtra("json", json)
        }
        activity.startService(intent)
    }

    /** 当前曲描述符——单脑第 3 步已退役为空实现：当前曲档案在快照队列里 */
    @JavascriptInterface
    fun postCurrentSong(json: String) {
    }

    /** 单脑第 1 步（3.7.0）：页面上下首改为给脑子发指令（脑子未就绪时壳回退遥控页面） */
    @JavascriptInterface
    fun cmdNext() {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_BRAIN_CMD
            putExtra("cmd", "next")
        }
        activity.startService(intent)
    }

    @JavascriptInterface
    fun cmdPrev() {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_BRAIN_CMD
            putExtra("cmd", "prev")
        }
        activity.startService(intent)
    }

    /** 单脑第 2 步（3.8.0）：网页点播改发「播这一首」指令，解析与起播归脑子；
     *  脑子明确办不了时会回推 startfailed，网页再走老路自救 */
    @JavascriptInterface
    fun cmdPlayKey(key: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_BRAIN_CMD
            putExtra("cmd", "playkey")
            putExtra("key", key)
        }
        activity.startService(intent)
    }

    /** 单脑第 3 步：睡眠定时归脑子计——页面只发命令（分钟数，0=取消；
     * afterTrack=true 为播完本首），到点由服务执行并回推 sleepfired */
    @JavascriptInterface
    fun cmdSetSleep(minutes: Int, afterTrack: Boolean) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_BRAIN_SLEEP
            putExtra("mins", minutes)
            putExtra("afterTrack", afterTrack)
        }
        activity.startService(intent)
    }

    /** 单脑第 0 步（3.6.0）：完整队列快照，壳只收存做影子对账，不参与播放 */
    @JavascriptInterface
    fun postQueueSnapshot(json: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_QUEUE_SNAPSHOT
            putExtra("json", json)
        }
        activity.startService(intent)
    }

    /** 壳原生下载（3.9.0）：网页把当场解析好的地址与歌曲信息递来，壳自己拉
     * 文件存盘——网页抓文件会被 CDN 跨域挡死（只剩名单），原生请求没有这道墙 */
    @JavascriptInterface
    fun downloadSong(json: String) {
        val intent = Intent(activity, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_DOWNLOAD
            putExtra("json", json)
        }
        activity.startService(intent)
    }

    /** 已下载对账（3.9.0）：返回壳里真有文件的歌曲编号（JSON 数组字符串），
     * 网页开机时拿它给「已下载」名单打/摘本地标记——文件真相在壳 */
    @JavascriptInterface
    fun getDownloadedKeys(): String {
        return try {
            val f = java.io.File(activity.filesDir, "dl_index.json")
            if (!f.exists()) {
                "[]"
            } else {
                val o = org.json.JSONObject(f.readText(Charsets.UTF_8))
                val arr = org.json.JSONArray()
                val it = o.keys()
                while (it.hasNext()) arr.put(it.next())
                arr.toString()
            }
        } catch (_: Exception) {
            "[]"
        }
    }

    /** 本地音乐（3.58.0）：扫系统音频库（MediaStore），返回
     * {"perm":bool,"songs":[...]}——只收 IS_MUSIC=1、按加入时间倒序；
     * 排除「下载/酸汤鱼音乐」目录（壳下载导出的副本，已在「已下载」里，
     * 再扫进来会一歌两现）。无音频权限时 perm=false、songs 空，
     * 页面据此显示提示并调 requestLocalPerm 申请 */
    @JavascriptInterface
    fun getLocalSongs(): String {
        val out = org.json.JSONObject()
        try {
            val perm = if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
            out.put("perm", perm)
            val arr = org.json.JSONArray()
            if (perm) {
                // 列名用字面量：MediaStore 的列常量散在接口里，Kotlin 经
                // Audio.Media 类名取不到（Media 是 class 不是 object）
                val proj = arrayOf(
                    "_id", "title", "artist", "album", "duration",
                    "relative_path", "_data"
                )
                activity.contentResolver.query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    proj, "is_music != 0", null, "date_added DESC"
                )?.use { cur ->
                    val idC = cur.getColumnIndex("_id")
                    val tC = cur.getColumnIndex("title")
                    val aC = cur.getColumnIndex("artist")
                    val alC = cur.getColumnIndex("album")
                    val dC = cur.getColumnIndex("duration")
                    val rpC = cur.getColumnIndex("relative_path")
                    val dataC = cur.getColumnIndex("_data")
                    var n = 0
                    while (cur.moveToNext() && n < 2000) {
                        val rp = if (rpC >= 0) cur.getString(rpC) ?: "" else ""
                        val data = if (dataC >= 0) cur.getString(dataC) ?: "" else ""
                        if (rp.startsWith("Download/酸汤鱼音乐") ||
                            data.contains("/Download/酸汤鱼音乐/")
                        ) continue
                        val id = cur.getLong(idC)
                        val title = (if (tC >= 0) cur.getString(tC) else null)
                            ?.trim().orEmpty()
                        var artist = (if (aC >= 0) cur.getString(aC) else null)
                            ?.trim().orEmpty()
                        if (artist == "<unknown>") artist = ""
                        val album = (if (alC >= 0) cur.getString(alC) else null)
                            ?.trim().orEmpty()
                        val durMs = if (dC >= 0) cur.getLong(dC) else 0L
                        val o = org.json.JSONObject()
                        o.put("id", "local:$id")
                        o.put("songmid", "local:$id")
                        o.put("name", title.ifEmpty { "未知歌曲" })
                        o.put("artist", artist)
                        o.put("singer", artist)
                        o.put("album", album)
                        o.put("source", "local")
                        o.put("luri", "content://media/external/audio/media/$id")
                        o.put("interval", (durMs / 1000).toInt())
                        arr.put(o)
                        n++
                    }
                }
            }
            out.put("songs", arr)
        } catch (_: Exception) {
            try {
                out.put("perm", false)
                out.put("songs", org.json.JSONArray())
            } catch (_: Exception) {
            }
        }
        return out.toString()
    }

    /** 本地音乐权限申请（3.58.0）：页面在无权限打开本地音乐段时调用，
     * 结果由 MainActivity 经 __aqLocalPerm 回推页面 */
    @JavascriptInterface
    fun requestLocalPerm() {
        activity.runOnUiThread {
            try {
                activity.requestAudioPermission()
            } catch (_: Exception) {
            }
        }
    }

    // —— 原生 HTTP 桥（3.25.0）——
    // 网页 adapterRequest 的原生通道：上游 hasNativeHttpBridge 只认 AQNative/fm/WebToApp 等桥名，
    // 网页侧垫片把 AQNative.request 转发到这里，原生直连目标 API（无 CORS、不绕 /proxy 与公共代理）。
    // 异步执行，响应体以 Base64 经 window.__baoHttpDone(id, b64) 回传；任何失败回空串，
    // 网页会据此自动降级回原来的浏览器兜底链，行为不比从前差。
    private val httpPool = java.util.concurrent.Executors.newFixedThreadPool(8)

    @JavascriptInterface
    fun httpReq(id: Int, url: String, method: String, headersJson: String, body: String, timeoutSec: Int) {
        httpPool.execute {
            var text = ""
            try {
                val to = timeoutSec.coerceIn(3, 60) * 1000
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = to
                conn.readTimeout = to
                conn.instanceFollowRedirects = true
                val m = method.uppercase().ifEmpty { "GET" }
                conn.requestMethod = m
                var hasUa = false
                try {
                    val hj = org.json.JSONObject(headersJson.ifEmpty { "{}" })
                    val keys = hj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                        conn.setRequestProperty(k, hj.optString(k, ""))
                    }
                } catch (_: Exception) {
                }
                if (!hasUa) {
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                }
                if (m != "GET" && m != "HEAD") {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                if (stream != null) {
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    stream.use { ins ->
                        while (true) {
                            val n = ins.read(chunk)
                            if (n <= 0) break
                            total += n
                            if (total > 8 * 1024 * 1024) break
                            buf.write(chunk, 0, n)
                        }
                    }
                    text = String(buf.toByteArray(), Charsets.UTF_8)
                }
                conn.disconnect()
            } catch (_: Exception) {
                text = ""
            }
            val b64 = try {
                android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            } catch (_: Exception) {
                ""
            }
            activity.runJsOnPage("window.__baoHttpDone && window.__baoHttpDone($id,\"$b64\")")
        }
    }
}
