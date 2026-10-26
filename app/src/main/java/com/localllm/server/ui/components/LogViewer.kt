package com.localllm.server.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.server.data.repository.LogEntry
import com.localllm.server.data.repository.LogLevel
import com.localllm.server.ui.theme.Amber
import com.localllm.server.ui.theme.Green
import com.localllm.server.ui.theme.Red

@Composable
fun LogLine(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> Red
        LogLevel.WARN -> Amber
        LogLevel.REQUEST -> Green
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("[${entry.time}] ", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
    }
}
