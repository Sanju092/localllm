package com.localllm.server.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.server.data.inference.LlamaEngine
import com.localllm.server.data.model.CatalogModel
import com.localllm.server.data.model.ServerConfig
import com.localllm.server.data.repository.LogRepository
import com.localllm.server.data.repository.ModelRepository
import com.localllm.server.data.repository.SettingsRepository
import com.localllm.server.server.ServerStateHolder
import com.localllm.server.server.ServerStatus
import com.localllm.server.service.LlmServerService
import com.localllm.server.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared view model for all screens. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val app: Application,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val logRepo: LogRepository,
    private val stateHolder: ServerStateHolder,
    private val engine: LlamaEngine,
) : AndroidViewModel(app) {

    val serverState = stateHolder.state
    val config: StateFlow<ServerConfig> = settings.config.stateIn(viewModelScope, SharingStarted.Eagerly, ServerConfig())
    val installed = models.installed
    val downloads = models.downloads
    val logs = logRepo.logs
    val loadedModel = engine.loadedModel

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _lanIp = MutableStateFlow(NetworkUtils.lanIpAddress())
    val lanIp: StateFlow<String?> = _lanIp.asStateFlow()

    val systemInfo: String by lazy { engine.systemInfo() }

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                if (_now.value / 1000 % 5 == 0L) _lanIp.value = NetworkUtils.lanIpAddress()
                delay(1000)
            }
        }
    }

    fun startServer() {
        models.refresh()
        LlmServerService.start(app)
    }

    fun stopServer() = LlmServerService.stop(app)

    fun restartIfRunning() {
        if (serverState.value.status == ServerStatus.RUNNING || serverState.value.status == ServerStatus.LOADING) {
            LlmServerService.start(app)
        }
    }

    fun selectModel(fileName: String) = viewModelScope.launch {
        settings.update { it.copy(selectedModelFileName = fileName) }
        restartIfRunning()
    }

    fun download(model: CatalogModel) = toastResult(models.download(model.url, model.fileName))

    fun downloadCustom(url: String) = toastResult(models.download(url))

    private fun toastResult(r: Result<String>) {
        r.onFailure { toast(it.message ?: "Download failed") }
        r.onSuccess { toast("Downloading $it") }
    }

    fun cancelDownload(fileName: String) = models.cancelDownload(fileName)
    fun dismissDownload(fileName: String) = models.dismissFailed(fileName)

    fun deleteModel(fileName: String) = viewModelScope.launch {
        if (loadedModel.value?.path?.endsWith(fileName) == true) {
            toast("Stop the server before deleting the loaded model")
            return@launch
        }
        models.delete(fileName)
        if (config.value.selectedModelFileName == fileName) settings.update { it.copy(selectedModelFileName = null) }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        models.import(uri).onSuccess { toast("Imported $it") }.onFailure { toast("Import failed: ${it.message}") }
    }

    fun updateConfig(transform: (ServerConfig) -> ServerConfig) = viewModelScope.launch { settings.update(transform) }

    /** Persists settings, then reloads the server so it picks them up. */
    fun saveAndRestart(transform: (ServerConfig) -> ServerConfig) = viewModelScope.launch {
        settings.update(transform)
        restartIfRunning()
    }

    fun clearLogs() = logRepo.clear()

    fun copy(label: String, text: String) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied $text")
    }

    fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
}
