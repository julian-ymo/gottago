package com.gottago.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "GottaGo/1.0 (Android toilet finder)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Blocking call. Run it on Dispatchers.IO. */
    fun call(
        method: String,
        url: String,
        body: String? = null,
        type: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): String {
        val requestBody = body?.toRequestBody(type.toMediaType())
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.method(method, requestBody).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(300)}")
            return text
        }
    }
}
