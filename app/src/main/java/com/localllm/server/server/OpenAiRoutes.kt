package com.localllm.server.server

import com.localllm.server.data.inference.ChatResult
import com.localllm.server.data.inference.InferenceException
import com.localllm.server.data.inference.LlamaEngine
import com.localllm.server.data.model.ServerConfig
import com.localllm.server.data.repository.LogLevel
import com.localllm.server.data.repository.LogRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.http.CacheControl
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

const val MODEL_ID = "local"

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun Route.openAiRoutes(
    engine: LlamaEngine,
    config: () -> ServerConfig,
    logs: LogRepository,
    state: ServerStateHolder,
) {
    get("/health") { call.respondText("ok") }

    get("/") {
        val loaded = engine.loadedModel.value
        call.respondText(
            buildJsonObject {
                put("name", "LocalLLM Server")
                put("status", if (loaded != null) "ready" else "no_model")
                put("model", loaded?.name)
                put("endpoints", buildJsonArray {
                    add(JsonPrimitive("GET /v1/models"))
                    add(JsonPrimitive("POST /v1/chat/completions"))
                    add(JsonPrimitive("GET /health"))
                })
            }.toString(),
            ContentType.Application.Json,
        )
    }

    get("/v1/models") {
        val loaded = engine.loadedModel.value
        val data = if (loaded != null) listOf(ModelObject(id = MODEL_ID, name = loaded.name)) else emptyList()
        call.respondText(json.encodeToString(ModelListResponse.serializer(), ModelListResponse(data = data)), ContentType.Application.Json)
    }

    get("/v1/models/{id}") {
        val loaded = engine.loadedModel.value
        if (loaded == null) {
            call.respondError(HttpStatusCode.NotFound, "No model loaded", "not_found")
        } else {
            call.respondText(json.encodeToString(ModelObject.serializer(), ModelObject(id = MODEL_ID, name = loaded.name)), ContentType.Application.Json)
        }
    }

    post("/v1/chat/completions") {
        val started = System.currentTimeMillis()
        val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        if (body == null || body["messages"] !is JsonArray) {
            call.respondError(HttpStatusCode.BadRequest, "Request body must be JSON with a 'messages' array", "invalid_request_error")
            return@post
        }
        if (!engine.isLoaded) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "No model loaded yet", "model_not_loaded")
            return@post
        }

        val cfg = config()
        val request = withDefaults(body, cfg)
        val stream = body["stream"]?.jsonPrimitive?.booleanOrNull == true
        val includeUsage = (body["stream_options"] as? JsonObject)?.get("include_usage")?.jsonPrimitive?.booleanOrNull == true
        val nMessages = (body["messages"] as JsonArray).size
        val nTools = (body["tools"] as? JsonArray)?.size ?: 0
        logs.log("POST /v1/chat/completions ($nMessages msgs${if (nTools > 0) ", $nTools tools" else ""}${if (stream) ", stream" else ""})", LogLevel.REQUEST)
        state.update { it.copy(activeRequests = it.activeRequests + 1, totalRequests = it.totalRequests + 1) }

        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000
        try {
            val result = if (stream) {
                streamCompletion(call, engine, request, id, created, includeUsage)
            } else {
                val r = engine.chat(request)
                call.respondText(completionJson(id, created, r).toString(), ContentType.Application.Json)
                r
            }
            if (result != null) {
                val tps = if (result.generation_ms > 0) result.completion_tokens * 1000.0 / result.generation_ms else 0.0
                state.update { it.copy(lastTokensPerSecond = tps) }
                logs.log(
                    "Generated ${result.completion_tokens} tokens (prompt ${result.prompt_tokens}, cached ${result.cached_tokens}) " +
                        "• %.1f tok/s • %s • %.1fs".format(tps, result.finish_reason, (System.currentTimeMillis() - started) / 1000.0),
                )
            }
        } catch (e: InferenceException) {
            logs.log("Inference error: ${e.message}", LogLevel.ERROR)
            if (!stream) call.respondError(HttpStatusCode.BadRequest, e.message ?: "Inference failed", "invalid_request_error")
        } finally {
            state.update { it.copy(activeRequests = (it.activeRequests - 1).coerceAtLeast(0)) }
        }
    }
}

