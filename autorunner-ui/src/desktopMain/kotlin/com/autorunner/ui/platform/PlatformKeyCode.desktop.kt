package com.autorunner.ui.platform

import androidx.compose.ui.input.key.KeyEvent
import java.awt.event.KeyEvent as AwtKeyEvent

// 键盘映射 UI 只在 Android 平板上出现；desktop 端仅保证编译，AWT 键码与
// Android KEYCODE_* 不同也无妨（捕获框在 desktop 上永远不会显示）。
internal actual fun KeyEvent.platformNativeKeyCode(): Int =
    (nativeKeyEvent as? AwtKeyEvent)?.keyCode ?: 0
