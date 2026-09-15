package dev.artplus.iconpackfiller.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Gemini "Banana" 图像 Provider。
 *
 * `POST {base}/v1beta/models/{model}:generateContent`
 * - 请求：`contents[].parts[]` 混合 `inline_data` 图片与 `text`
 * - 响应：`candidates[].content.parts[].inline_data` 取图
 *
 * 模型示例：`gemini-2.5-flash-image` / `gemini-3-pro-image-preview`（以用户配置为准）。
 */
class GeminiProvider(
    private val config: ProviderConfig,
    private val http: SimpleHttpClient = SimpleHttpClient(
        connectTimeoutMs = config.connectTimeoutMs,
        readTimeoutMs = config.readTimeoutMs,
        maxRetries = config.maxRetries,
    ),
) : ImageProvider {

    override val name: String get() = "Gemini (${config.model.ifBlank { DEFAULT_MODEL }})"

    override suspend fun generate(request: ImageRequest): Bitmap {
        val model = config.model.trim().ifBlank { DEFAULT_MODEL }
        val parts = JSONArray()
            .put(JSONObject().put("text", request.prompt))
        for (bitmap in request.images) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/png")
                        .put("data", bitmap.toPngBase64()),
                ),
            )
        }
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts),
                ),
            )
            .put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")),
            )

        val response = http.postJson(
            url = ProviderUrls.geminiGenerateContentUrl(config.baseUrl, model),
            body = body.toString(),
            apiKey = config.apiKey,
            extraHeaders = mapOf("x-goog-api-key" to config.apiKey.trim()),
            // 只发 x-goog-api-key，避免 Authorization: Bearer 干扰 Google 鉴权
            authMode = SimpleHttpClient.AuthMode.NONE,
        )
        val bytes = GeminiResponseParser.extractImageBytes(JSONObject(response))
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw ImageProviderException("Gemini 返回的图片无法解码")
        }
    }

    override fun costHint(): String? = "按 token 计费（含图片输出），模型 ${config.model.ifBlank { DEFAULT_MODEL }}"

    private fun Bitmap.toPngBase64(): String {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash-image"
    }
}