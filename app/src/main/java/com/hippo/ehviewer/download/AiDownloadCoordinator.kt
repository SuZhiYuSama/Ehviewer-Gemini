package com.hippo.ehviewer.download

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.UUID

enum class AiMode { COLOR, TRANSLATE, FULL }

data class AiJob(
    val gid: Long,
    val mode: AiMode,
    val apiFormat: String,
    val modelId: String,
    val targetLanguage: String?,
)

object AiDownloadCoordinator {

    private const val SEPARATOR = "|"

    @Synchronized
    fun enqueue(info: BaseGalleryInfo, mode: AiMode, apiFormat: String, modelId: String, targetLanguage: String?) {
        val pending = Settings.aiPendingJobs?.toMutableSet() ?: mutableSetOf()
        pending.add(encode(AiJob(info.gid, mode, apiFormat, modelId, targetLanguage)))
        Settings.aiPendingJobs = pending
    }

    @Synchronized
    fun cancel(gid: Long) {
        val pending = Settings.aiPendingJobs?.toMutableSet() ?: return
        val updated = pending.filterNot { decode(it)?.gid == gid }.toMutableSet()
        Settings.aiPendingJobs = updated
    }

    fun onDownloadFinished(downloadInfo: DownloadInfo) {
        val job = consume(downloadInfo.gid) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            BatchAiProcessor(appCtx).process(downloadInfo, job)
        }
    }

    @Synchronized
    private fun consume(gid: Long): AiJob? {
        val pending = Settings.aiPendingJobs?.toMutableSet() ?: return null
        val match = pending.firstOrNull { decode(it)?.gid == gid } ?: return null
        pending.remove(match)
        Settings.aiPendingJobs = pending
        return decode(match)
    }

    private fun encode(job: AiJob): String {
        val language = job.targetLanguage ?: ""
        return listOf(job.gid.toString(), job.mode.name, job.apiFormat, job.modelId, language, UUID.randomUUID().toString()).joinToString(SEPARATOR)
    }

    private fun decode(raw: String): AiJob? {
        val parts = raw.split(SEPARATOR)
        if (parts.size < 5) return null
        val gid = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val mode = parts.getOrNull(1)?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: return null
        val apiFormat = parts.getOrNull(2) ?: return null
        val modelId = parts.getOrNull(3) ?: ""
        val targetLanguage = parts.getOrNull(4)?.ifBlank { null }
        return AiJob(gid, mode, apiFormat, modelId, targetLanguage)
    }
}

