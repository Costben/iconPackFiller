package dev.artplus.iconpackfiller.provider

/**
 * Provider 配置。apiKey 由 [dev.artplus.iconpackfiller.settings.SettingsStore] 解密后注入，
 * 不持久化在此对象中。
 */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val mode: OpenAIMode = OpenAIMode.IMAGES,
    val quality: String = "high",
    val background: String = "transparent",
    /**
     * 该模型的透明直出声明。管线据此选择提示词版本：
     * 可透明直出 → 不让模型画任何背景；否则走键色底 + 本端抠除。
     */
    val transparency: TransparencyPreference = TransparencyPreference.AUTO,
    val connectTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 300_000,
    val maxRetries: Int = 2,
    /** 并发上限（1-2 默认）。 */
    val concurrency: Int = 1,
    /** 全局调用上限；null 不限。 */
    val callLimit: Int? = null,
)

enum class ProviderKind(val label: String) {
    OPENAI("OpenAI 兼容"),
    GEMINI("Google Gemini 原生"),
    ;

    companion object {
        fun fromValue(value: String?): ProviderKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OPENAI
    }
}

/**
 * Provider 工厂。
 */
object ImageProviderFactory {
    fun create(config: ProviderConfig): ImageProvider = when (config.kind) {
        ProviderKind.OPENAI -> OpenAIImagesProvider(config)
        ProviderKind.GEMINI -> GeminiProvider(config)
    }
}