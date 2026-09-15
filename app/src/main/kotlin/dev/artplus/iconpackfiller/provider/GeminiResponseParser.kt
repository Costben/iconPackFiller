package dev.artplus.iconpackfiller.provider

import org.json.JSONObject
import java.util.Base64

/**
 * Gemini `generateContent` 响应解析。纯 JVM，可单测。
 *
 * 响应结构：
 * ```
 * candidates[].content.parts[].inline_data { mime_type, data(base64) }
 * ```
 * 兼容 `inlineData` 驼峰写法。
 */
object GeminiResponseParser {

    fun extractImageBytes(json: JSONObject): ByteArray {
        val candidates = json.optJSONArray("candidates")
            ?: throw ImageProviderException("Gemini 响应缺少 candidates")
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val inline = part.optJSONObject("inline_data")
                    ?: part.optJSONObject("inlineData")
                    ?: continue
                val data = inline.optString("data")
                if (data.isBlank()) continue
                val decoded = runCatching {
                    Base64.getDecoder().decode(data.replace(Regex("\\s"), ""))
                }.getOrNull() ?: continue
                if (decoded.isNotEmpty()) return decoded
            }
        }
        throw ImageProviderException("Gemini 响应没有图片数据")
    }
}