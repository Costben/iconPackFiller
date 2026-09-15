package dev.artplus.iconpackfiller.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackNamingTest {

    @Test
    fun `package name uses hash8 prefix`() {
        val name = PackNaming.packageNameFor("com.example.iconpack")
        assertTrue(name.startsWith("dev.artplus.iconpack."))
        val segment = name.removePrefix("dev.artplus.iconpack.")
        assertTrue(segment.length in 8..9)
        assertTrue(segment.last() in "0123456789abcdef")
    }

    /**
     * Android 包名每段必须以字母开头：hash 首位是数字时曾直接产出
     * `dev.artplus.iconpack.280f2dd4`，安装器报
     * `INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME`。
     */
    @Test
    fun `package name is always installable shape`() {
        val samples = listOf(
            "studio14.application.auraicons",
            "app.lawnchair",
            "com.coolapk.market",
            "com.microsoft.launcher",
            "com.example.iconpack",
            "a",
            "b",
        )
        val pattern = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        for (sample in samples) {
            val name = PackNaming.packageNameFor(sample)
            assertTrue(pattern.matches(name), "$sample -> $name 不是合法包名")
        }
        // hex 首位是数字的真实案例：补 p 前缀
        assertEquals(
            "dev.artplus.iconpack.p280f2dd4",
            PackNaming.packageNameFor("studio14.application.auraicons"),
        )
    }

    @Test
    fun `package name is deterministic and case insensitive`() {
        assertEquals(
            PackNaming.packageNameFor("com.example.IconPack"),
            PackNaming.packageNameFor("  COM.EXAMPLE.ICONPACK "),
        )
    }

    @Test
    fun `different packages give different names`() {
        assertTrue(
            PackNaming.packageNameFor("a") != PackNaming.packageNameFor("b"),
        )
    }

    @Test
    fun `version code offset`() {
        assertEquals(10_042, PackNaming.versionCodeFor(42))
        assertEquals(10_000, PackNaming.versionCodeFor(null))
    }

    @Test
    fun `label suffix`() {
        assertEquals("测试图标包（补全）", PackNaming.labelFor("测试图标包"))
        assertEquals("测试图标包（补全）", PackNaming.labelFor("测试图标包（补全）"))
        assertEquals("图标包（补全）", PackNaming.labelFor(null))
    }

    @Test
    fun `version name suffix`() {
        assertEquals("1.2.3-filler", PackNaming.versionNameFor("1.2.3"))
        assertEquals("1.0.0-filler", PackNaming.versionNameFor(null))
    }

    @Test
    fun `drawable name format`() {
        assertEquals("ap_gen_0", PackNaming.drawableNameFor(0))
        assertEquals("ap_gen_17", PackNaming.drawableNameFor(17))
    }

    @Test
    fun `unique drawable name avoids collision`() {
        assertEquals("ap_gen_0", PackNaming.uniqueDrawableName("ap_gen_0", emptySet()))
        assertEquals("ap_gen_0_2", PackNaming.uniqueDrawableName("ap_gen_0", setOf("ap_gen_0")))
        assertEquals(
            "ap_gen_0_3",
            PackNaming.uniqueDrawableName("ap_gen_0", setOf("ap_gen_0", "ap_gen_0_2")),
        )
    }

    @Test
    fun `main activity follows package`() {
        assertEquals("dev.artplus.iconpack.abcd1234.MainActivity", PackNaming.mainActivityClassFor("dev.artplus.iconpack.abcd1234"))
    }
}

class AppFilterInjectorTest {

    private val original = """
        <?xml version="1.0" encoding="UTF-8"?>
        <resources>
            <iconback img1="iconback" />
            <iconmask img1="iconmask" />
            <item component="ComponentInfo{com.a/com.a.Main}" drawable="a_icon" />
        </resources>
    """.trimIndent()

    @Test
    fun `inserts before closing tag`() {
        val updated = AppFilterInjector.inject(
            original,
            listOf(AppFilterInjector.NewItem("ComponentInfo{com.b/com.b.Main}", "ap_gen_0")),
        )
        assertTrue(updated.contains("<item component=\"ComponentInfo{com.b/com.b.Main}\" drawable=\"ap_gen_0\" />"))
        assertTrue(updated.indexOf("ap_gen_0") < updated.indexOf("</resources>"))
        assertTrue(updated.contains("a_icon"))
        assertTrue(updated.contains("<iconback"))
    }

    @Test
    fun `preserves original order`() {
        val updated = AppFilterInjector.inject(
            original,
            listOf(
                AppFilterInjector.NewItem("ComponentInfo{com.b/com.b.Main}", "ap_gen_0"),
                AppFilterInjector.NewItem("ComponentInfo{com.c/com.c.Main}", "ap_gen_1"),
            ),
        )
        assertTrue(updated.indexOf("ap_gen_0") < updated.indexOf("ap_gen_1"))
    }

    @Test
    fun `creates document when original missing`() {
        val updated = AppFilterInjector.inject(
            null,
            listOf(AppFilterInjector.NewItem("ComponentInfo{com.b/com.b.Main}", "ap_gen_0")),
        )
        assertTrue(updated.startsWith("<?xml"))
        assertTrue(updated.contains("<resources>"))
        assertTrue(updated.contains("</resources>"))
        assertTrue(updated.contains("ap_gen_0"))
    }

    @Test
    fun `appends when no closing tag`() {
        val updated = AppFilterInjector.inject(
            "<resources><item component=\"ComponentInfo{com.a/com.a.Main}\" drawable=\"a\" />",
            listOf(AppFilterInjector.NewItem("ComponentInfo{com.b/com.b.Main}", "ap_gen_0")),
        )
        assertTrue(updated.contains("ap_gen_0"))
        assertTrue(updated.contains("com.a"))
    }

    @Test
    fun `no items returns original`() {
        assertEquals(original, AppFilterInjector.inject(original, emptyList()))
    }

    @Test
    fun `escapes special characters`() {
        val updated = AppFilterInjector.inject(
            original,
            listOf(AppFilterInjector.NewItem("ComponentInfo{com.a&b/c<d>}", "ap_gen_0")),
        )
        assertTrue(updated.contains("com.a&amp;b/c&lt;d&gt;"))
    }

    @Test
    fun `existing components extracted normalized`() {
        val doc = AppFilterParser.parse(original.byteInputStream(Charsets.UTF_8))
        val existing = AppFilterInjector.existingComponents(doc)
        assertEquals(setOf("com.a/com.a.main"), existing)
    }
}