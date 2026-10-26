package com.localllm.server.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localllm.server.server.ServerStatus
import com.localllm.server.ui.MainViewModel
import com.localllm.server.ui.components.LogLine
import com.localllm.server.ui.components.SectionCard
import com.localllm.server.ui.components.formatUptime
import com.localllm.server.ui.theme.Amber
import com.localllm.server.ui.theme.Green
import com.localllm.server.ui.theme.Red

@Composable
fun HomeScreen(onOpenModels: () -> Unit, onOpenLogs: () -> Unit, vm: MainViewModel = hiltViewModel()) {
    val state by vm.serverState.collectAsState()
    val config by vm.config.collectAsState()
    val ip by vm.lanIp.collectAsState()
    val now by vm.now.collectAsState()
    val logs by vm.logs.collectAsState()
    val installed by vm.installed.collectAsState()

    val (statusColor, statusText) = when (state.status) {
        ServerStatus.RUNNING -> Green to "Running"
        ServerStatus.LOADING -> Amber to "Loading model…"
        ServerStatus.ERROR -> Red to "Error"
        ServerStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant to "Stopped"
    }
    val baseUrl = "http://${ip ?: "<phone-ip>"}:${config.port}/v1"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LocalLLM Server", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard("Status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(8.dp))
                Text(statusText, color = statusColor, fontWeight = FontWeight.SemiBold)
                if (state.status == ServerStatus.RUNNING) {
                    Spacer(Modifier.weight(1f))
                    Text("Uptime ${formatUptime(now - state.startedAt)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.status == ServerStatus.LOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { Text(it, color = Red, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(8.dp))
            InfoRow("Model", state.modelName ?: config.selectedModelFileName ?: installed.firstOrNull()?.fileName ?: "none")
            if (state.status == ServerStatus.RUNNING) {
                InfoRow("Context", "${state.contextSize} tokens")
                InfoRow("Requests", "${state.totalRequests} total, ${state.activeRequests} active")
                if (state.lastTokensPerSecond > 0) InfoRow("Speed", "%.1f tok/s (last)".format(state.lastTokensPerSecond))
            }
            Spacer(Modifier.height(12.dp))
            when {
                installed.isEmpty() -> Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) { Text("Download a model") }
                state.status == ServerStatus.RUNNING || state.status == ServerStatus.LOADING ->
                    Button(
                        onClick = vm::stopServer,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                    ) {
                        Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop server")
                    }
                else -> Button(onClick = vm::startServer, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start server")
                }
            }
        }

        SectionCard("Connect") {
            Text("Base URL for Hermes / OpenAI clients:", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(baseUrl, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { vm.copy("Base URL", baseUrl) }) { Icon(Icons.Filled.ContentCopy, "Copy base URL") }
            }
            InfoRow("Model id", "local")
            InfoRow("API key", if (config.apiKey.isBlank()) "none (any value works)" else "required")
            if (ip == null) Text("No network connection — join the same Wi-Fi as your laptop.", color = Amber)
        }

        SectionCard("Recent activity") {
            if (logs.isEmpty()) Text("No activity yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            logs.takeLast(6).forEach { LogLine(it) }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.padding(top = 8.dp)) { Text("View all logs") }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value)
    }
}
