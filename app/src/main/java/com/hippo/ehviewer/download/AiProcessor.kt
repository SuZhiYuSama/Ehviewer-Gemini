package com.hippo.ehviewer.download

import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.util.EhUtils.getSuitableTitle
import com.hippo.ehviewer.util.FileUtils
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

enum class AiProcessingMode { COLORIZE, TRANSLATE, COMBINED }

/**
 * Handles AI post-processing for finished downloads. Images are sent to the configured
 * endpoint and saved as a new gallery so they show up as additional comics in the
 * download list.
 */
object AiProcessor {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    fun enqueue(info: DownloadInfo) {
        if (!Settings.aiProcessingEnabled) return
        scope.launch { processGallery(info) }
    }

    private suspend fun processGallery(info: DownloadInfo) {
        val sourceDir = info.downloadDir ?: return
        val mode = Settings.aiProcessingMode
        val targetDirname = buildTargetDirName(info, mode)
        val targetDir = downloadLocation.subFile(targetDirname)
            ?: downloadLocation.createDirectory(targetDirname)
            ?: return

        sourceDir.listFiles()?.filter { it.isFile }?.forEach { file ->
            runCatching {
                val bytes = file.openInputStream()?.use { it.readBytes() } ?: return@forEach
                val processed = callApi(bytes, mode)
                writeProcessedFile(targetDir, file, processed)
            }.onFailure { Log.e(TAG, "AI processing failed for ${file.name}", it) }
        }

        val aiGallery = cloneGalleryInfo(info.galleryInfo, mode)
        val aiDownloadInfo = DownloadInfo(aiGallery, targetDirname).apply {
            state = DownloadInfo.STATE_FINISH
            legacy = 0
            position = DownloadManager.allInfoList.size
            label = info.label
        }

        EhDB.putDownloadDirname(aiGallery.gid, targetDirname)
        DownloadManager.addDownload(listOf(aiDownloadInfo))
    }

    private fun buildTargetDirName(info: DownloadInfo, mode: AiProcessingMode): String {
        val suffix = when (mode) {
            AiProcessingMode.COLORIZE -> "colorized"
            AiProcessingMode.TRANSLATE -> "translated"
            AiProcessingMode.COMBINED -> "ai"
        }
        return FileUtils.sanitizeFilename("${info.gid}-${suffix}")
    }

    private fun callApi(bytes: ByteArray, mode: AiProcessingMode): ByteArray {
        val endpoint = Settings.aiProcessingEndpoint ?: return bytes
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("X-AI-Mode", mode.name.lowercase(Locale.US))
            .post(bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.bytes() ?: bytes
        }
    }

    private fun writeProcessedFile(targetDir: UniFile, file: UniFile, bytes: ByteArray) {
        val name = file.name ?: return
        targetDir.createFile(name)?.openOutputStream()?.use { it.write(bytes) }
    }

    private fun cloneGalleryInfo(src: BaseGalleryInfo, mode: AiProcessingMode): BaseGalleryInfo {
        val gid = syntheticGid(src.gid, mode)
        val titleSuffix = when (mode) {
            AiProcessingMode.COLORIZE -> "(Colorized)"
            AiProcessingMode.TRANSLATE -> "(Translated)"
            AiProcessingMode.COMBINED -> "(AI Processed)"
        }
        return BaseGalleryInfo(
            gid = gid,
            token = src.token,
            title = buildString {
                append(getSuitableTitle(src))
                append(' ')
                append(titleSuffix)
            },
            titleJpn = src.titleJpn,
            thumbKey = src.thumbKey,
            category = src.category,
            posted = src.posted,
            uploader = src.uploader,
            disowned = src.disowned,
            rating = src.rating,
            rated = src.rated,
            simpleTags = src.simpleTags?.let { ArrayList(it) },
            pages = src.pages,
            thumbWidth = src.thumbWidth,
            thumbHeight = src.thumbHeight,
            simpleLanguage = src.simpleLanguage,
            favoriteSlot = src.favoriteSlot,
            favoriteName = src.favoriteName,
            favoriteNote = src.favoriteNote,
        )
    }

    private fun syntheticGid(base: Long, mode: AiProcessingMode): Long {
        val suffix = when (mode) {
            AiProcessingMode.COLORIZE -> 1L
            AiProcessingMode.TRANSLATE -> 2L
            AiProcessingMode.COMBINED -> 3L
        }
        return (base shl 2) + suffix
    }

    private const val TAG = "AiProcessor"
}
