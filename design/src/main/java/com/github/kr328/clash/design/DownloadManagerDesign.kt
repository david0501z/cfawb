package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.DesignDownloadManagerBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class DownloadManagerDesign(context: Context) : Design<DownloadManagerDesign.Request>(context) {
    enum class Request {
        Back,
    }

    private val binding = DesignDownloadManagerBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    val downloadRecyclerView: RecyclerView
        get() = binding.downloadRecyclerView

    init {
        binding.self = this
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
