package dev.artplus.iconpackfiller.provider

/**
 * 接口协议预设（BYOK 约定：先选协议标准，再填 URL / Key / 模型）。
 *
 * 命名遵循业界惯例（Cline 的 "OpenAI Compatible"、Cherry Studio 的端点类型表）：
 * 用**协议标准名**而不是产品内部叫法，用户能直接对照网关文档填写。
 *
 * 存储上不新增字段：协议与既有的 [ProviderKind] + [OpenAIMode] 双向对应
 * （见 [of]，Gemini 槽位的 mode 无意义），因此配置格式零变更、旧配置自动
 * 以标准协议名呈现。
 */
enum class ApiProtocol(
    /** 列表主标题：协议标准名。 */
    val label: String,
    /** 紧凑标签（模型选择器里的协议角标）。 */
    val shortLabel: String,
    /** 下拉菜单里的短名（收起值与列表项共用，避免长文本换行）。 */
    val menuLabel: String,
    /** 一句话说明什么时候选它。 */
    val hint: String,
    /** 请求形态（展示用）。 */
    val endpointLine: String,
    /** 鉴权方式说明（展示用）。 */
    val authHint: String,
    /** 该协议下的建议模型（新建供应商时预填）。 */
    val defaultModel: String,
    /** 新建供应商时预填的 Base URL；空串表示必须由用户按网关文档填写。 */
    val defaultBaseUrl: String,
    val kind: ProviderKind,
    val mode: OpenAIMode,
) {
    OPENAI_CHAT(
        label = "OpenAI 兼容 · Chat Completions",
        shortLabel = "Chat",
        menuLabel = "OpenAI · Chat",
        hint = "聚合网关上的多模态图像模型（Banana 等）；网关不代理原生 Gemini 端点时选它",
        endpointLine = "POST /v1/chat/completions",
        authHint = "Bearer 鉴权",
        defaultModel = "gemini-2.5-flash-image",
        defaultBaseUrl = "",
        kind = ProviderKind.OPENAI,
        mode = OpenAIMode.CHAT,
    ),
    OPENAI_IMAGES(
        label = "OpenAI 兼容 · Images",
        shortLabel = "Images",
        menuLabel = "OpenAI · Images",
        hint = "标准图像编辑接口（gpt-image 系列的官方形态）",
        endpointLine = "POST /v1/images/edits",
        authHint = "Bearer 鉴权",
        defaultModel = "gpt-image-1",
        defaultBaseUrl = "https://api.openai.com",
        kind = ProviderKind.OPENAI,
        mode = OpenAIMode.IMAGES,
    ),
    OPENAI_RESPONSES(
        label = "OpenAI 兼容 · Responses",
        shortLabel = "Responses",
        menuLabel = "OpenAI · Responses",
        hint = "Responses 接口 + image_generation 工具（Codex 系网关）",
        endpointLine = "POST /v1/responses",
        authHint = "Bearer 鉴权",
        defaultModel = "gpt-5",
        defaultBaseUrl = "",
        kind = ProviderKind.OPENAI,
        mode = OpenAIMode.RESPONSES,
    ),
    GEMINI_NATIVE(
        label = "Google Gemini 原生",
        shortLabel = "Gemini 原生",
        menuLabel = "Gemini 原生",
        hint = "直连 Google AI Studio / Vertex 兼容端点（官方 v1beta 协议）",
        endpointLine = "POST /v1beta/models/{model}:generateContent",
        authHint = "x-goog-api-key 鉴权",
        defaultModel = "gemini-2.5-flash-image",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        kind = ProviderKind.GEMINI,
        mode = OpenAIMode.IMAGES,
    ),
    ;

    /** 展示用的一行格式说明，例：`POST /v1/chat/completions · Bearer 鉴权`。 */
    val formatLine: String
        get() = "$endpointLine · $authHint"

    /** 收起状态的一行说明（去掉方法前缀，尽量单行放下；放不下由调用方跑马灯）。 */
    val summaryLine: String
        get() = endpointLine.removePrefix("POST ")

    companion object {
        /**
         * 由槽位存储的 kind + mode 反查协议。
         *
         * Gemini 槽位的 mode 无意义（原生协议不用 OpenAI 模式），一律归到 [GEMINI_NATIVE]。
         */
        fun of(kind: ProviderKind, mode: OpenAIMode): ApiProtocol = when (kind) {
            ProviderKind.GEMINI -> GEMINI_NATIVE
            ProviderKind.OPENAI -> when (mode) {
                OpenAIMode.IMAGES -> OPENAI_IMAGES
                OpenAIMode.CHAT -> OPENAI_CHAT
                OpenAIMode.RESPONSES -> OPENAI_RESPONSES
            }
        }

        /** 未知取值回落到与旧配置一致的行为（OpenAI 兼容 Images）。 */
        fun fromValue(value: String?): ApiProtocol =
            entries.firstOrNull { it.name == value } ?: OPENAI_IMAGES

        /**
         * 网关元数据 `supported_endpoint_types` 的取值 → 本 App 协议。
         *
         * new-api 系实测取值：`openai`、`openai_response`、`image-generation`、`gemini`；
         * 其余（anthropic、embedding、rerank…）本 App 不支持，返回 null。
         */
        fun fromEndpointType(type: String): ApiProtocol? = when (type.trim().lowercase()) {
            "openai" -> OPENAI_CHAT
            "openai_response" -> OPENAI_RESPONSES
            "image-generation" -> OPENAI_IMAGES
            "gemini" -> GEMINI_NATIVE
            else -> null
        }

        /**
         * 一个模型声明的可用协议，按本 App 偏好排序。
         *
         * `image-generation` 存在时优先 Images（标准图生图接口），否则优先 Chat
         * （实测最通用的网关通道），Responses 与 Gemini 原生靠后。
         */
        fun fromEndpointTypes(types: List<String>): List<ApiProtocol> {
            val supported = types.mapNotNull(::fromEndpointType).toSet()
            return PREFERENCE_ORDER.filter { it in supported }
        }

        private val PREFERENCE_ORDER = listOf(
            OPENAI_IMAGES,
            OPENAI_CHAT,
            OPENAI_RESPONSES,
            GEMINI_NATIVE,
        )
    }
}
