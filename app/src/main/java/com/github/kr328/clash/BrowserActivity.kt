package com.github.kr328.clash

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.github.kr328.clash.design.BrowserDesign
import kotlinx.coroutines.isActive

class BrowserActivity : BaseActivity<BrowserDesign>() {
    companion object {
        private const val REQUEST_CODE_FILE_CHOOSER = 1001
    }
    
    private data class BrowserTab(
        val webView: WebView,
        val tabView: View,
        val tabContainer: View? = null,
        var title: String = "New Tab",
        var url: String = ""
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

        // Use closeMenuButton instead of closeButton which doesn't exist
        design.closeMenuButton.setOnClickListener {
            finish()
        }

        design.newTabButton.setOnClickListener {
            createNewTab(design, "https://www.google.com")
        }

        design.addTabButton.setOnClickListener {
            createNewTab(design, "https://www.google.com")
        }

        // Use downloadMenuButton instead of downloadButton which doesn't exist
        design.downloadMenuButton.setOnClickListener {
            // Open download management activity
            val intent = android.content.Intent(this, DownloadManagerActivity::class.java)
            startActivity(intent)
        }

        design.historyMenuButton.setOnClickListener {
            // Open history management activity
            val intent = android.content.Intent(this, HistoryManagerActivity::class.java)
            startActivity(intent)
        }

        design.menuButton.setOnClickListener {
            // Toggle menu popup visibility
            if (design.menuPopup.visibility == android.view.View.VISIBLE) {
                design.menuPopup.visibility = android.view.View.GONE
            } else {
                design.menuPopup.visibility = android.view.View.VISIBLE
            }
        }

        design.closeMenuButton.setOnClickListener {
            finish() // Close browser activity
        }

        design.historyMenuButton.setOnClickListener {
            // Open history management activity
            val intent = android.content.Intent(this, HistoryManagerActivity::class.java)
            startActivity(intent)
        }

        design.downloadMenuButton.setOnClickListener {
            // Open download management activity
            val intent = android.content.Intent(this, DownloadManagerActivity::class.java)
            startActivity(intent)
        }

        design.settingsMenuButton.setOnClickListener {
            // Navigate to proxy settings page
            finish() // Close browser activity
            // The main activity should handle navigation to settings
        }

        design.tabsCountButton.setOnClickListener {
            // Show tabs management popup
            showTabsManagementPopup(design)
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

        // Add keyboard visibility listener - adjust layout when keyboard appears
        val rootView = findViewById<android.view.View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rect = android.graphics.Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.height
                val keypadHeight = screenHeight - rect.bottom

                if (keypadHeight > screenHeight * 0.15) {
                    // Keyboard is opened - move the bottom navigation up
                    val bottomNav = design.bottomNavContainer
                    val layoutParams = bottomNav.layoutParams as android.widget.FrameLayout.LayoutParams
                    layoutParams.bottomMargin = keypadHeight
                    bottomNav.layoutParams = layoutParams
                } else {
                    // Keyboard is closed - reset margins
                    val bottomNav = design.bottomNavContainer
                    val layoutParams = bottomNav.layoutParams as android.widget.FrameLayout.LayoutParams
                    layoutParams.bottomMargin = 0
                    bottomNav.layoutParams = layoutParams
                }
            }
        })

        while (isActive) {
            events.receive()
        }
    }

    private fun createNewTab(design: BrowserDesign, url: String): BrowserTab {
        val webView = WebView(this)
        setupWebView(webView)

        // Create a container for the tab with close button
        val tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(android.R.drawable.btn_default_small)
        }

        val tabView = TextView(this).apply {
            text = "New Tab"
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }

        val closeTabButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(android.R.drawable.btn_default_small)
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        tabContainer.addView(tabView)
        tabContainer.addView(closeTabButton)

        val tab = BrowserTab(webView, tabView, tabContainer, url = url)  // 存储tabView和tabContainer以便后续操作
        tabs.add(tab)

        // Add WebView to container
        if (tabs.size == 1) {
            design.webViewContainer.addView(webView)
        }

        // Add tab to tabs container
        design.tabsContainer.addView(tabContainer)

        // Set click listener for tab switching
        val tabIndex = tabs.size - 1
        tabView.setOnClickListener {
            switchToTab(design, tabIndex)
        }

        // Set click listener for tab closing
        closeTabButton.setOnClickListener {
            closeTab(design, tabIndex)
        }

        // Load URL
        webView.loadUrl(url)

        // Update tabs count
        updateTabsCount(design)

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

    private fun closeTab(design: BrowserDesign, index: Int) {
        if (index < 0 || index >= tabs.size) return

        val tabToClose = tabs[index]
        
        // Remove WebView from container
        design.webViewContainer.removeView(tabToClose.webView)
        
        // Remove tab container from tabs container
        if (tabToClose.tabContainer != null) {
            design.tabsContainer.removeView(tabToClose.tabContainer)
        } else {
            design.tabsContainer.removeView(tabToClose.tabView)
        }
        
        // Remove tab from tabs list
        tabs.removeAt(index)
        
        // If we closed the current tab, switch to another tab
        if (index == currentTabIndex) {
            if (tabs.isNotEmpty()) {
                // Switch to the previous tab if available, otherwise the first tab
                val newTabIndex = if (index > 0) index - 1 else 0
                switchToTab(design, newTabIndex)
            } else {
                // No tabs left, create a new one
                createNewTab(design, "https://www.google.com")
            }
        } else if (index < currentTabIndex) {
            // If we closed a tab before the current tab, adjust the current tab index
            currentTabIndex--
        }
        
        // Update tabs count
        updateTabsCount(design)
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
                    (tabs[currentIndex].tabView as TextView).text = title
                    tabs[currentIndex].title = title
                    tabs[currentIndex].url = url ?: ""
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
                    (tabs[currentIndex].tabView as TextView).text = title
                    tabs[currentIndex].title = title
                    tabs[currentIndex].url = url ?: ""
                    
                    // Save to history
                    if (url != null) {
                        saveToHistory(title, url)
                    }
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

        // Enable file upload support
        webView.webChromeClient = object : WebChromeClient() {
            private var filePathCallback: ValueCallback<Array<Uri>?>? = null
            private var cameraPhotoPath: String? = null
            
            // For Android 5.0+
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>?>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Save the callback for later use
                this.filePathCallback = filePathCallback
                
                // Create an intent to open the file chooser
                val intent = fileChooserParams.createIntent()
                try {
                    // Start the file chooser activity
                    startActivityForResult(intent, REQUEST_CODE_FILE_CHOOSER)
                } catch (e: Exception) {
                    // If the intent fails, send null back to the WebView
                    this.filePathCallback = null
                    filePathCallback.onReceiveValue(null)
                    return false
                }
                
                return true
            }
        }
        
        // Enable download support
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            // Handle download request
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
            request.addRequestHeader("User-Agent", userAgent)
            request.setDescription("正在下载文件...")
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
            request.allowScanningByMediaScanner()
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
            
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            
            Toast.makeText(this, "开始下载文件", Toast.LENGTH_SHORT).show()
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

    private fun saveToHistory(title: String, url: String) {
        // Save to history using SharedPreferences
        val prefs = getSharedPreferences("browser_history", Context.MODE_PRIVATE)
        val history = prefs.getStringSet("history", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        // Create history entry with timestamp
        val timestamp = System.currentTimeMillis()
        val historyEntry = "$timestamp|$title|$url"
        
        // Add new entry
        history.add(historyEntry)
        
        // Limit history to 100 entries
        if (history.size > 100) {
            // Sort by timestamp and remove oldest entries
            val sortedHistory = history.sortedBy { entry ->
                entry.substringBefore("|").toLongOrNull() ?: 0L
            }
            val entriesToRemove = sortedHistory.take(history.size - 100)
            history.removeAll(entriesToRemove.toSet())
        }
        
        // Save back to SharedPreferences
        prefs.edit().putStringSet("history", history).apply()
    }
    
    private fun getHistory(): List<HistoryEntry> {
        val prefs = getSharedPreferences("browser_history", Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("history", mutableSetOf()) ?: setOf()
        
        return historySet.mapNotNull { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size >= 3) {
                val timestamp = parts[0].toLongOrNull() ?: 0L
                HistoryEntry(timestamp, parts[1], parts[2])
            } else {
                null
            }
        }.sortedByDescending { it.timestamp } // Sort by most recent first
    }
    
    data class HistoryEntry(
        val timestamp: Long,
        val title: String,
        val url: String
    )

    private fun extractDomain(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun updateTabsCount(design: BrowserDesign) {
        val tabCount = tabs.size
        val tabCountText = if (tabCount > 0) "⓿".replace("0", tabCount.toString()) else "⓪"
        design.tabsCountButton.contentDescription = "打开的页签数量: $tabCount"
        // We'll update the button text to show the tab count
        // Since we can't directly change the icon to show the number, we'll keep the icon
        // but we could use a different approach if needed
    }
    
    private fun showTabsManagementPopup(design: BrowserDesign) {
        // Create a popup window to show all tabs
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            setPadding(16, 16, 16, 16)
        }

        // Add each tab as a view in the popup
        tabs.forEachIndexed { index, tab ->
            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 8, 8, 8)
            }
            
            val tabTitle = TextView(this).apply {
                text = tab.title
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f
                )
                gravity = Gravity.CENTER_VERTICAL
            }
            
            val switchButton = Button(this).apply {
                text = "切换"
                setOnClickListener {
                    switchToTab(design, index)
                }
            }
            
            val closeButton = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setOnClickListener {
                    closeTab(design, index)
                }
            }
            
            tabView.addView(tabTitle)
            tabView.addView(switchButton)
            tabView.addView(closeButton)
            popupView.addView(tabView)
        }

        // Create popup window
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
            isOutsideTouchable = true
            isFocusable = true
        }

        // Show the popup window near the tabs count button
        val location = IntArray(2)
        design.tabsCountButton.getLocationOnScreen(location)
        popupWindow.showAtLocation(
            design.root,
            Gravity.NO_GRAVITY,
            location[0],
            location[1] - popupWindow.height
        )
    }

    override fun onBackPressed() {
        val currentTab = tabs.getOrNull(currentTabIndex)
        if (currentTab?.webView?.canGoBack() == true) {
            currentTab.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    override fun onNewIntent(intent: android.content.Intent?) {
    super.onNewIntent(intent)
    // Handle the new intent here
    // For example, you might want to load a new URL or refresh the current page
    val currentTab = tabs.getOrNull(currentTabIndex)
    if (currentTab != null) {
        // Bring the browser to front
        currentTab.webView.requestFocus()
    }
}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            // Handle file chooser result
            val webView = tabs.getOrNull(currentTabIndex)?.webView
            if (webView != null) {
                val webChromeClient = webView.webChromeClient
                // Note: We can't directly access the filePathCallback from the WebChromeClient
                // In a real implementation, you would need to store the callback in a way that
                // it can be accessed here, or use a different approach to handle file uploads
            }
        }
    }
}
