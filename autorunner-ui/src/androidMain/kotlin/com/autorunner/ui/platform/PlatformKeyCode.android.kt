package com.autorunner.ui.platform

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent

internal actual fun KeyEvent.platformNativeKeyCode(): Int =
    (nativeKeyEvent as? AndroidKeyEvent)?.keyCode ?: 0
