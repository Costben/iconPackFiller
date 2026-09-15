package dev.artplus.iconpackfiller.settings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderSlotsCodecTest {

    private fun slot(
        id: String = "slot-1",
        name: String = "默认供应商",
        baseUrl: String = "http://192.168.31.179:3002",
        model: String = "gpt-image-2",
        kind: String = "OPENAI",
        mode: String = "images",
    ) = ProviderSlot(id = id, name = name, baseUrl = baseUrl, model = model, kind = kind, mode = mode)

    @Test
    fun `encode decode round trip`() {
        val config = ProviderSlotsCodec.Config(
            slots = listOf(
                slot(),
                slot(id = "slot-2", name = "备用", baseUrl = "https://api.openai.com", model = "gpt-image-1"),
            ),
            activeId = "slot-2",
        )
        val decoded = ProviderSlotsCodec.decode(ProviderSlotsCodec.encode(config))
        assertNotNull(decoded)
        assertEquals(config, decoded)
        assertEquals("slot-2", decoded.activeId)
    }

    @Test
    fun `decode null or blank returns null`() {
        assertNull(ProviderSlotsCodec.decode(null))
        assertNull(ProviderSlotsCodec.decode(""))
        assertNull(ProviderSlotsCodec.decode("   "))
    }

    @Test
    fun `decode malformed json returns null`() {
        assertNull(ProviderSlotsCodec.decode("not json"))
        assertNull(ProviderSlotsCodec.decode("{}"))
        assertNull(ProviderSlotsCodec.decode("""{"slots":[]}"""))
    }

    @Test
    fun `decode skips slots without id`() {
        val json = """{"version":1,"active":"slot-2","slots":[{"name":"无 id"},{"id":"slot-2","name":"有 id"}]}"""
        val decoded = ProviderSlotsCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(1, decoded.slots.size)
        assertEquals("slot-2", decoded.slots.first().id)
    }

    @Test
    fun `transparency survives round trip`() {
        val config = ProviderSlotsCodec.Config(
            slots = listOf(
                slot().copy(transparency = "yes"),
                slot(id = "slot-2").copy(transparency = "no"),
            ),
            activeId = "slot-1",
        )
        val decoded = ProviderSlotsCodec.decode(ProviderSlotsCodec.encode(config))
        assertNotNull(decoded)
        assertEquals(config, decoded)
        assertEquals("yes", decoded.slots[0].transparency)
        assertEquals("no", decoded.slots[1].transparency)
    }

    @Test
    fun `legacy config without transparency decodes to auto`() {
        // 升级前的旧配置没有 transparency 字段
        val json = """{"version":1,"active":"slot-1","slots":[{"id":"slot-1","name":"A","baseUrl":"https://x","model":"gpt-image-1","kind":"OPENAI","mode":"images"}]}"""
        val decoded = ProviderSlotsCodec.decode(json)
        assertNotNull(decoded)
        assertEquals("auto", decoded.slots.single().transparency)
    }

    @Test
    fun `decode normalizes unknown transparency to auto`() {
        val json = """{"slots":[{"id":"slot-1","transparency":"maybe"}]}"""
        val decoded = ProviderSlotsCodec.decode(json)
        assertNotNull(decoded)
        assertEquals("auto", decoded.slots.single().transparency)
    }

    @Test
    fun `decode fills blanks with defaults`() {
        val json = """{"slots":[{"id":"slot-1"}]}"""
        val decoded = ProviderSlotsCodec.decode(json)
        assertNotNull(decoded)
        val only = decoded.slots.single()
        assertEquals("供应商 1", only.name)
        assertEquals("https://api.openai.com", only.baseUrl)
        assertEquals("gpt-image-1", only.model)
        assertEquals("OPENAI", only.kind)
        assertEquals("images", only.mode)
        assertEquals("auto", only.transparency)
    }

    @Test
    fun `decode resets active when unknown`() {
        val json = """{"active":"ghost","slots":[{"id":"slot-3","name":"A"}]}"""
        val decoded = ProviderSlotsCodec.decode(json)
        assertNotNull(decoded)
        assertEquals("slot-3", decoded.activeId)
        assertEquals("slot-3", decoded.active.id)
    }

    @Test
    fun `active falls back to first when activeId missing from list`() {
        val config = ProviderSlotsCodec.Config(
            slots = listOf(slot(id = "slot-1"), slot(id = "slot-2")),
            activeId = "nope",
        )
        assertEquals("slot-1", config.active.id)
    }

    @Test
    fun `migrate uses legacy values`() {
        val config = ProviderSlotsCodec.migrate(
            baseUrl = "http://192.168.31.179:3002",
            model = "gpt-image-2",
            kind = "OPENAI",
            mode = "images",
        )
        assertEquals(1, config.slots.size)
        val only = config.slots.single()
        assertEquals("http://192.168.31.179:3002", only.baseUrl)
        assertEquals("gpt-image-2", only.model)
        assertEquals(only.id, config.activeId)
    }

    @Test
    fun `migrate falls back to defaults when legacy blank`() {
        val config = ProviderSlotsCodec.migrate(baseUrl = null, model = "  ", kind = null, mode = null)
        val only = config.slots.single()
        assertEquals("https://api.openai.com", only.baseUrl)
        assertEquals("gpt-image-1", only.model)
        assertEquals("OPENAI", only.kind)
        assertEquals("images", only.mode)
    }

    @Test
    fun `migrated config survives round trip`() {
        val migrated = ProviderSlotsCodec.migrate("http://x:1/v1", "m", "GEMINI", "responses")
        assertEquals(migrated, ProviderSlotsCodec.decode(ProviderSlotsCodec.encode(migrated)))
    }

    @Test
    fun `duplicate name appends 2`() {
        assertEquals(
            "GPT image (2)",
            ProviderSlotsCodec.nextDuplicateName(listOf("GPT image"), "GPT image"),
        )
    }

    @Test
    fun `duplicate name skips taken suffixes`() {
        assertEquals(
            "GPT image (3)",
            ProviderSlotsCodec.nextDuplicateName(
                listOf("GPT image", "GPT image (2)"),
                "GPT image",
            ),
        )
    }

    @Test
    fun `duplicate name increments existing suffix instead of nesting`() {
        assertEquals(
            "GPT image (3)",
            ProviderSlotsCodec.nextDuplicateName(listOf("GPT image (2)"), "GPT image (2)"),
        )
    }

    @Test
    fun `duplicate name falls back when blank`() {
        assertEquals(
            "供应商 (2)",
            ProviderSlotsCodec.nextDuplicateName(emptyList(), "   "),
        )
    }

    @Test
    fun `nextId picks smallest free`() {
        assertEquals("slot-1", ProviderSlotsCodec.nextId(emptyList()))
        assertEquals("slot-2", ProviderSlotsCodec.nextId(listOf("slot-1")))
        assertEquals("slot-2", ProviderSlotsCodec.nextId(listOf("slot-1", "slot-3")))
        assertEquals("slot-4", ProviderSlotsCodec.nextId(listOf("slot-1", "slot-2", "slot-3")))
    }

    @Test
    fun `encode omits api key field`() {
        val config = ProviderSlotsCodec.Config(slots = listOf(slot()), activeId = "slot-1")
        val encoded = ProviderSlotsCodec.encode(config)
        assertTrue("apiKey" !in encoded)
        assertTrue("sk-" !in encoded)
    }

    @Test
    fun `slot bounds are sane`() {
        assertEquals(1, ProviderSlot.MIN_SLOTS)
        assertTrue(ProviderSlot.MAX_SLOTS >= 2)
    }

    @Test
    fun `default slot is usable`() {
        val default = ProviderSlot.default()
        assertEquals("slot-1", default.id)
        assertTrue(default.baseUrl.isNotBlank())
        assertTrue(default.model.isNotBlank())
    }
}
