package com.github.kr328.clash

import android.app.DownloadManager
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AlertDialog
import android.graphics.Typeface
import android.graphics.Color
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.github.kr328.clash.design.BrowserDesign
import kotlinx.coroutines.isActive
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
            val filename = URLUtil.guessFileName("", contentDisposition, mimeType)
            
            // Save data URL to file
            saveDataUrlToFile(dataUrl, filename, mimeType)
        }
    }
    
    private fun saveDataUrlToFile(dataUrl: String, filename: String, mimeType: String) {
        try {
            val logMessage = "Saving blob data to file: $filename"
            Log.d("BrowserActivity", logMessage)
            
            // Parse data URL
            val commaIndex = dataUrl.indexOf(',')
            if (commaIndex == -1) {
                Log.e("BrowserActivity", "Invalid data URL format")
                return
            }
            
            val base64Data = dataUrl.substring(commaIndex + 1)
            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            
            // Create download directory
            val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cfawb")
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            // Save to file
            val outputFile = File(downloadDir, filename)
            outputFile.writeBytes(decodedBytes)
            
            Log.d("BrowserActivity", "Successfully saved blob data to: ${outputFile.absolutePath}")
            
            // Show toast to user
            runOnUiThread {
                Toast.makeText(this@BrowserActivity, "文件已保存: $filename", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error saving blob data to file", e)
            runOnUiThread {
                Toast.makeText(this@BrowserActivity, "保存文件失败: ${e.message}", Toast.LENGTH_LONG).show()
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

        // Create first tab - check if we have a URL from the intent
        val initialUrl = intent.getStringExtra("url") ?: "https://www.google.com"
        createNewTab(design, initialUrl)

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
                currentTab?.webView?.reload()
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

        design.menuButton.setOnClickListener {
            try {
                toggleMenu(design)
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in menu button click", e)
            }
        }

        design.closeMenuButton.setOnClickListener {
            try {
                hideMenu(design)
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in close menu button click", e)
            }
        }

        design.settingsMenuButton.setOnClickListener {
            try {
                hideMenu(design)
                finish() // Return to main activity
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in settings menu button click", e)
            }
        }

        design.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
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

        // Create a container for the tab with close button
        val tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundResource(android.R.drawable.btn_default_small)
            
            val tabText = TextView(this@BrowserActivity).apply {
                text = "New Tab"
                textSize = 12f
                setTextColor(resources.getColor(android.R.color.black))
                maxWidth = 200
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            
            val closeButton = Button(this@BrowserActivity).apply {
                text = "×"
                textSize = 12f
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    val tabIndex = tabs.indexOfFirst { it.tabView == this@apply.parent }
                    if (tabIndex >= 0) {
                        closeTab(design, tabIndex)
                    }
                }
            }
            
            addView(tabText, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(closeButton, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tab = BrowserTab(webView, tabContainer, tabContainer)
        tabs.add(tab)
        currentTabIndex = tabs.size - 1
        
        // Add WebView to container
        design.webViewContainer.addView(webView)
        
        // Load URL
        webView.loadUrl(url)
        
        return tab
    }

    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            
            // Enable zoom controls
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            
            // Enable wide viewport for better mobile browsing
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // Media playback settings
            mediaPlaybackRequiresUserGesture = false
            
            // Mixed content mode for HTTP/HTTPS compatibility
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        
        // Add JavaScript interface for handling blob downloads
        webView.addJavascriptInterface(BlobDownloadInterface(), "AndroidInterface")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                Log.d("BrowserActivity", "shouldOverrideUrlLoading: $url")
                
                // 处理特殊协议，如 baiduboxapp://
                if (url != null && url.startsWith("baiduboxapp://")) {
                    Log.d("BrowserActivity", "Intercepted baiduboxapp URL: $url")
                    try {
                        // 显示对话框询问用户是否要打开对应的应用
                        showProtocolHandlerDialog(url, view)
                        return true
                    } catch (e: Exception) {
                        Log.e("BrowserActivity", "Error processing baiduboxapp URL", e)
                        return true
                    }
                }
                
                return false
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                design?.progressBar?.visibility = android.view.View.VISIBLE
                
                // Update URL input field
                design?.urlInput?.setText(url)
                
                // Update tab title
                val currentIndex = tabs.indexOfFirst { it.webView == view }
                if (currentIndex >= 0) {
                    val title = url?.let { extractDomain(it) } ?: "Loading..."
                    // tabView is a LinearLayout, we need to find the TextView inside it
                    val tabContainer = tabs[currentIndex].tabView as LinearLayout
                    val tabText = tabContainer.getChildAt(0) as TextView
                    tabText.text = title
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
                    // tabView is a LinearLayout, we need to find the TextView inside it
                    val tabContainer = tabs[currentIndex].tabView as LinearLayout
                    val tabText = tabContainer.getChildAt(0) as TextView
                    tabText.text = title
                    tabs[currentIndex].title = title
                    tabs[currentIndex].url = url ?: ""
                    
                    // Save to history
                    if (url != null) {
                        saveToHistory(title, url)
                    }
                }
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                isLoading = false
                design?.progressBar?.visibility = android.view.View.GONE
                
                val url = request?.url?.toString()
                val errorCode = error?.errorCode
                val description = error?.description?.toString()
                
                Log.e("BrowserActivity", "Loading error for URL: $url, error: $description")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                design?.progressBar?.progress = newProgress
            }
            
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val currentIndex = tabs.indexOfFirst { it.webView == view }
                if (currentIndex >= 0 && !title.isNullOrEmpty()) {
                    (tabs[currentIndex].tabView as TextView).text = title
                    tabs[currentIndex].title = title
                }
            }
            
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.let {
                    this@BrowserActivity.filePathCallback = it
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_CODE_FILE_CHOOSER)
                }
                return true
            }
        }
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            filePathCallback?.onReceiveValue(
                if (resultCode == Activity.RESULT_OK) {
                    data?.dataString?.let { arrayOf(Uri.parse(it)) }
                } else {
                    null
                }
            )
            filePathCallback = null
        }
    }

    private fun switchToTab(design: BrowserDesign, index: Int) {
        if (index < 0 || index >= tabs.size) return

        // Hide current WebView
        tabs[currentTabIndex].webView.visibility = View.GONE

        // Show selected WebView
        currentTabIndex = index
        tabs[index].webView.visibility = View.VISIBLE
        
        // Update URL input
        design.urlInput.setText(tabs[index].url)
    }

    private fun closeTab(design: BrowserDesign, index: Int) {
        if (index < 0 || index >= tabs.size) return

        val tab = tabs[index]
        
        // Remove WebView
        design.webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        
        // Remove from list
        tabs.removeAt(index)
        
        // Adjust current tab index
        if (currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.size - 1
        }
        
        // Show new current tab
        if (tabs.isNotEmpty()) {
            tabs[currentTabIndex].webView.visibility = View.VISIBLE
            design.urlInput.setText(tabs[currentTabIndex].url)
        }
        
        // Update tabs count
        updateTabsCount(design)
    }

    private fun toggleMenu(design: BrowserDesign) {
        val menuPopup = design.menuPopup
        if (menuPopup.visibility == View.GONE) {
            menuPopup.visibility = View.VISIBLE
        } else {
            menuPopup.visibility = View.GONE
        }
    }

    private fun hideMenu(design: BrowserDesign) {
        design.menuPopup.visibility = View.GONE
    }

    private fun loadUrlFromInput(design: BrowserDesign) {
        var url = design.urlInput.text.toString().trim()
        if (!TextUtils.isEmpty(url)) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            
            val currentTab = tabs.getOrNull(currentTabIndex)
            if (currentTab != null) {
                Log.d("BrowserActivity", "Loading URL in current tab ($currentTabIndex): $url")
                currentTab.webView.loadUrl(url)
                // 更新当前标签页的URL信息
                currentTab.url = url
            } else {
                Log.w("BrowserActivity", "No current tab available for URL loading")
            }
            
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(design.urlInput.windowToken, 0)
        }
    }

    private fun saveToHistory(title: String, url: String) {
        // Save to history using SharedPreferences
        val prefs = getSharedPreferences("browser_history", Context.MODE_PRIVATE)
        val history = prefs.getStringSet("history", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        // Add new entry
        val timestamp = System.currentTimeMillis()
        history.add("$timestamp,$title,$url")
        
        // Limit history size (keep last 100 entries)
        if (history.size > 100) {
            val sortedEntries = history.mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size >= 3) {
                    Triple(parts[0].toLong(), parts[1], parts[2])
                } else null
            }.sortedByDescending { it.first }
            
            history.clear()
            sortedEntries.take(100).forEach { (timestamp, title, url) ->
                history.add("$timestamp,$title,$url")
            }
        }
        
        // Save back to SharedPreferences
        prefs.edit().putStringSet("history", history).apply()
    }

    private fun getHistory(): List<HistoryEntry> {
        val prefs = getSharedPreferences("browser_history", Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("history", setOf()) ?: setOf()
        
        return historySet.mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size >= 3) {
                HistoryEntry(parts[0].toLong(), parts[1], parts[2])
            } else null
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
                setPadding(16, 16, 16, 16)
                setBackgroundColor(resources.getColor(android.R.color.white))
                
                // Header
                addView(TextView(this@BrowserActivity).apply {
                    text = "标签页管理"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 16)
                })
                
                // Tabs list
                tabs.forEachIndexed { index, tab ->
                    addView(LinearLayout(this@BrowserActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8, 8, 8, 8)
                        
                        // Tab info
                        addView(LinearLayout(this@BrowserActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            
                            addView(TextView(this@BrowserActivity).apply {
                                text = tab.title
                                textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            })
                            
                            addView(TextView(this@BrowserActivity).apply {
                                text = extractDomain(tab.url)
                                textSize = 12f
                                setTextColor(resources.getColor(android.R.color.darker_gray))
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            })
                        })
                        
                        // Switch button
                        addView(Button(this@BrowserActivity).apply {
                            text = "切换"
                            setOnClickListener {
                                switchToTab(design, index)
                                // Dismiss popup
                                (parent as? PopupWindow)?.dismiss()
                            }
                        })
                        
                        // Close button
                        addView(Button(this@BrowserActivity).apply {
                            text = "关闭"
                            setOnClickListener {
                                closeTab(design, index)
                                // Refresh tabs list
                                // This would require recreating the popup
                            }
                        })
                    })
                }
            }
            
            // Create popup window
            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                // Set animation
                animationStyle = android.R.style.Animation_Dialog
                // Show below the tabs count button
                showAsDropDown(design.tabsCountButton, 0, -design.tabsCountButton.height - 100)
            }
            
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
        val newUrl = intent?.getStringExtra("url")
        if (newUrl != null) {
            val currentTab = tabs.getOrNull(currentTabIndex)
            currentTab?.webView?.loadUrl(newUrl)
        } else {
            // Bring the browser to front
            val currentTab = tabs.getOrNull(currentTabIndex)
            currentTab?.webView?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure the current tab is properly focused when returning to the activity
        val currentTab = tabs.getOrNull(currentTabIndex)
        currentTab?.webView?.requestFocus()
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            // Determine filename from content-disposition header or URL
            var filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            
            // Create download directory
            val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cfawb")
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            // Download original URL
            downloadOriginalFile(downloadManager, url, userAgent, filename, downloadDir, mimeType)
            
            // Download proxy version
            val proxyUrl = "https://g.david525.cloudns.ch/p?u=$url"
            val proxyFilename = "d_$filename"
            downloadProxyFile(downloadManager, proxyUrl, userAgent, proxyFilename, downloadDir, mimeType)

        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = "下载出错: ${e.message ?: "Unknown error"}"
            Log.e("BrowserActivity", "Download exception", e)
            Toast.makeText(this@BrowserActivity, errorMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadOriginalFile(downloadManager: DownloadManager, url: String, userAgent: String, filename: String, downloadDir: File, mimeType: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription("Downloading from browser")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "cfawb/$filename")
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }
            downloadManager.enqueue(request)
            Toast.makeText(this@BrowserActivity, "开始下载原始文件: $filename", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error downloading original file", e)
            Toast.makeText(this@BrowserActivity, "原始文件下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadProxyFile(downloadManager: DownloadManager, proxyUrl: String, userAgent: String, filename: String, downloadDir: File, mimeType: String) {
        try {
            // 输出代理URL到日志
            Log.d("BrowserActivity", "代理下载URL: $proxyUrl")
            
            // 复制代理URL到剪贴板
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("代理下载URL", proxyUrl)
            clipboard.setPrimaryClip(clip)
            
            // 显示包含代理URL的Toast消息
            Toast.makeText(this@BrowserActivity, "代理URL已复制到剪贴板:\n$proxyUrl", Toast.LENGTH_LONG).show()
            
            val request = DownloadManager.Request(Uri.parse(proxyUrl)).apply {
                setTitle(filename)
                setDescription("Downloading proxy version: $proxyUrl")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "cfawb/$filename")
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }
            downloadManager.enqueue(request)
            Toast.makeText(this@BrowserActivity, "开始下载代理文件: $filename\nURL: $proxyUrl", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error downloading proxy file", e)
            Toast.makeText(this@BrowserActivity, "代理文件下载失败: ${e.message}\nURL: $proxyUrl", Toast.LENGTH_LONG).show()
        }
    }

    // 添加物理返回键处理
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            try {
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (currentTab?.webView?.canGoBack() == true) {
                    currentTab.webView.goBack()
                    return true
                }
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error handling back key", e)
            }
        }
        return super.onKeyDown(keyCode, event)
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

    private fun showProtocolHandlerDialog(protocolUrl: String, webView: WebView?) {
        try {
            val appName = getAppNameForProtocol(protocolUrl)
            val message = "此链接需要在 $appName 应用中打开。\n\n链接: $protocolUrl\n\n是否允许打开？"
            
            AlertDialog.Builder(this)
                .setTitle("协议链接处理")
                .setMessage(message)
                .setPositiveButton("打开", { _, _ ->
                    tryOpenExternalApp(protocolUrl)
                })
                .setNegativeButton("取消", null)
                .show()

        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error showing protocol handler dialog", e)
        }
    }

    private fun getAppNameForProtocol(protocolUrl: String): String {
        return when {
            protocolUrl.startsWith("baiduboxapp://") -> "百度"
            protocolUrl.startsWith("weixin://") -> "微信"
            protocolUrl.startsWith("alipay://") -> "支付宝"
            protocolUrl.startsWith("taobao://") -> "淘宝"
            protocolUrl.startsWith("jd://") -> "京东"
            protocolUrl.startsWith("qq://") -> "QQ"
            protocolUrl.startsWith("zhihu://") -> "知乎"
            protocolUrl.startsWith("douyin://") -> "抖音"
            protocolUrl.startsWith("tiktok://") -> "TikTok"
            else -> "相应"
        }
    }

    private fun tryOpenExternalApp(protocolUrl: String) {
        try {
            Log.d("BrowserActivity", "Attempting to open external app with URL: $protocolUrl")

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(protocolUrl))
            
            // Check if there's an app that can handle this intent
            val packageManager = packageManager
            val activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            
            if (activities.isNotEmpty()) {
                startActivity(intent)
                Log.d("BrowserActivity", "Successfully launched external app for: $protocolUrl")
            } else {
                Log.w("BrowserActivity", "No app found to handle protocol: $protocolUrl")
                runOnUiThread {
                    Toast.makeText(this@BrowserActivity, "未找到能处理此链接的应用", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error opening external app", e)
            runOnUiThread {
                Toast.makeText(this@BrowserActivity, "打开应用失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}