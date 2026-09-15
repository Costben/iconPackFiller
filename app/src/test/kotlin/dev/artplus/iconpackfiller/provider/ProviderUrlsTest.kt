package dev.artplus.iconpackfiller.provider

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderUrlsTest {

    @Test
    fun `responses url from bare host`() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            ProviderUrls.normalizeResponsesUrl("https://api.openai.com"),
        )
    }

    @Test
    fun `responses url keeps explicit path`() {
        assertEquals(
            "https://proxy.example.com/v1/responses",
            ProviderUrls.normalizeResponsesUrl("https://proxy.example.com/v1/responses"),
        )
        assertEquals(
            "https://proxy.example.com/v1/responses",
            ProviderUrls.normalizeResponsesUrl("https://proxy.example.com/v1/"),
        )
    }

    @Test
    fun `images edit url variants`() {
        assertEquals(
            "https://api.openai.com/v1/images/edits",
            ProviderUrls.normalizeImagesEditUrl("https://api.openai.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/images/edits",
            ProviderUrls.normalizeImagesEditUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/images/edits",
            ProviderUrls.normalizeImagesEditUrl("https://api.openai.com/v1/images/edits"),
        )
    }

    @Test
    fun `gemini url variants`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent",
            ProviderUrls.geminiGenerateContentUrl(
                "https://generativelanguage.googleapis.com",
                "gemini-2.5-flash-image",
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/m:generateContent",
            ProviderUrls.geminiGenerateContentUrl(
                "https://generativelanguage.googleapis.com/v1beta",
                "m",
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/m:generateContent",
            ProviderUrls.geminiGenerateContentUrl(
                "https://generativelanguage.googleapis.com/v1",
                "m",
            ),
        )
    }

    @Test
    fun `models list url per kind`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            ProviderUrls.modelsListUrl("https://api.openai.com", ProviderKind.OPENAI),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            ProviderUrls.modelsListUrl("https://api.openai.com/v1", ProviderKind.OPENAI),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            ProviderUrls.modelsListUrl(
                "https://generativelanguage.googleapis.com",
                ProviderKind.GEMINI,
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            ProviderUrls.modelsListUrl(
                "https://generativelanguage.googleapis.com/v1beta",
                ProviderKind.GEMINI,
            ),
        )
    }
}

class ImageResponseParserTest {

    private val tinyB64 = java.util.Base64.getEncoder()
        .encodeToString(ByteArray(200) { it.toByte() })

    @Test
    fun `parses openai images data b64_json`() {
        val body = JSONObject()
            .put("data", JSONArray().put(JSONObject().put("b64_json", tinyB64)))
            .toString()
        val bytes = kotlinx.coroutines.runBlocking { ImageResponseParser.parse(body) }
        assertEquals(200, bytes.size)
    }

    @Test
    fun `parses openai images data url`() {
        val body = JSONObject()
            .put("data", JSONArray().put(JSONObject().put("url", "data:image/png;base64,$tinyB64")))
            .toString()
        val bytes = kotlinx.coroutines.runBlocking { ImageResponseParser.parse(body) }
        assertEquals(200, bytes.size)
    }

    @Test
    fun `parses responses output image_base64`() {
        val body = JSONObject()
            .put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("type", "image_generation_call")
                        .put("image_base64", tinyB64),
                ),
            )
            .toString()
        val bytes = kotlinx.coroutines.runBlocking { ImageResponseParser.parse(body) }
        assertEquals(200, bytes.size)
    }

    @Test
    fun `parses responses image_url object`() {
        val body = JSONObject()
            .put(
                "output",
                JSONArray().put(
                    JSONObject().put(
                        "image_url",
                        JSONObject().put("url", "data:image/png;base64,$tinyB64"),
                    ),
                ),
            )
            .toString()
        val bytes = kotlinx.coroutines.runBlocking { ImageResponseParser.parse(body) }
        assertEquals(200, bytes.size)
    }

    @Test
    fun `downloads remote url through callback`() {
        val body = JSONObject()
            .put("data", JSONArray().put(JSONObject().put("url", "https://example.com/a.png")))
            .toString()
        var requested: String? = null
        val bytes = kotlinx.coroutines.runBlocking {
            ImageResponseParser.parse(body) { url ->
                requested = url
                ByteArray(300)
            }
        }
        assertEquals("https://example.com/a.png", requested)
        assertEquals(300, bytes.size)
    }

    @Test
    fun `parses sse stream`() {
        val b64 = tinyB64
        val sse = buildString {
            append("event: response.output_item.done\n")
            append("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"image_base64\":\"$b64\"}}\n\n")
            append("data: [DONE]\n\n")
        }
        val bytes = kotlinx.coroutines.runBlocking { ImageResponseParser.parse(sse) }
        assertEquals(200, bytes.size)
    }

    @Test
    fun `parses sse partial image`() {
        val b64 = tinyB64
        val sse = "data: {\"type\":\"response.image_generation_call.partial_image\",\"partial_image_b64\":\"$b64\"}\n\n"
        val bytes = kotlinx.coroutines.runBlocking { ImageResponseParser.parse(sse) }
        assertEquals(200, bytes.size)
    }

    @Test
    fun `throws when no image data`() {
        val body = JSONObject().put("output", JSONArray()).toString()
        assertFailsWith<ImageProviderException> {
            kotlinx.coroutines.runBlocking { ImageResponseParser.parse(body) }
        }
    }

    @Test
    fun `ignores too short base64`() {
        val body = JSONObject()
            .put("data", JSONArray().put(JSONObject().put("b64_json", "AAAA")))
            .toString()
        assertFailsWith<ImageProviderException> {
            kotlinx.coroutines.runBlocking { ImageResponseParser.parse(body) }
        }
    }

    @Test
    fun `strips whitespace in base64`() {
        val spaced = tinyB64.chunked(40).joinToString("\n")
        val body = JSONObject()
            .put("data", JSONArray().put(JSONObject().put("b64_json", spaced)))
            .toString()
        val bytes = kotlinx.coroutines.runBlocking { ImageResponseParser.parse(body) }
        assertEquals(200, bytes.size)
    }
}