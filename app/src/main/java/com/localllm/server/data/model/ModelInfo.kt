package com.localllm.server.data.model

/** A downloadable model from the built-in catalog. */
data class CatalogModel(
    val id: String,
    val displayName: String,
    val params: String,
    val quant: String,
    val sizeLabel: String,
    val sizeBytes: Long,
    val tagline: String,
    val url: String,
) {
    val fileName: String get() = url.substringAfterLast('/').substringBefore('?')
}

object ModelCatalog {
    val models = listOf(
        CatalogModel(
            id = "phi-4-mini",
            displayName = "Phi-4-mini-instruct",
            params = "3.8B",
            quant = "Q4_K_M",
            sizeLabel = "2.5 GB",
            sizeBytes = 2_490_000_000L,
            tagline = "Recommended • strong reasoning & function calling",
            url = "https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen2.5-3b",
            displayName = "Qwen2.5-3B-Instruct",
            params = "3B",
            quant = "Q4_K_M",
            sizeLabel = "2.1 GB",
            sizeBytes = 2_100_000_000L,
            tagline = "Lighter • reliable tool calling",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
        ),
        CatalogModel(
            id = "functiongemma-270m",
            displayName = "FunctionGemma",
            params = "270M",
            quant = "Q4_K_M",
            sizeLabel = "0.25 GB",
            sizeBytes = 250_000_000L,
            tagline = "Tiny • tool-calling router only, low-RAM devices",
            url = "https://huggingface.co/unsloth/functiongemma-270m-it-GGUF/resolve/main/functiongemma-270m-it-Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen2.5-0.5b",
            displayName = "Qwen2.5-0.5B-Instruct",
            params = "0.5B",
            quant = "Q4_K_M",
            sizeLabel = "0.4 GB",
            sizeBytes = 400_000_000L,
            tagline = "Tiny • quick testing, weak reasoning",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        ),
    )

    fun byFileName(fileName: String): CatalogModel? = models.firstOrNull { it.fileName == fileName }
}

/** A GGUF file present on the device. */
data class InstalledModel(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
) {
    val displayName: String get() = ModelCatalog.byFileName(fileName)?.displayName ?: fileName.removeSuffix(".gguf")
}
