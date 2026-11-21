package com.autorunner.ui.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * 只读网页状态页的运行时信息。
 *
 * 开关本身存放在 [com.autorunner.core.model.AppSettings.webStatusEnabled]，由
 * Android 层的 [WebStatusProvider] 实现观察并启动 / 停止内置 HTTP 服务；
 * 设置页只读取这里的状态用于展示访问地址。
 */
data class WebStatusInfo(
    val running: Boolean = false,
    val port: Int = 0,
    /** 本机在局域网内的 IPv4 地址（可能多于一个，例如同时连着 Wi-Fi 与热点）。 */
    val addresses: List<String> = emptyList(),
    /** 启动失败的原因（端口占用等）；正常时为 `null`。 */
    val error: String? = null,
) {
    /** 展示用的首个访问地址。 */
    val primaryAddress: String? get() = addresses.firstOrNull()
}

/**
 * 平台侧的网页状态页能力。
 *
 * `autorunner-ui` 不依赖 Android，因此只声明只读接口；Android 实现位于
 * `cn.helilab.autorunner.web.StatusWebServer`。
 */
interface WebStatusProvider {

    val info: StateFlow<WebStatusInfo>
}
