package com.baobao.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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

    /** 把系统栏高度注入网页，页面 CSS 的 var(--fm-safe-top/bottom) 自动生效 */
    private fun injectSafeArea() {
        if (!::webView.isInitialized) return
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
                injectSafeArea()
            }
        }

        // 监听系统栏高度变化（旋转、分屏等），实时注入网页
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            lastTopCss = (bars.top / density).toInt()
            lastBottomCss = (bars.bottom / density).toInt()
            injectSafeArea()
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
