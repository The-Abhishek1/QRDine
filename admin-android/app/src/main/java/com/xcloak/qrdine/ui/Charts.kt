package com.xcloak.qrdine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Column chart built from plain boxes: no chart library needed. */
@Composable
fun BarColumns(values: List<Long>, labels: List<String>, color: Color, height: Dp = 100.dp) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { v ->
            Box(
                Modifier.weight(1f).fillMaxHeight((v / max).coerceIn(0.03f, 1f))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (v > 0) color else MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
    }
}
