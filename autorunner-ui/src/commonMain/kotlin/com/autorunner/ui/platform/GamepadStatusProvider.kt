package com.autorunner.ui.platform

/**
 * 可选手柄模块的只读视图。
 *
 * `autorunner-ui` 刻意不依赖 `autorunner-gamepad`；应用模块负责把
 * `LocalGamepadGateway` 适配到本接口（以及执行器使用的
 * `com.autorunner.core.model.GamepadGateway`）。
 */
interface GamepadStatusProvider {

    /** 本设备是否支持本地手柄注入。 */
    val supported: Boolean

    /** 是否已有主机连接。 */
    val connected: Boolean

    /** 平台最近一次上报的失败信息，没有则为 `null`。 */
    val lastError: String?

    /** 在后台广播 HID 配置。 */
    fun connect()

    /** 停止广播 / 断开连接。 */
    fun disconnect()
}
