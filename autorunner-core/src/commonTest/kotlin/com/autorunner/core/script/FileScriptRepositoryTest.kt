package com.autorunner.core.script

import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.TapStep
import com.autorunner.core.serialization.ArScriptCodec
import com.autorunner.core.storage.InMemoryScriptStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileScriptRepositoryTest {

    private val metrics = ScreenMetrics(1080, 2400, 2.75f)

    private fun repository(storage: InMemoryScriptStorage = InMemoryScriptStorage()) = storage to
        FileScriptRepository(storage = storage, codec = ArScriptCodec(), metricsProvider = { metrics })

    @Test
    fun savesLoadsAndListsScripts() = runTest {
        val (storage, repository) = repository()

        val saved = repository.save(
            id = null,
            script = ScriptModel(
                execution = ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 3),
                flow = listOf(TapStep(10f, 20f)),
            ),
        )

        assertTrue(saved.fileName.endsWith(".arscript"))
        assertEquals(metrics.widthPx, saved.script.info.device.width)
        assertTrue(saved.script.info.createdAt.isNotBlank(), "createdAt must be stamped")

        val listed = repository.refresh()
        assertEquals(1, listed.size)
        assertEquals(saved.id, listed.single().id)

        val loaded = repository.load(saved.id)
        assertNotNull(loaded)
        assertEquals(1, loaded.stepCount)
        assertEquals(ExecutionMode.REPEAT, loaded.execution.mode)
        assertNotNull(storage.read(saved.fileName))
    }

    @Test
    fun updatesAnExistingScriptInPlace() = runTest {
        val (_, repository) = repository()
        val saved = repository.save(null, ScriptModel(flow = listOf(TapStep(1f, 1f))))

        val updated = repository.save(saved.id, saved.script.copy(flow = listOf(TapStep(1f, 1f), TapStep(2f, 2f))))

        assertEquals(saved.id, updated.id)
        assertEquals(saved.fileName, updated.fileName)
        assertEquals(2, repository.refresh().single().stepCount)
    }

    @Test
    fun renameDuplicateAndDelete() = runTest {
        val (_, repository) = repository()
        val saved = repository.save(null, ScriptModel(flow = listOf(TapStep(1f, 1f))))

        val renamed = repository.rename(saved.id, "新名字")
        assertNotNull(renamed)
        assertEquals("新名字", renamed.name)
        assertEquals(saved.id, renamed.id)

        val copy = repository.duplicate(saved.id)
        assertNotNull(copy)
        assertTrue(copy.name.contains("副本"))
        assertEquals(2, repository.refresh().size)

        assertTrue(repository.delete(saved.id))
        assertEquals(1, repository.refresh().size)
        assertNull(repository.load(saved.id))
    }

    @Test
    fun importsAndRejectsBrokenPayloads() = runTest {
        val (_, repository) = repository()
        val payload = ArScriptCodec().encode(
            ScriptModel(info = com.autorunner.core.model.ScriptInfo(name = "导入的脚本"), flow = listOf(TapStep(5f, 5f))),
        )

        val imported = repository.importFromText(payload, fileName = "imported.arscript")
        assertTrue(imported is ImportResult.Success)
        assertEquals("导入的脚本", (imported as ImportResult.Success).record.name)
        assertEquals(1, repository.refresh().size)

        val broken = repository.importFromText("{ definitely not a script }")
        assertTrue(broken is ImportResult.Failure)

        val empty = repository.importFromText("""{"version":"1.0","flow":[]}""")
        assertTrue(empty is ImportResult.Failure)
    }

    @Test
    fun importGeneratesUniqueNamesByDefault() = runTest {
        val (_, repository) = repository()
        val payload = ArScriptCodec().encode(
            ScriptModel(info = com.autorunner.core.model.ScriptInfo(name = "同名"), flow = listOf(TapStep(1f, 1f))),
        )

        repository.importFromText(payload, "same.arscript", overwrite = false)
        repository.importFromText(payload, "same.arscript", overwrite = false)

        val names = repository.refresh().map { it.fileName }
        assertEquals(2, names.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun exportsPrettyJsonAndSuggestsAFileName() = runTest {
        val (_, repository) = repository()
        val saved = repository.save(null, ScriptModel(info = com.autorunner.core.model.ScriptInfo(name = "我的脚本"), flow = listOf(TapStep(1f, 1f))))

        val text = repository.exportToText(saved.id)
        assertNotNull(text)
        assertTrue(text.contains("\"type\": \"tap\""))
        assertTrue(text.contains("\n"))

        assertEquals("我的脚本.arscript", repository.suggestedFileName(saved.id))
    }

    @Test
    fun metricsAreInjectedIntoNewScripts() = runTest {
        val (_, repository) = repository()
        val saved = repository.save(null, ScriptModel(flow = listOf(TapStep(1f, 1f))))

        assertEquals(1080, saved.script.info.device.width)
        assertEquals(2400, saved.script.info.device.height)
    }
}
