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
import java.util.concurrent.TimeUnit

object GeminiManager {

    private const val TAG = "GeminiManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    const val PROMPT_COLORIZE = "请根据彩图对漫画进行上色。注意保持文字框为白底。"
    const val PROMPT_TRANSLATE = "请将漫画汉化为中文"

    fun generateImageContent(
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String = "gemini-2.0-flash-exp",
    ): Result<Bitmap> {
        if (apiKey.isBlank()) return Result.failure(Exception("请先在设置中配置 Gemini API Key"))

        val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        val apiUrl = "$cleanBaseUrl/v1beta/models/$modelId:generateContent?key=$apiKey"

        Log.d(TAG, "Requesting Gemini AI: $modelId")

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
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val msg = if (response.code == 429) "API 请求过于频繁 (429)" else "HTTP ${response.code}"
                    Log.e(TAG, "API Error: $responseBody")
                    return@use Result.failure(Exception(msg))
                }

                val root = JSONObject(responseBody)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@use Result.failure(Exception("模型未返回结果"))
                }

                val part = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.getJSONObject(0)
                val inlineData = part?.optJSONObject("inlineData")

                if (inlineData != null) {
                    val base64Data = inlineData.getString("data")
                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val resultBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    return@use Result.success(resultBitmap)
                } else {
                    return@use Result.failure(Exception("模型返回了文本而非图片"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network Exception", e)
            Result.failure(e)
        }
    }
}
