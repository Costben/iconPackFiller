package dev.artplus.iconpackfiller.provider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransparencySupportTest {

    @Test
    fun `gpt-image family is preset supported`() {
        assertTrue(TransparencyPresets.supports("gpt-image-1"))
        assertTrue(TransparencyPresets.supports("GPT-IMAGE-1"))
        assertTrue(TransparencyPresets.supports("openai/gpt-image-1"))
        assertTrue(TransparencyPresets.supports("gpt-image-1-mini"))
        assertTrue(TransparencyPresets.supports("gpt-image-2"))
        assertTrue(TransparencyPresets.supports("gpt-image-2.5"))
        assertTrue(TransparencyPresets.supports("openai/gpt-image-2.5"))
    }

    @Test
    fun `dall-e-2 is preset supported but dall-e-3 is not`() {
        assertTrue(TransparencyPresets.supports("dall-e-2"))
        assertFalse(TransparencyPresets.supports("dall-e-3"))
    }

    @Test
    fun `unknown models default to unsupported`() {
        assertFalse(TransparencyPresets.supports("gpt-4o"))
        assertFalse(TransparencyPresets.supports("gemini-2.5-flash-image"))
        assertFalse(TransparencyPresets.supports("gemini-3.1-flash-image"))
        assertFalse(TransparencyPresets.supports("qwen-image"))
        assertFalse(TransparencyPresets.supports(""))
        assertFalse(TransparencyPresets.supports("   "))
    }

    @Test
    fun `explicit declaration beats preset`() {
        // 预设不支持，但用户手动声明支持 → 生效
        assertTrue(TransparencyPresets.effective("gemini-2.5-flash-image", TransparencyPreference.YES))
        // 预设支持，但用户手动关闭 → 不生效
        assertFalse(TransparencyPresets.effective("gpt-image-1", TransparencyPreference.NO))
    }

    @Test
    fun `auto follows preset`() {
        assertTrue(TransparencyPresets.effective("gpt-image-1", TransparencyPreference.AUTO))
        assertFalse(TransparencyPresets.effective("dall-e-3", TransparencyPreference.AUTO))
    }

    @Test
    fun `preference round trips through stored value`() {
        for (pref in TransparencyPreference.entries) {
            assertEquals(pref, TransparencyPreference.fromValue(pref.value))
        }
        assertEquals(TransparencyPreference.AUTO, TransparencyPreference.fromValue(null))
        assertEquals(TransparencyPreference.AUTO, TransparencyPreference.fromValue(""))
        assertEquals(TransparencyPreference.AUTO, TransparencyPreference.fromValue("maybe"))
    }

    @Test
    fun `summary shows current model preset result`() {
        assertEquals(
            "预设支持 → 透明直出",
            TransparencyPresets.summary("gpt-image-1", TransparencyPreference.AUTO),
        )
        assertEquals(
            "预设不支持 → 键色底",
            TransparencyPresets.summary("dall-e-3", TransparencyPreference.AUTO),
        )
        assertEquals(
            "手动透明直出",
            TransparencyPresets.summary("gemini-3.1-flash-image", TransparencyPreference.YES),
        )
        assertEquals(
            "手动键色底",
            TransparencyPresets.summary("gpt-image-1", TransparencyPreference.NO),
        )
        assertEquals(
            "填写模型后显示预设结果",
            TransparencyPresets.summary("   ", TransparencyPreference.AUTO),
        )
    }
}
