package com.baobao.player

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.animation.DecelerateInterpolator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 宝宝巴士混合壳：WebView 承载现有网页播放器（音频仍由网页 <audio> 播放，
 * 你的几万行代码不用动），原生 ForegroundService 保活 + 通知栏媒体控制。
 *
 * 沉浸式：网页内容延伸到状态栏/导航栏之下（顶部全屏无黑条），
 * 真实系统栏高度注入网页的 --fm-safe-top / --fm-safe-bottom，
 * 页面自己的安全区变量会自动避让，不会被状态栏图标压住。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 你的 Cloudflare Pages 地址，换了部署地址就改这里 */
        const val START_URL = "https://yinyue-bfq.pages.dev/"
        const val ACTION_CONTROL = "com.baobao.player.CONTROL"
        var instance: MainActivity? = null
            private set
    }

    private lateinit var webView: WebView
    private var lastTopCss: Int = 0
    private var lastBottomCss: Int = 0
    /** 启动闪跳修复：insets 和页面都就绪、注入已画出来后，才显示 WebView */
    private var insetsReady = false
    private var pageReady = false
    private var revealed = false
    private var earlyInjected = false
    /** 文件选择器回调（音源管理"本地文件"按钮用） */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    /** 键盘高度平滑过渡动画（与键盘视觉动画同步，消除白条闪现） */
    private var kbHeightAnim: ValueAnimator? = null
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

    /** 供 KeepAliveService 直接调用：在网页里执行 JS（通知栏/锁屏按钮直达页面，不经过广播） */
    fun runJsOnPage(js: String) {
        if (!::webView.isInitialized) return
        webView.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 键盘高度变化时平滑过渡 WebView 高度（~200ms），与键盘视觉动画大致同步。
     * 背景：系统上报的 IME inset 是"一步到位"的终值，而键盘视觉上是慢慢升起的；
     * 若 WebView 瞬间缩到终值，动画期间会露出一截白色窗口底。平滑过渡盖住这个差值。
     */
    private fun smoothResizeWebView(view: View, target: Int) {
        if (target <= 0) return
        val lp = view.layoutParams
        if (lp.height == target) return
        kbHeightAnim?.cancel()
        kbHeightAnim = null
        val start = view.height.takeIf { it > 0 } ?: lp.height
        if (start <= 0 || start == target) {
            lp.height = target
            view.layoutParams = lp
            return
        }
        kbHeightAnim = ValueAnimator.ofInt(start, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                try {
                    val p = view.layoutParams
                    p.height = a.animatedValue as Int
                    view.layoutParams = p
                } catch (_: Exception) {
                }
            }
            start()
        }
    }

    /** 键盘弹起时隐藏页面底部导航（#dock），收起时恢复；CSS 由壳注入，无需改网页包 */
    private fun syncKbDock(kbOpen: Boolean) {
        if (!::webView.isInitialized) return
        val cls = if (kbOpen) "add" else "remove"
        webView.post {
            try {
                webView.evaluateJavascript(
                    "(function(){try{" +
                        "var h=document.documentElement;" +
                        "h.classList." + cls + "('kb-open');" +
                        "if(!document.getElementById('kb-dock-fix')){" +
                        "var s=document.createElement('style');s.id='kb-dock-fix';" +
                        "s.textContent='html.kb-open #dock{display:none!important}';" +
                        "document.head.appendChild(s);}" +
                        "}catch(e){}})()",
                    null
                )
            } catch (_: Exception) {
            }
        }
    }

    /** 条件满足后：补一次注入，轮询确认计算样式已生效再显示，彻底杜绝时序竞态 */
    private fun maybeReveal() {
        if (revealed || !insetsReady || !pageReady || !::webView.isInitialized) return
        revealed = true
        injectSafeArea()
        var tries = 0
        fun check() {
            if (webView.visibility == View.VISIBLE) return
            if (tries++ >= 50) { webView.visibility = View.VISIBLE; return } // 5s 兜底，绝不白屏
            webView.evaluateJavascript(
                "(function(){try{var e=document.documentElement;" +
                    "var c=e.classList.contains('fm-chrome-full')?'1':'0';" +
                    "var v=getComputedStyle(e).getPropertyValue('--safe-t').trim();" +
                    "return c+'|'+v;}catch(x){return '0|';}})()"
            ) { r ->
                val s = r ?: ""
                val clsOk = s.startsWith("\"1|")
                // 有状态栏时期望 --safe-t 非 0；无状态栏时只看类
                val valOk = lastTopCss == 0 || !(s.startsWith("\"1|0px\"") || s == "\"1|\"")
                if (clsOk && valOk) {
                    // 淡入显示，避免内容"啪"一下弹出的两层感
                    webView.alpha = 0f
                    webView.visibility = View.VISIBLE
                    webView.animate().alpha(1f).setDuration(250).start()
                } else {
                    webView.postDelayed({ check() }, 100)
                }
            }
        }
        webView.post { check() }
    }

    /** 把系统栏高度注入网页，页面 CSS 的 var(--fm-safe-top/bottom) 自动生效 */
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
        // 先隐身：等安全区注入画好再显示，标题不会先顶上去再跳下来
        webView.visibility = View.INVISIBLE
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
        // 网页 → 原生：上报曲名/播放状态，驱动通知栏
        webView.addJavascriptInterface(BaoBridge(this), "BaoBus")
        webView.webViewClient = object : WebViewClient() {
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

        // 监听系统栏高度变化（旋转、分屏等），实时注入网页
        // edge-to-edge 下系统不会自动压缩窗口，键盘高度必须手动处理：
        // 用可见显示区域的高度直接设置 WebView 高度，触发页面的 resizes-content
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density
            lastTopCss = (bars.top / density).toInt()
            lastBottomCss = (bars.bottom / density).toInt()
            insetsReady = true
            // 页面已就绪：值变了（旋转/分屏）就补注入
            if (pageReady) injectSafeArea()
            maybeReveal()
            // 键盘开/关：藏/显底部导航，避免导航栏骑在键盘上
            syncKbDock(ime.bottom > 0)
            // 键盘弹起/收起时：窗口总高度 - 键盘高度 = 可见高度，平滑重设 WebView 高度
            // （不用 getWindowVisibleDisplayFrame，它在全面屏下会多扣导航栏，底部留白条）
            val winH = view.rootView.height
            if (winH > 0) smoothResizeWebView(view, winH - ime.bottom)
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
            webView.loadUrl(START_URL)
        }

        // 启动前台保活服务（通知栏常驻就靠它）
        ContextCompat.startForegroundService(
            this, Intent(this, KeepAliveService::class.java)
        )
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
