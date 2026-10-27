package cn.helilab.autorunner.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field

/**
 * 小米焦点通知 / 超级岛白名单解锁（LSPosed 模块）。
 *
 * 该模块只做两件事，且只针对白名单里的目标应用：
 *
 * 1. `com.xiaomi.xmsf`：XMSF 的 [AuthSession] 在服务端返回 `-300 scope mismatch`
 *    时会走 `getAuthError(Bundle)`。这里把错误码清零并返回 `getAuthSuccess()`，
 *    让焦点通知的客户端鉴权通过。
 * 2. `com.miui.systemui`：`NotificationSettingsManager` 的
 *    `canShowFocusState` / `canShowFocusStateApp` 会按签名白名单判断某应用能否
 *    上岛；这里对目标包名直接返回允许。
 *
 * 作用域（LSPosed 中勾选）：
 * - 系统界面（`com.miui.systemui`）
 * - 小米服务框架（`com.xiaomi.xmsf`）
 *
 * 注意：XMSF 的 AuthSession 是通用鉴权入口，清零错误码会放宽该进程的鉴权；
 * 这是“解锁白名单”类模块的既有做法。模块只在本机 root + LSPosed 环境生效。
 */
class AutoRunnerXposed : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            XMSF_PACKAGE -> hookXmsfAuth(param.classLoader)
            SYSTEM_UI_PACKAGE -> hookSystemUiFocusWhitelist(param.classLoader)
        }
    }

    private fun hookXmsfAuth(classLoader: ClassLoader) {
        val authSession = runCatching {
            Class.forName("com.xiaomi.xms.auth.AuthSession", false, classLoader)
        }.getOrNull() ?: return logInfo("AuthSession not found")

        val authErrorClass = runCatching {
            Class.forName("com.xiaomi.xms.auth.AuthError", false, classLoader)
        }.getOrNull()

        val errorCodeField = authErrorClass?.declaredFields
            ?.firstOrNull { it.type == Int::class.javaPrimitiveType }
            ?.apply { isAccessible = true }

        val successMethod = authSession.declaredMethods.firstOrNull {
            it.name == "getAuthSuccess" && it.parameterCount == 0
        }?.apply { isAccessible = true } ?: return logInfo("getAuthSuccess not found")

        val errorMethods = authSession.declaredMethods.filter {
            it.name == "getAuthError" && it.parameterCount == 1
        }
        if (errorMethods.isEmpty()) return logInfo("getAuthError not found")

        errorMethods.forEach { method ->
            method.isAccessible = true
            hook(method).intercept { chain ->
                val error = runCatching { chain.getArg(0) }.getOrNull()
                if (error != null && authErrorClass != null &&
                    authErrorClass.isInstance(error) && errorCodeField != null
                ) {
                    runCatching { errorCodeField.setInt(error, 0) }
                }
                // 与 HyperCeiler 的解锁逻辑一致：错误分发时直接返回成功 Bundle。
                runCatching { successMethod.invoke(chain.thisObject) }
                    .getOrElse { chain.proceed() }
            }
        }
        logInfo("hooked XMSF AuthSession (${errorMethods.size} getAuthError)")
    }

    private fun hookSystemUiFocusWhitelist(classLoader: ClassLoader) {
        val manager = runCatching {
            Class.forName(
                "com.miui.systemui.notification.NotificationSettingsManager",
                false,
                classLoader,
            )
        }.getOrNull() ?: return logInfo("NotificationSettingsManager not found")

        val methods = manager.declaredMethods.filter {
            it.name == "canShowFocusState" || it.name == "canShowFocusStateApp"
        }
        if (methods.isEmpty()) return logInfo("focus whitelist methods not found")

        methods.forEach { method ->
            method.isAccessible = true
            hook(method).intercept { chain ->
                val target = chain.args.any { it is String && it == AUTORUNNER_PACKAGE }
                if (!target) {
                    chain.proceed()
                } else if (method.returnType == Boolean::class.javaPrimitiveType) {
                    true
                } else {
                    1
                }
            }
        }
        logInfo("hooked SystemUI focus whitelist (${methods.size} methods)")
    }

    private fun logInfo(message: String) {
        log(Log.INFO, TAG, message)
    }

    companion object {
        private const val TAG = "AutoRunnerXposed"
        private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
        private const val SYSTEM_UI_PACKAGE = "com.miui.systemui"
        private const val AUTORUNNER_PACKAGE = "cn.helilab.autorunner"
    }
}
