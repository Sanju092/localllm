package com.localllm.server.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localllm.server.ui.MainViewModel
import com.localllm.server.ui.components.SectionCard

@Composable
fun SettingsScreen(vm: MainViewModel = hiltViewModel()) {
    val config by vm.config.collectAsState()
    val context = LocalContext.current

    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    var bind by remember(config.bindAddress) { mutableStateOf(config.bindAddress) }
    var ctx by remember(config.contextSize) { mutableStateOf(config.contextSize.toString()) }
    var threads by remember(config.threads) { mutableStateOf(config.threads.toString()) }
    var maxTokens by remember(config.maxTokens) { mutableStateOf(config.maxTokens.toString()) }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var temp by remember(config.temperature) { mutableFloatStateOf(config.temperature) }

    val dirty = port != config.port.toString() || bind != config.bindAddress || ctx != config.contextSize.toString() ||
        threads != config.threads.toString() || maxTokens != config.maxTokens.toString() || apiKey != config.apiKey ||
        temp != config.temperature

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard("Server") {
            NumberField("Port", port) { port = it }
            OutlinedTextField(bind, { bind = it }, label = { Text("Bind address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                apiKey, { apiKey = it.trim() },
                label = { Text("API key (optional)") },
                supportingText = { Text("If set, clients must send Authorization: Bearer <key>") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Inference") {
            NumberField("Context size (tokens)", ctx) { ctx = it }
            NumberField("Threads (0 = auto)", threads) { threads = it }
            NumberField("Default max output tokens", maxTokens) { maxTokens = it }
            Text("Default temperature: %.2f".format(temp), modifier = Modifier.padding(top = 8.dp))
            Slider(value = temp, onValueChange = { temp = (it * 20).toInt() / 20f }, valueRange = 0f..2f)
            Text("Changing model settings reloads the model if the server is running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Button(
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val p = port.toIntOrNull()?.takeIf { it in 1..65535 }
                val c = ctx.toIntOrNull()?.takeIf { it in 256..131072 }
                val t = threads.toIntOrNull()?.takeIf { it in 0..32 }
                val m = maxTokens.toIntOrNull()?.takeIf { it in 1..32768 }
                if (p == null || c == null || t == null || m == null || bind.isBlank()) {
                    vm.toast("Invalid value (port 1-65535, context 256-131072, threads 0-32)")
                    return@Button
                }
                vm.saveAndRestart { it.copy(port = p, bindAddress = bind.trim(), contextSize = c, threads = t, maxTokens = m, apiKey = apiKey, temperature = temp) }
                vm.toast("Saved")
            },
        ) { Text("Save") }

        SectionCard("Behavior") {
            ToggleRow("Auto-start server when app opens", config.autoStartServer) { v -> vm.updateConfig { it.copy(autoStartServer = v) } }
            ToggleRow("Keep screen on while app is open", config.keepScreenOn) { v -> vm.updateConfig { it.copy(keepScreenOn = v) } }
            ToggleRow("Start server on boot", config.startOnBoot) { v -> vm.updateConfig { it.copy(startOnBoot = v) } }
            val pm = context.getSystemService(PowerManager::class.java)
            var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
            LaunchedEffect(Unit) { ignoring = pm.isIgnoringBatteryOptimizations(context.packageName) }
            if (!ignoring) {
                OutlinedButton(
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = {
                        @Suppress("BatteryLife")
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    },
                ) { Text("Disable battery optimization") }
            } else {
                Text("Battery optimization: disabled for this app ✓", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }

        SectionCard("About") {
            Text("LocalLLM Server 1.0.0 • llama.cpp", style = MaterialTheme.typography.bodySmall)
            Text(vm.systemInfo, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
