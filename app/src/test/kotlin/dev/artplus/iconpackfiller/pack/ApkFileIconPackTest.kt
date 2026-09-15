package dev.artplus.iconpackfiller.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkFileIconPackTest {

    private fun fixture(): ApkFileIconPack {
        val url = javaClass.classLoader!!.getResource("test-iconpack.apk")
        assertNotNull(url, "test-iconpack.apk 缺失")
        return ApkFileIconPack.open(java.io.File(url.toURI()))
    }

    @Test
    fun `lists all drawables from fixture arsc`() {
        fixture().use { pack ->
            val names = pack.drawables.keys
            assertTrue("ic_wechat" in names, "缺 ic_wechat: $names")
            assertTrue("ic_vlc" in names)
            assertTrue("ic_syncthing" in names)
            assertTrue("ic_mt" in names)
            assertTrue("ic_ghost" in names)
            assertTrue("pack_icon" in names)
        }
    }

    @Test
    fun `reads drawable bytes`() {
        fixture().use { pack ->
            val bytes = pack.readDrawableBytes("ic_wechat")
            assertNotNull(bytes)
            assertTrue(bytes.size > 8)
            // PNG magic
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
            assertEquals('N'.code.toByte(), bytes[2])
            assertEquals('G'.code.toByte(), bytes[3])
        }
    }

    @Test
    fun `returns null for missing drawable`() {
        fixture().use { pack ->
            assertNull(pack.readDrawableBytes("no_such_drawable"))
        }
    }

    @Test
    fun `density path prefers xxxhdpi`() {
        fixture().use { pack ->
            assertEquals("res/drawable-xxxhdpi-v4/ic_wechat.png", pack.drawables["ic_wechat"])
        }
    }
}