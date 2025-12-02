package com.hippo.ehviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenAiManager {

    private const val TAG = "OpenAiManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun generateImageContent(
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        if (apiKey.isBlank()) return Result.failure(Exception("请先在设置中配置 OpenAI Key"))

        val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        val apiUrl = "$cleanBaseUrl/v1/images/edits"

        val jsonBody = JSONObject().apply {
            put("model", modelId)
            put("prompt", prompt)
            put("image", originalImageBase64)
            put("response_format", "b64_json")
        }

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "API Error: $body")
                    val message = JSONObject(body).optJSONObject("error")?.optString("message")
                    throw IllegalStateException(message ?: "HTTP ${response.code}")
                }
                val dataArray = JSONObject(body).optJSONArray("data")
                val base64Data = dataArray?.optJSONObject(0)?.optString("b64_json")
                if (base64Data.isNullOrEmpty()) throw IllegalStateException("模型未返回图片数据")
                val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    ?: throw IllegalStateException("无法解析模型返回的图片")
            }
        }
    }
}

