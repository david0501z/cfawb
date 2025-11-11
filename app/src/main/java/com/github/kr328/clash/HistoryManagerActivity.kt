package com.github.kr328.clash

import android.os.Bundle
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.HistoryManagerDesign
import kotlinx.coroutines.isActive

class HistoryManagerActivity : BaseActivity<HistoryManagerDesign>() {
    private lateinit var historyAdapter: HistoryAdapter
    private val historyList = mutableListOf<HistoryItem>()

    override suspend fun main() {
        val design = HistoryManagerDesign(this)
        setContentDesign(design)

        // Load history data
        loadHistory()

        // Setup RecyclerView
        historyAdapter = HistoryAdapter(historyList) { historyItem ->
            // Handle history item click
            Toast.makeText(this, "History item clicked: ${historyItem.title}", Toast.LENGTH_SHORT).show()
        }
        
        // Assuming design.historyRecyclerView is the RecyclerView in the layout
        // design.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        // design.historyRecyclerView.adapter = historyAdapter

        while (isActive) {
            events.receive()
        }
    }

    private fun loadHistory() {
        // TODO: Load history from storage or database
        // For now, add some sample data
        historyList.clear()
        historyList.add(HistoryItem("Google", "https://www.google.com", System.currentTimeMillis()))
        historyList.add(HistoryItem("GitHub", "https://github.com", System.currentTimeMillis() - 100000))
        historyList.add(HistoryItem("Stack Overflow", "https://stackoverflow.com", System.currentTimeMillis() - 200000))
        historyList.sortByDescending { it.timestamp }
    }
}

data class HistoryItem(
    val title: String,
    val url: String,
    val timestamp: Long
)

class HistoryAdapter(
    private val historyItems: List<HistoryItem>,
    private val onItemClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): HistoryViewHolder {
        // Create a simple TextView for each history item
        val textView = android.widget.TextView(parent.context)
        textView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        textView.setPadding(16, 16, 16, 16)
        return HistoryViewHolder(textView)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val history = historyItems[position]
        holder.bind(history)
    }

    override fun getItemCount(): Int = historyItems.size

    inner class HistoryViewHolder(private val textView: android.widget.TextView) : 
        RecyclerView.ViewHolder(textView) {
        
        fun bind(history: HistoryItem) {
            textView.text = "${history.title}\n${history.url}"
            textView.setOnClickListener { onItemClick(history) }
        }
    }
}
