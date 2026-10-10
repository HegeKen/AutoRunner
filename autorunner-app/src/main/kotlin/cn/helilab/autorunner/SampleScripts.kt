package cn.helilab.autorunner

import android.content.Context
import android.util.Log
import com.autorunner.core.di.AppContainer
import com.autorunner.core.model.ArScriptConventions
import com.autorunner.core.script.ImportResult
import kotlinx.coroutines.launch

/** 内置示例脚本所在目录（`autorunner-app/src/androidMain/assets/samples`）。 */
private const val SAMPLE_ASSET_DIR = "samples"

/** 记录「示例脚本已导入」，保证只在首次启动时塞一次。 */
private const val SEEDED_KEY = "sample_scripts_seeded_v1"

/**
 * 首次启动时把 `assets/samples/` 下的示例脚本导入脚本库，让用户装上就有可直接运行的样例。
 *
 * 用 [SEEDED_KEY] 做一次性标记，而不是用「脚本库是否为空」判断：否则用户手动删光脚本后，
 * 下次启动又会被重新塞回来。导入失败（读到损坏的 asset）时不写标记，留待下次重试。
 */
internal fun seedSampleScripts(context: Context, container: AppContainer) {
    val store = container.keyValueStore
    if (store.getBoolean(SEEDED_KEY, false)) return
    container.scope.launch {
        runCatching { importSampleScripts(context, container) }
            .onSuccess { count ->
                store.putBoolean(SEEDED_KEY, true)
                Log.i(AutoRunnerApplication.TAG, "已导入 $count 个内置示例脚本")
            }
            .onFailure { error ->
                Log.w(AutoRunnerApplication.TAG, "内置示例脚本导入失败：${error.message}")
            }
    }
}

/** 逐个导入 [SAMPLE_ASSET_DIR] 下的 `.arscript`，返回成功条数。 */
private suspend fun importSampleScripts(context: Context, container: AppContainer): Int {
    val names = context.assets.list(SAMPLE_ASSET_DIR).orEmpty()
        .filter { it.endsWith(ArScriptConventions.DOT_EXTENSION) }
        .sorted()
    var imported = 0
    names.forEach { name ->
        val text = context.assets.open("$SAMPLE_ASSET_DIR/$name").use { it.readBytes().decodeToString() }
        when (val result = container.scriptRepository.importFromText(text, name)) {
            is ImportResult.Success -> imported++
            is ImportResult.Failure ->
                Log.w(AutoRunnerApplication.TAG, "示例脚本 $name 导入失败：${result.message}")
        }
    }
    return imported
}
