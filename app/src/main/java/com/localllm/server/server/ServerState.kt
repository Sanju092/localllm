package com.localllm.server.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ServerStatus { STOPPED, LOADING, RUNNING, ERROR }

data class ServerState(
    val status: ServerStatus = ServerStatus.STOPPED,
    val port: Int = 8080,
    val modelName: String? = null,
    val contextSize: Int = 0,
    val startedAt: Long = 0,
    val error: String? = null,
    val activeRequests: Int = 0,
    val totalRequests: Int = 0,
    val lastTokensPerSecond: Double = 0.0,
)

@Singleton
class ServerStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun update(transform: (ServerState) -> ServerState) = _state.update(transform)
}
