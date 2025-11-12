package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.DesignHistoryManagerBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class HistoryManagerDesign(context: Context) : Design<HistoryManagerDesign.Request>(context) {
    enum class Request {
        Back,
    }

    private val binding = DesignHistoryManagerBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    val historyRecyclerView: RecyclerView
        get() = binding.historyRecyclerView

    init {
        binding.self = this
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
