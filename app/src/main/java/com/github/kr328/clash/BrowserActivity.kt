package com.github.kr328.clash

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.github.kr328.clash.design.BrowserDesign
import kotlinx.coroutines.isActive

class BrowserActivity : BaseActivity<BrowserDesign>() {
    private lateinit var webView: WebView
    private var isLoading = false

    override suspend fun main() {
        val design = BrowserDesign(this)

        setContentDesign(design)

        webView = design.webView
        setupWebView()
        setupProxy()

        // Load default URL
        webView.loadUrl("https://www.google.com")

        // Setup UI event listeners
        design.backButton.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        design.forwardButton.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }

        design.reloadButton.setOnClickListener {
            if (isLoading) {
                webView.stopLoading()
            } else {
                webView.reload()
            }
        }

        design.urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                loadUrlFromInput(design)
                true
            } else {
                false
            }
        }

        while (isActive) {
            events.receive()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                design?.progressBar?.visibility = android.view.View.VISIBLE
                design?.urlInput?.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                design?.progressBar?.visibility = android.view.View.GONE
                design?.urlInput?.setText(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                isLoading = false
                design?.progressBar?.visibility = android.view.View.GONE
            }
        }
    }

    private fun setupProxy() {
        // Check if ProxyController is supported
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:7890") // Clash Meta default HTTP proxy port
                .build()
            
            ProxyController.getInstance().setProxyOverride(proxyConfig, java.util.concurrent.Executors.newSingleThreadExecutor()) {
                // Proxy override applied
            }
        }
    }

    private fun loadUrlFromInput(design: BrowserDesign) {
        var url = design.urlInput.text.toString().trim()
        if (!TextUtils.isEmpty(url)) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            webView.loadUrl(url)
            
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(design.urlInput.windowToken, 0)
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
