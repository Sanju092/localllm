package com.localllm.server.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import com.localllm.server.data.model.InstalledModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadState(
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: Status,
    val error: String? = null,
) {
    enum class Status { PENDING, RUNNING, PAUSED, FAILED }

    val progress: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
}

/**
 * Lists, downloads, imports and deletes GGUF models. Downloads go through the system
 * [DownloadManager], so they are resumable and survive the app being closed.
 */
@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logs: LogRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    val modelsDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    init {
        refresh()
        if (prefs.all.isNotEmpty()) startPolling()
    }

    fun refresh() {
        _installed.value = modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { InstalledModel(it.name, it.absolutePath, it.length()) }
            ?: emptyList()
    }

    fun fileFor(fileName: String): File = File(modelsDir, fileName)

    fun download(url: String, requestedName: String? = null): Result<String> = runCatching {
        val uri = Uri.parse(url.trim())
        require(uri.scheme == "http" || uri.scheme == "https") { "URL must start with http(s)://" }
        var fileName = (requestedName ?: uri.lastPathSegment ?: "model.gguf").substringBefore('?')
        if (!fileName.endsWith(".gguf", true)) fileName += ".gguf"
        require(!fileFor(fileName).exists()) { "$fileName is already installed" }
        require(!prefs.contains(fileName)) { "$fileName is already downloading" }

        File(modelsDir, "$fileName.part").delete()
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription("LocalLLM Server model download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(File(modelsDir, "$fileName.part")))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = downloadManager.enqueue(request)
        prefs.edit { putLong(fileName, id) }
        _downloads.update { it + (fileName to DownloadState(fileName, 0, -1, DownloadState.Status.PENDING)) }
        logs.log("Download started: $fileName")
        startPolling()
        fileName
    }

    fun cancelDownload(fileName: String) {
        val id = prefs.getLong(fileName, -1)
        if (id >= 0) downloadManager.remove(id)
        prefs.edit { remove(fileName) }
        File(modelsDir, "$fileName.part").delete()
        _downloads.update { it - fileName }
        logs.log("Download cancelled: $fileName", LogLevel.WARN)
    }

    fun dismissFailed(fileName: String) {
        prefs.edit { remove(fileName) }
        _downloads.update { it - fileName }
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        fileFor(fileName).delete()
        refresh()
        logs.log("Deleted model $fileName")
    }

    /** Copies a GGUF picked with the system file picker into the models directory. */
    suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "imported.gguf"
            if (!name.endsWith(".gguf", true)) name += ".gguf"
            val target = fileFor(name)
            val tmp = File(modelsDir, "$name.part")
            logs.log("Importing $name…")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
            check(tmp.renameTo(target)) { "Could not move imported file" }
            refresh()
            logs.log("Imported $name (${target.length() / 1_000_000} MB)")
            name
        }.onFailure { logs.log("Import failed: ${it.message}", LogLevel.ERROR) }
    }

    @Volatile private var polling = false

    private fun startPolling() {
        if (polling) return
        polling = true
        scope.launch {
            while (isActive) {
                val active = poll()
                if (!active) break
                delay(700)
            }
            polling = false
        }
    }

    /** Returns true while any download is still in progress. */
    private fun poll(): Boolean {
        var anyActive = false
        val states = mutableMapOf<String, DownloadState>()
        for ((fileName, value) in prefs.all) {
            val id = value as? Long ?: continue
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                prefs.edit { remove(fileName) }
                continue
            }
            cursor.use { c ->
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val part = File(modelsDir, "$fileName.part")
                        if (part.renameTo(fileFor(fileName))) {
                            logs.log("Download complete: $fileName (${fileFor(fileName).length() / 1_000_000} MB)")
                        } else {
                            logs.log("Download finished but file could not be moved: $fileName", LogLevel.ERROR)
                        }
                        downloadManager.remove(id).takeIf { false }
                        prefs.edit { remove(fileName) }
                        refresh()
                    }
                    DownloadManager.STATUS_FAILED -> {
                        states[fileName] = DownloadState(fileName, done, total, DownloadState.Status.FAILED, "Failed (code $reason)")
                        if (_downloads.value[fileName]?.status != DownloadState.Status.FAILED) {
                            logs.log("Download failed: $fileName (code $reason)", LogLevel.ERROR)
                        }
                    }
                    else -> {
                        anyActive = true
                        val s = when (status) {
                            DownloadManager.STATUS_RUNNING -> DownloadState.Status.RUNNING
                            DownloadManager.STATUS_PAUSED -> DownloadState.Status.PAUSED
                            else -> DownloadState.Status.PENDING
                        }
                        states[fileName] = DownloadState(fileName, done, total, s)
                    }
                }
            }
        }
        _downloads.value = states
        return anyActive
    }
}
