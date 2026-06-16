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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.github.kr328.clash.design.BrowserDesign
import kotlinx.coroutines.isActive
import java.io.File
import android.view.ViewTreeObserver
import java.io.FileOutputStream

class BrowserActivity : BaseActivity<BrowserDesign>() {
    companion object {
        private const val REQUEST_CODE_FILE_CHOOSER = 1001
        private const val REQUEST_CODE_STORAGE_PERMISSIONS = 1002
    }
    
    private data class BrowserTab(
            val webView: WebView,
            var title: String = "新标签页",
            var url: String = ""
        )
    
    // JavaScript interface to handle blob downloads (local blob URLs from JavaScript)
    inner class BlobDownloadInterface {
        @android.webkit.JavascriptInterface
        fun onBlobDataReady(dataUrl: String, contentDisposition: String, mimeType: String) {
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
            saveDataUrlToFile(dataUrl, filename, mimeType)
        }
    }
    
    private fun saveDataUrlToFile(dataUrl: String, filename: String, mimeType: String) {
        try {
            val commaIndex = dataUrl.indexOf(",")
            if (commaIndex == -1) {
                throw IllegalArgumentException("Invalid data URL format")
            }
            val base64Data = dataUrl.substring(commaIndex + 1)
            val data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            
            val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cfawb")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val file = File(downloadsDir, filename)
            
            val fos = FileOutputStream(file)
            fos.write(data)
            fos.close()
            
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
                null
            )
            
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadUri = Uri.fromFile(file)
            val request = DownloadManager.Request(downloadUri).apply {
                setTitle(filename)
                setDescription("浏览器下载完成")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(file))
            }
            downloadManager.enqueue(request)
            
