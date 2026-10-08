package com.autorunner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Small status chip (running / paused / idle / recording). */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
) {
    Row(
        modifier = modifier
            .background(color = color, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MiuixTheme.textStyles.footnote2,
            // 药丸永远单行：窄栏里换行会把徽标撑成两行文字块（平板左栏出现过）。
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Neutral chip used for action type badges. */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(
                color = MiuixTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
            style = MiuixTheme.textStyles.footnote2,
        )
    }
}
