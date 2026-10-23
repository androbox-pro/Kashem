package com.kashem.shaikh.services

import android.content.Context
import org.json.JSONObject

object ConfigManager {

    private const val CONFIG_FILE = "Server.json"
    @Volatile private var cached: Config? = null

    data class Config(
        val socket: String,
        val host: String,
        val username: String
    )

    fun getConfig(context: Context): Config? {
        cached?.let { return it }
        return try {
            val json = context.assets.open(CONFIG_FILE)
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(json)

            var socket = obj.getString("socket").trim()
            val host = obj.getString("host").trim()

            if (socket.startsWith("https://")) socket = "wss://" + socket.removePrefix("https://")
            else if (socket.startsWith("http://")) socket = "ws://" + socket.removePrefix("http://")

            Config(
                socket = socket,
                host = host,
                username = obj.optString("username", "")
            ).also { cached = it }
        } catch (e: Exception) {
            AppLogs.error("ConfigManager: load failed", e)
            null
        }
    }

    fun clearCache() {
        cached = null
    }
}