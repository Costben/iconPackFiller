package dev.artplus.iconpackfiller.pack

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppFilterParserTest {

    private fun parse(xml: String): AppFilterDocument =
        AppFilterParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test
    fun `parses fixture style appfilter with package level entry and unknown component`() {
        val doc = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <item component="ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}" drawable="ic_wechat" />
                <item component="ComponentInfo{bin.mt.plus}" drawable="ic_mt" />
                <item component="ComponentInfo{com.not.installed.app/com.not.installed.app.Main}" drawable="ic_ghost" />
            </resources>
            """.trimIndent(),
        )
        assertEquals(3, doc.items.size)
        assertEquals("ic_wechat", doc.items[0].drawableName)
        assertTrue(doc.items[1].component.isPackageLevel)
        assertTrue(doc.items[2].component.packageName == "com.not.installed.app")
    }

    @Test
    fun `passes through iconback iconmask iconupon scale`() {
        val doc = parse(
            """
            <resources>
                <iconback img1="iconback" img2="iconback2" />
                <iconmask img1="iconmask" />
                <iconupon img1="iconupon" />
                <iconScale scale="1.1" />
                <item component="ComponentInfo{com.foo.bar}" drawable="x" />
            </resources>
            """.trimIndent(),
        )
        assertEquals(listOf("iconback", "iconback2"), doc.extras.iconback)
        assertEquals(listOf("iconmask"), doc.extras.iconmask)
        assertEquals(listOf("iconupon"), doc.extras.iconupon)
        assertEquals(1.1f, doc.extras.scale)
        assertEquals(1, doc.items.size)
    }

    @Test
    fun `skips item without drawable`() {
        val doc = parse(
            """
            <resources>
                <item component="ComponentInfo{com.foo.bar}" />
                <item drawable="orphan" />
                <item component="ComponentInfo{com.ok.app}" drawable="ok" />
            </resources>
            """.trimIndent(),
        )
        assertEquals(1, doc.items.size)
        assertEquals("ok", doc.items[0].drawableName)
    }

    @Test
    fun `exact match wins over package level`() {
        val doc = parse(
            """
            <resources>
                <item component="ComponentInfo{com.foo.bar}" drawable="pkg" />
                <item component="ComponentInfo{com.foo.bar/com.foo.bar.Main}" drawable="exact" />
            </resources>
            """.trimIndent(),
        )
        assertEquals("exact", doc.match("com.foo.bar", "com.foo.bar.Main")?.drawableName)
        assertEquals("pkg", doc.match("com.foo.bar", "com.foo.bar.Other")?.drawableName)
        assertEquals("pkg", doc.match("com.foo.bar", null)?.drawableName)
    }

    @Test
    fun `last duplicate entry wins`() {
        val doc = parse(
            """
            <resources>
                <item component="ComponentInfo{com.foo.bar/com.foo.bar.Main}" drawable="first" />
                <item component="ComponentInfo{com.foo.bar/com.foo.bar.Main}" drawable="second" />
            </resources>
            """.trimIndent(),
        )
        assertEquals("second", doc.match("com.foo.bar", "com.foo.bar.Main")?.drawableName)
    }

    @Test
    fun `match is case insensitive`() {
        val doc = parse(
            """
            <resources>
                <item component="ComponentInfo{com.foo.bar/com.foo.bar.Main}" drawable="x" />
            </resources>
            """.trimIndent(),
        )
        assertNotNull(doc.match("COM.FOO.BAR", "com.foo.bar.Main"))
    }

    @Test
    fun `match returns null when absent`() {
        val doc = parse("<resources></resources>")
        assertNull(doc.match("com.absent", "com.absent.Main"))
        assertTrue(doc.items.isEmpty())
        assertTrue(doc.extras.isEmpty)
    }

    @Test
    fun `tolerates namespace prefixed root and unknown tags`() {
        val doc = parse(
            """
            <resources xmlns:tools="http://schemas.android.com/tools">
                <unknown attr="1" />
                <item component="ComponentInfo{com.foo.bar}" drawable="ok" tools:ignore="all" />
            </resources>
            """.trimIndent(),
        )
        assertEquals(1, doc.items.size)
    }

    @Test
    fun `containsDrawable`() {
        val doc = parse("<resources><item component=\"ComponentInfo{com.foo.bar}\" drawable=\"abc\" /></resources>")
        assertTrue(doc.containsDrawable("abc"))
        assertTrue(!doc.containsDrawable("def"))
    }
}