package com.autorunner.ui.remote

/**
 * 读取另一台设备 `/api/status` 的响应正文。
 *
 * 这里只做一次裸 HTTP GET，不依赖 ktor / OkHttp：目标地址是局域网内的明文
 * HTTP 服务，`HttpURLConnection` 已经够用，也避免为了一处请求把网络栈带进
 * commonMain。实现分别在 Android 与桌面源集中，均在 IO 线程上执行。
 *
 * 协程被取消时实现必须主动断开连接：长轮询会挂起十几秒，只取消协程不会打断
 * 阻塞读，连接会留成一条僵尸长轮询，既占着服务端的处理线程，也会和下一次请求
 * 抢同一条链路（监控页切后台 / 点「立即刷新」时就会发生）。
 *
 * @throws java.io.IOException 连接失败、超时或对方返回非 2xx 状态码。
 */
internal expect suspend fun fetchRemoteStatusText(url: String, readTimeoutMs: Int): String

/**
 * 拉取并解析一次状态快照；内容不是合法 JSON 时抛出 [IllegalStateException]。
 *
 * 长轮询时服务端最多挂起它自己上报的 `longPollMs`，调用方用 [remoteReadTimeoutMs]
 * 把 [readTimeoutMs] 设得比它长一点即可。
 */
internal suspend fun fetchRemoteStatus(
    url: String,
    readTimeoutMs: Int = 6_000,
): RemoteStatusSnapshot {
    val payload = fetchRemoteStatusText(url, readTimeoutMs)
    return parseRemoteStatus(payload)
        ?: throw IllegalStateException("对方返回的内容不是状态 JSON")
}
