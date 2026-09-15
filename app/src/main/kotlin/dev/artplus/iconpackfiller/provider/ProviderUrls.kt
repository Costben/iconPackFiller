package dev.artplus.iconpackfiller.provider

/**
 * URL 归一化。从 ArtPlus `GptClient.kt` 移植。
 */
object ProviderUrls {

    fun normalizeResponsesUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/responses") -> normalized
            normalized.endsWith("/v1") -> "$normalized/responses"
            "/v1/" in "$normalized/" -> "$normalized/responses"
            else -> "$normalized/v1/responses"
        }
    }

    fun normalizeImagesEditUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/images/edits") -> normalized
            normalized.endsWith("/v1") -> "$normalized/images/edits"
            "/v1/" in "$normalized/" -> "$normalized/images/edits"
            else -> "$normalized/v1/images/edits"
        }
    }

    /**
     * Chat Completions 端点：`{base}/v1/chat/completions`。
     *
     * 聚合网关（new-api 等）在 chat 协议上承载图像模型；实测原生 `:generateContent`
     * 会返回 `model_not_found`，必须走这里。
     */
    fun normalizeChatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/chat/completions") -> normalized
            normalized.endsWith("/v1") -> "$normalized/chat/completions"
            "/v1/" in "$normalized/" -> "$normalized/chat/completions"
            else -> "$normalized/v1/chat/completions"
        }
    }

    /**
     * Gemini `generateContent` 端点：
     * `{base}/v1beta/models/{model}:generateContent`
     */
    fun geminiGenerateContentUrl(baseUrl: String, model: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        val base = when {
            normalized.endsWith("/v1beta") -> normalized
            normalized.endsWith("/v1") -> normalized.removeSuffix("/v1") + "/v1beta"
            else -> "$normalized/v1beta"
        }
        return "$base/models/$model:generateContent"
    }

    /**
     * 连接测试端点（GET）。OpenAI 兼容用 `/v1/models`，Gemini 用 `/v1beta/models`。
     */
    fun modelsListUrl(baseUrl: String, kind: ProviderKind): String {
        val normalized = baseUrl.trim().trimEnd('/')
        val suffix = if (kind == ProviderKind.GEMINI) "v1beta" else "v1"
        return when {
            normalized.endsWith("/$suffix") -> "$normalized/models"
            normalized.endsWith("/models") -> normalized
            else -> "$normalized/$suffix/models"
        }
    }
}