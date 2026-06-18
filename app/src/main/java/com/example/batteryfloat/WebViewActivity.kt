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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * WebView Activity - 应用内浏览网页
 * 用于 GitHub / Gitee 等外部链接，避免跳转浏览器导致闪退
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

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
        val webView = WebView(ctx)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl(url ?: return false)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }

        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(webView)

        setContentView(rootLayout)
        webView.loadUrl(url)
    }
}