package com.autorunner.ui.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal actual suspend fun fetchRemoteStatusText(url: String, readTimeoutMs: Int): String =
    withContext(Dispatchers.IO) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        // 与 Android 实现一致：取消时主动断开，别把长轮询留成僵尸连接。
        val abortOnCancel = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("对方返回 HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            abortOnCancel.cancel()
            connection.disconnect()
        }
    }
