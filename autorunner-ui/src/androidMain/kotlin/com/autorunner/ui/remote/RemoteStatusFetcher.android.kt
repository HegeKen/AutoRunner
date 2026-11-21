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
            // 状态接口是纯只读快照，不需要缓存。
            useCaches = false
        }
        // 长轮询会一直挂到服务端返回。协程被取消（监控页切到后台、点「立即刷新」）
        // 时主动断开连接，阻塞读才会马上抛错退出；否则这条请求会一直挂到读超时，
        // 留成一条僵尸连接，回到前台后的新请求还会和它抢同一条链路。
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
