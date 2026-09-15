package dev.artplus.iconpackfiller.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayCatalogTest {

    @Test
    fun `pricing url strips api version suffix`() {
        assertEquals(
            "http://192.168.31.179:3002/api/pricing",
            GatewayCatalog.pricingUrl("http://192.168.31.179:3002"),
        )
        assertEquals(
            "http://192.168.31.179:3002/api/pricing",
            GatewayCatalog.pricingUrl("http://192.168.31.179:3002/"),
        )
        assertEquals(
            "https://gw.example.com/api/pricing",
            GatewayCatalog.pricingUrl("https://gw.example.com/v1"),
        )
        assertEquals(
            "https://gw.example.com/api/pricing",
            GatewayCatalog.pricingUrl("https://gw.example.com/v1beta/"),
        )
    }

    @Test
    fun `parse pricing maps endpoint types to protocols`() {
        val json = """
        {"data":[
          {"model_name":"gemini-3.1-flash-image","supported_endpoint_types":["openai"]},
          {"model_name":"gpt-image-2","supported_endpoint_types":["image-generation","openai"]},
          {"model_name":"claude-x","supported_endpoint_types":["anthropic"]},
          {"model_name":"no-types","supported_endpoint_types":null},
          {"supported_endpoint_types":["openai"]},
          {"model_name":"  spaced  ","supported_endpoint_types":["gemini"]}
        ]}
        """.trimIndent()
        val models = GatewayCatalog.parsePricing(json)
        assertEquals(
            listOf("gemini-3.1-flash-image", "gpt-image-2", "claude-x", "no-types", "spaced"),
            models.map { it.name },
        )
        assertEquals(listOf(ApiProtocol.OPENAI_CHAT), models[0].protocols)
        assertEquals(listOf(ApiProtocol.OPENAI_IMAGES, ApiProtocol.OPENAI_CHAT), models[1].protocols)
        assertEquals(emptyList(), models[2].protocols)
        assertEquals(emptyList(), models[3].protocols)
        assertEquals(listOf(ApiProtocol.GEMINI_NATIVE), models[4].protocols)
    }

    @Test
    fun `parse models list supports openai and gemini shapes`() {
        val openAi = """{"object":"list","data":[{"id":"m1"},{"id":"m2"},{"id":""}]}"""
        assertEquals(listOf("m1", "m2"), GatewayCatalog.parseModelsList(openAi).map { it.name })

        val gemini = """{"models":[{"name":"models/gemini-2.5-flash-image"},{"name":"gemini-3-pro"}]}"""
        assertEquals(
            listOf("gemini-2.5-flash-image", "gemini-3-pro"),
            GatewayCatalog.parseModelsList(gemini).map { it.name },
        )

        assertTrue(GatewayCatalog.parseModelsList("<html>not json</html>").isEmpty())
        assertTrue(GatewayCatalog.parsePricing("<html>not json</html>").isEmpty())
    }

    @Test
    fun `preferred protocol favors images then chat`() {
        val imageModel = GatewayModel("gpt-image-2", listOf("image-generation", "openai"))
        assertEquals(ApiProtocol.OPENAI_IMAGES, imageModel.preferredProtocol)

        val chatModel = GatewayModel("gemini-3.1-flash-image", listOf("openai"))
        assertEquals(ApiProtocol.OPENAI_CHAT, chatModel.preferredProtocol)

        val responsesOnly = GatewayModel("codex-x", listOf("openai_response"))
        assertEquals(ApiProtocol.OPENAI_RESPONSES, responsesOnly.preferredProtocol)

        val nativeGemini = GatewayModel("g", listOf("gemini"))
        assertEquals(ApiProtocol.GEMINI_NATIVE, nativeGemini.preferredProtocol)

        val unsupported = GatewayModel("embed", listOf("embedding", "rerank"))
        assertEquals(null, unsupported.preferredProtocol)
    }

    @Test
    fun `image models are recognized by naming`() {
        val imageIds = listOf(
            "gpt-image-1",
            "GPT-IMAGE-1",
            "dall-e-2",
            "dall-e-3",
            "gemini-2.5-flash-image",
            "gemini-3.1-flash-image",
            "imagen-4",
            "qwen-image",
            "z-image-turbo",
            "flux-schnell",
            "midjourney-v7",
            "niji-6",
            "banana-pro",
            "sdxl-turbo",
            "stable-diffusion-xl",
            "seedream-4",
            "jimeng-3",
            "kolors-2",
            "ideogram-3",
            "wanx-2.1",
        )
        for (id in imageIds) {
            assertTrue(GatewayModel(id).isImageModel, id)
        }
    }

    @Test
    fun `text models are not image models`() {
        val textIds = listOf(
            "gpt-4o",
            "gpt-5",
            "claude-sonnet-4",
            "qwen-max",
            "deepseek-chat",
            "gemini-2.0-flash",
            "text-embedding-3-small",
            "grok-4",
            "kimi-k2",
            "",
            "   ",
        )
        for (id in textIds) {
            assertFalse(GatewayModel(id).isImageModel, id)
        }
    }

    @Test
    fun `image-generation endpoint type counts even with odd name`() {
        assertTrue(GatewayModel("my-icon-model", listOf("image-generation")).isImageModel)
        assertFalse(GatewayModel("my-icon-model", listOf("openai")).isImageModel)
    }

    @Test
    fun `endpoint types map case insensitively`() {
        assertEquals(ApiProtocol.OPENAI_CHAT, ApiProtocol.fromEndpointType("OPENAI"))
        assertEquals(ApiProtocol.OPENAI_IMAGES, ApiProtocol.fromEndpointType(" image-generation "))
        assertEquals(ApiProtocol.OPENAI_RESPONSES, ApiProtocol.fromEndpointType("openai_response"))
        assertEquals(ApiProtocol.GEMINI_NATIVE, ApiProtocol.fromEndpointType("gemini"))
        assertEquals(null, ApiProtocol.fromEndpointType("anthropic"))
    }
}
