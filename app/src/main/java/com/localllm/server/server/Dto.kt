@file:Suppress("PropertyName")

package com.localllm.server.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelObject(
    val id: String,
    @SerialName("object") val obj: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    val owned_by: String = "local",
    val name: String? = null,
)

@Serializable
data class ModelListResponse(
    @SerialName("object") val obj: String = "list",
    val data: List<ModelObject>,
)

@Serializable
data class ErrorBody(val message: String, val type: String, val code: String? = null)

@Serializable
data class ErrorResponse(val error: ErrorBody)
