package cn.helilab.autorunner.gamepad

import com.autorunner.core.platform.AccessibilityController
import com.autorunner.gamepad.LocalGamepadGateway
import com.autorunner.ui.platform.GamepadStatusProvider

/**
 * 将本地手柄注入器适配为 UI 使用的 [GamepadStatusProvider]。
 *
 * 手柄模拟不再与外部宿主通信，因此没有需要执行的握手流程：只要无障碍服务
 * （用于注入按键的通道）处于已连接状态，该功能即可用；唯一的按设备配置
 * 是在设置中把每个按键映射到屏幕上的坐标。
 */
class AndroidGamepadStatusProvider(
    private val gateway: LocalGamepadGateway,
    private val accessibilityController: AccessibilityController,
) : GamepadStatusProvider {

    override val supported: Boolean get() = gateway.isSupported

    /** 只有按键确实可以注入时才为 `true`。 */
    override val connected: Boolean get() = accessibilityController.isConnected

    /** 不存在异步握手，因此没有需要上报的内容。 */
    override val lastError: String? get() = null

    override fun connect() = Unit

    override fun disconnect() = Unit
}
