package com.autorunner.ui.platform

import androidx.compose.ui.input.key.KeyEvent

/**
 * 返回按键的平台原生键码（Android 上即 `android.view.KeyEvent.KEYCODE_*`）。
 *
 * CMP 1.12 的 common [KeyEvent] 只暴露 `type` / `utf16CodePoint` /
 * `nativeKeyEvent`，不再提供 `key` / `nativeKeyCode`；平板键盘映射需要
 * Android 键码与 `GamepadButtonMapping.keyCode` 中存储的值比较，因此用
 * expect/actual 下探一层。取不到时返回 `0`，调用方按不可绑定键拒绝。
 */
internal expect fun KeyEvent.platformNativeKeyCode(): Int
