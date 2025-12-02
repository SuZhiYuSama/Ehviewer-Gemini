package com.hippo.ehviewer.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.readCompatFromUniFile
import com.hippo.ehviewer.spider.write
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.GeminiManager
import com.hippo.ehviewer.util.OpenAiManager
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Locale

@Suppress("UNUSED_PARAMETER")
class BatchAiProcessor(private val context: android.content.Context) {

    interface ProgressListener {
        fun onProgress(current: Int, total: Int, message: String)
        fun onComplete(downloadInfo: DownloadInfo)
        fun onError(error: String)
    }

    suspend fun process(downloadInfo: DownloadInfo, job: AiJob, listener: ProgressListener? = null) = withContext(Dispatchers.IO) {
        val sourceDir = downloadInfo.downloadDir
        if (sourceDir == null) {
            listener?.onError("找不到原始下载文件")
            return@withContext
        }

        val imageFiles = sourceDir.listFiles { f ->
            val name = f.name?.lowercase(Locale.getDefault())
            name != null && (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg"))
        }?.sortedBy { it.name } ?: emptyList()

        if (imageFiles.isEmpty()) {
            listener?.onError("文件夹内无图片")
            return@withContext
        }

        val sourceInfo = downloadInfo.galleryInfo
        when (job.mode) {
            AiMode.COLOR -> {
                createVariant(sourceInfo, sourceDir, imageFiles, job, listener, AiMode.COLOR)
            }
            AiMode.TRANSLATE -> {
                createVariant(sourceInfo, sourceDir, imageFiles, job, listener, AiMode.TRANSLATE)
            }
            AiMode.FULL -> {
                val colorInfo = createVariant(sourceInfo, sourceDir, imageFiles, job, listener, AiMode.COLOR)
                val colorDir = colorInfo?.downloadDir
                if (colorInfo != null && colorDir != null) {
                    val colorImages = colorDir.listFiles()?.sortedBy { it.name } ?: emptyList()
                    createVariant(colorInfo.galleryInfo, colorDir, colorImages, job, listener, AiMode.TRANSLATE)
                }
            }
        }
    }

    private suspend fun createVariant(
        sourceInfo: BaseGalleryInfo,
        sourceDir: UniFile,
        imageFiles: List<UniFile>,
        job: AiJob,
        listener: ProgressListener?,
        stage: AiMode,
    ): DownloadInfo? {
        val suffix = when (stage) {
            AiMode.COLOR -> "AI-Color"
            AiMode.TRANSLATE -> "AI-TL"
            AiMode.FULL -> "AI"
        }
        val newGid = System.currentTimeMillis()
        val derivedInfo = BaseGalleryInfo(
            gid = newGid,
            token = sourceInfo.token,
            title = "[$suffix] ${sourceInfo.title}",
            titleJpn = sourceInfo.titleJpn,
            thumbKey = sourceInfo.thumbKey,
            category = sourceInfo.category,
            posted = sourceInfo.posted,
            uploader = sourceInfo.uploader,
            rating = sourceInfo.rating,
            favoriteSlot = GalleryInfo.NOT_FAVORITED,
        ).apply {
            pages = sourceInfo.pages
            simpleTags = sourceInfo.simpleTags
            simpleLanguage = sourceInfo.simpleLanguage
            favoriteName = null
        }

        val targetDirName = FileUtils.sanitizeFilename("$newGid-${derivedInfo.title}")
        val targetDir = sourceDir.parentFile?.createDirectory(targetDirName)
        if (targetDir == null) {
            listener?.onError("无法创建目标文件夹")
            return null
        }

        copySpiderInfo(sourceDir, targetDir, newGid)

        val total = imageFiles.size
        for ((index, file) in imageFiles.withIndex()) {
            listener?.onProgress(index + 1, total, "正在处理第 ${index + 1} 页")
            processPage(file, job, stage)?.let { bitmap ->
                saveBitmap(targetDir, file.name ?: "${index + 1}.jpg", bitmap)
            }
            delay(1500)
        }

        return DownloadManager.addFinishedDownload(derivedInfo, targetDirName).also {
            if (it != null) listener?.onComplete(it)
        }
    }

    private fun copySpiderInfo(sourceDir: UniFile, targetDir: UniFile, newGid: Long) {
        val spiderFile = sourceDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return
        val info = readCompatFromUniFile(spiderFile) ?: return
        info.gid = newGid
        info.write(targetDir.createFile(SpiderQueen.SPIDER_INFO_FILENAME))
    }

    private suspend fun processPage(
        file: UniFile,
        job: AiJob,
        stage: AiMode,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val inputStream = file.openInputStream()
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (originalBitmap == null) return@withContext null

        val prompt = when (stage) {
            AiMode.COLOR -> GeminiManager.PROMPT_COLORIZE
            else -> GeminiManager.PROMPT_TRANSLATE_TEMPLATE.format(job.targetLanguage ?: "中文")
        }

        val base64 = GeminiManager.bitmapToBase64(originalBitmap)
        val apiKey = if (job.apiFormat.lowercase(Locale.getDefault()) == "openai") Settings.aiOpenAiApiKey else Settings.aiGeminiApiKey
        val baseUrl = if (job.apiFormat.lowercase(Locale.getDefault()) == "openai") Settings.aiOpenAiBaseUrl else Settings.aiGeminiBaseUrl
        val model = if (job.apiFormat.lowercase(Locale.getDefault()) == "openai") Settings.aiOpenAiModel else Settings.aiGeminiModel
        val callModel = if (job.modelId.isNotBlank()) job.modelId else model

        val result = if (job.apiFormat.lowercase(Locale.getDefault()) == "openai") {
            OpenAiManager.generateImageContent(baseUrl, apiKey, base64, prompt, callModel)
        } else {
            GeminiManager.generateImageContent(baseUrl, apiKey, base64, prompt, callModel)
        }

        result.getOrElse {
            Log.e("BatchAiProcessor", "AI 调用失败", it)
            null
        }
    }

    private fun saveBitmap(targetDir: UniFile, fileName: String, bitmap: Bitmap) {
        val targetFile = targetDir.createFile(fileName)
        val os: OutputStream = targetFile.openOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
        os.close()
    }
}

