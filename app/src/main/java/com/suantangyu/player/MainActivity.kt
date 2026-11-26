package com.suantangyu.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 酸汤鱼音乐混合壳：WebView 承载现有网页播放器（音频仍由网页 <audio> 播放，
 * 你的几万行代码不用动），原生 ForegroundService 保活 + 通知栏媒体控制。
 *
 * 沉浸式：网页内容延伸到状态栏/导航栏之下（顶部全屏无黑条），
 * 真实系统栏高度注入网页的 --fm-safe-top / --fm-safe-bottom，
 * 页面自己的安全区变量会自动避让，不会被状态栏图标压住。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 你的 Cloudflare Pages 地址，换了部署地址就改这里 */
        const val START_URL = "https://m.858600.xyz/"
        const val ACTION_CONTROL = "com.suantangyu.player.CONTROL"
        /** 3.69.0：APK 内置页面版本（assets/web/index.html），与页面 aq-ver 标记对应 */
        const val BUNDLED_PAGE_VER = 157
        /** 离线包查更新每进程只跑一次 */
        var pageUpdateChecked = false
        var instance: MainActivity? = null
            private set
    }

    private lateinit var webView: WebView
    private var lastTopCss: Int = 0
    private var lastBottomCss: Int = 0
    /** 当前键盘高度（CSS px），由系统 IME inset 换算；只灌给网页当 --kb-h 用，不动 WebView 布局 */
    private var lastKbCss: Int = 0
    /** 启动闪跳修复：insets 和页面都就绪、注入已画出来后，才显示 WebView */
    private var insetsReady = false
    private var wantInitialLoad = false
    private var pageLoadStarted = false
    private var pageReady = false
    private var revealed = false
    private var earlyInjected = false
    /** 网页桥（本地音乐授权文件夹回调要回调用） */
    private var baoBridge: BaoBridge? = null

    /** 文件选择器回调（音源管理"本地文件"按钮用） */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 通知栏按钮 → 转发给网页的 window.__baoCtl（见 web/bridge-snippet.js）
            val action = intent.getStringExtra("action") ?: return
            webView.post {
                webView.evaluateJavascript(
                    "window.__baoCtl && window.__baoCtl('$action')", null
                )
            }
        }
    }

    /** 供 KeepAliveService 直接调用：在网页里执行 JS（通知栏/锁屏按钮直达页面，不经过广播）。
     * 注意：必须走主线程 Handler，不能用 webView.post——App 在后台、WebView 脱离窗口时
     * View.post 的任务会被攒住不执行，导致暂停/切歌指令石沉大海。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    fun runJsOnPage(js: String) {
        if (!::webView.isInitialized) return
        uiHandler.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }

    /** 3.75.0：显现门已拆（WebView 全程可见、边下边画，同 WebToApp 壳），
     *  本函数只负责在条件齐时补一次安全区注入。触发点不变：进度 20% 提前
     *  注入点／onPageFinished／insets 就位，三处任一先到都调，revealed 保证
     *  只注一次；其后 insets 变化由监听器里 pageReady 分支补注。注入落地前
     *  页面 --safe-t 另有 env(safe-area-inset-top) 兜底，顶部不会跳。 */
    private fun maybeReveal() {
        if (revealed || !insetsReady || !(pageReady || earlyInjected) || !::webView.isInitialized) return
        revealed = true
        injectSafeArea()
    }

    // —— 3.69.0 离线包：页面主文档从本地供（同源不变，localStorage/设置全保留） ——
    // 优先用已下载的新版（filesDir/webpkg），没有或比内置旧就用 APK 内置页。
    private fun openLocalPage(): InputStream? {
        try {
            val dir = File(filesDir, "webpkg")
            val f = File(dir, "index.html")
            val v = try { File(dir, "ver.txt").readText().trim().toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
            if (f.isFile && f.length() > 500_000L && v >= BUNDLED_PAGE_VER) {
                return FileInputStream(f)
            }
        } catch (_: Exception) {
        }
        return try { assets.open("web/index.html") } catch (_: Exception) { null }
    }

    private fun parsePageVer(text: String): Int? {
        val m = Regex("aq-ver:1_(\\d+)").find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** 后台查一次线上页面版本：比本地新才整页下载存好，下次启动生效；全程静默失败、不挡启动 */
    private fun checkPageUpdate() {
        if (pageUpdateChecked) return
        pageUpdateChecked = true
        Thread {
            try {
                val dir = File(filesDir, "webpkg"); dir.mkdirs()
                val savedVer = try { File(dir, "ver.txt").readText().trim().toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                val localVer = maxOf(BUNDLED_PAGE_VER, savedVer)
                // 只读头部 8KB 找版本标记（不管服务器认不认 Range 都不会整页白下）
                var remoteVer: Int? = null
                val conn = URL(START_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("Range", "bytes=0-8191")
                conn.setRequestProperty("User-Agent", "SuantangyuShell")
                conn.inputStream.use { ins ->
                    val buf = ByteArray(8192)
                    var n = 0
                    while (n < buf.size) {
                        val r = ins.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    remoteVer = parsePageVer(String(buf, 0, n, Charsets.UTF_8))
                }
                conn.disconnect()
                val rv = remoteVer ?: return@Thread
                if (rv <= localVer) return@Thread
                val tmp = File(dir, "index.html.tmp")
                val c2 = URL(START_URL).openConnection() as HttpURLConnection
                c2.connectTimeout = 15000; c2.readTimeout = 30000
                c2.setRequestProperty("User-Agent", "SuantangyuShell")
                var ok = false
                if (c2.responseCode == 200) {
                    c2.inputStream.use { ins ->
                        tmp.outputStream().use { out -> ins.copyTo(out) }
                    }
                    ok = tmp.length() > 500_000L && parsePageVer(tmp.readText(Charsets.UTF_8)) == rv
                }
                c2.disconnect()
                if (ok) {
                    val target = File(dir, "index.html")
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        File(dir, "ver.txt").writeText(rv.toString())
                    } else tmp.delete()
                } else tmp.delete()
            } catch (_: Exception) {
            }
        }.start()
    }

    /** 把系统栏高度注入网页，页面 CSS 的 var(--fm-safe-top/bottom) 自动生效 */
    /** 3.77.0：安全区文档起始注入——赶在页面任何绘制之前把 --fm-safe-top/bottom
     *  和 fm-chrome-full 写进文档。旧路（加载 20% 时 evaluateJavascript 注入）与
     *  第一帧赛跑：页面走缓存来得快时先按 --safe-t=0 画出错位一帧、注入落地再
     *  整页滑回（cheng 2026-11-26 录屏实锤：高约 40dp、停约 0.45 秒后滑回）。
     *  本函数只在首次加载前登记一次（脚本对后续每次导航同样生效），值取登记
     *  时刻的真实 insets；其后 insets 变化仍由 injectSafeArea 实时修正。 */
    private fun safeAreaScript(): String =
        "document.documentElement.classList.add('fm-chrome-full');" +
            "document.documentElement.style.setProperty('--fm-safe-top','" + lastTopCss + "px');" +
            "document.documentElement.style.setProperty('--fm-safe-bottom','" + lastBottomCss + "px')"

    /** 首次加载：等 insets 到手才登记文档起始脚本并 loadUrl——insets 在窗口
     *  首次布局时就到（远早于页面下载），等待不拖慢启动；800ms 兜底防异常
     *  环境下 insets 不来时页面永远打不开（force 用当时的值先加载，后续注入
     *  再修正）。 */
    private fun maybeLoadPage(force: Boolean = false) {
        if (!wantInitialLoad || pageLoadStarted || !(insetsReady || force)) return
        if (!::webView.isInitialized) return
        pageLoadStarted = true
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, safeAreaScript(), setOf("*"))
            }
        } catch (_: Exception) {
        }
        webView.loadUrl(START_URL)
    }

    private fun injectSafeArea() {        if (!::webView.isInitialized) return
        webView.post {
            webView.evaluateJavascript(
                // fm-chrome-full：启用页面自己的沉浸式安全区（--safe-t = --safe-t-raw）
                "document.documentElement.classList.add('fm-chrome-full');" +
                    "document.documentElement.style.setProperty('--fm-safe-top','" + lastTopCss + "px');" +
                    "document.documentElement.style.setProperty('--fm-safe-bottom','" + lastBottomCss + "px')",
                null
            )
        }
        // 顺带同步一次键盘高度（页面后到、insets 先到的情况）
        injectKb()
    }

    /**
     * 键盘高度精确版：只把系统 IME 高度灌成 CSS 变量 --kb-h，并给 <html> 挂 kb-on 类；
     * 网页弹窗用 padding-bottom: var(--kb-h) 居中到键盘上方可见区。不改 WebView 高度。
     * 动画全权交给网页的一条 CSS 过渡：值只在这里随 inset 变化更新一次，过渡一笔画完。
     */
    private fun injectKb() {
        if (!pageReady) return
        applyKbCss(lastKbCss)
    }

    private fun applyKbCss(kbCss: Int) {
        runJsOnPage(
            "(function(){try{var h=document.documentElement;" +
                "h.style.setProperty('--kb-h','" + kbCss + "px');" +
                "if(" + kbCss + ">0)h.classList.add('kb-on');else h.classList.remove('kb-on');" +
                "}catch(e){}})()"
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        // —— 沉浸式：内容延伸到系统栏之下，系统栏透明（顶部全屏，不再有黑条） ——
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        // 首页/搜索页是浅色底，状态栏和导航栏图标用深色
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        webView = WebView(this)
        // 3.75.0：向 WebToApp 壳看齐——不设显现门，WebView 从头可见、边下边画。
        // 3.78.0：删掉 3.75 的 #F3F3F6 底色预刷——它让空屏阶段多出一段实灰，
        // 与系统启动窗的白一帧硬换成一闪（cheng 2026-11-26 定：砍掉第二段）。
        // 3.79.0：但完全不预刷会露黑——WebView 未绘制的硬件表面是纯黑（cheng
        // 录屏实锤：黑段约 1.7 秒、长度随页面到达时间变）。故预刷改白色：与
        // 系统白连成一段，灰段、黑段都没有，页面带着内容一次到位。
        webView.setBackgroundColor(Color.WHITE)
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
        }
        // 渲染进程优先级拉到 IMPORTANT 且不可见时不降级：熄屏/切后台后系统别冻结
        // 网页渲染进程——切歌决策、下一首解析、给服务重新预置续播都靠它的 JS 活着；
        // 被冻住时页面收不到 ended、发不出新地址，原生播放器只能干等（phase-2.6）。
        try {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        } catch (_: Exception) {
        }
        // 网页 → 原生：上报曲名/播放状态，驱动通知栏
        val bridge = BaoBridge(this)
        baoBridge = bridge
        webView.addJavascriptInterface(bridge, "BaoBus")
        webView.webViewClient = object : WebViewClient() {
            // 3.69.0 离线包：主文档走本地副本（同源不变），其余请求一律不碰
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // 3.77.0 定版：离线包已拆——主文档走真网络（3.76 测试版验证：
                // 本地直供让页面各构建阶段挤成一帧闪，走网络后阶段拉开如
                // WebToApp 壳）。拦截函数保留空壳，防后续误接回本地供页。
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 页面加载完补一次注入（防止 insets 先到、页面后到的情况）
                pageReady = true
                injectSafeArea()
                maybeReveal()
            }
        }
        // 进度起来后 DOM 基本可用，提前注入一次，缩短隐身等待（最终显示仍以校验为准）
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!earlyInjected && newProgress >= 20 && insetsReady && ::webView.isInitialized) {
                    earlyInjected = true
                    injectSafeArea()
                    // 3.68.0：注入点一到就启动露脸校验，不再等 onPageFinished
                    maybeReveal()
                }
            }
            // 网页 <input type="file">（如音源管理"本地文件"）调起系统文件选择器
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        // 监听系统栏高度变化（旋转、分屏等），实时注入网页。
        // 键盘：WebView 永远全高，键盘作为系统窗口直接盖在 App 上层（导航栏被盖住是主流行为）；
        // 壳只把系统 IME 高度换算成 CSS px 灌给网页（--kb-h），弹窗居中由网页 CSS 用这个值完成。
        // 刻意不用逐帧动画回调：它在部分输入法上时有时无、采样不匀，逐帧直写拼出来的轨迹
        // 反而不如一条完整的 CSS 过渡顺滑——弹窗动画全权交给网页的一条过渡（见 --kb-h 注释）。
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density
            lastTopCss = (bars.top / density).toInt()
            lastBottomCss = (bars.bottom / density).toInt()
            insetsReady = true
            maybeLoadPage()
            // 页面已就绪：值变了（旋转/分屏）就补注入
            if (pageReady) injectSafeArea()
            maybeReveal()
            val kbCss = (ime.bottom / density).toInt()
            if (kbCss != lastKbCss) {
                lastKbCss = kbCss
                injectKb()
            }
            insets
        }

        // 通知栏控制 → 网页
        val filter = IntentFilter(ACTION_CONTROL)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }

        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }

        // 返回键：网页能后退就后退，否则退出
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        if (savedInstanceState == null) {
            // 3.77.0：不等这里直接加载——等 insets 到手、文档起始脚本登记后再
            // 加载（见 maybeLoadPage）；800ms 兜底防 insets 迟到卡住首屏。
            wantInitialLoad = true
            maybeLoadPage()
            uiHandler.postDelayed({ maybeLoadPage(force = true) }, 800)
        }

        // 启动前台保活服务（通知栏常驻就靠它）
        ContextCompat.startForegroundService(
            this, Intent(this, KeepAliveService::class.java)
        )

        // 3.77.0：离线包已正式拆除（3.76 测试验证：本地直供把空架子压成一帧闪，
        // 走真网络后各阶段拉开、读作正常加载）——不再后台下载页面副本。
    }

    /** 本地音乐多选文件导入（3.61.0）：系统文件选择器回调——逐个文件
     * 取持久读授权，把 URI 名单交桥存档并扫描 */
    private val localFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                try {
                    clip.getItemAt(i)?.uri?.let { uris.add(it) }
                } catch (_: Exception) {
                }
            }
        }
        if (uris.isEmpty()) return@registerForActivityResult
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        try {
            baoBridge?.onLocalFilesPicked(uris)
        } catch (_: Exception) {
        }
    }

    /** 拉起系统文件选择器（多选）：用户一首首选，只有选中的进列表 */
    fun pickLocalFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        localFilesLauncher.launch(intent)
    }

    /** 脚本导入（3.64.0）：系统文件选择器（单选）回调——把选定的
     * 文件 URI 交桥读文本回推页面，不需要持久授权 */
    private val scriptFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            baoBridge?.onScriptFilePicked(uri)
        } catch (_: Exception) {
        }
    }

    /** 拉起系统文件选择器选脚本文件（单选） */
    fun pickScriptFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scriptFileLauncher.launch(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(controlReceiver)
        } catch (_: Exception) {
        }
        instance = null
        super.onDestroy()
    }
}
