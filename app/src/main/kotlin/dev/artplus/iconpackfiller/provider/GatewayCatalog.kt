package dev.artplus.iconpackfiller.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * 网关里的一个可选模型。
 *
 * [endpointTypes] 来自网关元数据（new-api 系 `GET /api/pricing` 的
 * `supported_endpoint_types`）；通用 `/v1/models` 回退时为空。
 */
data class GatewayModel(
    val name: String,
    val endpointTypes: List<String> = emptyList(),
) {
    /** 映射到本 App 支持的协议（按偏好排序；空 = 网关未声明或仅支持其他协议）。 */
    val protocols: List<ApiProtocol> get() = ApiProtocol.fromEndpointTypes(endpointTypes)

    /** 自动定协议的首选。 */
    val preferredProtocol: ApiProtocol? get() = protocols.firstOrNull()

    /**
     * 是否像图片生成模型（选择器只列这类）。
     *
     * 网关元数据很少标注"可生图"，只能按命名惯例识别（小写子串）；
     * 元数据明确声明 image-generation 端点的也算。
     * 漏网的生图模型仍可在模型输入框手动填写，不影响使用。
     */
    val isImageModel: Boolean
        get() {
            if (endpointTypes.any { it.trim().lowercase() == "image-generation" }) return true
            val id = name.trim().lowercase()
            return IMAGE_MODEL_HINTS.any { id.contains(it) }
        }
}

/**
 * 图片生成模型的命名特征（小写子串匹配）。
 *
 * "image" 一条即可覆盖 gpt-image / imagen / qwen-image / z-image /
 * gemini-*-image 等；其余收录名字里不带 image 的主流生图家族。
 */
private val IMAGE_MODEL_HINTS = listOf(
    "image",
    "dall-e",
    "flux",
    "midjourney",
    "niji",
    "banana",
    "sdxl",
    "stable-diffusion",
    "seedream",
    "jimeng",
    "kolors",
    "ideogram",
    "wanx",
)

/** 一次模型目录拉取的结果。 */
data class GatewayModelList(
    val models: List<GatewayModel>,
    /** 数据来源说明（选择对话框副标题用）。 */
    val sourceLabel: String,
    /** 是否带协议信息；false 时不应自动切换协议。 */
    val protocolAware: Boolean,
)

/**
 * 网关模型目录：按模型自动定协议的数据源。
 *
 * 优先读 new-api 系的公开元数据 `GET {base}/api/pricing`（每个模型带
 * `supported_endpoint_types`，可精确映射协议）；网关不支持时回退到
 * `GET {base}/v1/models`（仅模型名，协议保持用户当前选择）。
 */
object GatewayCatalog {

    /** new-api 元数据端点：`{base}/api/pricing`（剥掉 Base URL 尾部 API 版本后缀）。 */
    fun pricingUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        val root = when {
            normalized.endsWith("/v1") -> normalized.removeSuffix("/v1")
            normalized.endsWith("/v1beta") -> normalized.removeSuffix("/v1beta")
            else -> normalized
        }
        return "$root/api/pricing"
    }

    /** `/api/pricing` 响应：`{"data":[{"model_name":..., "supported_endpoint_types":[...]}]}`。 */
    fun parsePricing(json: String): List<GatewayModel> = try {
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val name = item.optString("model_name").trim()
                if (name.isNotBlank()) {
                    add(GatewayModel(name, endpointTypes = parseTypes(item.optJSONArray("supported_endpoint_types"))))
                }
            }
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    /** 通用模型列表：OpenAI 系 `data[].id` / Gemini 系 `models[].name`（去 `models/` 前缀）。 */
    fun parseModelsList(json: String): List<GatewayModel> = try {
        val root = JSONObject(json)
        val openAiData = root.optJSONArray("data")
        if (openAiData != null) {
            buildList {
                for (i in 0 until openAiData.length()) {
                    val item = openAiData.optJSONObject(i) ?: continue
                    val name = item.optString("id").trim()
                    if (name.isNotBlank()) add(GatewayModel(name))
                }
            }
        } else {
            val geminiData = root.optJSONArray("models") ?: JSONArray()
            buildList {
                for (i in 0 until geminiData.length()) {
                    val item = geminiData.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim().removePrefix("models/")
                    if (name.isNotBlank()) add(GatewayModel(name))
                }
            }
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    /**
     * 拉取模型目录。先试元数据（带协议），失败或为空再退回通用模型列表。
     *
     * @throws ImageProviderException 两个来源都不可用时。
     */
    suspend fun fetch(
        client: SimpleHttpClient,
        baseUrl: String,
        apiKey: String,
        kind: ProviderKind = ProviderKind.OPENAI,
    ): GatewayModelList {
        val isGemini = kind == ProviderKind.GEMINI
        val authMode = if (isGemini) SimpleHttpClient.AuthMode.NONE else SimpleHttpClient.AuthMode.BEARER
        val extraHeaders = if (isGemini) mapOf("x-goog-api-key" to apiKey.trim()) else emptyMap()
        var pricingError: String? = null

        try {
            val body = client.getText(
                url = pricingUrl(baseUrl),
                apiKey = apiKey,
                extraHeaders = extraHeaders,
                authMode = authMode,
            )
            val models = parsePricing(body)
            if (models.isNotEmpty()) {
                val declared = models.count { it.protocols.isNotEmpty() }
                return GatewayModelList(
                    models = models.sortedBy { it.name },
                    sourceLabel = "网关元数据：$declared/${models.size} 个模型带协议声明",
                    protocolAware = true,
                )
            }
        } catch (e: Exception) {
            pricingError = e.message
        }

        val body = client.getText(
            url = ProviderUrls.modelsListUrl(baseUrl, kind),
            apiKey = apiKey,
            extraHeaders = extraHeaders,
            authMode = authMode,
        )
        val models = parseModelsList(body)
        if (models.isEmpty()) {
            throw ImageProviderException(
                "网关未返回任何模型" + (pricingError?.let { "（元数据：${it.take(80)}）" } ?: ""),
            )
        }
        return GatewayModelList(
            models = models.sortedBy { it.name },
            sourceLabel = "通用模型列表：${models.size} 个模型（无协议声明，保持当前协议）",
            protocolAware = false,
        )
    }

    private fun parseTypes(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val type = array.optString(i).trim()
                if (type.isNotBlank()) add(type)
            }
        }
    }
}
