package com.localllm.server.server

import com.localllm.server.data.inference.LlamaEngine
import com.localllm.server.data.model.ServerConfig
import com.localllm.server.data.repository.LogLevel
import com.localllm.server.data.repository.LogRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the embedded Ktor (CIO) HTTP server. */
@Singleton
class ServerManager @Inject constructor(
    private val engine: LlamaEngine,
    private val logs: LogRepository,
    private val state: ServerStateHolder,
) {
    private var server: EmbeddedServer<*, *>? = null
    @Volatile private var config = ServerConfig()

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(cfg: ServerConfig) {
        if (server != null) stop()
        config = cfg
        server = embeddedServer(CIO, port = cfg.port, host = cfg.bindAddress) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logs.log("Unhandled error on ${call.request.path()}: ${cause.message}", LogLevel.ERROR)
                    call.respondError(HttpStatusCode.InternalServerError, cause.message ?: "Internal error", "server_error")
                }
            }
            // Optional bearer-token auth for /v1/*
            install(createApplicationPlugin("ApiKeyAuth") {
                onCall { call ->
                    val key = config.apiKey
                    if (key.isNotBlank() && call.request.path().startsWith("/v1") && call.request.httpMethod != HttpMethod.Options) {
                        if (call.request.headers[HttpHeaders.Authorization] != "Bearer $key") {
                            logs.log("Rejected ${call.request.path()} (bad API key)", LogLevel.WARN)
                            call.respondError(HttpStatusCode.Unauthorized, "Invalid API key", "invalid_api_key")
                        }
                    }
                }
            })
            routing {
                openAiRoutes(this@ServerManager.engine, { config }, logs, state)
            }
        }.start(wait = false)
        logs.log("HTTP server listening on ${cfg.bindAddress}:${cfg.port}")
    }

    @Synchronized
    fun stop() {
        server?.let {
            it.stop(500, 2000)
            logs.log("HTTP server stopped")
        }
        server = null
    }
}
