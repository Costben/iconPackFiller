package dev.artplus.iconpackfiller.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider 响应解析。从 ArtPlus `GptClient.kt` 移植并去 Android 依赖。
 *
 * 支持：
 * - OpenAI Responses 模式（output[].image_base64 / b64_json / url / image_url.url）
 * - OpenAI Images 模式（data[].b64_json / url）
 * - SSE 流式拼接（`data:` 行）
 *
 * 纯 JVM 实现，可单测。
 */
object ImageResponseParser {

    /**
     * 从完整响应体解析图片字节。URL 形式由 [fetchBytes] 回调下载。
     * @throws ImageProviderException 无图片数据时。
     */
    suspend fun parse(
        responseBody: String,
        fetchBytes: suspend (String) -> ByteArray = { error("不支持 URL 下载") },
    ): ByteArray {
        val json = parseBody(responseBody)
        json.optJSONArray("output")?.let { output ->
            findImageBytes(output, fetchBytes)?.let { return it }
        }
        json.optJSONArray("data")?.let { data ->
            findImageBytes(data, fetchBytes)?.let { return it }
        }
        findImageBytes(JSONArray().put(json), fetchBytes)?.let { return it }
        throw ImageProviderException("AI 响应没有图片数据")
    }

    /** 识别 SSE 流并折叠为普通 JSON 对象。 */
    fun parseBody(responseBody: String): JSONObject {
        val trimmed = responseBody.trimStart()
        return if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
            parseSseStream(responseBody)
        } else {
            JSONObject(responseBody)
        }
    }

    fun parseSseStream(text: String): JSONObject {
        val output = JSONArray()
        var response: JSONObject? = null
        for (block in text.split("\n\n")) {
            val data = block.lineSequence()
                .map { it.trimEnd() }
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trimStart() }
                .trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue

            event.optJSONObject("response")?.let {
                response = it
                it.optJSONArray("output")?.let { existing ->
                    for (i in 0 until existing.length()) output.put(existing.get(i))
                }
            }
            val item = event.optJSONObject("item")
            if (item != null && event.optString("type") in
                setOf("response.output_item.done", "response.output_item.added")
            ) {
                output.put(item)
            }
            if (event.optString("type") == "response.image_generation_call.partial_image") {
                val partial = event.optString("partial_image_b64")
                if (partial.isNotBlank()) {
                    output.put(
                        JSONObject()
                            .put("type", "image_generation_call")
                            .put("image_base64", partial),
                    )
                }
            }
        }
        return (response ?: JSONObject()).put("output", output)
    }

    private suspend fun findImageBytes(
        items: JSONArray,
        fetchBytes: suspend (String) -> ByteArray,
    ): ByteArray? {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            for (key in BASE64_KEYS) {
                decodeReference(item.opt(key), fetchBytes)?.let { return it }
            }
            item.optJSONObject("image_url")?.let { imageUrl ->
                decodeReference(imageUrl.opt("url"), fetchBytes)?.let { return it }
            }
        }
        return null
    }

    private val BASE64_KEYS = listOf(
        "b64_json", "b64", "image_base64", "base64", "result", "url", "imageUrl", "remoteImageUrl",
    )

    /**
     * 解码单个引用：http(s) URL 交给 [fetchBytes]；data URL 或裸 base64 直接解码。
     * 空白串 / 过短串返回 null。
     */
    suspend fun decodeReference(value: Any?, fetchBytes: suspend (String) -> ByteArray): ByteArray? {
        val text = (value as? String)?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return runCatching { fetchBytes(text) }.getOrNull()
        }
        val b64 = if (text.startsWith("data:image/")) {
            text.substringAfter("base64,", "")
        } else {
            text
        }.replace(Regex("\\s"), "")
        if (b64.length < 128) return null
        return runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
            ?: runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()
    }
}