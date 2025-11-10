package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import com.github.kr328.clash.design.databinding.DesignBrowserBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class BrowserDesign(context: Context) : Design<BrowserDesign.Request>(context) {
    enum class Request {
        Back,
        Forward,
        Reload,
        Stop,
        LoadUrl,
    }

    private val binding = DesignBrowserBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    val webView: WebView
        get() = binding.webView

    val urlInput: EditText
        get() = binding.urlInput

    val progressBar: ProgressBar
        get() = binding.progressBar

    val backButton: ImageButton
        get() = binding.backButton

    val forwardButton: ImageButton
        get() = binding.forwardButton

    val reloadButton: ImageButton
        get() = binding.reloadButton

    init {
        binding.self = this
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
