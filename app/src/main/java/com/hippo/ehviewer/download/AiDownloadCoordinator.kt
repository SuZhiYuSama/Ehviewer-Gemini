package com.hippo.ehviewer.download

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AiDownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingTasks: MutableMap<Long, AiProcessMode> = mutableMapOf()

    init {
        restoreTasks()
    }

    private fun restoreTasks() {
        Settings.aiPendingTasks?.forEach { entry ->
            val parts = entry.split(":")
            val gid = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val mode = parts.getOrNull(1)?.let { raw ->
                runCatching { AiProcessMode.valueOf(raw) }.getOrNull()
            } ?: return@forEach
            pendingTasks[gid] = mode
        }
    }

    private fun persistTasks() {
        Settings.aiPendingTasks = pendingTasks.map { "${it.key}:${it.value}" }.toSet()
    }

    fun enqueue(galleryInfo: BaseGalleryInfo, mode: AiProcessMode) {
        pendingTasks[galleryInfo.gid] = mode
        persistTasks()
    }

    fun onDownloadFinished(context: Context, info: DownloadInfo) {
        val mode = pendingTasks.remove(info.gid) ?: return
        persistTasks()
        if (info.state != DownloadInfo.STATE_FINISH) return

        scope.launch {
            val processor = BatchAiProcessor(context)
            processor.processGallery(info.galleryInfo, mode, object : BatchAiProcessor.ProgressListener {
                override fun onProgress(current: Int, total: Int, message: String) {
                    Log.d("AiDownload", "[$current/$total] $message")
                }

                override fun onComplete(newInfo: List<DownloadInfo>) {
                    Log.d("AiDownload", "AI processing completed for ${info.gid}: ${newInfo.size} items")
                }

                override fun onError(error: String) {
                    Log.e("AiDownload", "AI processing failed: $error")
                }
            })
        }
    }
}
