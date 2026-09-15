package dev.artplus.iconpackfiller.provider

import org.json.JSONObject

/**
 * OpenAI Chat Completions 响应里的图片解析。纯 JVM，可单测。
 *
 * 中转站/聚合网关（如 new-api）在 chat 协议上承载图片模型时的常见形态：
 * ```
 * choices[0].message.images[0].image_url.url = "data:image/jpeg;base64,..."
 * ```
 * 模型本体是 Gemini "Banana" 这类多模态图像模型时走这个通道
 * （many relays do not proxy native `:generateContent`）。
 *
 * 兼容几种变体：
 * - `images[]` 元素是字符串（data URL / http URL）
 * - `images[]` 元素是对象，url 在 `url` 或 `image_url.url`
 * - 图片直接塞在 `message.content` 的 data URL 里（部分网关）
 */
object ChatResponseParser {

    /**
     * 提取第一张图片的引用串（data URL 或 http(s) URL）；没有则 null。
     */
    fun findImageReference(json: JSONObject): String? {
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            if (message != null) {
                extractFromImages(message.optJSONArray("images"))?.let { return it }
                extractContentUrl(message.opt("content"))?.let { return it }
            }
        }
        // 少数网关把图片放在顶层 images
        extractFromImages(json.optJSONArray("images"))?.let { return it }
        return null
    }

    private fun extractFromImages(images: org.json.JSONArray?): String? {
        if (images == null) return null
        for (i in 0 until images.length()) {
            when (val item = images.opt(i)) {
                is String -> if (item.isNotBlank()) return item
                is JSONObject -> {
                    item.optJSONObject("image_url")?.optString("url")?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                    item.optString("url").takeIf { it.isNotBlank() }?.let { return it }
                    item.optString("b64_json").takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * content 可能是纯字符串，也可能是 parts 数组（含 image_url）。
     */
    private fun extractContentUrl(content: Any?): String? = when (content) {
        is String -> content.takeIf { it.startsWith("data:image/") || it.startsWith("http") }
        is org.json.JSONArray -> {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                part.optJSONObject("image_url")?.optString("url")?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                part.optString("url").takeIf { it.startsWith("data:image/") }?.let { return it }
            }
            null
        }
        else -> null
    }

    /**
     * 提取人读的文本内容（诊断用）；没有则 null。
     */
    fun extractText(json: JSONObject): String? {
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return null
        val content = message.opt("content")
        val text = when (content) {
            is String -> content
            is org.json.JSONArray -> content.optJSONObject(0)?.optString("text").orEmpty()
            else -> ""
        }.trim()
        if (text.isNotEmpty()) return text
        return message.optString("reasoning_content").takeIf { it.isNotBlank() }
    }
}
