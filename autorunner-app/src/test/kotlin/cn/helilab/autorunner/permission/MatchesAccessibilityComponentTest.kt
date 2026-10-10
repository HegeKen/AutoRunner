package cn.helilab.autorunner.permission

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchesAccessibilityComponentTest {

    private val pkg = "cn.helilab.autorunner"
    private val cls = "cn.helilab.autorunner.accessibility.AutoRunnerAccessibilityService"

    @Test
    fun matchesRelativeClassSpelling() {
        assertTrue(
            matchesAccessibilityComponent(
                "$pkg/.accessibility.AutoRunnerAccessibilityService",
                pkg,
                cls,
            ),
        )
    }

    @Test
    fun matchesFullyQualifiedClassSpelling() {
        assertTrue(matchesAccessibilityComponent("$pkg/$cls", pkg, cls))
    }

    @Test
    fun matchesShortClassSpelling() {
        // 短名按 Android 规则直接拼在包名后：pkg/Foo → pkg.Foo。
        assertTrue(
            matchesAccessibilityComponent(
                "$pkg/AutoRunnerAccessibilityService",
                pkg,
                "$pkg.AutoRunnerAccessibilityService",
            ),
        )
        // 类在子包里时短名拼不出完整类名，应判不匹配。
        assertFalse(
            matchesAccessibilityComponent("$pkg/AutoRunnerAccessibilityService", pkg, cls),
        )
    }

    @Test
    fun rejectsDifferentPackage() {
        assertFalse(
            matchesAccessibilityComponent(
                "com.other/.accessibility.AutoRunnerAccessibilityService",
                pkg,
                cls,
            ),
        )
    }

    @Test
    fun rejectsDifferentClass() {
        assertFalse(matchesAccessibilityComponent("$pkg/.accessibility.Other", pkg, cls))
    }

    @Test
    fun rejectsEmptyOrMalformedEntries() {
        assertFalse(matchesAccessibilityComponent("", pkg, cls))
        assertFalse(matchesAccessibilityComponent("   ", pkg, cls))
        assertFalse(matchesAccessibilityComponent(pkg, pkg, cls)) // 无 '/'
        assertFalse(matchesAccessibilityComponent("/$cls", pkg, cls)) // 以 '/' 开头
    }
}
