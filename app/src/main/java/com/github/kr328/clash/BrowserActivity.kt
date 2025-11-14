package com.github.kr328.clash

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.github.kr328.clash.design.BrowserDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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
    
    // JavaScript interface to handle blob downloads
    inner class BlobDownloadInterface {
        @android.webkit.JavascriptInterface
        fun onBlobDataReady(dataUrl: String, contentDisposition: String, mimeType: String) {
            // Extract filename from contentDisposition
            var filename = "download"
            if (contentDisposition.contains("filename=")) {
                val startIndex = contentDisposition.indexOf("filename=") + 9
                val endIndex = contentDisposition.indexOf(";", startIndex)
                filename = if (endIndex > startIndex) {
                    contentDisposition.substring(startIndex, endIndex).replace("\"", "")
                } else {
                    contentDisposition.substring(startIndex).replace("\"", "")
                }
            }
            
            // Save data URL to file
            saveDataUrlToFile(dataUrl, filename, mimeType)
        }
    }
    
    private fun saveDataUrlToFile(dataUrl: String, filename: String, mimeType: String) {
        try {
            // Remove data URL prefix (e.g., "data:image/png;base64,")
            val base64Data = dataUrl.substring(dataUrl.indexOf(",") + 1)
            val data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            
            // Save to app's specific downloads directory for consistency
            val downloadsDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "cfawb")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val file = java.io.File(downloadsDir, filename)
            
            val fos = java.io.FileOutputStream(file)
            fos.write(data)
            fos.close()
            
            // Notify media scanner
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
                null
            )
            
            // Also add to DownloadManager for better integration with system downloads
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadUri = Uri.fromFile(file)
            val request = DownloadManager.Request(downloadUri).apply {
                setTitle(filename)
                setDescription("Downloaded from browser (blob)")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(file))
            }
            downloadManager.enqueue(request)
            
            runOnUiThread {
                Toast.makeText(this, "文件已下载: $filename", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                val errorMessage = "下载失败: ${e.message ?: "Unknown error"}"
                Log.e("BrowserActivity", "Blob download error", e)
                Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

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
            try {
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (currentTab?.webView?.canGoBack() == true) {
                    currentTab.webView.goBack()
                }
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in back button click", e)
            }
        }

        design.forwardButton.setOnClickListener {
            try {
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (currentTab?.webView?.canGoForward() == true) {
                    currentTab.webView.goForward()
                }
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in forward button click", e)
            }
        }

        design.reloadButton.setOnClickListener {
            try {
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (isLoading) {
                    currentTab?.webView?.stopLoading()
                } else {
                    currentTab?.webView?.reload()
                }
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in reload button click", e)
            }
        }

        design.newTabButton.setOnClickListener {
            try {
                createNewTab(design, "https://www.google.com")
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in new tab button click", e)
            }
        }

        // Use downloadMenuButton instead of downloadButton which doesn't exist
        design.downloadMenuButton.setOnClickListener {
            try {
                // Open download management activity
                val intent = android.content.Intent(this, DownloadManagerActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in download menu button click", e)
            }
        }

        design.historyMenuButton.setOnClickListener {
            try {
                // Open history management activity
                val intent = android.content.Intent(this, HistoryManagerActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in history menu button click", e)
            }
        }

        // Use PopupMenu instead of PopupWindow to avoid view parent issues
        design.menuButton.setOnClickListener {
            try {
                val popup = android.widget.PopupMenu(this, design.menuButton)
                val inflater = popup.menuInflater
                // Since we don't have a menu resource, we'll create menu items programmatically
                popup.menu.add("关闭").setOnMenuItemClickListener { 
                    // Close the menu by just returning true
                    finish()
                    true
                }
                popup.menu.add("历史").setOnMenuItemClickListener { 
                    val intent = Intent(this@BrowserActivity, HistoryManagerActivity::class.java)
                    startActivity(intent)
                    true 
                }
                popup.menu.add("下载").setOnMenuItemClickListener { 
                    val intent = Intent(this@BrowserActivity, DownloadManagerActivity::class.java)
                    startActivity(intent)
                    true 
                }
                popup.menu.add("返回代理页面").setOnMenuItemClickListener { 
                    // Return to the main activity (proxy settings) without minimizing the app
                    val intent = Intent(this@BrowserActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    true 
                }
                
                popup.show()
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error showing popup menu", e)
            }
        }

        design.closeMenuButton.setOnClickListener {
            // Close the popup menu by dismissing the popup window
            // We don't want to finish the activity here, just close the menu
            // PopupMenu auto-dismisses, no action needed
        }

        design.settingsMenuButton.setOnClickListener {
            try {
                // Navigate to proxy settings page without closing browser
                // Just minimize the browser activity to background
                moveTaskToBack(true)
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in settings menu button click", e)
            }
        }

        design.tabsCountButton.setOnClickListener {
            try {
                // Show tabs management popup
                showTabsManagementPopup(design)
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in tabs count button click", e)
            }
        }

        design.urlInput.setOnEditorActionListener { _, actionId, event ->
            try {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    loadUrlFromInput(design)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in URL input action", e)
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
                    val layoutParams = bottomNav.layoutParams as android.view.ViewGroup.MarginLayoutParams
                    layoutParams.bottomMargin = keypadHeight
                    bottomNav.layoutParams = layoutParams
                } else {
                    // Keyboard is closed - reset margins
                    val bottomNav = design.bottomNavContainer
                    val layoutParams = bottomNav.layoutParams as android.view.ViewGroup.MarginLayoutParams
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
        return try {
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
            design.webViewContainer.addView(webView)

            // Switch to the newly created tab
            switchToTab(design, tabs.size - 1)

            // Set click listener for tab switching
            val tabIndex = tabs.size - 1
            tabView.setOnClickListener {
                try {
                    switchToTab(design, tabIndex)
                } catch (e: Exception) {
                    Log.e("BrowserActivity", "Error switching tab", e)
                }
            }

            // Set click listener for tab closing
            closeTabButton.setOnClickListener {
                try {
                    closeTab(design, tabIndex)
                } catch (e: Exception) {
                    Log.e("BrowserActivity", "Error closing tab", e)
                }
            }

            // Load URL
            webView.loadUrl(url)

            // Update tabs count
            updateTabsCount(design)

            tab
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error creating new tab", e)
            // Create a fallback tab with error message
            val webView = WebView(this)
            webView.loadData("<html><body><h1>Failed to create tab</h1><p>Error: ${e.message}</p></body></html>", "text/html", "UTF-8")
            val tab = BrowserTab(webView, TextView(this).apply { text = "Error Tab" }, null, url = url)
            tabs.add(tab)
            design.webViewContainer.addView(webView)
            updateTabsCount(design)
            tab
        }
    }

    private fun switchToTab(design: BrowserDesign, index: Int) {
        try {
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
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error switching tab", e)
        }
    }

    private fun closeTab(design: BrowserDesign, index: Int) {
        try {
            if (index < 0 || index >= tabs.size) return

            val tabToClose = tabs[index]
            
            // Remove WebView from container
            design.webViewContainer.removeView(tabToClose.webView)
            
            
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
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error closing tab", e)
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
        
        // Add JavaScript interface for handling blob downloads
        webView.addJavascriptInterface(BlobDownloadInterface(), "AndroidInterface")

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
            
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                design?.progressBar?.progress = newProgress
            }
            
            // For Android 5.0+
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>?>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Save the callback for later use
                this@BrowserActivity.filePathCallback = filePathCallback
                
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
            // Check if the URL is a blob URL, which cannot be handled by DownloadManager directly
            if (url.startsWith("blob:")) {
                // For blob URLs, use JavaScript to extract the blob data
                val js = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                var blob = xhr.response;
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    AndroidInterface.onBlobDataReady(reader.result, '$contentDisposition', '$mimeType');
                                };
                                reader.onerror = function() {
                                    console.error('Error reading blob data');
                                };
                                reader.readAsDataURL(blob);
                            } else {
                                console.error('Failed to fetch blob: ' + xhr.status);
                            }
                        };
                        xhr.onerror = function() {
                            console.error('Network error while fetching blob');
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                // Use internal download via HTTP client to go through proxy
                downloadFileViaApp(url, userAgent, contentDisposition, mimeType)
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
        try {
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
                    // Set click listener for the entire row to switch to the tab
                    setOnClickListener {
                        try {
                            switchToTab(design, index)
                        } catch (e: Exception) {
                            Log.e("BrowserActivity", "Error switching tab from popup", e)
                        }
                    }
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
                
                // Remove the switch button since clicking the row will switch tabs
                val closeButton = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setOnClickListener { view ->
                        try {
                            // Close the tab without switching to it
                            closeTab(design, index)
                        } catch (e: Exception) {
                            Log.e("BrowserActivity", "Error closing tab from popup", e)
                        }
                    }
                    setOnTouchListener { v, event ->
                        // Stop touch event propagation to prevent switching to the tab when closing
                        event.action = android.view.MotionEvent.ACTION_CANCEL
                        false
                    }
                }
                
                tabView.addView(tabTitle)
                tabView.addView(closeButton)
                popupView.addView(tabView)
            }

            // Create popup window with increased width
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val popupWidth = (screenWidth * 0.8).toInt() // Use 80% of screen width
            
            val popupWindow = PopupWindow(
                popupView,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
                isOutsideTouchable = true
                isFocusable = true
            }

            // Show the popup window at the bottom of the screen, above the navigation bar
            popupWindow.showAtLocation(
                design.root,
                Gravity.BOTTOM or Gravity.END,
                0,
                100  // 100dp margin from bottom to keep it above the bottom navigation
            )
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error showing tabs management popup", e)
        }
    }

    override fun onBackPressed() {
        try {
            val currentTab = tabs.getOrNull(currentTabIndex)
            if (currentTab?.webView?.canGoBack() == true) {
                currentTab.webView.goBack()
            } else {
                super.onBackPressed()
            }
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error in back pressed", e)
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

    // Add a property to hold the filePathCallback at class level
    private var filePathCallback: ValueCallback<Array<Uri>?>? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            if (resultCode == RESULT_OK) {
                // Handle file chooser result
                if (filePathCallback != null) {
                    val results = if (data == null || data.data == null) {
                        null
                    } else {
                        arrayOf(data.data!!)
                    }
                    filePathCallback?.onReceiveValue(results)
                    filePathCallback = null
                }
            } else {
                // User cancelled the file selection
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }
    }
    
    private fun downloadFileViaApp(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        // Create a coroutine to handle the download in the background
        lifecycleScope.launch {
            try {
                // Create HTTP client with proxy settings to go through Clash
                val client = OkHttpClient.Builder()
                    .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 7890)))
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", userAgent)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    // Determine filename from content-disposition header or URL
                    var filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    if (filename.isEmpty()) {
                        filename = "downloaded_file"
                    }

                    // Create download directory in public downloads folder for better accessibility
                    val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cfawb")
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs()
                    }

                    // Create file with the determined name
                    val file = File(downloadDir, filename)

                    // Write response body to file
                    response.body?.byteStream()?.use { input ->
                        java.io.FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Update UI on main thread
                    Toast.makeText(this@BrowserActivity, "文件下载完成: $filename", Toast.LENGTH_SHORT).show()

                    // Notify media scanner so the file appears in system file managers
                    sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                    
                    // Also add to DownloadManager for better integration with system downloads
                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val downloadUri = Uri.fromFile(file)
                    val request = DownloadManager.Request(downloadUri).apply {
                        setTitle(filename)
                        setDescription("Downloaded from browser")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationUri(Uri.fromFile(file))
                    }
                    downloadManager.enqueue(request)
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    val errorMessage = "下载失败: ${response.code} - ${response.message}"
                    Log.e("BrowserActivity", "Download failed: ${errorMessage}, body: $errorBody")
                    Toast.makeText(this@BrowserActivity, errorMessage, Toast.LENGTH_SHORT).show()
                }
                response.close()
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMessage = "下载出错: ${e.message ?: "Unknown error"}"
                Log.e("BrowserActivity", "Download exception", e)
                Toast.makeText(this@BrowserActivity, errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
