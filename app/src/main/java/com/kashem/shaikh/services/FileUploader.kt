package com.kashem.shaikh.services

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object FileUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun uploadFile(
        context: Context,
        file: File,
        host: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val baseUrl = if (host.endsWith("/")) host else "$host/"
        val url = "${baseUrl}uploadFile"

        AppLogs.info("Uploading ${file.name} (${file.length()} bytes) → $url")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("model", android.os.Build.MODEL)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLogs.error("Upload network failure: ${e.message}", e)
                callback(false, null)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                var downloadUrl: String? = null
                if (success) {
                    try {
                        val body = response.body?.string() ?: "{}"
                        val json = JSONObject(body)
                        // ✅ FIX: platform-type ambiguity এড়াতে safe check
                        val raw = json.optString("downloadUrl", "")
                        downloadUrl = raw.takeIf { it.isNotBlank() }
                    } catch (e: Exception) {
                        AppLogs.warn("Response parse failed: ${e.message}")
                    }
                } else {
                    AppLogs.error("Upload HTTP ${response.code} ${response.message}")
                }
                response.close()
                callback(success, downloadUrl)
            }
        })
    }
}