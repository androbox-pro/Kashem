package com.kashem.shaikh.telegram

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class ServerUrls(
    val host: String,
    val socket: String,
    val username: String = "",
    val chatId: String = ""
)

object ConfigManager {
    private const val CONFIG_FILE = "Server.json"
    private var cachedUrls: ServerUrls? = null

    fun getServerUrls(context: Context): ServerUrls? {
        if (cachedUrls != null) return cachedUrls
        return try {
            val jsonString = context.assets.open(CONFIG_FILE).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val host = json.getString("host")
            val socket = json.getString("socket")
            val username = json.optString("username", "")
            val chatId = json.optString("chatId", "")
            ServerUrls(host, socket, username, chatId).also { cachedUrls = it }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Failed to load config", e)
            null
        }
    }
}