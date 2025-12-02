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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.github.kr328.clash.design.BrowserDesign
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.common.log.Log as ClashLog
import com.github.kr328.clash.util.logsDir
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.io.FileWriter
import java.io.IOException

class BrowserActivity : BaseActivity<BrowserDesign>() {
    companion object {
        private const val REQUEST_CODE_FILE_CHOOSER = 1001
        private const val BROWSER_LOG_TAG = "BrowserActivity"
    }
    
    // Browser logger instance - nullable for safety
    private var browserLogger: BrowserLogger? = null
    
    /**
     * 浏览器专用日志记录器 - 完全集成到标准日志系统
     */
    private open inner class BrowserLogger {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        private val logFile: File
        
        init {
            // 按照标准日志系统命名规则生成日志文件名
            val currentTime = Date()
            val fileName = "clash-${currentTime.time}.log"
            logFile = logsDir.resolve(fileName)
            initializeLogFile()
        }
        
        private fun initializeLogFile() {
            try {
                // 确保日志目录存在
                logsDir.mkdirs()
                
                if (!logFile.exists()) {
                    logFile.createNewFile()
                    writeLog("INIT", "Browser log file created: ${logFile.absolutePath}")
                    ClashLog.i("Browser log initialized at: ${logFile.absolutePath}")
                }
            } catch (e: IOException) {
                ClashLog.e("Failed to create browser log file", e)
            }
        }
        
        private fun writeLog(level: String, message: String) {
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] [$level] $message\n"
            
            try {
                FileWriter(logFile, true).use { writer ->
                    writer.append(logEntry)
                    writer.flush()
                }
            } catch (e: IOException) {
                Log.e(BROWSER_LOG_TAG, "Failed to write to browser log file", e)
            }
            
            // 同时写入系统日志
            when (level) {
                "DEBUG" -> ClashLog.d(message)
                "INFO" -> ClashLog.i(message)
                "WARN" -> ClashLog.w(message)
                "ERROR" -> ClashLog.e(message)
                else -> ClashLog.i(message)
            }
        }
        
        open fun debug(message: String) = writeLog("DEBUG", message)
        open fun info(message: String) = writeLog("INFO", message)
        open fun warn(message: String) = writeLog("WARN", message)
        open fun error(message: String, throwable: Throwable? = null) {
            val fullMessage = if (throwable != null) {
                "$message - ${throwable.message}"
            } else {
                message
            }
            writeLog("ERROR", fullMessage)
        }
        
        open fun logProxyEvent(event: String, details: Map<String, Any> = emptyMap()) {
            val detailStr = if (details.isNotEmpty()) {
                details.entries.joinToString(", ") { "${it.key}=${it.value}" }
            } else {
                ""
            }
            writeLog("PROXY", "$event${if (detailStr.isNotEmpty()) " - $detailStr" else ""}")
        }
        
        open fun logWebviewEvent(action: String, url: String? = null, details: String = "") {
            val message = StringBuilder().apply {
                append("WEBVIEW_$action")
                url?.let { append(" URL: $it") }
                if (details.isNotEmpty()) append(" Details: $details")
            }.toString()
            writeLog("WEBVIEW", message)
        }
        
        fun exportLogs(): String {
            return try {
                if (logFile.exists()) {
                    logFile.readText()
                } else {
                    "No browser logs available"
                }
            } catch (e: Exception) {
                "Error reading browser logs: ${e.message}"
            }
        }
        
        fun clearLogs() {
            try {
                if (logFile.exists()) {
                    logFile.writeText("")
                    writeLog("INIT", "Browser logs cleared")
                }
            } catch (e: Exception) {
                ClashLog.e("Failed to clear browser logs", e)
            }
        }
        
        /**
         * 获取当前日志文件的路径，用于在日志查看界面中显示
         */
        fun getLogFile(): File = logFile
        
