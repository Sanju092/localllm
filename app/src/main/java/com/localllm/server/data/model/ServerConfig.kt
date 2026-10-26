package com.localllm.server.data.model

data class ServerConfig(
    val port: Int = 8080,
    val bindAddress: String = "0.0.0.0",
    val contextSize: Int = 4096,
    val threads: Int = 0, // 0 = auto
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val apiKey: String = "",
    val autoStartServer: Boolean = true,
    val keepScreenOn: Boolean = false,
    val startOnBoot: Boolean = false,
    val selectedModelFileName: String? = null,
)
