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
        // 顺带预加载封面(原图+强模糊版)进 CoverCache:切歌时就备好,
        // 播放页打开时取色/氛围底首帧即就绪(3.84.15;取色未就绪时
        // 播放页默认黑底,会先在屏底露黑色页头)
        if (cover.isNotEmpty()) {
            Thread {
                try {
                    kotlinx.coroutines.runBlocking {
                        CoverCache.load(cover)
                        CoverCache.loadBlurred(cover)
                        // 3.84.18:取色与浅底合成背景也一并预合成,
                        // 播放页打开首帧即完整(治迟滞与闪屏)
                        val dm = activity.resources.displayMetrics
                        CoverCache.precompute(cover, dm.widthPixels, dm.heightPixels)
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
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

    /** 3.80.0 原生队列：网页问壳有没有（老壳无此法、网页自动走网页队列） */
    @JavascriptInterface
    fun hasNativeQueue(): Boolean = true

    /** 3.80.0 原生队列：网页把当前深色/主题色递来，壳弹原生队列 sheet */
    /** 3.82.0:原生播放页在场标记(网页 openNowPlaying 据此改走原生) */
    @JavascriptInterface
    fun hasNativePlayer(): Boolean = true

    @JavascriptInterface
    fun showNativePlayer(dark: Boolean, accent: String) {
        activity.runOnUiThread { activity.showNativePlayer(dark, accent) }
    }

    /** 3.82.0:网页推播放页数据(喜欢/音质/整行歌词),__aqPlayerPush 发起 */
    @JavascriptInterface
    fun postPlayerData(json: String) {
        activity.onPlayerData(json)
    }

    /** 3.85.0 原生 Dock：网页问壳有没有（老壳无此法、网页自动走网页 Dock） */
    @JavascriptInterface
    fun hasNativeDock(): Boolean = true

    /** 3.85.0：网页推 Dock 状态（页/收起/详情/样式/深色/主题色/歌词句色），1_181 __aqDockPush 发起 */
    @JavascriptInterface
    fun postDockState(json: String) {
        activity.onDockState(json)
    }

    /** 3.85.2：网页滚动（内层容器）在滚 → 通知壳重截毛玻璃底图，1_182 滚动监听发起 */
    @JavascriptInterface
    fun postDockScroll() {
        activity.onDockScroll()
    }

    @JavascriptInterface
    fun showNativeQueue(dark: Boolean, accent: String) {
        activity.runOnUiThread { activity.showNativeQueue(dark, accent) }
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

    // —— 本地音乐（3.61.0）：系统多选文件导入 + 自带封面 + 移除 ——
    // 导入走 ACTION_OPEN_DOCUMENT 多选：逐个文件取持久读授权，只有
    // 选中的文件进列表。状态存 SharedPreferences(aq_local)：files
    // （文件 URI 的 JSON 数组）、cache（最近一次扫描列表）。
    // 3.60 的授权文件夹（folders 键）首启迁移：走一遍文件夹扫描，把
    // 在册歌曲的文件 URI 播种进 files，列表不丢，此后不再扫文件夹。
    private fun localPrefs() =
        activity.getSharedPreferences("aq_local", android.content.Context.MODE_PRIVATE)

    /** 已选文件 URI 名单（纯读取，不触发迁移） */
    private fun storedFileUris(): List<String> {
        val raw = localPrefs().getString("files", null) ?: return emptyList()
        val out = mutableListOf<String>()
        try {
            val a = org.json.JSONArray(raw)
            for (i in 0 until a.length()) {
                val u = a.optString(i)
                if (u.isNotEmpty()) out.add(u)
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveFileUris(list: List<String>) {
        try {
            val a = org.json.JSONArray()
            for (u in list) a.put(u)
            localPrefs().edit().putString("files", a.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun docIdOf(uriStr: String): String {
        return try {
            android.provider.DocumentsContract.getDocumentId(Uri.parse(uriStr))
        } catch (_: Exception) {
            uriStr
        }
    }

    /** 3.60 授权文件夹名单（仅迁移用） */
    private fun localFolders(): List<Pair<String, String>> {
        val p = localPrefs()
        val out = mutableListOf<Pair<String, String>>()
        val raw = p.getString("folders", null)
        if (raw != null) {
            try {
                val a = org.json.JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val u = o.optString("uri")
                    if (u.isNotEmpty()) out.add(u to o.optString("name"))
                }
            } catch (_: Exception) {
            }
            return out
        }
        if (p.getString("mode", "") == "folder") {
            val u = p.getString("folderUri", "") ?: ""
            if (u.isNotEmpty()) out.add(u to (p.getString("folderName", "") ?: ""))
        }
        return out
    }

    /** 首启迁移：files 键不存在时，若有 3.60 文件夹存档，后台走一遍
     * 文件夹把在册歌曲 URI 播种进 files，随后正常扫描回推 */
    private fun ensureFilesReady() {
        val p = localPrefs()
        if (p.contains("files")) return
        saveFileUris(emptyList())
        val folders = localFolders()
        if (folders.isEmpty()) return
        Thread {
            try {
                val seed = mutableListOf<String>()
                for ((uriStr, _) in folders) {
                    try {
                        val root = androidx.documentfile.provider.DocumentFile
                            .fromTreeUri(activity, Uri.parse(uriStr)) ?: continue
                        val files = mutableListOf<
                            androidx.documentfile.provider.DocumentFile>()
                        collectAudioFiles(root, files, 0)
                        for (f in files) seed.add(f.uri.toString())
                    } catch (_: Exception) {
                    }
                }
                if (seed.isNotEmpty()) {
                    saveFileUris(seed.distinct())
                    scanFilesAsync()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun localStateJson(songs: org.json.JSONArray?): String {
        val out = org.json.JSONObject()
        out.put("picked", storedFileUris().size)
        if (songs != null) {
            out.put("songs", songs)
        } else {
            val cache = try {
                org.json.JSONArray(localPrefs().getString("cache", "[]") ?: "[]")
            } catch (_: Exception) {
                org.json.JSONArray()
            }
            out.put("songs", cache)
        }
        return out.toString()
    }

    private fun pushLocalState(songs: org.json.JSONArray) {
        try {
            localPrefs().edit().putString("cache", songs.toString()).apply()
        } catch (_: Exception) {
        }
        activity.runJsOnPage(
            "window.__aqLocalList && window.__aqLocalList(" +
                org.json.JSONObject.quote(localStateJson(songs)) + ")"
        )
    }

    /** 页面拉当前状态（同步、快）：回已选文件数与缓存列表 */
    @JavascriptInterface
    fun getLocalState(): String {
        ensureFilesReady()
        return localStateJson(null)
    }

    /** 导入音乐：拉起系统多选文件选择器，选定后 MainActivity 取
     * 持久授权并调 onLocalFilesPicked */
    @JavascriptInterface
    fun importFiles() {
        activity.runOnUiThread {
            try {
                activity.pickLocalFiles()
            } catch (_: Exception) {
            }
        }
    }

    /** 重新扫描已选文件（后台）：文件已删的自动剔除，完成回推 */
    @JavascriptInterface
    fun rescanLocal() {
        ensureFilesReady()
        if (storedFileUris().isNotEmpty()) scanFilesAsync()
    }

    /** 文件选定后（MainActivity 回调）：新文件追加进名单（同 URI 不重复），全量重扫 */
    fun onLocalFilesPicked(uris: List<Uri>) {
        val cur = storedFileUris().toMutableList()
        var changed = false
        for (u in uris) {
            val s = u.toString()
            if (s !in cur) {
                cur.add(s)
                changed = true
            }
        }
        if (changed) saveFileUris(cur)
        if (cur.isNotEmpty()) scanFilesAsync()
    }

    /** 移除一首（页面长按移除状态点「移除」）：名单删该文件、释放
     * 其持久授权、缓存同步剔除并回推。文件本身不动。 */
    @JavascriptInterface
    fun removeLocalSong(songmid: String) {
        val docId = songmid.removePrefix("local:")
        val cur = storedFileUris()
        val target = cur.firstOrNull { docIdOf(it) == docId } ?: return
        saveFileUris(cur - target)
        try {
            activity.contentResolver.releasePersistableUriPermission(
                Uri.parse(target), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        try {
            val cache = org.json.JSONArray(
                localPrefs().getString("cache", "[]") ?: "[]"
            )
            val kept = org.json.JSONArray()
            for (i in 0 until cache.length()) {
                val o = cache.optJSONObject(i) ?: continue
                if (o.optString("id") != songmid) kept.put(o)
            }
            pushLocalState(kept)
        } catch (_: Exception) {
        }
    }

    /** 自带封面（页面渲染本地行时按 songmid 来要）：磁盘缓存优先，
     * 未命中用 MMR 取内嵌图、缩到 256px 内转 JPEG，经 __aqLocalCover
     * 回推 data URI；无内嵌图回空串，页面保留占位 */
    @JavascriptInterface
    fun requestLocalCover(songmid: String) {
        Thread {
            var dataUri = ""
            try {
                val docId = songmid.removePrefix("local:")
                val uriStr = storedFileUris()
                    .firstOrNull { docIdOf(it) == docId }
                if (uriStr != null) {
                    val dir = java.io.File(activity.cacheDir, "local_covers")
                    if (!dir.exists()) dir.mkdirs()
                    val key = java.security.MessageDigest
                        .getInstance("MD5")
                        .digest(docId.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val cacheFile = java.io.File(dir, "$key.jpg")
                    var bytes: ByteArray? = null
                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        bytes = cacheFile.readBytes()
                    } else {
                        var rawPic: ByteArray? = null
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(activity, Uri.parse(uriStr))
                            rawPic = mmr.embeddedPicture
                        } catch (_: Exception) {
                        } finally {
                            try { mmr.release() } catch (_: Exception) {
                            }
                        }
                        if (rawPic == null) {
                            // 3.62：MMR 对 FLAC 图片块支持不全（真机实测
                            // 取不到），改按文件格式从文件头直接抠图
                            rawPic = extractArtBytes(Uri.parse(uriStr))
                        }
                        if (rawPic != null) {
                            val bmp = android.graphics.BitmapFactory
                                .decodeByteArray(rawPic, 0, rawPic.size)
                            if (bmp != null) {
                                val maxDim = maxOf(bmp.width, bmp.height)
                                val scaled = if (maxDim > 256) {
                                    val r = 256f / maxDim
                                    android.graphics.Bitmap.createScaledBitmap(
                                        bmp,
                                        (bmp.width * r).toInt().coerceAtLeast(1),
                                        (bmp.height * r).toInt().coerceAtLeast(1),
                                        true
                                    )
                                } else bmp
                                val bos = java.io.ByteArrayOutputStream()
                                scaled.compress(
                                    android.graphics.Bitmap.CompressFormat.JPEG,
                                    80, bos
                                )
                                bytes = bos.toByteArray()
                                try {
                                    cacheFile.writeBytes(bytes)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        dataUri = "data:image/jpeg;base64," + android.util.Base64
                            .encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                }
            } catch (_: Exception) {
            }
            activity.runJsOnPage(
                "window.__aqLocalCover && window.__aqLocalCover(" +
                    org.json.JSONObject.quote(songmid) + "," +
                    org.json.JSONObject.quote(dataUri) + ")"
            )
        }.start()
    }

    // —— 封面抠图（3.62.0）：FLAC 图片块 / ID3v2 APIC 直接解析 ——
    // 与离线验证过的镜像实现（fixtures/ArtParser.java）逻辑逐行对应。
    private fun artReadFully(
        ins: java.io.InputStream, buf: ByteArray, off: Int, len: Int
    ): Boolean {
        var got = 0
        while (got < len) {
            val r = try {
                ins.read(buf, off + got, len - got)
            } catch (_: Exception) {
                return false
            }
            if (r < 0) return false
            got += r
        }
        return true
    }

    private fun artSkipFully(ins: java.io.InputStream, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val s = try {
                ins.skip(left)
            } catch (_: Exception) {
                return false
            }
            if (s <= 0) {
                val b = try {
                    ins.read()
                } catch (_: Exception) {
                    return false
                }
                if (b < 0) return false
                left -= 1
            } else {
                left -= s
            }
        }
        return true
    }

    private fun artBe32(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }

    private fun extractArtBytes(uri: Uri): ByteArray? {
        return try {
            val raw = activity.contentResolver.openInputStream(uri)
                ?: return null
            raw.use {
                val ins = java.io.BufferedInputStream(it, 65536)
                ins.mark(32)
                val head = ByteArray(10)
                var got = 0
                while (got < 10) {
                    val r = ins.read(head, got, 10 - got)
                    if (r < 0) break
                    got += r
                }
                ins.reset()
                when {
                    got >= 4 && head[0] == 'f'.code.toByte() &&
                        head[1] == 'L'.code.toByte() &&
                        head[2] == 'a'.code.toByte() &&
                        head[3] == 'C'.code.toByte() ->
                        parseFlacPicture(ins)
                    got >= 3 && head[0] == 'I'.code.toByte() &&
                        head[1] == 'D'.code.toByte() &&
                        head[2] == '3'.code.toByte() ->
                        parseId3Apic(ins)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFlacPicture(
        ins: java.io.InputStream
    ): ByteArray? {
        val magic = ByteArray(4)
        if (!artReadFully(ins, magic, 0, 4)) return null
        var skipped = 0L
        for (blk in 0 until 16) {
            val h = ByteArray(4)
            if (!artReadFully(ins, h, 0, 4)) return null
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or
                ((h[2].toInt() and 0xFF) shl 8) or
                (h[3].toInt() and 0xFF)
            if (type == 6) {
                if (len <= 0 || len > 16 * 1024 * 1024) return null
                val body = ByteArray(len)
                if (!artReadFully(ins, body, 0, len)) return null
                return parseFlacPictureBlock(body)
            }
            if (last) return null
            skipped += len
            if (skipped > 16L * 1024 * 1024) return null
            if (!artSkipFully(ins, len.toLong())) return null
        }
        return null
    }

    private fun parseFlacPictureBlock(b: ByteArray): ByteArray? {
        var off = 0
        if (b.size < 8) return null
        off += 4 // 图片类型（封面=3）：真实文件必有此字段，3.62 漏了
        val mimeLen = artBe32(b, off)
        off += 4
        if (mimeLen < 0 || off + mimeLen + 4 > b.size) return null
        off += mimeLen
        val descLen = artBe32(b, off)
        off += 4
        if (descLen < 0 || off + descLen + 16 + 4 > b.size) return null
        off += descLen + 16
        val dataLen = artBe32(b, off)
        off += 4
        if (dataLen <= 0 || off + dataLen > b.size) return null
        return b.copyOfRange(off, off + dataLen)
    }

    private fun parseId3Apic(
        ins: java.io.InputStream
    ): ByteArray? {
        val hdr = ByteArray(10)
        if (!artReadFully(ins, hdr, 0, 10)) return null
        val ver = hdr[3].toInt() and 0xFF
        val tagSize = ((hdr[6].toInt() and 0x7F) shl 21) or
            ((hdr[7].toInt() and 0x7F) shl 14) or
            ((hdr[8].toInt() and 0x7F) shl 7) or
            (hdr[9].toInt() and 0x7F)
        if (tagSize <= 0 || tagSize > 20 * 1024 * 1024) return null
        val body = ByteArray(tagSize)
        if (!artReadFully(ins, body, 0, tagSize)) return null
        var off = 0
        while (off + 10 <= body.size) {
            val id = String(body, off, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (ver >= 4) {
                ((body[off + 4].toInt() and 0x7F) shl 21) or
                    ((body[off + 5].toInt() and 0x7F) shl 14) or
                    ((body[off + 6].toInt() and 0x7F) shl 7) or
                    (body[off + 7].toInt() and 0x7F)
            } else {
                artBe32(body, off + 4)
            }
            if (size <= 0 || off + 10 + size > body.size) break
            if (id == "APIC") {
                return parseApicBody(body, off + 10, size)
            }
            off += 10 + size
        }
        return null
    }

    private fun parseApicBody(
        b: ByteArray, start: Int, len: Int
    ): ByteArray? {
        val end = start + len
        if (len < 4) return null
        val enc = b[start].toInt() and 0xFF
        var off = start + 1
        while (off < end && b[off].toInt() != 0) off++
        if (off >= end) return null
        off++
        if (off >= end) return null
        off++
        if (enc == 1 || enc == 2) {
            while (off + 1 < end &&
                !(b[off].toInt() == 0 && b[off + 1].toInt() == 0)
            ) off += 2
            off += 2
        } else {
            while (off < end && b[off].toInt() != 0) off++
            off++
        }
        if (off >= end) return null
        return b.copyOfRange(off, end)
    }

    /** 脚本导入（3.64.0）：拉起系统文件选择器选 .js，选定后
     * MainActivity 回调 onScriptFilePicked 读文本回推页面 */
    @JavascriptInterface
    fun importScriptFile() {
        activity.runOnUiThread {
            try {
                activity.pickScriptFile()
            } catch (_: Exception) {
            }
        }
    }

    /** 脚本文件选定后（MainActivity 回调）：后台读文本（上限 2MB），
     * 经 __aqScriptFile 回推页面导入处理线 */
    fun onScriptFilePicked(uri: Uri) {
        Thread {
            try {
                val name = try {
                    androidx.documentfile.provider.DocumentFile
                        .fromSingleUri(activity, uri)?.name
                } catch (_: Exception) {
                    null
                } ?: "本地音源.js"
                val bytes = activity.contentResolver
                    .openInputStream(uri)?.use { ins ->
                        val bos = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(65536)
                        var total = 0
                        while (total < 2 * 1024 * 1024) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            bos.write(buf, 0, r)
                            total += r
                        }
                        bos.toByteArray()
                    } ?: return@Thread
                val text = String(bytes, Charsets.UTF_8)
                activity.runJsOnPage(
                    "window.__aqScriptFile && window.__aqScriptFile(" +
                        org.json.JSONObject.quote(name) + "," +
                        org.json.JSONObject.quote(text) + ")"
                )
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun scanFilesAsync() {
        Thread {
            val arr = org.json.JSONArray()
            try {
                val uris = storedFileUris()
                val items = mutableListOf<
                    Pair<androidx.documentfile.provider.DocumentFile, Long>>()
                val alive = mutableListOf<String>()
                for (uriStr in uris) {
                    try {
                        val f = androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(activity, Uri.parse(uriStr))
                        if (f == null || !f.exists()) continue
                        alive.add(uriStr)
                        val lm = try {
                            f.lastModified()
                        } catch (_: Exception) {
                            0L
                        }
                        items.add(f to lm)
                    } catch (_: Exception) {
                    }
                }
                if (alive.size != uris.size) saveFileUris(alive)
                // 新文件在前：按最后修改时间倒序；同文件只收一次
                items.sortByDescending { it.second }
                val seen = HashSet<String>()
                for ((f, _) in items) {
                    if (arr.length() >= 2000) break
                    val docId = try {
                        android.provider.DocumentsContract.getDocumentId(f.uri)
                    } catch (_: Exception) {
                        f.uri.toString()
                    }
                    if (!seen.add(docId)) continue
                    arr.put(folderSongJson(f))
                }
            } catch (_: Exception) {
            }
            pushLocalState(arr)
        }.start()
    }

    private fun collectAudioFiles(
        dir: androidx.documentfile.provider.DocumentFile,
        out: MutableList<androidx.documentfile.provider.DocumentFile>,
        depth: Int
    ) {
        if (depth > 12) return
        val kids = try { dir.listFiles() } catch (_: Exception) { return }
        for (k in kids) {
            try {
                if (k.isDirectory) collectAudioFiles(k, out, depth + 1)
                else if (k.isFile && isAudioDoc(k)) out.add(k)
            } catch (_: Exception) {
            }
        }
    }

    private fun isAudioDoc(f: androidx.documentfile.provider.DocumentFile): Boolean {
        val t = try { f.type } catch (_: Exception) { null }
        if (t != null && t.startsWith("audio/")) return true
        val n = (try { f.name } catch (_: Exception) { null } ?: "").lowercase()
        return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a") ||
            n.endsWith(".aac") || n.endsWith(".ogg") || n.endsWith(".opus") ||
            n.endsWith(".wav") || n.endsWith(".ape") || n.endsWith(".wma") ||
            n.endsWith(".alac") || n.endsWith(".aiff")
    }

    private fun folderSongJson(
        f: androidx.documentfile.provider.DocumentFile
    ): org.json.JSONObject {
        val uri = f.uri
        var title = ""
        var artist = ""
        var album = ""
        var durMs = 0L
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(activity, uri)
            title = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
            )?.trim().orEmpty()
            artist = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
            )?.trim().orEmpty()
            album = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM
            )?.trim().orEmpty()
            durMs = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {
            }
        }
        if (title.isEmpty()) {
            val raw = (try { f.name } catch (_: Exception) { null } ?: "").trim()
            title = raw.replace(Regex("\\.[A-Za-z0-9]{2,5}$"), "")
                .ifEmpty { "未知歌曲" }
        }
        if (artist == "<unknown>") artist = ""
        val docId = try {
            android.provider.DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            uri.toString()
        }
        val o = org.json.JSONObject()
        o.put("id", "local:$docId")
        o.put("songmid", "local:$docId")
        o.put("name", title)
        o.put("artist", artist)
        o.put("singer", artist)
        o.put("album", album)
        o.put("source", "local")
        o.put("luri", uri.toString())
        o.put("interval", (durMs / 1000).toInt())
        return o
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
