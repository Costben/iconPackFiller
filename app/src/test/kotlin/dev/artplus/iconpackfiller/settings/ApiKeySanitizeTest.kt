package dev.artplus.iconpackfiller.settings

import org.junit.Test
import kotlin.test.assertEquals

class ApiKeySanitizeTest {

    @Test
    fun `plain key passes through`() {
        // 合成 fixture：禁止写入真实密钥（ASCII 直通语义不受影响）
        val key = "sk-testAaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTt"
        assertEquals(key, SettingsStore.sanitizeKey(key))
    }

    @Test
    fun `surrounding whitespace trimmed`() {
        assertEquals("sk-abc", SettingsStore.sanitizeKey("  sk-abc \n"))
    }

    @Test
    fun `ime chinese pollution removed`() {
        // WeType 联想污染示例：汉字被插进密钥
        val polluted = "sk-帅abc看def楼顶"
        assertEquals("sk-abcdef", SettingsStore.sanitizeKey(polluted))
    }

    @Test
    fun `full width characters removed`() {
        // U+FF0D FULLWIDTH HYPHEN-MINUS 同样在 ASCII 之外，被丢弃
        assertEquals("skabc", SettingsStore.sanitizeKey("sk－abc"))
        assertEquals("abc", SettingsStore.sanitizeKey("ｓｋabc"))
    }

    @Test
    fun `internal whitespace removed`() {
        assertEquals("skabcdef", SettingsStore.sanitizeKey("sk abc\tdef"))
    }

    @Test
    fun `zero width and control chars removed`() {
        assertEquals("sk-abc", SettingsStore.sanitizeKey("sk-\u200Babc\u0000\uFEFF"))
    }

    @Test
    fun `non ascii only input yields empty`() {
        assertEquals("", SettingsStore.sanitizeKey("帅看楼顶"))
        assertEquals("", SettingsStore.sanitizeKey("   "))
        assertEquals("", SettingsStore.sanitizeKey(""))
    }

    @Test
    fun `dash underscore and punctuation preserved`() {
        assertEquals("a-b_c.d~e", SettingsStore.sanitizeKey("a-b_c.d~e"))
    }

    @Test
    fun `emoji removed`() {
        assertEquals("skabc", SettingsStore.sanitizeKey("sk😀abc"))
    }
}
