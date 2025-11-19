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
            // Handle history item click - open the URL in a new browser activity
            val intent = android.content.Intent(this, BrowserActivity::class.java)
            intent.putExtra("url", historyItem.url)
            startActivity(intent)
        }
        
        // Setup the RecyclerView with the design
        design?.historyRecyclerView?.layoutManager = LinearLayoutManager(this)
        design?.historyRecyclerView?.adapter = historyAdapter

        // 设置返回按钮点击事件
        design.root.findViewById<android.widget.ImageButton>(com.github.kr328.clash.design.R.id.backButton)?.setOnClickListener {
            finish()
        }

        while (isActive) {
            events.receive()
        }
    }

    private fun loadHistory() {
        // Load history from the same SharedPreferences as BrowserActivity
        val prefs = getSharedPreferences("browser_history", android.content.Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("history", mutableSetOf()) ?: setOf()
        
        historyList.clear()
        
        for (entry in historySet) {
            val parts = entry.split("|", limit = 3)
            if (parts.size >= 3) {
                val timestamp = parts[0].toLongOrNull() ?: 0L
                val title = parts[1]
                val url = parts[2]
                historyList.add(HistoryItem(title, url, timestamp))
            }
        }
        
        // Sort by most recent first
        historyList.sortByDescending { it.timestamp }
        
        // Notify adapter of changes
        historyAdapter.notifyDataSetChanged()
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