        /**
         * 创建一个新的浏览器日志文件实例（用于外部访问）
         */
        fun createBrowserLogFile(activity: BrowserActivity): File {
            val currentTime = Date()
            val fileName = "clash-${currentTime.time}.log"
            return activity.logsDir.resolve(fileName)
        }
    }
    
    /**
     * 创建备用的简单日志记录器（当文件日志失败时使用）
     */
    private fun createFallbackLogger(): BrowserLogger {
        return object : BrowserLogger() {
            override fun debug(message: String) {
                ClashLog.d("[FALLBACK_BROWSER] $message")
            }
            
            override fun info(message: String) {
                ClashLog.i("[FALLBACK_BROWSER] $message")
            }
            
            override fun warn(message: String) {
                ClashLog.w("[FALLBACK_BROWSER] $message")
            }
            
            override fun error(message: String, throwable: Throwable?) {
                if (throwable != null) {
                    ClashLog.e("[FALLBACK_BROWSER] $message", throwable)
                } else {
                    ClashLog.e("[FALLBACK_BROWSER] $message")
                }
            }
            
            override fun logProxyEvent(event: String, details: Map<String, Any>) {
                ClashLog.i("[FALLBACK_PROXY] $event - $details")
            }
            
            override fun logWebviewEvent(action: String, url: String?, details: String) {
                ClashLog.i("[FALLBACK_WEBVIEW] $action - URL: $url - $details")
            }
        }
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
            val logMessage = "Saving blob data to file: $filename"
            Log.d("BrowserActivity", logMessage)
            
            // 复制日志信息到剪贴板
            copyToClipboard(logMessage)
            
            // Remove data URL prefix (e.g., "data:image/png;base64,")
            val commaIndex = dataUrl.indexOf(",")
            if (commaIndex == -1) {
                val errorMsg = "Invalid data URL format"
                Log.e("BrowserActivity", errorMsg)
                copyToClipboard("Blob Error: $errorMsg")
                throw IllegalArgumentException(errorMsg)
            }
            
            val base64Data = dataUrl.substring(commaIndex + 1)
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
            
            val successMessage = "Blob file saved successfully: ${file.absolutePath}"
            Log.d("BrowserActivity", successMessage)
            copyToClipboard(successMessage)
            
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
                Toast.makeText(this, "Blob文件已下载: $filename\n日志已复制到剪贴板", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = "Blob下载失败: ${e.message ?: "Unknown error"}"
            Log.e("BrowserActivity", errorMessage, e)
            copyToClipboard(errorMessage)
            runOnUiThread {
                Toast.makeText(this, "$errorMessage\n错误日志已复制到剪贴板", Toast.LENGTH_LONG).show()
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
    
    private suspend fun getSystemProxyPort(): Int {
        return try {
            ClashLog.d("BrowserActivity: Getting system proxy port...")
            // Get actual proxy port from Clash service
            val remote = Remote.service
            ClashLog.d("BrowserActivity: Remote service status - isBound: ${remote.isBound}")
            
            if (remote.isBound) {
                ClashLog.d("BrowserActivity: Remote service is bound, getting clash manager...")
                try {
                    val manager = remote.getClashManager()
                    ClashLog.d("BrowserActivity: Clash manager obtained: ${manager != null}")
                    
                    manager?.let { 
                        val override = it.queryOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Session)
                        val mixedPort = override.mixedPort
                        val tproxyPort = override.tproxyPort
                        val httpPort = override.httpPort
                        val port = mixedPort ?: tproxyPort ?: httpPort ?: 7890
                        
                        val details = mapOf(
                            "mixedPort" to (mixedPort ?: "null"),
                            "tproxyPort" to (tproxyPort ?: "null"),
                            "httpPort" to (httpPort ?: "null"),
                            "selectedPort" to port
                        )
                        ClashLog.d("BrowserActivity: Port retrieved - $details")
                        
                        runOnUiThread {
                            Toast.makeText(this@BrowserActivity, "检测到代理端口: $port", Toast.LENGTH_SHORT).show()
                        }
                        
                        ClashLog.d("Retrieved proxy port: $port (mixed: $mixedPort, tproxy: $tproxyPort, http: $httpPort)")
                        port
                    } ?: 7890.also { 
                        ClashLog.w("BrowserActivity: Clash manager is null, using default port: $it")
                        runOnUiThread {
                            Toast.makeText(this@BrowserActivity, "Clash管理器为空，使用默认端口: $it", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    ClashLog.e("BrowserActivity: Error getting clash manager", e)
                    runOnUiThread {
                        Toast.makeText(this@BrowserActivity, "获取Clash管理器失败: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                    7890
                }
            } else {
                ClashLog.w("BrowserActivity: Clash service not bound, using default port: 7890")
                runOnUiThread {
                    Toast.makeText(this@BrowserActivity, "Clash服务未绑定，使用默认端口7890", Toast.LENGTH_LONG).show()
                }
                7890
            }
        } catch (e: Exception) {
            ClashLog.e("BrowserActivity: Error getting proxy port", e)
            runOnUiThread {
                Toast.makeText(this@BrowserActivity, "获取代理端口出错: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
            7890 // Fallback to default port
        }
    }
    
    private suspend fun getClashServicePort(): Int {
        return try {
            // Check if Clash service is running
            val remote = Remote.service
            if (remote.isBound) {
                val manager = remote.getClashManager()
                manager?.let { 
                    val override = it.queryOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Session)
                    override.mixedPort ?: override.tproxyPort ?: override.httpPort ?: 7890
                } ?: -1
            } else {
                Log.w("BrowserActivity", "Clash service not running")
                -1
            }
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error getting Clash service port", e)
            -1
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

    // Add a property to hold the filePathCallback at class level
    private var filePathCallback: ValueCallback<Array<Uri>?>? = null

    override suspend fun main() {
        // 最小化初始化，逐步排查问题
        ClashLog.i("BrowserActivity: Starting main()")
        
        val design = try {
            val design = BrowserDesign(this)
            setContentDesign(design)
            ClashLog.i("BrowserActivity: Design set successfully")
            
            // 启用代理设置以解决网络连接问题
            setupProxy()
            ClashLog.i("BrowserActivity: Proxy setup initiated")
            
            // Create first tab - check if we have a URL from the intent
            val initialUrl = intent.getStringExtra("url") ?: "https://www.google.com"
            createNewTabSimple(design, initialUrl)
            ClashLog.i("BrowserActivity: Tab created successfully")
            design
        } catch (e: Exception) {
            ClashLog.e("BrowserActivity: Error in main()", e)
            throw e
        }

        // Setup UI event listeners
        design.backButton.setOnClickListener {
            try {
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (currentTab?.webView?.canGoBack() == true) {
                    currentTab.webView.goBack()
                }
            } catch (e: Exception) {
                ClashLog.e("Error in back button click", e)
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
                createNewTabSimple(design, "https://www.google.com")
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error in new tab button click", e)
            }
        }

        // Removed downloadMenuButton and historyMenuButton listeners as buttons are removed from layout

        // 使用布局文件中的menuPopup而不是PopupMenu
        design.menuButton.setOnClickListener {
            try {
                Log.d("BrowserActivity", "Menu button clicked, toggling menuPopup visibility")
                // 切换menuPopup的可见性
                if (design.menuPopup.visibility == View.GONE) {
                    design.menuPopup.visibility = View.VISIBLE
                } else {
                    design.menuPopup.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error toggling menu popup", e)
            }
        }

        // 点击菜单外部区域关闭菜单
        design.coordinatorLayout.setOnClickListener {
            if (design.menuPopup.visibility == View.VISIBLE) {
                design.menuPopup.visibility = View.GONE
            }
        }

        design.closeMenuButton.setOnClickListener {
            // 关闭浏览器
            try {
                Log.d("BrowserActivity", "Close browser")
                finish()
            } catch (e: Exception) {
                Log.e("BrowserActivity", "Error closing", e)
            }
        }

        design.settingsMenuButton.setOnClickListener {
            Log.d("BrowserActivity", "Settings button clicked")
            try {
                // Close menu popup first
                design.menuPopup.visibility = View.GONE
                // 切换到MainActivity但不结束BrowserActivity
                val intent = Intent(this@BrowserActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(intent)
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

    private fun createNewTabSimple(design: BrowserDesign, url: String): BrowserTab {
        return try {
            val webView = WebView(this)
            setupWebViewSimple(webView)

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

            // Set click listener for tab closing - capture tabIndex in a final variable to avoid closure issues
            val closeTabIndex = tabIndex
            closeTabButton.setOnClickListener {
                try {
                    closeTab(design, closeTabIndex)
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

            // Update URL input with current URL
            design.urlInput.setText(newTab.webView.url ?: "")
            
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
                    createNewTabSimple(design, "https://www.google.com")
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

    private fun setupWebViewSimple(webView: WebView) {
        ClashLog.d("BrowserActivity: Setting up WebView...")

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
        
        ClashLog.d("BrowserActivity: WebView settings configured")
        
        // Add JavaScript interface for handling blob downloads
        webView.addJavascriptInterface(BlobDownloadInterface(), "AndroidInterface")
        ClashLog.d("BrowserActivity: JavaScript interface added")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                ClashLog.d("BrowserActivity: URL loading: $url")
                ClashLog.d("shouldOverrideUrlLoading: $url")
                
                // 处理特殊协议，如 baiduboxapp://
                if (url != null && url.startsWith("baiduboxapp://")) {
                    ClashLog.d("BrowserActivity: Intercepted protocol: $url")
                    ClashLog.d("Intercepted baiduboxapp URL: $url")
                    try {
                        // 显示对话框询问用户是否要打开对应的应用
                        showProtocolHandlerDialog(url, view)
                        return true
                    } catch (e: Exception) {
                        ClashLog.e("BrowserActivity: Error processing baiduboxapp URL", e)
                        ClashLog.e("Error processing baiduboxapp URL", e)
                        return true
                    }
                }
                
                return false
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                ClashLog.d("BrowserActivity: Page started: $url")
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
                ClashLog.d("BrowserActivity: Page finished: $url, Title: ${view?.title}")
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
                    
                    ClashLog.d("BrowserActivity: Tab updated: index=$currentIndex, title='$title', url='$url'")
                    
                    // Save to history
                    if (url != null) {
                        saveToHistory(title, url)
                        ClashLog.d("BrowserActivity: Saved to history: '$title' -> '$url'")
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val url = request?.url?.toString()
                val errorCode = error?.errorCode
                val description = error?.description?.toString()
                
                ClashLog.e("BrowserActivity: Received error - URL: $url, ErrorCode: $errorCode, Description: $description")
                
                isLoading = false
                design?.progressBar?.visibility = android.view.View.GONE
                ClashLog.e("Loading error for URL: $url, error: ${error?.description}")
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
                // For blob URLs, use JavaScript to extract the blob data and convert to data URL
                val js = """
                    (function() {
                        console.log('Processing blob download: $url');
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                var blob = xhr.response;
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    if (reader.result) {
                                        console.log('Blob converted to data URL successfully');
                                        AndroidInterface.onBlobDataReady(reader.result, '$contentDisposition', '$mimeType');
                                    } else {
                                        console.error('Failed to convert blob to data URL');
                                    }
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
                webView.evaluateJavascript(js) { result ->
                    Log.d("BrowserActivity", "JavaScript execution result: $result")
                }
            } else {
                // Use system download manager for regular URLs
                downloadFileViaApp(url, userAgent, contentDisposition, mimeType)
            }
        }
    }
    
    // 添加物理返回键处理
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            try {
                Log.d("BrowserActivity", "Back key pressed")
                // 检查当前WebView是否能后退
                val currentTab = tabs.getOrNull(currentTabIndex)
                if (currentTab?.webView?.canGoBack() == true) {
                    currentTab.webView.goBack()
                    return true
                } else {
                    // 不能后退时，切换到MainActivity但不结束BrowserActivity
                    Log.d("BrowserActivity", "Cannot go back further, switching to MainActivity")
                    val intent = Intent(this@BrowserActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    startActivity(intent)
                    // 将BrowserActivity移到后台
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
        // Check if ProxyController is supported
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:7890") // Clash Meta default HTTP proxy port
                .build()
            
            ProxyController.getInstance().setProxyOverride(proxyConfig, java.util.concurrent.Executors.newSingleThreadExecutor()) {
                // Proxy override applied
                runOnUiThread {
                    Toast.makeText(this@BrowserActivity, "代理已设置，端口: 7890", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun testProxyConnectivity(port: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ClashLog.d("BrowserActivity: Testing proxy connectivity on port $port...")
                ClashLog.d("Testing proxy connectivity on port $port...")
                
                val startTime = System.currentTimeMillis()
                // Simple socket test to check if proxy is listening
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 3000)
                    val connectTime = System.currentTimeMillis() - startTime
                    
                ClashLog.d("BrowserActivity: Connectivity test success - port: $port, connectTimeMs: $connectTime, localAddress: ${socket.localAddress}, remoteAddress: ${socket.remoteSocketAddress}")
                    
                    ClashLog.d("Proxy is reachable on port $port (connection time: ${connectTime}ms)")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BrowserActivity, "代理连接正常 (${connectTime}ms)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                val errorMessage = e.message ?: e.javaClass.simpleName
                val errorDetails = mapOf(
                    "port" to port,
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to errorMessage
                )
                ClashLog.e("BrowserActivity: Connectivity test failed - $errorDetails")
                ClashLog.e("Proxy connectivity test failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BrowserActivity, "代理连接失败: $errorMessage", Toast.LENGTH_LONG).show()
                }
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
        val newUrl = intent?.getStringExtra("url")
        if (currentTab != null && !newUrl.isNullOrBlank()) {
            // Load the new URL in the current tab
            currentTab.webView.loadUrl(newUrl)
        } else if (currentTab != null) {
            // Bring the browser to front
            currentTab.webView.requestFocus()
        }
    }
    
    override fun onResume() {
        super.onResume()
        browserLogger?.info("=== BROWSER ACTIVITY LIFECYCLE: RESUME ===")
        browserLogger?.info("Browser activity resumed - continuing logging...")
        
        // Ensure the current tab is properly focused when returning to the activity
        val currentTab = tabs.getOrNull(currentTabIndex)
        currentTab?.webView?.requestFocus()
    }

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
        try {
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
            //Toast.makeText(this@BrowserActivity, "代理URL已复制到剪贴板:\n$proxyUrl", Toast.LENGTH_LONG).show()
            
            val request = DownloadManager.Request(Uri.parse(proxyUrl)).apply {
                setTitle(filename)
                setDescription("Downloading proxy version: $proxyUrl")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "cfawb/$filename")
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }
            downloadManager.enqueue(request)
            //Toast.makeText(this@BrowserActivity, "开始下载代理文件: $filename\nURL: $proxyUrl", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
                ClashLog.e("Error downloading proxy file", e)
            Toast.makeText(this@BrowserActivity, "代理文件下载失败: ${e.message}\nURL: $proxyUrl", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onPause() {
        super.onPause()
        browserLogger?.info("=== BROWSER ACTIVITY LIFECYCLE: PAUSE ===")
        browserLogger?.info("Browser activity paused - logging continues...")
    }
    
    override fun onStop() {
        super.onStop()
        browserLogger?.info("=== BROWSER ACTIVITY LIFECYCLE: STOP ===")
        browserLogger?.info("Browser activity stopped - logging persists...")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        browserLogger?.info("=== BROWSER ACTIVITY LIFECYCLE: DESTROY ===")
        browserLogger?.info("Browser activity destroyed - saving final logs...")
        
        // Export final logs before destruction
        try {
            val finalLogs = browserLogger?.exportLogs()
            if (!finalLogs.isNullOrEmpty()) {
                ClashLog.i("Final browser logs:\n$finalLogs")
            }
        } catch (e: Exception) {
            ClashLog.e("Failed to export final logs", e)
        }
    }
}
