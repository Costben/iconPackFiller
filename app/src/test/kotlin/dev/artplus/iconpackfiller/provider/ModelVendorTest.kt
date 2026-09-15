package dev.artplus.iconpackfiller.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelVendorTest {

    @Test
    fun `vendors are recognized`() {
        val cases = mapOf(
            "gpt-image-1" to ModelVendor.OPENAI,
            "dall-e-3" to ModelVendor.OPENAI,
            "o1-mini" to ModelVendor.OPENAI,
            "gemini-2.5-flash-image" to ModelVendor.GOOGLE,
            "banana-pro" to ModelVendor.GOOGLE,
            "imagen-4" to ModelVendor.GOOGLE,
            "claude-sonnet-4" to ModelVendor.ANTHROPIC,
            "grok-4" to ModelVendor.XAI,
            "qwen-max" to ModelVendor.ALIBABA,
            "wanx-2.1" to ModelVendor.ALIBABA,
            "doubao-seed-1-6" to ModelVendor.BYTEDANCE,
            "jimeng-3" to ModelVendor.BYTEDANCE,
            "seedream-4" to ModelVendor.BYTEDANCE,
            "deepseek-chat" to ModelVendor.DEEPSEEK,
            "glm-4" to ModelVendor.ZHIPU,
            "kimi-k2" to ModelVendor.MOONSHOT,
            "minimax-text-01" to ModelVendor.MINIMAX,
            "step-2" to ModelVendor.STEPFUN,
            "baichuan-m1" to ModelVendor.BAICHUAN,
            "yi-large" to ModelVendor.YI,
            "stable-diffusion-xl" to ModelVendor.STABILITY,
            "sdxl-turbo" to ModelVendor.STABILITY,
            "flux-schnell" to ModelVendor.BFL,
            "midjourney-v7" to ModelVendor.MIDJOURNEY,
            "niji-6" to ModelVendor.MIDJOURNEY,
            "ideogram-3" to ModelVendor.IDEOGRAM,
            "kolors-2" to ModelVendor.KUAISHOU,
            "hunyuan-turbos" to ModelVendor.TENCENT,
            "ernie-4.5" to ModelVendor.BAIDU,
            "spark-max" to ModelVendor.IFLYTEK,
        )
        for ((id, expected) in cases) {
            assertEquals(expected, ModelVendor.forModel(id), id)
        }
    }

    /**
     * doubao 名字里的 "o-1" 是版本号分隔，不能误判成 OpenAI o1。
     */
    @Test
    fun `doubao version suffix is not openai o1`() {
        assertEquals(ModelVendor.BYTEDANCE, ModelVendor.forModel("doubao-1-5-thinking"))
    }

    @Test
    fun `unknown and blank fall back`() {
        assertEquals(ModelVendor.UNKNOWN, ModelVendor.forModel("some-future-model-9"))
        assertEquals(ModelVendor.UNKNOWN, ModelVendor.forModel(""))
        assertEquals(ModelVendor.UNKNOWN, ModelVendor.forModel("   "))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(ModelVendor.GOOGLE, ModelVendor.forModel("Gemini-3.1-Flash-Image"))
        assertEquals(ModelVendor.OPENAI, ModelVendor.forModel("GPT-Image-1"))
    }

    /**
     * 除 UNKNOWN 外每个厂商都有真图标；单色图标必须标记 tint（否则黑 logo 在深色下不可见）。
     */
    @Test
    fun `every known vendor has an icon`() {
        for (vendor in ModelVendor.entries) {
            if (vendor == ModelVendor.UNKNOWN) {
                assertNull(vendor.iconRes, vendor.name)
                continue
            }
            assertNotEquals(0, vendor.iconRes, vendor.name)
        }
        val tinted = ModelVendor.entries.filter { it.tintIcon }.map { it.name }.toSet()
        assertEquals(
            setOf("OPENAI", "XAI", "MOONSHOT", "YI", "BFL", "MIDJOURNEY", "IDEOGRAM", "KUAISHOU"),
            tinted,
        )
        assertFalse(ModelVendor.UNKNOWN.tintIcon)
        assertTrue(ModelVendor.entries.count { it.iconRes != null } == ModelVendor.entries.size - 1)
    }
}
