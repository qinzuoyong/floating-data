package com.example.batteryfloat.map

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.batteryfloat.BuildConfig
import com.google.gson.Gson

/**
 * WebView 地图实现（百度 JS API，零原生体积）
 *
 * 结构：AndroidView 承载 WebView → 加载 assets/map.html（内嵌百度 JS API 脚本，
 * AK 由 __AK__ 占位符在运行时替换，避免写死资源）；
 * 数据通道双向：原生侧 evaluateJavascript 推送 applyData(json)，
 * JS 侧 initMap 后主动 AndroidBridge.getData() 拉取（防推送早于加载完成）。
 */
class WebMapProvider : MapProvider {

    private val gson = Gson()

    /** 桥接对象：JS 侧 initMap 后主动拉取最新数据 */
    private class Bridge(private val holder: WebMapHolder) {
        @JavascriptInterface
        fun getData(): String? = holder.latestJson
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun FamilyMap(
        myLocation: MapPoint?,
        targets: List<MapPoint>,
        modifier: Modifier
    ) {
        val holder = remember { WebMapHolder() }

        DisposableEffect(Unit) {
            onDispose {
                holder.webView?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                holder.webView = null
            }
        }

        AndroidView(
            factory = { ctx: Context ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(Bridge(holder), "AndroidBridge")
                    val html = ctx.assets.open("map.html").bufferedReader().use { it.readText() }
                        .replace("__AK__", BuildConfig.BAIDU_MAP_AK)
                    loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
                    holder.webView = this
                }
            },
            update = { view ->
                holder.webView = view
                pushData(view, holder, myLocation, targets)
            },
            modifier = modifier
        )
    }

    /**
     * 推送最新地图数据到 JS（同时缓存供 JS 主动拉取）
     *
     * @param view 目标 WebView
     * @param holder 数据持有者
     * @param myLocation 我的位置（可空）
     * @param targets 家人位置列表
     */
    private fun pushData(
        view: WebView,
        holder: WebMapHolder,
        myLocation: MapPoint?,
        targets: List<MapPoint>
    ) {
        val data = MapData(myLocation, targets)
        holder.latestJson = gson.toJson(data)
        runCatching {
            view.evaluateJavascript("window.applyData(" + holder.latestJson + ")", null)
        }.onFailure { e ->
            Log.w(TAG, "pushData failed", e)
        }
    }

    /** 原生 → JS 数据模型（字段名与 map.html 的 applyData 约定一致） */
    private data class MapData(
        val my: MapPoint?,
        val targets: List<MapPoint>
    )

    /** 跨重组持有的 WebView 与最新数据 */
    private class WebMapHolder {
        var webView: WebView? = null
        var latestJson: String? = null
    }

    private companion object {
        const val TAG = "WebMapProvider"
    }
}
