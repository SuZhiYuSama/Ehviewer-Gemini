package com.hippo.ehviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object GeminiManager {

    private const val TAG = "GeminiManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    const val PROMPT_COLORIZE = "请根据彩图对漫画进行上色。注意保持文字框为白底。"
    const val PROMPT_TRANSLATE_TEMPLATE = "请将漫画翻译为 %s"

    fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 85): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(format, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun generateImageContent(
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        if (apiKey.isBlank()) return Result.failure(Exception("请先在设置中配置 Gemini API Key"))

        val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        val apiUrl = "$cleanBaseUrl/v1beta/models/$modelId:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", originalImageBase64)
                    }))
                })
            }))
            put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE")))
        }

        val request = Request.Builder()
            .url(apiUrl)
            .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "API Error: $responseBody")
                    val msg = if (response.code == 429) "API 请求过于频繁 (429)" else "HTTP ${response.code}"
                    throw IllegalStateException(msg)
                }

                val root = JSONObject(responseBody)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    throw IllegalStateException("模型未返回结果")
                }

                val part = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                val inlineData = part?.optJSONObject("inlineData")
                val base64Data = inlineData?.optString("data")
                if (base64Data.isNullOrEmpty()) {
                    throw IllegalStateException("模型返回了文本而非图片")
                }
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ?: throw IllegalStateException("无法解析模型返回的图片")
            }
        }
    }
}

