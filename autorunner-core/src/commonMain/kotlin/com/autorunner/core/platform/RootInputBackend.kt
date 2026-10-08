package com.autorunner.core.platform

import com.autorunner.core.model.ActionStep

/**
 * Root 模式输入注入。
 *
 * Android 侧由 `AndroidRootInputBackend` 实现：把动作写入 AutoRunner 私有目录下的
 * 命令文件，交给 Magisk / KernelSU 模块里的守护进程用 root `input` 执行。这样在
 * MIUI 等限制无障碍、或用户不愿开启无障碍的设备上依然可以回放脚本。
 */
interface RootInputBackend {
    /** Root 注入是否可用：模块就绪，或本应用已获得 root 授权。 */
    val isAvailable: Boolean

    /** 是否安装了 Root Bridge 模块（文件桥，注入最快）。 */
    val moduleInstalled: Boolean get() = false

    /** 重新探测 root 授权状态（用户在 root 管理器里刚点过允许时调用）。 */
    fun probe() {}

    /**
     * 把内置的 Root Bridge 模块导出到公共下载目录。
     *
     * @return 展示给用户的路径（如 `Downloads/autorunner_root-v1.0.0.zip`），失败为 `null`。
     */
    fun exportModule(): String? = null

    /**
     * 按需请求修复无障碍服务条目（Root 模式自愈）。
     *
     * 系统「强停应用」会 unbind 无障碍服务并把条目从 `enabled_accessibility_services`
     * 移除。模块里的守护进程不再周期轮询，而是等这里下发一条修复请求后即时补回。
     *
     * @return 请求是否已成功下发（并不代表条目已生效）。
     */
    fun repairAccessibility(): Boolean = false

    /** 执行一步动作；不支持的步骤返回 [ActionResult.Unsupported]。 */
    suspend fun perform(step: ActionStep): ActionResult
}
