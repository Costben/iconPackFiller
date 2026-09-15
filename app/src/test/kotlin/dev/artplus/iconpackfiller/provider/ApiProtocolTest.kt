package dev.artplus.iconpackfiller.provider

import dev.artplus.iconpackfiller.settings.ProviderSlot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 协议枚举与存储字段（kind + mode）的映射契约。
 *
 * 协议不单独持久化，靠这两个测试锁住"配置格式零变更"的前提：
 * 任一 (kind, mode) 都必须能反查出协议，且四个协议的取值互不重合。
 */
class ApiProtocolTest {

    @Test
    fun `every protocol maps back from its stored kind and mode`() {
        for (protocol in ApiProtocol.entries) {
            assertEquals(
                protocol,
                ApiProtocol.of(protocol.kind, protocol.mode),
                "协议 ${protocol.name} 无法由 kind=${protocol.kind} + mode=${protocol.mode} 还原",
            )
        }
    }

    @Test
    fun `stored pairs are unique across protocols`() {
        val pairs = ApiProtocol.entries.map { it.kind to it.mode }
        assertEquals(pairs.size, pairs.toSet().size, "存在共享 (kind, mode) 的协议，映射不唯一")
    }

    @Test
    fun `gemini slot ignores mode`() {
        for (mode in OpenAIMode.entries) {
            assertEquals(ApiProtocol.GEMINI_NATIVE, ApiProtocol.of(ProviderKind.GEMINI, mode))
        }
    }

    @Test
    fun `old config without explicit fields resolves to openai images`() {
        // 旧配置默认值：kind=OPENAI、mode=images
        assertEquals(
            ApiProtocol.OPENAI_IMAGES,
            ApiProtocol.of(ProviderKind.fromValue(null), OpenAIMode.fromValue(null)),
        )
        assertEquals(ApiProtocol.OPENAI_IMAGES, ApiProtocol.fromValue(null))
        assertEquals(ApiProtocol.OPENAI_IMAGES, ApiProtocol.fromValue("不存在的协议"))
    }

    @Test
    fun `format line names endpoint and auth`() {
        assertEquals(
            "POST /v1/chat/completions · Bearer 鉴权",
            ApiProtocol.OPENAI_CHAT.formatLine,
        )
        assertTrue(ApiProtocol.GEMINI_NATIVE.formatLine.contains("x-goog-api-key"))
    }

    @Test
    fun `menu labels are short and unique`() {
        val labels = ApiProtocol.entries.map { it.menuLabel }
        assertEquals(labels.size, labels.toSet().size, "下拉短名重复")
        assertTrue(labels.all { it.length <= 20 }, "下拉短名过长：$labels")
    }

    @Test
    fun `fresh slot prefills protocol defaults`() {
        val images = ProviderSlot.fresh("slot-2", 2, ApiProtocol.OPENAI_IMAGES, fallbackBaseUrl = "http://gw:3002")
        assertEquals("https://api.openai.com", images.baseUrl)
        assertEquals("gpt-image-1", images.model)
        assertEquals("OPENAI", images.kind)
        assertEquals("images", images.mode)

        val gemini = ProviderSlot.fresh("slot-3", 3, ApiProtocol.GEMINI_NATIVE, fallbackBaseUrl = "http://gw:3002")
        assertEquals("https://generativelanguage.googleapis.com", gemini.baseUrl)
        assertEquals("GEMINI", gemini.kind)
    }

    @Test
    fun `fresh slot falls back to current base url when protocol has no default`() {
        // Chat / Responses 没有官方默认地址：沿用当前槽位地址（同一网关上开多槽位是常态）
        val chat = ProviderSlot.fresh("slot-2", 2, ApiProtocol.OPENAI_CHAT, fallbackBaseUrl = "http://192.168.31.179:3002")
        assertEquals("http://192.168.31.179:3002", chat.baseUrl)
        assertEquals("gemini-2.5-flash-image", chat.model)
        assertEquals("chat", chat.mode)
    }
}
