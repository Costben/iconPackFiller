package dev.artplus.iconpackfiller.provider

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeminiResponseParserTest {

    private val payload = ByteArray(200) { (it % 256).toByte() }
    private val b64 = java.util.Base64.getEncoder().encodeToString(payload)

    @Test
    fun `extracts inline_data snake case`() {
        val json = JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", "here"),
                            ).put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "image/png")
                                        .put("data", b64),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        val bytes = GeminiResponseParser.extractImageBytes(json)
        assertEquals(200, bytes.size)
    }

    @Test
    fun `extracts inlineData camel case`() {
        val json = JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().put("data", b64),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        assertEquals(200, GeminiResponseParser.extractImageBytes(json).size)
    }

    @Test
    fun `throws when candidates missing`() {
        assertFailsWith<ImageProviderException> {
            GeminiResponseParser.extractImageBytes(JSONObject())
        }
    }

    @Test
    fun `throws when no inline data`() {
        val json = JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", "no image")),
                        ),
                    ),
                ),
            )
        assertFailsWith<ImageProviderException> {
            GeminiResponseParser.extractImageBytes(json)
        }
    }
}