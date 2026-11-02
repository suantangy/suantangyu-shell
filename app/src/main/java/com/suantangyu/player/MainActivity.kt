package com.suantangyu.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
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
    private var pageReady = false
    private var revealed = false
    private var earlyInjected = false
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
        // 渲染进程优先级拉到 IMPORTANT 且不可见时不降级：熄屏/切后台后系统别冻结
        // 网页渲染进程——切歌决策、下一首解析、给服务重新预置续播都靠它的 JS 活着；
        // 被冻住时页面收不到 ended、发不出新地址，原生播放器只能干等（phase-2.6）。
        try {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        } catch (_: Exception) {
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
            webView.loadUrl(START_URL)
        }

        // 启动前台保活服务（通知栏常驻就靠它）
        ContextCompat.startForegroundService(
            this, Intent(this, KeepAliveService::class.java)
        )

        maybeShowProbeResult()
    }

    /** 3.1.16 探针读数：汇总服务 WebView 的熄屏心跳（见 KeepAliveService.startProbe） */
    private fun maybeShowProbeResult() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val f = File(filesDir, "probe_beats.log")
                if (!f.exists() || f.length() == 0L) return@postDelayed
                val prefs = getSharedPreferences("probe", MODE_PRIVATE)
                if (prefs.getLong("shown_mtime", -1L) == f.lastModified()) return@postDelayed
                var segStart = -1L
                var segBeats = 0
                var segMaxGap = 0L
                var segEnd = -1L
                var bestDur = 0L
                var bestBeats = 0
                var bestMaxGap = 0L
                var lastFetchMs = -1L
                var lastFetchOk = false
                var anyBeat = false
                fun closeSeg(endElapsed: Long) {
                    if (segStart >= 0) {
                        val dur = endElapsed - segStart
                        if (dur > bestDur) {
                            bestDur = dur
                            bestBeats = segBeats
                            bestMaxGap = segMaxGap
                        }
                    }
                    segStart = -1
                    segBeats = 0
                    segMaxGap = 0
                }
                f.forEachLine { line ->
                    val p = line.split(" ")
                    when (p[0]) {
                        "SCREEN_OFF" -> {
                            val e = p.getOrNull(1)?.toLongOrNull()
                            if (e != null) {
                                closeSeg(e)
                                segStart = e
                                segEnd = e
                            }
                        }
                        "SCREEN_ON", "STOP" -> {
                            val e = p.getOrNull(1)?.toLongOrNull()
                            if (e != null) closeSeg(e)
                        }
                        "BEAT" -> if (p.size >= 7) {
                            anyBeat = true
                            lastFetchMs = p[5].toLongOrNull() ?: -1
                            lastFetchOk = p[6] == "true"
                            if (segStart >= 0) {
                                segBeats++
                                val gap = p[4].toLongOrNull() ?: 0
                                if (gap > segMaxGap) segMaxGap = gap
                                segEnd = p[1].toLongOrNull() ?: segEnd
                            }
                        }
                    }
                }
                if (segStart >= 0 && segEnd > segStart) closeSeg(segEnd)
                if (!anyBeat) return@postDelayed
                val msg = StringBuilder()
                if (bestDur >= 5 * 60 * 1000L) {
                    msg.append("最长熄屏段：约 ").append(bestDur / 60000).append(" 分钟\n")
                    msg.append("这段内心跳 ").append(bestBeats).append(" 次（每 5 秒一次）\n")
                    msg.append("最大间隔：").append(String.format("%.1f", bestMaxGap / 1000.0)).append(" 秒\n")
                    msg.append(
                        if (bestMaxGap <= 15000) "→ JS 基本没被冻，方案一（解析页常驻补仓）可行"
                        else if (bestMaxGap <= 60000) "→ 有节流但没冻死，截图发我细看"
                        else "→ 间隔到分钟级，这个 WebView 也被冻了，此路不通"
                    )
                } else {
                    msg.append("还没测到 ≥5 分钟的熄屏段。\n放着歌熄屏 30 分钟以上，再打开 App 看这里。")
                }
                if (lastFetchMs >= 0) {
                    msg.append("\n最近一次网络请求：").append(if (lastFetchOk) "成功" else "失败")
                        .append("（").append(lastFetchMs).append("ms）")
                }
                prefs.edit().putLong("shown_mtime", f.lastModified()).apply()
                AlertDialog.Builder(this)
                    .setTitle("解析 WebView 熄屏探针")
                    .setMessage(msg.toString())
                    .setPositiveButton("知道了", null)
                    .setNegativeButton("清除重测") { _, _ ->
                        try {
                            f.delete()
                        } catch (_: Exception) {
                        }
                        prefs.edit().remove("shown_mtime").apply()
                        Toast.makeText(this, "探针数据已清除", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } catch (_: Exception) {
            }
        }, 1200)
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
