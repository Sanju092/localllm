package com.localllm.server.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localllm.server.ui.MainViewModel
import com.localllm.server.ui.components.LogLine

@Composable
fun LogScreen(vm: MainViewModel = hiltViewModel()) {
    val logs by vm.logs.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.copy("Logs", logs.joinToString("\n") { "[${it.time}] ${it.message}" }) }) { Text("Copy") }
            TextButton(onClick = vm::clearLogs) { Text("Clear") }
        }
        if (logs.isEmpty()) Text("No log entries yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logs) { LogLine(it) }
        }
    }
}