            runOnUiThread {
                Toast.makeText(this, "文件已下载: $filename", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun copyToClipboard(message: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("BrowserActivity Log", message)
            clipboard.setPrimaryClip(clip)
            Log.d("BrowserActivity", "Copied to clipboard: $message")
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Failed to copy to clipboard", e)
        }
    }
    
    private fun showProtocolHandlerDialog(protocolUrl: String, webView: WebView?) {
        try {
            val appName = getAppNameForProtocol(protocolUrl)
            val message = "此链接需要在 $appName 应用中打开。\n\n链接: $protocolUrl\n\n是否允许打开？"
            
            android.app.AlertDialog.Builder(this)
                .setTitle("打开外部应用")
                .setMessage(message)
                .setPositiveButton("允许") { _, _ ->
                    tryOpenExternalApp(protocolUrl)
                }
                .setNegativeButton("取消") { dialog, _ ->
                    dialog.dismiss()
                    // 用户取消时，保持在当前页面不做任何操作
                    Log.d("BrowserActivity", "User cancelled opening external app, staying on current page")
                }
                .setOnCancelListener {
                    // 对话框被取消时，也保持在当前页面
                    Log.d("BrowserActivity", "Dialog cancelled, staying on current page")
                }
                .setCancelable(true)
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
            protocolUrl.startsWith("qq://") -> "QQ"
            protocolUrl.startsWith("mqq://") -> "手机QQ"
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
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            // 检查是否有应用可以处理此意图
            val packageManager = packageManager
            val activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            
            if (activities.isNotEmpty()) {
                startActivity(intent)
                Log.d("BrowserActivity", "Successfully launched external app")
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

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = 0
    private var isLoading = false
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var webViewParent: FrameLayout? = null

    private var designRef: BrowserDesign? = null

    override suspend fun main() {
        val design = BrowserDesign(this)
        designRef = design

        setContentDesign(design)

        setupProxy()

        // 初始化下拉刷新布局
        setupSwipeRefreshLayout(design)

        // 请求文件存储权限
        requestStoragePermissions()

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
                if (currentTab != null) {
                    if (isLoading) {
                        Log.d("BrowserActivity", "Stopping loading in current tab ($currentTabIndex)")
                        currentTab.webView.stopLoading()
                    } else {
                        Log.d("BrowserActivity", "Reloading current tab ($currentTabIndex): ${currentTab.webView.url}")
                        currentTab.webView.reload()
                    }
                } else {
                    Log.w("BrowserActivity", "No current tab available for reload")
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

        

        // Use PopupMenu instead of PopupWindow to avoid view parent issues
        design.menuButton.setOnClickListener {
            try {
                val popup = PopupMenu(this, design.menuButton)
                // Create menu items programmatically
                popup.menu.add("关闭").setOnMenuItemClickListener { 
                    finish()
                    true
                }
                popup.menu.add("返回代理页面").setOnMenuItemClickListener { 
                    try {
                        Log.d("BrowserActivity", "Switching to MainActivity without finishing BrowserActivity")
                        val intent = Intent(this@BrowserActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("BrowserActivity", "Error switching to MainActivity", e)
                    }
                    true 
                }
                
                popup.show()
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error showing popup menu", e)
            }
        }

        design.closeMenuButton.setOnClickListener {
            // Close the popup menu by dismissing the popup window
            // PopupMenu auto-dismisses, no action needed
        }

        design.settingsMenuButton.setOnClickListener {
            Log.d("BrowserActivity", "Settings button clicked")
            try {
                val intent = Intent(this@BrowserActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(intent)
                moveTaskToBack(false)
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in settings menu button click", e)
            }
        }

        design.tabsCountButton.setOnClickListener {
            try {
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

        // Add keyboard visibility listener
        val rootView = findViewById<View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rect = android.graphics.Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.height
                val keypadHeight = screenHeight - rect.bottom

                if (keypadHeight > screenHeight * 0.15) {
                    val bottomNav = design.bottomNavContainer
                    val layoutParams = bottomNav.layoutParams as ViewGroup.MarginLayoutParams
                    layoutParams.bottomMargin = keypadHeight
                    bottomNav.layoutParams = layoutParams
                } else {
                    val bottomNav = design.bottomNavContainer
                    val layoutParams = bottomNav.layoutParams as ViewGroup.MarginLayoutParams
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
            setupWebView(webView, design)

            val tab = BrowserTab(webView, url = url)
            tabs.add(tab)

            // Switch to the newly created tab (which will add the WebView to the container)
            switchToTab(design, tabs.size - 1)

            // Load URL
            webView.loadUrl(url)

            // Update tabs count
            updateTabsCount(design)
            
            // 更新前进后退按钮状态
            updateNavigationButtons(design)

            tab
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error creating new tab", e)
            // Create a fallback tab with error message
            val webView = WebView(this)
            webView.loadData("<html><body><h1>Failed to create tab</h1><p>Error: ${e.message}</p></body></html>", "text/html", "UTF-8")
            val tab = BrowserTab(webView, url = url)
            tabs.add(tab)
            switchToTab(design, tabs.size - 1)
            updateTabsCount(design)
            tab
        }
    }

    private fun switchToTab(design: BrowserDesign, index: Int) {
        try {
            if (index < 0 || index >= tabs.size) return

            val previousTab = tabs.getOrNull(currentTabIndex)
            val newTab = tabs[index]

            // Use webViewParent (inside SwipeRefreshLayout) as the actual WebView container
            val container = webViewParent
            if (container == null) {
                Log.e("BrowserActivity", "webViewParent is null, cannot switch tab")
                return
            }

            // Remove current WebView if it's attached
            if (previousTab != null && previousTab.webView.parent == container) {
                container.removeView(previousTab.webView)
            }

            // Add new WebView if not already attached, or reattach if attached elsewhere
            if (newTab.webView.parent == null) {
                newTab.webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                container.addView(newTab.webView)
            } else if (newTab.webView.parent != container) {
                (newTab.webView.parent as? ViewGroup)?.removeView(newTab.webView)
                newTab.webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                container.addView(newTab.webView)
            }

            currentTabIndex = index

            // 修复地址栏不更新问题：优先使用 WebView 当前 URL，否则使用存储的 URL
            val currentUrl = newTab.webView.url
            design.urlInput.setText(if (!currentUrl.isNullOrEmpty()) currentUrl else newTab.url)

            // 同步存储的 URL
            if (!currentUrl.isNullOrEmpty() && currentUrl != newTab.url) {
                newTab.url = currentUrl
            }

            // 更新前进后退按钮状态
            updateNavigationButtons(design)
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error switching tab", e)
        }
    }

    private fun closeTab(design: BrowserDesign, index: Int) {
        try {
            if (index < 0 || index >= tabs.size) return

            val tabToClose = tabs[index]

            // 从正确的容器中移除 WebView
            webViewParent?.removeView(tabToClose.webView)

            // 移除滚动监听器防止内存泄漏（OnScrollChangeListener 会在 WebView.destroy 时自动清理）

            // 正确销毁 WebView 释放 native 资源
            tabToClose.webView.stopLoading()
            tabToClose.webView.removeAllViews()
            tabToClose.webView.destroy()

            // Remove tab from tabs list
            tabs.removeAt(index)

            // If we closed the current tab, switch to another tab
            if (index == currentTabIndex) {
                if (tabs.isNotEmpty()) {
                    val newTabIndex = if (index > 0) index - 1 else 0
                    switchToTab(design, newTabIndex)
                } else {
                    // No tabs left, create a new one
                    createNewTab(design, "https://www.google.com")
                }
            } else if (index < currentTabIndex) {
                currentTabIndex--
            }

            // Update tabs count
            updateTabsCount(design)
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error closing tab", e)
        }
    }

    private fun setupWebView(webView: WebView, design: BrowserDesign) {
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

        // 设置WebView的滚动监听，用于控制下拉刷新
        // 使用 OnScrollChangeListener（API 23+），确保 WebView 内部滚动正确触发
        val scrollListener = View.OnScrollChangeListener { _, _, _, _, _ ->
            val canScrollUp = webView.canScrollVertically(-1)
            swipeRefreshLayout?.isEnabled = !canScrollUp
        }
        webView.setOnScrollChangeListener(scrollListener)
        webView.setTag(scrollListener)
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                Log.d("BrowserActivity", "shouldOverrideUrlLoading: $url")
                
                if (url != null && (url.startsWith("baiduboxapp://") || url.startsWith("weixin://") ||
                        url.startsWith("alipay://") || url.startsWith("taobao://") ||
                        url.startsWith("mqq://") || url.startsWith("zhihu://") ||
                        url.startsWith("douyin://") || url.startsWith("tiktok://") ||
                        url.startsWith("tg://") || url.startsWith("intent://"))) {
                    Log.d("BrowserActivity", "Intercepted external protocol URL: $url")
                    try {
                        showProtocolHandlerDialog(url, view)
                        return true
                    } catch (e: Exception) {
                        Log.e("BrowserActivity", "Error processing external protocol URL", e)
                        return true
                    }
                } else if (url != null && (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("market://"))) {
                    tryOpenExternalApp(url)
                    return true
                }
                return false
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                design.progressBar.visibility = View.VISIBLE

                val currentIndex = tabs.indexOfFirst { it.webView == view }
                if (currentIndex >= 0) {
                    val title = url?.let { extractDomain(it) } ?: "加载中..."
                    tabs[currentIndex].title = title
                    tabs[currentIndex].url = url ?: ""

                    if (currentIndex == currentTabIndex) {
                        design.urlInput.setText(url)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                design.progressBar.visibility = View.GONE
                swipeRefreshLayout?.isRefreshing = false

                val currentIndex = tabs.indexOfFirst { it.webView == view }
                if (currentIndex >= 0) {
                    val title = view?.title?.takeIf { it.isNotEmpty() } ?: url?.let { extractDomain(it) } ?: "新标签页"
                    tabs[currentIndex].title = title
                    tabs[currentIndex].url = url ?: tabs[currentIndex].url

                    if (currentIndex == currentTabIndex) {
                        design.urlInput.setText(url)
                        // 修复：使用安全调用，避免 design 为 null 导致崩溃
                        design.let { updateNavigationButtons(it) }
                    }

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
                design.progressBar.visibility = View.GONE
                val url = request?.url?.toString()
                Log.e("BrowserActivity", "Loading error for URL: $url, error: ${error?.description}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                design.progressBar.progress = newProgress
            }
            
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>?>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@BrowserActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                try {
                    startActivityForResult(intent, REQUEST_CODE_FILE_CHOOSER)
                } catch (e: Exception) {
                    this@BrowserActivity.filePathCallback = null
                    filePathCallback.onReceiveValue(null)
                    return false
                }
                return true
            }
        }
        
        // Add JavaScript interface for handling blob downloads
        webView.addJavascriptInterface(BlobDownloadInterface(), "AndroidInterface")

        // Download listener - handle blob URLs and normal downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (url.startsWith("blob:")) {
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
                                    if (reader.result) {
                                        AndroidInterface.onBlobDataReady(reader.result, '$contentDisposition', '$mimeType');
                                    }
                                };
                                reader.readAsDataURL(blob);
                            }
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                downloadFileViaApp(url, userAgent, contentDisposition, mimeType)
            }
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            try {
                Log.d("BrowserActivity", "Back key pressed")
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (currentTab?.webView?.canGoBack() == true) {
                    currentTab.webView.goBack()
                    return true
                } else {
                    Log.d("BrowserActivity", "Cannot go back further, switching to MainActivity")
                    val intent = Intent(this@BrowserActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    startActivity(intent)
                    moveTaskToBack(false)
                    return true
                }
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error handling back key", e)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupProxy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:7890")
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
            if (currentTab != null) {
                Log.d("BrowserActivity", "Loading URL in current tab ($currentTabIndex): $url")
                currentTab.webView.loadUrl(url)
                currentTab.url = url
            } else {
                Log.w("BrowserActivity", "No current tab available for URL loading")
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(design.urlInput.windowToken, 0)
        }
    }

    private fun saveToHistory(title: String, url: String) {
        val prefs = getSharedPreferences("browser_history", Context.MODE_PRIVATE)
        val history = prefs.getStringSet("history", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val timestamp = System.currentTimeMillis()
        val historyEntry = "$timestamp|$title|$url"
        history.add(historyEntry)
        if (history.size > 100) {
            val sortedHistory = history.sortedBy { entry ->
                entry.substringBefore("|").toLongOrNull() ?: 0L
            }
            val entriesToRemove = sortedHistory.take(history.size - 100)
            history.removeAll(entriesToRemove.toSet())
        }
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
        }.sortedByDescending { it.timestamp }
    }
    
    data class HistoryEntry(
        val timestamp: Long,
        val title: String,
        val url: String
    )

    private fun extractDomain(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun updateTabsCount(design: BrowserDesign) {
        val tabCount = tabs.size
        design.tabsCountButton.contentDescription = "打开的页签数量: $tabCount"
    }

    private fun setupSwipeRefreshLayout(design: BrowserDesign) {
        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            setOnRefreshListener {
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (currentTab != null) {
                    Log.d("BrowserActivity", "下拉刷新: 重新加载页面 ${currentTab.webView.url}")
                    currentTab.webView.reload()
                } else {
                    isRefreshing = false
                }
            }
            setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light
            )
            isEnabled = false
        }

        // 创建一个 FrameLayout 作为 SwipeRefreshLayout 的唯一子 View
        // WebView 将添加到这个 FrameLayout 中，而不是直接添加到 SwipeRefreshLayout
        // 这样可以避免 SwipeRefreshLayout 只支持一个子 View 的限制
        webViewParent = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        swipeRefreshLayout!!.addView(webViewParent)

        // 将现有的 webViewContainer 中的子视图转移到 SwipeRefreshLayout 中
        val currentChildren = mutableListOf<View>()
        for (i in 0 until design.webViewContainer.childCount) {
            currentChildren.add(design.webViewContainer.getChildAt(i))
        }
        design.webViewContainer.removeAllViews()
        design.webViewContainer.addView(swipeRefreshLayout)
        currentChildren.forEach { child ->
            webViewParent?.addView(child)
        }
    }

    private fun updateNavigationButtons(design: BrowserDesign) {
        val currentTab = tabs.getOrNull(currentTabIndex)
        design.backButton.isEnabled = currentTab?.webView?.canGoBack() == true
        design.forwardButton.isEnabled = currentTab?.webView?.canGoForward() == true
    }
    
    private fun showTabsManagementPopup(design: BrowserDesign) {
        try {
            val popupView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                setPadding(16, 16, 16, 16)
            }

            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val popupWidth = (screenWidth * 0.8).toInt()

            val popupWindow = PopupWindow(
                popupView,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
                isOutsideTouchable = true
                isFocusable = true
            }

            tabs.forEachIndexed { index, tab ->
                val tabView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 8, 8, 8)
                    setOnClickListener {
                        try {
                            switchToTab(design, index)
                            popupWindow.dismiss()
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

                val closeButton = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setOnClickListener { view ->
                        view?.cancelPendingInputEvents()
                        closeTab(design, index)
                        popupWindow.dismiss()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                v.isPressed = true
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                true
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                v.isPressed = false
                                v.performClick()
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                true
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                v.isPressed = false
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                true
                            }
                            else -> false
                        }
                    }
                }

                tabView.addView(tabTitle)
                tabView.addView(closeButton)
                popupView.addView(tabView)
            }

            popupWindow.showAtLocation(
                design.root,
                Gravity.BOTTOM or Gravity.END,
                0,
                100
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
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newUrl = intent?.getStringExtra("url")
        // 从外部 intent 接收 URL 时创建新标签，不覆盖当前标签
        if (!newUrl.isNullOrBlank()) {
            designRef?.let { createNewTab(it, newUrl) }
        } else {
            tabs.getOrNull(currentTabIndex)?.webView?.requestFocus()
        }
    }
    
    override fun onResume() {
        super.onResume()
        val currentTab = tabs.getOrNull(currentTabIndex)
        currentTab?.webView?.requestFocus()
    }

    private var filePathCallback: ValueCallback<Array<Uri>?>? = null

    private fun requestStoragePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val permissionsToRequest = permissions.filter { 
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsToRequest, REQUEST_CODE_STORAGE_PERMISSIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_STORAGE_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.d("BrowserActivity", "存储权限已授予")
                } else {
                    Log.w("BrowserActivity", "存储权限被拒绝")
                    Toast.makeText(this, "文件上传和下载功能可能需要存储权限才能正常工作", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            if (resultCode == Activity.RESULT_OK) {
                if (filePathCallback != null) {
                    val results = if (data == null) {
                        null
                    } else {
                        if (data.clipData != null) {
                            val uris = mutableListOf<Uri>()
                            val clipData = data.clipData!!
                            for (i in 0 until clipData.itemCount) {
                                val uri = clipData.getItemAt(i).uri
                                uris.add(uri)
                            }
                            uris.toTypedArray()
                        } else if (data.data != null) {
                            arrayOf(data.data!!)
                        } else {
                            null
                        }
                    }
                    filePathCallback?.onReceiveValue(results)
                    filePathCallback = null
                }
            } else {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }
    }
    
    private fun downloadFileViaApp(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            var filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (filename.isEmpty()) {
                filename = "downloaded_file"
            }
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription("浏览器下载中")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "cfawb/$filename")
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }
            downloadManager.enqueue(request)
            Toast.makeText(this@BrowserActivity, "开始下载: $filename", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = "下载出错: ${e.message ?: "Unknown error"}"
            Log.e("BrowserActivity", "Download exception", e)
            Toast.makeText(this@BrowserActivity, errorMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
