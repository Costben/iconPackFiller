package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.generate.IconPackSource
import dev.artplus.iconpackfiller.pack.AppFilterDocument
import dev.artplus.iconpackfiller.pack.AppFilterExtras
import dev.artplus.iconpackfiller.pack.AppFilterItem
import dev.artplus.iconpackfiller.pack.ComponentKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对应表构建：appfilter item -> PackEntrySpec（resPath / kind / density）。
 */
class PackEntryBuilderTest {

    private fun fixtureFile(): File {
        val url = javaClass.classLoader!!.getResource("test-iconpack.apk")
        assertNotNull(url, "test-iconpack.apk 缺失")
        return File(url.toURI())
    }

    @Test
    fun `one entry per appfilter item with kind density and resPath`() {
        val source = IconPackSource.open(fixtureFile())
        assertNotNull(source)
        source.use { pack ->
            val entries = PackEntryBuilder.build(
                document = pack.document,
                resourcePath = { pack.resourcePath(it) },
                exists = { it in pack.availableDrawables },
            )

            assertEquals(5, entries.size)
            assertEquals(pack.document.items.size, entries.size)
            assertTrue(entries.all { it.origin == PackEntryOrigin.APPFILTER })
            assertTrue(entries.all { it.inPack })
            assertTrue(entries.all { it.kind == PackEntryKind.PNG })
            assertTrue(entries.all { it.density == "xxxhdpi-v4" })

            val wechat = entries.first { it.drawableName == "ic_wechat" }
            assertEquals("com.tencent.mm", wechat.packageName)
            assertEquals("com.tencent.mm.ui.launcherui", wechat.activityName)
            assertEquals("res/drawable-xxxhdpi-v4/ic_wechat.png", wechat.resPath)
            assertEquals(
                "ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}",
                wechat.componentRaw,
            )

            val packageLevel = entries.first { it.drawableName == "ic_mt" }
            assertEquals("bin.mt.plus", packageLevel.packageName)
            assertNull(packageLevel.activityName)
        }
    }

    @Test
    fun `missing drawable is marked out of pack`() {
        val document = AppFilterDocument(
            items = listOf(
                AppFilterItem(
                    component = ComponentKey("com.x", null),
                    drawableName = "ghost",
                    rawComponent = "ComponentInfo{com.x}",
                ),
            ),
            extras = AppFilterExtras(),
        )

        val entry = PackEntryBuilder.build(
            document = document,
            resourcePath = { null },
            exists = { false },
        ).single()

        assertFalse(entry.inPack)
        assertNull(entry.resPath)
        assertEquals(PackEntryKind.OTHER, entry.kind)
        assertNull(entry.density)
    }
}
