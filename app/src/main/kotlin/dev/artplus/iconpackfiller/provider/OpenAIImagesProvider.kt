package dev.artplus.iconpackfiller.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * OpenAI 兼容图像 Provider。移植 ArtPlus `GptClient.kt` 的 Images / Responses 双模式，
 * 外加 Chat 模式（聚合网关上的多模态图像模型，如 Gemini "Banana"）。
 *
 * - Images 模式：`POST {base}/v1/images/edits`，multipart，字段 model/prompt/size/quality/
 *   background/output_format + image 文件。
 * - Responses 模式：`POST {base}/v1/responses`，input 数组混合 input_text + input_image(data URL)，
 *   tools 声明 image_generation；响应经 SSE 折叠后解析。
 * - Chat 模式：`POST {base}/v1/chat/completions`，content 数组混合 text + image_url(data URL)；
 *   响应图在 `choices[0].message.images[].image_url.url`（data URL）。
 *   聚合网关普遍只代理 chat 协议，原生 `:generateContent` 会返回 model_not_found。
 */
class OpenAIImagesProvider(
    private val config: ProviderConfig,
    private val http: SimpleHttpClient = SimpleHttpClient(
        connectTimeoutMs = config.connectTimeoutMs,
        readTimeoutMs = config.readTimeoutMs,
        maxRetries = config.maxRetries,
    ),
) : ImageProvider {

    override val name: String get() = "OpenAI (${config.mode.label})"

    override suspend fun generate(request: ImageRequest): Bitmap {
        val bytes = when (config.mode) {
            OpenAIMode.IMAGES -> imagesEdit(request)
            OpenAIMode.RESPONSES -> responsesEdit(request)
            OpenAIMode.CHAT -> chatEdit(request)
        }
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw ImageProviderException("AI 返回的图片无法解码")
        }
    }

    override fun costHint(): String? = when (config.mode) {
        OpenAIMode.IMAGES -> "按张计费，${config.model.ifBlank { DEFAULT_IMAGE_MODEL }}"
        OpenAIMode.RESPONSES -> "按 token + 图片生成计费，${config.model.ifBlank { DEFAULT_RESPONSES_MODEL }}"
        OpenAIMode.CHAT -> "按 token 计费（含图片输出），${config.model}"
    }

    private suspend fun imagesEdit(request: ImageRequest): ByteArray {
        val model = config.model.trim().ifBlank { DEFAULT_IMAGE_MODEL }
        val boundary = "----IconPackFiller${UUID.randomUUID().toString().replace("-", "")}"
        val body = ByteArrayOutputStream()

        fun field(name: String, value: String) {
            body.write("--$boundary\r\n".toByteArray())
            body.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            body.write(value.toByteArray(Charsets.UTF_8))
            body.write("\r\n".toByteArray())
        }

        field("model", model)
        field("prompt", request.prompt)
        field("size", request.size)
        field("quality", config.quality)
        field("background", config.background)
        field("output_format", "png")
        for ((index, bitmap) in request.images.withIndex()) {
            body.write("--$boundary\r\n".toByteArray())
            body.write(
                ("Content-Disposition: form-data; name=\"image\"; " +
                    "filename=\"icon_${index}.png\"\r\n").toByteArray(),
            )
            body.write("Content-Type: image/png\r\n\r\n".toByteArray())
            body.write(bitmap.toPngBytes())
            body.write("\r\n".toByteArray())
        }
        body.write("--$boundary--\r\n".toByteArray())

        val response = http.postBytes(
            url = ProviderUrls.normalizeImagesEditUrl(config.baseUrl),
            body = body.toByteArray(),
            contentType = "multipart/form-data; boundary=$boundary",
            apiKey = config.apiKey,
        )
        return ImageResponseParser.parse(response) { http.getBytes(it) }
    }

    private suspend fun responsesEdit(request: ImageRequest): ByteArray {
        val model = config.model.trim().ifBlank { DEFAULT_RESPONSES_MODEL }
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", request.prompt))
        for (bitmap in request.images) {
            content.put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", bitmap.toDataUrl()),
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "image_generation")
                        .put("size", request.size)
                        .put("quality", config.quality)
                        .put("background", config.background)
                        .put("output_format", "png"),
                ),
            )
            .put("tool_choice", JSONObject().put("type", "image_generation"))
            .put("stream", true)

        val response = http.postJson(
            url = ProviderUrls.normalizeResponsesUrl(config.baseUrl),
            body = body.toString(),
            apiKey = config.apiKey,
            accept = "text/event-stream, application/json",
        )
        return ImageResponseParser.parse(response) { http.getBytes(it) }
    }

    /**
     * Chat 模式：聚合网关（new-api 等）在 chat 协议上承载图像模型。
     *
     * 实测（gemini-3.1-flash-image @ new-api）：
     * - 返回 JPEG，且尺寸由提示词决定（要求 1:1 才给方形），所以提示词里
     *   必须写明输出为正方形（[dev.artplus.iconpackfiller.reference.PromptTemplate] 已含）。
     * - 图在 `message.images[0].image_url.url`，`message.content` 为 null。
     */
    private suspend fun chatEdit(request: ImageRequest): ByteArray {
        val model = config.model.trim().ifBlank { DEFAULT_CHAT_MODEL }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", request.prompt))
        for (bitmap in request.images) {
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", bitmap.toDataUrl()),
                    ),
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )

        val response = http.postJson(
            url = ProviderUrls.normalizeChatCompletionsUrl(config.baseUrl),
            body = body.toString(),
            apiKey = config.apiKey,
        )
        val json = JSONObject(response)
        val reference = ChatResponseParser.findImageReference(json)
            ?: throw ImageProviderException(
                "AI 响应没有图片数据" +
                    (ChatResponseParser.extractText(json)?.take(120)?.let { "（返回文本：$it）" } ?: ""),
            )
        return ImageResponseParser.decodeReference(reference) { http.getBytes(it) }
            ?: throw ImageProviderException("AI 返回的图片无法解码")
    }

    private fun Bitmap.toPngBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }

    private fun Bitmap.toDataUrl(): String =
        "data:image/png;base64," + Base64.encodeToString(toPngBytes(), Base64.NO_WRAP)

    companion object {
        const val DEFAULT_IMAGE_MODEL = "gpt-image-1"
        const val DEFAULT_RESPONSES_MODEL = "gpt-5"
        const val DEFAULT_CHAT_MODEL = "gemini-2.5-flash-image"
    }
}

/**
 * Images / Responses 双模式，移植自 ArtPlus `GptImageMode`。
 */
enum class OpenAIMode(val value: String, val label: String) {
    IMAGES("images", "Images"),
    RESPONSES("responses", "Responses"),

    /** 聚合网关上的多模态图像模型（Banana 等），走 chat 协议。 */
    CHAT("chat", "Chat Completions");

    companion object {
        fun fromValue(value: String?): OpenAIMode =
            entries.firstOrNull { it.value == value } ?: IMAGES
    }
}
