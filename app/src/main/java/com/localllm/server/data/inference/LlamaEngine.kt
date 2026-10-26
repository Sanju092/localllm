package com.localllm.server.data.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Serializable
data class NativeToolCall(val id: String = "", val name: String = "", val arguments: String = "")

@Serializable
data class ChatResult(
    val content: String = "",
    val reasoning_content: String = "",
    val tool_calls: List<NativeToolCall> = emptyList(),
    val finish_reason: String = "stop",
    val prompt_tokens: Int = 0,
    val cached_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val prompt_ms: Double = 0.0,
    val generation_ms: Double = 0.0,
    val error: String? = null,
)

@Serializable
data class LoadedModelInfo(
    val description: String = "",
    val n_params: Long = 0,
    val size_bytes: Long = 0,
    val n_ctx: Int = 0,
    val n_ctx_train: Int = 0,
    val supports_tools: Boolean = false,
)

class InferenceException(message: String) : Exception(message)

/** Thread-safe wrapper around the native llama.cpp engine. One request is processed at a time. */
@Singleton
class LlamaEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mutex = Mutex()
    private var handle: Long = 0L
    private var initialised = false

    private val _loadedModel = MutableStateFlow<LoadedModel?>(null)
    val loadedModel: StateFlow<LoadedModel?> = _loadedModel.asStateFlow()

    data class LoadedModel(val path: String, val name: String, val info: LoadedModelInfo)

    val isLoaded: Boolean get() = handle != 0L

    private fun ensureInit() {
        if (!initialised) {
            LlamaNative.nativeInit(context.applicationInfo.nativeLibraryDir)
            initialised = true
        }
    }

    fun systemInfo(): String = runCatching { ensureInit(); LlamaNative.nativeSystemInfo() }.getOrDefault("")

    suspend fun loadModel(modelPath: String, contextSize: Int, threads: Int): LoadedModel =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureInit()
                freeLocked()
                val file = File(modelPath)
                if (!file.exists()) throw InferenceException("Model file not found: $modelPath")
                val h = LlamaNative.nativeLoadModel(modelPath, contextSize, threads)
                if (h == 0L) throw InferenceException("Failed to load model (out of memory or unsupported GGUF?)")
                handle = h
                val info = json.decodeFromString<LoadedModelInfo>(LlamaNative.nativeModelInfo(h))
                LoadedModel(modelPath, file.nameWithoutExtension, info).also { _loadedModel.value = it }
            }
        }

    suspend fun unload() = withContext(Dispatchers.IO) { mutex.withLock { freeLocked() } }

    private fun freeLocked() {
        if (handle != 0L) {
            LlamaNative.nativeFreeModel(handle)
            handle = 0L
        }
        _loadedModel.value = null
    }

    /**
     * Runs a chat completion. [request] is an OpenAI chat-completions body; [onDelta] receives
     * streamed `{content?, reasoning_content?}` objects and may return false to stop generation.
     */
    suspend fun chat(request: JsonObject, onDelta: (JsonObject) -> Boolean = { true }): ChatResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (handle == 0L) throw InferenceException("No model loaded")
                val ctx = coroutineContext
                val bytes = LlamaNative.nativeChat(
                    handle,
                    request.toString().toByteArray(Charsets.UTF_8),
                ) { delta ->
                    if (!ctx.isActive) return@nativeChat false
                    val obj = runCatching {
                        json.parseToJsonElement(delta.toString(Charsets.UTF_8)).jsonObject
                    }.getOrNull() ?: return@nativeChat true
                    onDelta(obj)
                }
                coroutineContext.ensureActive()
                val result = json.decodeFromString<ChatResult>(bytes.toString(Charsets.UTF_8))
                result.error?.let { throw InferenceException(it) }
                result
            }
        }
}
