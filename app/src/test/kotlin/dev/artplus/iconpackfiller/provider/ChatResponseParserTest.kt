package dev.artplus.iconpackfiller.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatResponseParserTest {

    private val dataUrl = "data:image/jpeg;base64," + "A".repeat(200)

    @Test
    fun `extracts data url from message images`() {
        val json = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", JSONObject.NULL)
                            .put(
                                "images",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", dataUrl)),
                                ),
                            ),
                    ),
                ),
            )
        assertEquals(dataUrl, ChatResponseParser.findImageReference(json))
    }

    @Test
    fun `extracts when images holds bare strings`() {
        val json = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("images", JSONArray().put(dataUrl)),
                ),
            ),
        )
        assertEquals(dataUrl, ChatResponseParser.findImageReference(json))
    }

    @Test
    fun `extracts when image sits in content parts`() {
        val json = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUrl)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(dataUrl, ChatResponseParser.findImageReference(json))
    }

    @Test
    fun `returns null when no image present`() {
        val json = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", "抱歉，我不能生成图片")),
            ),
        )
        assertNull(ChatResponseParser.findImageReference(json))
        assertEquals("抱歉，我不能生成图片", ChatResponseParser.extractText(json))
    }

    @Test
    fun `extractText falls back to reasoning_content`() {
        val json = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("content", JSONObject.NULL).put("reasoning_content", "thinking..."),
                ),
            ),
        )
        assertEquals("thinking...", ChatResponseParser.extractText(json))
    }
}

class ProviderUrlsChatTest {

    @Test
    fun `chat url for bare host`() {
        assertEquals(
            "http://192.168.31.179:3002/v1/chat/completions",
            ProviderUrls.normalizeChatCompletionsUrl("http://192.168.31.179:3002"),
        )
    }

    @Test
    fun `chat url keeps explicit v1`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            ProviderUrls.normalizeChatCompletionsUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun `chat url respects full path`() {
        val full = "https://api.example.com/v1/chat/completions"
        assertEquals(full, ProviderUrls.normalizeChatCompletionsUrl(full))
    }

    @Test
    fun `chat mode round trip via value`() {
        assertEquals(OpenAIMode.CHAT, OpenAIMode.fromValue("chat"))
        // 未知值回落到 IMAGES，不影响既有配置
        assertEquals(OpenAIMode.IMAGES, OpenAIMode.fromValue("nope"))
    }
}
