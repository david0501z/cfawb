package com.github.kr328.clash

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.github.kr328.clash.design.BrowserDesign
import kotlinx.coroutines.isActive

class BrowserActivity : BaseActivity<BrowserDesign>() {
    private data class BrowserTab(
        val webView: WebView,
        val tabView: TextView,
        var title: String = "New Tab"
    )

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = 0
    private var isLoading = false

    override suspend fun main() {
        val design = BrowserDesign(this)

        setContentDesign(design)

        setupProxy()

        // Create first tab
        createNewTab(design, "https://www.google.com")

        // Setup UI event listeners
        design.backButton.setOnClickListener {
            val currentTab = tabs.getOrNull(currentTabIndex)
            if (currentTab?.webView?.canGoBack() == true) {
                currentTab.webView.goBack()
            }
        }

        design.forwardButton.setOnClickListener {
            val currentTab = tabs.getOrNull(currentTabIndex)
            if (currentTab?.webView?.canGoForward() == true) {
                currentTab.webView.goForward()
            }
        }

        design.reloadButton.setOnClickListener {
            val currentTab = tabs.getOrNull(currentTabIndex)
            if (isLoading) {
                currentTab?.webView?.stopLoading()
            } else {
                currentTab?.webView?.reload()
            }
        }

        design.closeButton.setOnClickListener {
            finish()
        }

        design.newTabButton.setOnClickListener {
            createNewTab(design, "https://www.google.com")
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

    private fun createNewTab(design: BrowserDesign, url: String): BrowserTab {
        val webView = WebView(this)
        setupWebView(webView)

        val tabView = TextView(this).apply {
            text = "New Tab"
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER
            setBackgroundResource(android.R.drawable.btn_default_small)
        }

        val tab = BrowserTab(webView, tabView)
        tabs.add(tab)

        // Add WebView to container
        if (tabs.size == 1) {
            design.webViewContainer.addView(webView)
        }

        // Add tab to tabs container
        design.tabsContainer.addView(tabView)

        // Set click listener for tab switching
        val tabIndex = tabs.size - 1
        tabView.setOnClickListener {
            switchToTab(design, tabIndex)
        }

        // Load URL
        webView.loadUrl(url)

        return tab
    }

    private fun switchToTab(design: BrowserDesign, index: Int) {
        if (index < 0 || index >= tabs.size) return

        val previousTab = tabs.getOrNull(currentTabIndex)
        val newTab = tabs[index]

        // Remove current WebView from container
        if (previousTab != null) {
            design.webViewContainer.removeView(previousTab.webView)
        }

        // Add new WebView to container
        design.webViewContainer.addView(newTab.webView)

        // Update current tab index
        currentTabIndex = index

        // Update URL input
        design.urlInput.setText(newTab.webView.url)
        
        // Update tab view appearance to show active tab
        for (i in tabs.indices) {
            tabs[i].tabView.isSelected = (i == currentTabIndex)
        }
    }

    private fun setupWebView(webView: WebView) {
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
                
                // Update tab title
                val currentIndex = tabs.indexOfFirst { it.webView == view }
                if (currentIndex >= 0) {
                    val title = url?.let { extractDomain(it) } ?: "Loading..."
                    tabs[currentIndex].tabView.text = title
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                design?.progressBar?.visibility = android.view.View.GONE
                design?.urlInput?.setText(url)
                
                // Update tab title
                val currentIndex = tabs.indexOfFirst { it.webView == view }
                if (currentIndex >= 0) {
                    val title = view?.title?.takeIf { it.isNotEmpty() } ?: url?.let { extractDomain(it) } ?: "New Tab"
                    tabs[currentIndex].tabView.text = title
                }
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
            
            val currentTab = tabs.getOrNull(currentTabIndex)
            currentTab?.webView?.loadUrl(url)
            
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(design.urlInput.windowToken, 0)
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    override fun onBackPressed() {
        val currentTab = tabs.getOrNull(currentTabIndex)
        if (currentTab?.webView?.canGoBack() == true) {
            currentTab.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