/** Fill in server-side defaults the client did not specify. */
private fun withDefaults(body: JsonObject, cfg: ServerConfig): JsonObject {
    val map = body.toMutableMap()
    if (map["temperature"] == null || map["temperature"] is JsonNull) map["temperature"] = JsonPrimitive(cfg.temperature)
    val hasMax = listOf("max_tokens", "max_completion_tokens").any { map[it] != null && map[it] !is JsonNull }
    if (!hasMax && cfg.maxTokens > 0) map["max_tokens"] = JsonPrimitive(cfg.maxTokens)
    return JsonObject(map)
}

private fun toolCallsJson(result: ChatResult, withIndex: Boolean): JsonArray = buildJsonArray {
    result.tool_calls.forEachIndexed { i, tc ->
        add(buildJsonObject {
            if (withIndex) put("index", i)
            put("id", tc.id)
            put("type", "function")
            putJsonObject("function") {
                put("name", tc.name)
                put("arguments", tc.arguments)
            }
        })
    }
}

private fun usageJson(r: ChatResult) = buildJsonObject {
    put("prompt_tokens", r.prompt_tokens)
    put("completion_tokens", r.completion_tokens)
    put("total_tokens", r.prompt_tokens + r.completion_tokens)
    putJsonObject("prompt_tokens_details") { put("cached_tokens", r.cached_tokens) }
}

private fun completionJson(id: String, created: Long, r: ChatResult): JsonObject = buildJsonObject {
    put("id", id)
    put("object", "chat.completion")
    put("created", created)
    put("model", MODEL_ID)
    put("choices", buildJsonArray {
        add(buildJsonObject {
            put("index", 0)
            putJsonObject("message") {
                put("role", "assistant")
                if (r.tool_calls.isNotEmpty() && r.content.isEmpty()) put("content", JsonNull) else put("content", r.content)
                if (r.reasoning_content.isNotEmpty()) put("reasoning_content", r.reasoning_content)
                if (r.tool_calls.isNotEmpty()) put("tool_calls", toolCallsJson(r, withIndex = false))
            }
            put("finish_reason", r.finish_reason)
        })
    })
    put("usage", usageJson(r))
}

private fun chunkJson(id: String, created: Long, delta: JsonObject, finishReason: String?, usage: JsonObject? = null) =
    buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", MODEL_ID)
        put("choices", buildJsonArray {
            if (usage == null) {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", finishReason?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
        if (usage != null) put("usage", usage)
    }

private suspend fun ByteWriteChannel.sse(data: String) {
    writeStringUtf8("data: $data\n\n")
    flush()
}

/** Streams the completion as Server-Sent Events. Returns null if the client went away. */
private suspend fun streamCompletion(
    call: ApplicationCall,
    engine: LlamaEngine,
    request: JsonObject,
    id: String,
    created: Long,
    includeUsage: Boolean,
): ChatResult? {
    var result: ChatResult? = null
    call.response.cacheControl(CacheControl.NoCache(null))
    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
        val deltas = Channel<JsonObject>(Channel.UNLIMITED)
        coroutineScope {
            val job = async(Dispatchers.IO) {
                try {
                    engine.chat(request) { delta -> deltas.trySendBlocking(delta).isSuccess }
                } finally {
                    deltas.close()
                }
            }
            sse(chunkJson(id, created, buildJsonObject { put("role", "assistant"); put("content", "") }, null).toString())
            for (delta in deltas) {
                sse(chunkJson(id, created, delta, null).toString())
            }
            val r = try {
                job.await()
            } catch (e: InferenceException) {
                sse(json.encodeToString(ErrorResponse.serializer(), ErrorResponse(ErrorBody(e.message ?: "error", "server_error"))))
                sse("[DONE]")
                throw e
            }
            if (r.tool_calls.isNotEmpty()) {
                sse(chunkJson(id, created, buildJsonObject { put("tool_calls", toolCallsJson(r, withIndex = true)) }, null).toString())
            }
            sse(chunkJson(id, created, JsonObject(emptyMap()), r.finish_reason).toString())
            if (includeUsage) sse(chunkJson(id, created, JsonObject(emptyMap()), null, usageJson(r)).toString())
            sse("[DONE]")
            result = r
        }
    }
    return result
}

suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String, type: String) {
    respondText(
        json.encodeToString(ErrorResponse.serializer(), ErrorResponse(ErrorBody(message, type))),
        ContentType.Application.Json,
        status,
    )
}

@Suppress("unused")
private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
