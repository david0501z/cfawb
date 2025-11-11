package com.github.kr328.clash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.DownloadManagerDesign
import kotlinx.coroutines.isActive
import java.io.File

class DownloadManagerActivity : BaseActivity<DownloadManagerDesign>() {
    private lateinit var downloadAdapter: DownloadAdapter
    private val downloadList = mutableListOf<DownloadItem>()
    private val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cfawb")

    override suspend fun main() {
        val design = DownloadManagerDesign(this)
        setContentDesign(design)

        // Check and request storage permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_PERMISSION
            )
        } else {
            initializeDownloadManager()
        }

        while (isActive) {
            events.receive()
        }
    }

    private fun initializeDownloadManager() {
        // Create download directory if not exists
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        // Load existing downloads
        loadDownloads()

        // Setup RecyclerView
        downloadAdapter = DownloadAdapter(downloadList) { downloadItem ->
            // Handle download item click
            Toast.makeText(this, "Download item clicked: ${downloadItem.fileName}", Toast.LENGTH_SHORT).show()
        }
        
        // Assuming design.downloadRecyclerView is the RecyclerView in the layout
        // design.downloadRecyclerView.layoutManager = LinearLayoutManager(this)
        // design.downloadRecyclerView.adapter = downloadAdapter
    }

    private fun loadDownloads() {
        downloadList.clear()
        if (downloadDir.exists()) {
            downloadDir.listFiles()?.forEach { file ->
                downloadList.add(DownloadItem(file.name, file.length(), file.lastModified()))
            }
        }
        downloadList.sortByDescending { it.timestamp }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initializeDownloadManager()
                } else {
                    Toast.makeText(this, "Storage permission is required for download management", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSION = 1001
    }
}

data class DownloadItem(
    val fileName: String,
    val fileSize: Long,
    val timestamp: Long
)

class DownloadAdapter(
    private val downloads: List<DownloadItem>,
    private val onItemClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.DownloadViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DownloadViewHolder {
        // Create a simple TextView for each download item
        val textView = android.widget.TextView(parent.context)
        textView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        textView.setPadding(16, 16, 16, 16)
        return DownloadViewHolder(textView)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        val download = downloads[position]
        holder.bind(download)
    }

    override fun getItemCount(): Int = downloads.size

    inner class DownloadViewHolder(private val textView: android.widget.TextView) : 
        RecyclerView.ViewHolder(textView) {
        
        fun bind(download: DownloadItem) {
            textView.text = "${download.fileName} (${formatFileSize(download.fileSize)})"
            textView.setOnClickListener { onItemClick(download) }
        }
        
        private fun formatFileSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${String.format("%.1f", size / 1024.0)} KB"
                size < 1024 * 1024 * 1024 -> "${String.format("%.1f", size / (1024.0 * 1024.0))} MB"
                else -> "${String.format("%.1f", size / (1024.0 * 1024.0 * 1024.0))} GB"
            }
        }
    }
}
