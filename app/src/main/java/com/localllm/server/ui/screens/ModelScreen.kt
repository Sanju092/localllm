package com.localllm.server.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localllm.server.data.model.ModelCatalog
import com.localllm.server.data.repository.DownloadState
import com.localllm.server.ui.MainViewModel
import com.localllm.server.ui.components.SectionCard
import com.localllm.server.ui.components.formatBytes
import com.localllm.server.ui.theme.Red

@Composable
fun ModelScreen(vm: MainViewModel = hiltViewModel()) {
    val installed by vm.installed.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val config by vm.config.collectAsState()
    val loaded by vm.loadedModel.collectAsState()
    var customUrl by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val selected = config.selectedModelFileName ?: installed.firstOrNull()?.fileName

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Models", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp)) }

        item {
            SectionCard("Installed") {
                if (installed.isEmpty()) Text("No models yet. Download one below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                installed.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.selectModel(m.fileName) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m.fileName == selected, onClick = { vm.selectModel(m.fileName) })
                        Column(Modifier.weight(1f)) {
                            Text(m.fileName, fontWeight = FontWeight.Medium)
                            val tag = if (loaded?.path == m.path) " • loaded" else ""
                            Text(formatBytes(m.sizeBytes) + tag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirmDelete = m.fileName }) { Icon(Icons.Filled.Delete, "Delete ${m.fileName}") }
                    }
                }
            }
        }

        if (downloads.isNotEmpty()) {
            item {
                SectionCard("Downloading") {
                    downloads.values.forEach { d -> DownloadRow(d, onCancel = { vm.cancelDownload(d.fileName) }, onDismiss = { vm.dismissDownload(d.fileName) }) }
                }
            }
        }

        item {
            SectionCard("Catalog") {
                ModelCatalog.models.forEach { m ->
                    val isInstalled = installed.any { it.fileName == m.fileName }
                    val isDownloading = downloads.containsKey(m.fileName)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, fontWeight = FontWeight.Medium)
                            Text("${m.params} • ${m.quant} • ${m.sizeLabel}", style = MaterialTheme.typography.bodySmall)
                            Text(m.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            isInstalled -> Text("Installed", color = MaterialTheme.colorScheme.primary)
                            isDownloading -> Text("…")
                            else -> Button(onClick = { vm.download(m) }) { Text("Get") }
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Custom GGUF") {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Direct .gguf URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadCustom(customUrl); customUrl = "" }, enabled = customUrl.isNotBlank()) { Text("Download") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import file…") }
                }
            }
        }
        item { Text(" ") }
    }

    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete model?") },
            text = { Text(name) },
            confirmButton = { TextButton(onClick = { vm.deleteModel(name); confirmDelete = null }) { Text("Delete", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadRow(d: DownloadState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.fileName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (d.status == DownloadState.Status.FAILED) TextButton(onClick = onDismiss) { Text("Dismiss") }
            else TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (d.totalBytes > 0) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        val status = when (d.status) {
            DownloadState.Status.FAILED -> d.error ?: "Failed"
            DownloadState.Status.PAUSED -> "Paused (waiting for network)"
            DownloadState.Status.PENDING -> "Pending"
            DownloadState.Status.RUNNING -> "${formatBytes(d.downloadedBytes)} / ${formatBytes(d.totalBytes)} (${(d.progress * 100).toInt()}%)"
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (d.status == DownloadState.Status.FAILED) Red else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
