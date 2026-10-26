package com.autorunner.core.settings

import com.autorunner.core.model.ComboConfig
import com.autorunner.core.model.ComboSlotConfig
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.storage.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComboStoreTest {

    @Test
    fun savesAndRestoresBothSlots() {
        val store = ComboStore(InMemoryKeyValueStore())
        val config = ComboConfig(
            slotA = ComboSlotConfig(
                scriptId = "a",
                execution = ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = 0,
                    intervalMs = 14 * 60_000L,
                ),
            ),
            slotB = ComboSlotConfig(
                scriptId = "b",
                execution = ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = 0,
                    intervalMs = 50 * 60_000L,
                    startDelayMs = 10 * 60_000L,
                ),
            ),
        )

        store.save(config)

        val restored = store.load()
        assertEquals("a", restored.slotA.scriptId)
        assertEquals(14 * 60_000L, restored.slotA.execution.intervalMs)
        assertEquals("b", restored.slotB.scriptId)
        assertEquals(50 * 60_000L, restored.slotB.execution.intervalMs)
        assertEquals(10 * 60_000L, restored.slotB.execution.startDelayMs)
    }

    @Test
    fun slotAStartDelayIsAlwaysCleared() {
        val store = ComboStore(InMemoryKeyValueStore())
        store.save(
            ComboConfig(
                slotA = ComboSlotConfig(
                    scriptId = "a",
                    execution = ExecutionConfig(startDelayMs = 5_000L),
                ),
                slotB = ComboSlotConfig(scriptId = "b"),
            ),
        )

        assertEquals(0L, store.load().slotA.execution.startDelayMs)
    }

    @Test
    fun malformedOrMissingConfigurationFallsBackToEmpty() {
        val backing = InMemoryKeyValueStore()
        backing.putString("combo.config", "{ not json")
        val store = ComboStore(backing)

        val loaded = store.load()
        assertTrue(loaded.slotA.scriptId.isEmpty())
        assertTrue(loaded.slotB.scriptId.isEmpty())
        assertEquals(ComboConfig.Empty, loaded)
    }
}
