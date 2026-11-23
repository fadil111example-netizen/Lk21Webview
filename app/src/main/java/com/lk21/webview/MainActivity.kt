package com.lk21.webview

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var homeButton: Button
    private lateinit var menuButton: ImageButton
    private lateinit var customContainer: FrameLayout
    private lateinit var mobileUserAgent: String

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("lk21_prefs", MODE_PRIVATE)
    }

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var mainFrameError = false
    private var lastUrl: String? = null
    private var lastBlockedToast = 0L
    private var lastBackPress = 0L
    private var webViewDestroyed = false
    private var firstPageLoaded = false

    // Status pemutaran video (per frame) untuk menjaga layar tetap menyala.
    private val playingFrames: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val blockedCount = AtomicInteger(0)

    // Picture-in-Picture
    private var enteringPip = false
    private var inPip = false

    // Episode berikutnya otomatis
    private var autoNextPending = false
    private var autoNextRunnable: Runnable? = null
    private var autoNextSnack: Snackbar? = null

    private val connectivity: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (!webViewDestroyed && mainFrameError) {
                    hideError()
                    webView.loadUrl(lastUrl ?: START_URL)
                }
            }
        }
    }

    private val injectedScript: String by lazy {
        readAsset("guard.js") + "\n" + readAsset("hydrax.js")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        homeButton = findViewById(R.id.homeButton)
        menuButton = findViewById(R.id.menuButton)
        customContainer = findViewById(R.id.customContainer)

        retryButton.setOnClickListener {
            hideError()
            webView.loadUrl(lastUrl ?: START_URL)
        }
        homeButton.setOnClickListener {
            hideError()
            webView.loadUrl(START_URL)
        }
        menuButton.setOnClickListener { showMenu(it) }

        swipeRefresh.setColorSchemeResources(R.color.brand)
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.WHITE)
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { reloadPage() }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    autoNextPending -> cancelAutoNext()
                    webView.canGoBack() -> {
                        hideError()
                        webView.goBack()
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPress < 2000) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        } else {
                            lastBackPress = now
                            Toast.makeText(
                                this@MainActivity,
                                R.string.press_again_exit,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(START_URL)
        }
        refreshUiState()
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
        mobileUserAgent = s.userAgentString
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        applyUserAgent()

        webView.setBackgroundColor(Color.BLACK)
        webView.addJavascriptInterface(PlaybackBridge(), "LkBridge")

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
                if (HostPolicy.isBlocked(url)) {
                    notifyBlocked()
                    return true
                }
                // Sebelum halaman pertama berhasil tampil, redirect/mirror situs dibiarkan lewat
                // (kecuali domain iklan). Setelah itu, hanya domain situs yang boleh dibuka.
                if (!firstPageLoaded || HostPolicy.isAllowedPage(url)) {
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
                    blockedCount.incrementAndGet()
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
                cancelAutoNext()
                playingFrames.clear()
                refreshUiState()
                view.evaluateJavascript(injectedScript, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                view.evaluateJavascript(injectedScript, null)
                if (!mainFrameError) {
                    firstPageLoaded = true
                    hideError()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    mainFrameError = true
                    swipeRefresh.isRefreshing = false
                    showError(
                        getString(R.string.error_message) + "\n\n" +
                            error.description + "\n" + request.url
                    )
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
                if (newProgress >= 100) swipeRefresh.isRefreshing = false
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
                refreshUiState()
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    // ---------------------------------------------------------------- Jembatan JS -> Kotlin

    inner class PlaybackBridge {
        @JavascriptInterface
        fun onPlayback(frameId: String, playing: Boolean) {
            if (playing) playingFrames.add(frameId) else playingFrames.remove(frameId)
            runOnUiThread { refreshUiState() }
        }

        @JavascriptInterface
        fun onEnded() {
            runOnUiThread { handleVideoEnded() }
        }
    }

    // ---------------------------------------------------------------- Layar, UI, dan PiP

    /**
     * Menjaga layar tetap menyala hanya saat video diputar (inline atau fullscreen),
     * menonaktifkan tarik-untuk-refresh dan tombol menu agar tidak mengganggu pemutar.
     */
    private fun refreshUiState() {
        if (webViewDestroyed) return
        val playing = playingFrames.isNotEmpty()
        if (playing || customView != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        swipeRefresh.isEnabled = !playing && customView == null && !inPip
        menuButton.visibility =
            if (playing || customView != null || inPip) View.GONE else View.VISIBLE
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (customView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enteringPip = true
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            } catch (e: Exception) {
                enteringPip = false
            }
        }
    }

    override fun onPictureInPictureModeChanged(inPipMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPipMode, newConfig)
        inPip = inPipMode
        if (!inPipMode) enteringPip = false
        refreshUiState()
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
        refreshUiState()
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

    // ---------------------------------------------------------------- Episode berikutnya otomatis

    private fun handleVideoEnded() {
        if (webViewDestroyed || autoNextPending) return
        if (!prefs.getBoolean(PREF_AUTO_NEXT, true)) return
        webView.evaluateJavascript(NEXT_EPISODE_JS) { raw ->
            val url = (raw ?: "")
                .removeSurrounding("\"")
                .replace("\\/", "/")
                .trim()
            if (url.isNotEmpty() && url != "null" && HostPolicy.isAllowedPage(url)) {
                scheduleAutoNext(url)
            }
        }
    }

    private fun scheduleAutoNext(url: String) {
        if (autoNextPending) return
        autoNextPending = true

        val snack = Snackbar.make(
            findViewById(R.id.root),
            R.string.auto_next_message,
            Snackbar.LENGTH_INDEFINITE
        )
        snack.setAction(R.string.cancel) { cancelAutoNext() }
        snack.show()
        autoNextSnack = snack

        val runnable = Runnable {
            autoNextPending = false
            autoNextRunnable = null
            autoNextSnack?.dismiss()
            autoNextSnack = null
            exitFullscreen()
            webView.loadUrl(url)
        }
        autoNextRunnable = runnable
        handler.postDelayed(runnable, AUTO_NEXT_DELAY_MS)
    }

    private fun cancelAutoNext() {
        autoNextRunnable?.let { handler.removeCallbacks(it) }
        autoNextRunnable = null
        autoNextPending = false
        autoNextSnack?.dismiss()
        autoNextSnack = null
    }

    // ---------------------------------------------------------------- Menu

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.menu.findItem(R.id.menu_auto_next).isChecked =
            prefs.getBoolean(PREF_AUTO_NEXT, true)
        popup.menu.findItem(R.id.menu_desktop).isChecked =
            prefs.getBoolean(PREF_DESKTOP, false)
        popup.menu.findItem(R.id.menu_stats).title =
            getString(R.string.menu_stats, blockedCount.get())
        popup.setOnMenuItemClickListener { item -> onMenuItem(item.itemId) }
        popup.show()
    }

    private fun onMenuItem(id: Int): Boolean {
        when (id) {
            R.id.menu_home -> {
                hideError()
                webView.loadUrl(START_URL)
            }
            R.id.menu_refresh -> reloadPage()
            R.id.menu_share -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl())
                }
                startActivity(Intent.createChooser(send, null))
            }
            R.id.menu_copy -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", currentUrl()))
                Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
            R.id.menu_browser -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl())))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
                }
            }
            R.id.menu_auto_next -> {
                val newValue = !prefs.getBoolean(PREF_AUTO_NEXT, true)
                prefs.edit().putBoolean(PREF_AUTO_NEXT, newValue).apply()
            }
            R.id.menu_desktop -> {
                val newValue = !prefs.getBoolean(PREF_DESKTOP, false)
                prefs.edit().putBoolean(PREF_DESKTOP, newValue).apply()
                applyUserAgent()
                webView.reload()
            }
            R.id.menu_clear -> {
                webView.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                webView.reload()
            }
            else -> return false
        }
        return true
    }

    private fun applyUserAgent() {
        webView.settings.userAgentString =
            if (prefs.getBoolean(PREF_DESKTOP, false)) DESKTOP_USER_AGENT else mobileUserAgent
    }

    private fun currentUrl(): String = webView.url ?: lastUrl ?: START_URL

    private fun reloadPage() {
        if (mainFrameError || webView.url == null) {
            hideError()
            webView.loadUrl(lastUrl ?: START_URL)
        } else {
            webView.reload()
        }
    }

    // ---------------------------------------------------------------- Error, toast, util

    private fun showError(message: String) {
        errorText.text = message
        errorView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
        if (customView == null) webView.visibility = View.VISIBLE
    }

    private fun notifyBlocked() {
        blockedCount.incrementAndGet()
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

    // ---------------------------------------------------------------- Lifecycle

    override fun onStart() {
        super.onStart()
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Abaikan: pemulihan otomatis saat online hanyalah fitur tambahan.
        }
    }

    override fun onStop() {
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Callback belum terdaftar.
        }
        super.onStop()
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
        // Saat masuk mode PiP, video harus tetap berjalan.
        if (!webViewDestroyed && !enteringPip && !inPip) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!webViewDestroyed) {
            webViewDestroyed = true
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://tv12.lk21official.cc/"
        private const val PREF_AUTO_NEXT = "auto_next"
        private const val PREF_DESKTOP = "desktop_mode"
        private const val AUTO_NEXT_DELAY_MS = 6000L
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        private const val NEXT_EPISODE_JS =
            "(function(){var i=document.querySelector('i.fa-angle-double-right');" +
                "var a=i&&i.closest('a');if(!a)return '';" +
                "var h=a.getAttribute('href');if(!h||h==='#')return '';return a.href;})()"
    }
}
