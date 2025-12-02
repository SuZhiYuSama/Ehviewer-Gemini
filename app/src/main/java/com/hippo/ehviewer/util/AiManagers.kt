package com.hippo.ehviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class AiApiFormat(val raw: String) {
    GEMINI("gemini"),
    OPENAI("openai"),
    ;

    companion object {
        fun fromRaw(raw: String?): AiApiFormat = values().firstOrNull { it.raw == raw } ?: GEMINI
    }
}

data class AiConfig(
    val format: AiApiFormat,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
)

private const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash-exp"
private const val DEFAULT_OPENAI_MODEL = "gpt-image-1"

object AiManagers {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun currentConfig(): AiConfig {
        val format = AiApiFormat.fromRaw(Settings.aiApiFormat)
        return AiConfig(
            format = format,
            baseUrl = if (format == AiApiFormat.GEMINI) Settings.aiGeminiBaseUrl else Settings.aiOpenAiBaseUrl,
            apiKey = if (format == AiApiFormat.GEMINI) Settings.aiGeminiApiKey else Settings.aiOpenAiApiKey,
            model = Settings.aiDefaultModel,
        )
    }

    suspend fun processBitmap(bitmap: Bitmap, prompt: String, config: AiConfig = currentConfig()): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            when (config.format) {
                AiApiFormat.GEMINI -> GeminiManager.generateImageContent(
                    baseUrl = config.baseUrl ?: "https://generativelanguage.googleapis.com",
                    apiKey = config.apiKey.orEmpty(),
                    originalImageBase64 = bitmap.toBase64(),
                    prompt = prompt,
                    modelId = config.model ?: DEFAULT_GEMINI_MODEL,
                )
                AiApiFormat.OPENAI -> OpenAiManager.generateImageContent(
                    baseUrl = config.baseUrl ?: "https://api.openai.com",
                    apiKey = config.apiKey.orEmpty(),
                    originalImageBase64 = bitmap.toBase64(),
                    prompt = prompt,
                    modelId = config.model ?: DEFAULT_OPENAI_MODEL,
                )
            }
        }

    private fun Bitmap.toBase64(quality: Int = 90): String {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private object OpenAiManager {
        fun generateImageContent(
            baseUrl: String,
            apiKey: String,
            originalImageBase64: String,
            prompt: String,
            modelId: String,
        ): Result<Bitmap> {
            if (apiKey.isBlank()) return Result.failure(IllegalStateException("OpenAI API key 未配置"))

            val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
            val apiUrl = "$cleanBaseUrl/v1/images/edits"

            return runCatching {
                val tempFile = File.createTempFile("openai-image", ".png")
                val decoded = Base64.decode(originalImageBase64, Base64.DEFAULT)
                FileOutputStream(tempFile).use { it.write(decoded) }

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", modelId)
                    .addFormDataPart("prompt", prompt)
                    .addFormDataPart(
                        "image",
                        tempFile.name,
                        tempFile.asRequestBody("image/png".toMediaType()),
                    )
                    .addFormDataPart("response_format", "b64_json")
                    .build()

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: $responseBody")
                    }
                    val root = JSONObject(responseBody)
                    val dataArr = root.optJSONArray("data") ?: JSONArray()
                    if (dataArr.length() == 0) error("模型未返回图片数据")
                    val base64 = dataArr.getJSONObject(0).optString("b64_json")
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("无法解码返回的图片数据")
                }
            }
        }
    }
}
