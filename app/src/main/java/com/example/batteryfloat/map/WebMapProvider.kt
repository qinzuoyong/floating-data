package com.example.batteryfloat.map

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.batteryfloat.BuildConfig
import com.example.batteryfloat.location.CoordTransform
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

    /**
     * 桥接对象：只保留"事件上报"能力。
     *
     * 数据一律由原生侧 [pushData] 主动推送，网页脚本无法反向拉取位置数据
     * （此前暴露 getData() 会返回全部家人坐标，配合页面内注入可越权读取位置）。
     */
    private class Bridge(private val holder: WebMapHolder) {
        /** 页面就绪：重推一次最新数据，覆盖"推送早于页面加载完成"的时序问题 */
        @JavascriptInterface
        fun onMapReady() {
            Log.i(TAG, "MAP READY")
            holder.repush()
        }

        /** JS 逆地理编码结果：坐标 → 详细地址（仅接受当前展示的成员 uid，长度截断） */
        @JavascriptInterface
        fun onAddress(uid: String?, address: String?) {
            val safeUid = uid ?: return
            val safeAddress = address ?: return
            if (!holder.isKnownUid(safeUid)) {
                Log.w(TAG, "drop onAddress for unknown uid")
                return
            }
            holder.onAddress?.invoke(safeUid, safeAddress.take(MAX_ADDRESS_LENGTH))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun FamilyMap(
        myLocation: MapPoint?,
        targets: List<MapPoint>,
        modifier: Modifier,
        onAddress: (String, String) -> Unit
    ) {
        val holder = remember { WebMapHolder() }
        holder.onAddress = onAddress

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
                    // 百度 JS API 的 qt=verify 验证请求走 http 明文；页面 baseURL 为 https 时
                    // 会被 Mixed Content 策略拦截（BMapGL 初始化失败），需显式允许
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // 百度 JS API 对桌面 UA（Linux x86_64）可能拦截，强制移动端 UA
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    // 记录 WebView 请求（诊断 JS API 加载链路）
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            if (request != null &&
                                (request.url.toString().contains("baidu") || request.url.toString().contains("bdimg"))
                            ) {
                                Log.i(TAG, "req: " + request.url)
                            }
                            return null
                        }
                    }
                    // console 日志转发到 logcat（诊断 JS API 加载/鉴权）
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (consoleMessage != null) {
                                Log.i(TAG, "console: " + consoleMessage.message())
                            }
                            return true
                        }
                    }
                    addJavascriptInterface(Bridge(holder), "AndroidBridge")
                    val html = ctx.assets.open("map.html").bufferedReader().use { it.readText() }
                        .replace("__AK__", BuildConfig.BAIDU_MAP_AK)
                    // baseURL 用自定义域名（http://familymap.gcdar.com/）作 Referer 载体，
                    // 匹配百度控制台该 AK 的 Referer 白名单；不实际联网访问该域名。
                    // 用 http 而非 https：百度 JS API 的 qt=verify 验证请求走 http 明文，
                    // https 页面会被 Mixed Content 拦截导致地图初始化失败（需 network_security_config 放行该域明文）
                    loadDataWithBaseURL("http://familymap.gcdar.com/", html, "text/html", "utf-8", null)
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
        // 定位层产出 GCJ-02；百度 JS API 需要 BD-09，渲染入口统一转换
        // （latestJson 供 JS 主动拉取，故转换在缓存写入前完成，两条数据路径同源）
        val myBd = myLocation?.let { p ->
            val c = CoordTransform.gcj02ToBd09(p.lat, p.lng)
            MapPoint(c.first, c.second, p.title, p.label)
        }
        val targetsBd = targets.map { t ->
            val c = CoordTransform.gcj02ToBd09(t.lat, t.lng)
            MapPoint(c.first, c.second, t.title, t.label)
        }
        val data = MapData(myBd, targetsBd)
        val json = gson.toJson(data)
        // 数据未变化时跳过推送：地址回调触发重组 → update 再次 pushData 会形成
        // "推送→逆地理编码→回调→重组→推送" 的无限循环，瞬间打爆百度并发配额
        // 记录本次展示的成员 uid，作为 JS 回调（onAddress）的来源白名单
        val uids = mutableSetOf<String>()
        myLocation?.let { uids.add(it.title) }
        targets.forEach { uids.add(it.title) }
        holder.knownUids = uids
        if (json == holder.lastPushedJson) return
        holder.lastPushedJson = json
        // 缓存最近一次数据：页面 onMapReady 时由 Bridge 触发重推（替代 JS 反向拉取）
        holder.pendingJson = json
        runCatching {
            view.evaluateJavascript("window.applyData(" + json + ")", null)
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
        /** 上次已推送给 JS 的 json（防重复推送） */
        var lastPushedJson: String? = null
        /** 最近一次推送内容：页面 onMapReady 后重推用（替代 JS 反向拉取） */
        var pendingJson: String? = null
        /** 当前展示的成员 uid 集合（校验 JS 回调来源，防跨成员伪造） */
        var knownUids: Set<String> = emptySet()
        /** JS 逆地理编码结果回调（uid → 详细地址） */
        var onAddress: ((String, String) -> Unit)? = null

        fun isKnownUid(uid: String): Boolean = knownUids.contains(uid)

        /**
         * 页面就绪后重推最新数据。
         * onMapReady 由 JS 桥线程回调，evaluateJavascript 必须在视图线程执行，故 post。
         */
        fun repush() {
            val view = webView ?: return
            val json = pendingJson ?: return
            view.post {
                runCatching { view.evaluateJavascript("window.applyData(" + json + ")", null) }
                    .onFailure { Log.w(TAG, "repush failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "WebMapProvider"

        /** 地址文本长度上限（与 JS 侧截断保持一致） */
        const val MAX_ADDRESS_LENGTH = 300
    }
}