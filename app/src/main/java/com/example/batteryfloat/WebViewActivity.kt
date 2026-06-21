package com.example.batteryfloat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge

/**
 * WebView Activity - 应用内浏览网页
 * 用于 GitHub / Gitee 等外部链接，避免跳转浏览器导致闪退
 *
 * 修复项：
 * - WebView 存为字段，onDestroy 中销毁防止内存泄漏
 * - 返回键优先回退 WebView 历史记录
 * - 使用新版 shouldOverrideUrlLoading(WebResourceRequest) 替代弃用重载
 * - URL 白名单机制：仅允许 gitee.com 和 github.com 域名在 WebView 内加载
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        /** 允许在 WebView 内加载的域名白名单 */
        private val ALLOWED_DOMAINS = listOf("gitee.com", "github.com")
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "浏览"
        val ctx: Context = this
        val density = resources.displayMetrics.density

        // 根布局
        val rootLayout = LinearLayout(ctx)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootLayout.setBackgroundColor(Color.WHITE)

        // 顶部标题栏
        val titleBar = LinearLayout(ctx)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (48 * density).toInt()
        )
        titleBar.setBackgroundColor(Color.parseColor("#1A73E8"))
        titleBar.setPadding(
            (8 * density).toInt(), 0,
            (16 * density).toInt(), 0
        )
        titleBar.gravity = Gravity.CENTER_VERTICAL

        // 关闭按钮
        val closeBtn = TextView(ctx)
        closeBtn.text = "✕"
        closeBtn.textSize = 18f
        closeBtn.setTextColor(Color.WHITE)
        closeBtn.gravity = Gravity.CENTER
        closeBtn.layoutParams = ViewGroup.LayoutParams(
            (48 * density).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        closeBtn.setOnClickListener { finish() }
        titleBar.addView(closeBtn)

        // 标题文本
        val titleText = TextView(ctx)
        titleText.text = title
        titleText.textSize = 16f
        titleText.setTextColor(Color.WHITE)
        titleText.gravity = Gravity.CENTER_VERTICAL
        titleText.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        )
        titleBar.addView(titleText)

        rootLayout.addView(titleBar)

        // 进度条
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (3 * density).toInt()
        )
        progressBar.max = 100
        rootLayout.addView(progressBar)

        // WebView
        val wv = WebView(ctx)
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                // 白名单域名在 WebView 内加载，外部链接跳转系统浏览器
                val host = request.url?.host ?: ""
                val isAllowed = ALLOWED_DOMAINS.any { domain ->
                    host == domain || host.endsWith(".$domain")
                }
                if (isAllowed) {
                    view?.loadUrl(targetUrl)
                    return true
                }
                // 外部链接跳转系统浏览器
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    android.util.Log.w("WebViewActivity", "无法打开外部链接: $targetUrl", e)
                }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }

        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(wv)

        setContentView(rootLayout)
        wv.loadUrl(url)

        // 返回键优先回退 WebView 历史记录，无历史时才退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onDestroy() {
        // 销毁 WebView 防止内存泄漏
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
            webView = null
        }
        super.onDestroy()
    }
}
