package com.lk21.webview

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var customContainer: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var mainFrameError = false
    private var lastUrl: String? = null
    private var lastBlockedToast = 0L
    private var webViewDestroyed = false

    private val injectedScript: String by lazy {
        readAsset("guard.js") + "\n" + readAsset("hydrax.js")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        customContainer = findViewById(R.id.customContainer)

        retryButton.setOnClickListener {
            hideError()
            webView.loadUrl(lastUrl ?: START_URL)
        }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    webView.canGoBack() -> {
                        hideError()
                        webView.goBack()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(START_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.userAgentString = s.userAgentString
            .replace("; wv", "")
            .replace("Version/4.0 ", "")

        webView.setBackgroundColor(Color.BLACK)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, injectedScript, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (!request.isForMainFrame) {
                    return HostPolicy.isBlocked(url)
                }
                val scheme = request.url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return true
                }
                if (HostPolicy.isAllowedPage(url)) {
                    return false
                }
                notifyBlocked()
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!request.isForMainFrame && HostPolicy.isBlocked(request.url.toString())) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameError = false
                if (!url.isNullOrBlank()) lastUrl = url
                view.evaluateJavascript(injectedScript, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(injectedScript, null)
                if (!mainFrameError) hideError()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    mainFrameError = true
                    showError()
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                if (!webViewDestroyed) {
                    webViewDestroyed = true
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                val temp = WebView(this@MainActivity)
                temp.settings.javaScriptEnabled = false
                temp.webViewClient = object : WebViewClient() {
                    private var handled = false

                    private fun handle(url: String?) {
                        if (handled || url.isNullOrBlank() || url == "about:blank") return
                        handled = true
                        if (HostPolicy.isAllowedPage(url)) {
                            this@MainActivity.webView.post {
                                this@MainActivity.webView.loadUrl(url)
                            }
                        } else {
                            notifyBlocked()
                        }
                        temp.post {
                            temp.stopLoading()
                            temp.destroy()
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        handle(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        handle(url)
                    }
                }
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                previousOrientation = requestedOrientation
                customContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                customContainer.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                applyFullscreenMode(true)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        customContainer.removeView(view)
        customContainer.visibility = View.GONE
        if (!mainFrameError) webView.visibility = View.VISIBLE
        requestedOrientation = previousOrientation
        applyFullscreenMode(false)
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun applyFullscreenMode(enable: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enable) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showError() {
        errorView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
        if (customView == null) webView.visibility = View.VISIBLE
    }

    private fun notifyBlocked() {
        val now = System.currentTimeMillis()
        if (now - lastBlockedToast < 2000) return
        lastBlockedToast = now
        runOnUiThread {
            Toast.makeText(this, R.string.blocked_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun readAsset(name: String): String {
        return assets.open(name).bufferedReader().use { it.readText() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDestroyed) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) webView.onResume()
    }

    override fun onPause() {
        if (!webViewDestroyed) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (!webViewDestroyed) {
            webViewDestroyed = true
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://tv.lk21official.dev/"
    }
}
