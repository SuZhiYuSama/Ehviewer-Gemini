package com.hippo.ehviewer.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.getGalleryDownloadDir
import com.hippo.ehviewer.util.AiManagers
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.GeminiManager
import com.hippo.unifile.UniFile
// 重点：加回这个 Import，它是独立的接口
import com.hippo.unifile.FilenameFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class BatchAiProcessor(private val context: Context) {

    interface ProgressListener {
        fun onProgress(current: Int, total: Int, message: String)
        fun onComplete(newInfo: List<DownloadInfo>)
        fun onError(error: String)
    }

    suspend fun processGallery(
        sourceInfo: BaseGalleryInfo,
        mode: AiProcessMode,
        listener: ProgressListener,
    ) = withContext(Dispatchers.IO) {
        val sourceDir = getGalleryDownloadDir(sourceInfo.gid)

        if (sourceDir == null || !sourceDir.exists()) {
            withContext(Dispatchers.Main) { listener.onError("找不到原始下载文件") }
            return@withContext
        }

        // --- 修复重点：使用 object : FilenameFilter (不带 UniFile. 前缀) ---
        // 这样既解决了 Lambda 歧义，又解决了 Unresolved reference
        val filter = object : FilenameFilter {
            override fun accept(dir: UniFile?, name: String?): Boolean {
                if (name == null) return false
                val lowerName = name.lowercase()
                return lowerName.endsWith(".jpg") || lowerName.endsWith(".png") || lowerName.endsWith(".jpeg")
            }
        }

        val imageFiles = sourceDir.listFiles(filter)?.sortedBy { it.name } ?: emptyList()
        // -----------------------------------------------------------

        if (imageFiles.isEmpty()) {
            withContext(Dispatchers.Main) { listener.onError("文件夹内无图片") }
            return@withContext
        }

        val tasks = when (mode) {
            AiProcessMode.COLOR -> listOf(ProcessTarget.ColorOnly)
            AiProcessMode.TRANSLATE -> listOf(ProcessTarget.TranslateOnly)
            AiProcessMode.FULL -> listOf(ProcessTarget.ColorOnly, ProcessTarget.TranslateFromColor)
            else -> emptyList()
        }

        val results = mutableListOf<DownloadInfo>()

        var previousDir: UniFile? = null
        for (task in tasks) {
            val targetDir = createTargetDir(sourceDir, sourceInfo, task)
            if (targetDir == null) {
                withContext(Dispatchers.Main) { listener.onError("无法创建目标文件夹") }
                return@withContext
            }

            val total = imageFiles.size
            for ((index, file) in imageFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    listener.onProgress(index + 1, total, "正在处理第 ${index + 1} 页...")
                }

                val fileName = file.name ?: "page_${index + 1}.jpg"

                val sourceBitmap = when (task) {
                    ProcessTarget.TranslateFromColor -> {
                        previousDir?.findFile(fileName)?.let {
                            val bytes = it.readBytesCompat()
                            if (bytes.isNotEmpty()) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } else null
                        }
                    }
                    else -> file.openInputStream()?.use { BitmapFactory.decodeStream(it) }
                }

                if (sourceBitmap == null) continue

                val prompt = if (task == ProcessTarget.ColorOnly) GeminiManager.PROMPT_COLORIZE else GeminiManager.PROMPT_TRANSLATE

                val resultBitmap = runCatching {
                    AiManagers.processBitmap(sourceBitmap, prompt).getOrThrow()
                }.getOrElse {
                    Log.e("BatchAi", "Page ${index + 1} failed", it)
                    sourceBitmap
                }

                saveBitmap(targetDir, fileName, resultBitmap)

                delay(1500)
            }

            val downloadInfo = registerAsDownload(targetDir, sourceInfo, task)
            if (downloadInfo != null) {
                results += downloadInfo
            }
            previousDir = targetDir
        }

        withContext(Dispatchers.Main) { listener.onComplete(results) }
    }

    private fun createTargetDir(
        sourceDir: UniFile,
        sourceInfo: GalleryInfo,
        task: ProcessTarget,
    ): UniFile? {
        val suffix = when (task) {
            ProcessTarget.ColorOnly -> "[AI-Color]"
            ProcessTarget.TranslateOnly -> "[AI-TL]"
            ProcessTarget.TranslateFromColor -> "[AI-Color+TL]"
        }
        val newTitle = "$suffix ${sourceInfo.title ?: sourceInfo.gid}"
        val newDirName = FileUtils.sanitizeFilename(newTitle)
        return sourceDir.parentFile?.createDirectory(newDirName)
    }

    private fun saveBitmap(targetDir: UniFile, name: String, bitmap: Bitmap) {
        val targetFile = targetDir.createFile(name) ?: return
        targetFile.openOutputStream()?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
        }
    }

    private suspend fun registerAsDownload(
        targetDir: UniFile,
        sourceInfo: BaseGalleryInfo,
        task: ProcessTarget,
    ): DownloadInfo? {
        val newGid = when (task) {
            ProcessTarget.ColorOnly -> sourceInfo.gid * 10 + 1
            ProcessTarget.TranslateOnly -> sourceInfo.gid * 10 + 2
            ProcessTarget.TranslateFromColor -> sourceInfo.gid * 10 + 3
        }
        val newInfo = BaseGalleryInfo().apply {
            gid = newGid
            token = sourceInfo.token
            title = targetDir.name
            titleJpn = sourceInfo.titleJpn
            thumbKey = sourceInfo.thumbKey
            category = sourceInfo.category
            posted = sourceInfo.posted
            uploader = sourceInfo.uploader
            rating = sourceInfo.rating
            pages = sourceInfo.pages
            favoriteSlot = sourceInfo.favoriteSlot
            simpleLanguage = sourceInfo.simpleLanguage
        }

        val dirname = targetDir.name ?: return null
        EhDB.putDownloadDirname(newGid, dirname)
        val info = DownloadInfo(newInfo, dirname)
        info.state = DownloadInfo.STATE_FINISH
        info.position = DownloadManager.allInfoList.size
        DownloadManager.addDownload(listOf(info), notify = true)
        return info
    }
}

enum class AiProcessMode {
    NONE,
    COLOR,
    TRANSLATE,
    FULL,
}

private enum class ProcessTarget {
    ColorOnly,
    TranslateOnly,
    TranslateFromColor,
}

// ================= Extension Functions =================

fun UniFile.openInputStream(): InputStream? = try {
    this.openFileDescriptor("r")?.let { java.io.FileInputStream(it.fileDescriptor) }
} catch (e: Exception) {
    null
}

fun UniFile.openOutputStream(): OutputStream? = try {
    this.openFileDescriptor("w")?.let { java.io.FileOutputStream(it.fileDescriptor) }
} catch (e: Exception) {
    null
}

fun UniFile.readBytesCompat(): ByteArray {
    return this.openInputStream()?.use { it.readBytes() } ?: ByteArray(0)
}