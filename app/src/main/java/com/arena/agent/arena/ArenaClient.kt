package com.arena.agent.arena

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

interface ArenaPageListener {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
}

class ArenaClient(
    private val web: WebView,
    private val script: String,
) {
    var listener: ArenaPageListener? = null

    fun loadInitial(restore: () -> Unit) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        restore()
        web.loadUrl(ArenaUrls.DIRECT)
    }

    fun loadFresh() {
        web.post {
            web.loadUrl("about:blank")
            web.postDelayed({ web.loadUrl(ArenaUrls.DIRECT) }, 180)
        }
    }

    fun canGoBack(): Boolean = web.canGoBack()

    fun goBack() {
        web.post { if (web.canGoBack()) web.goBack() }
    }

    suspend fun probe(): JSONObject? = json("window.__arena.probe();")

    suspend fun assistant(): JSONObject? = json("window.__arena.assistant();")

    suspend fun clickLogin() {
        eval("window.__arena.clickLogin();")
    }

    suspend fun clickGoogle() {
        eval("window.__arena.clickGoogle();")
    }

    suspend fun clickMenu() {
        eval("window.__arena.clickMenu();")
    }

    suspend fun stop() {
        eval("window.__arena.stop();")
    }

    suspend fun send(text: String): Boolean {
        val quoted = JSONObject.quote(text)
        repeat(5) { attempt ->
            val force = attempt == 4
            val raw = eval("window.__arena.send($quoted, $force);")
            if (raw?.contains("\"ok\":true") == true) return true
            delay(280)
        }
        return false
    }

    private suspend fun json(call: String): JSONObject? {
        val raw = eval(call) ?: return null
        if (raw == "null" || raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private suspend fun eval(call: String): String? = suspendCancellableCoroutine { cont ->
        web.post {
            try {
                web.evaluateJavascript(script + "\n" + call) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
class ArenaWebView(context: Context) : WebView(context) {
    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.userAgentString = ArenaUrls.USER_AGENT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(0xFFFFFFFF.toInt())
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(VISIBLE)
    }

    fun attach(client: ArenaClient) {
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                client.listener?.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                client.listener?.onPageFinished(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                if (url.startsWith("intent://")) {
                    val fallback = runCatching {
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            .getStringExtra("browser_fallback_url")
                    }.getOrNull()
                    if (!fallback.isNullOrBlank()) view.loadUrl(fallback)
                    return true
                }
                return false
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                view.post { view.loadUrl(ArenaUrls.DIRECT) }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val popup = WebView(view.context)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url?.toString().orEmpty()
                        if (target.startsWith("http://") || target.startsWith("https://")) {
                            view.loadUrl(target)
                        }
                        popupView.destroy()
                        return true
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }
}
