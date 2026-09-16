package dev.artplus.iconpackfiller.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkFileIconPackTest {

    private fun fixture(): ApkFileIconPack {
        val url = javaClass.classLoader!!.getResource("test-iconpack.apk")
        assertNotNull(url, "test-iconpack.apk 缺失")
        return ApkFileIconPack.open(java.io.File(url.toURI()))
    }

    private fun compiledFixture(): File {
        val url = javaClass.classLoader!!.getResource("test-iconpack-compiled.apk")
        assertNotNull(url, "test-iconpack-compiled.apk 缺失")
        return File(url.toURI())
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

    @Test
    fun `reads compiled appfilter when assets appfilter is absent`() {
        val compiledOnly = File.createTempFile("compiled-only-", ".apk")
        try {
            ZipFile(compiledFixture()).use { source ->
                ZipOutputStream(compiledOnly.outputStream()).use { output ->
                    source.entries().asSequence()
                        .filterNot { it.name == "assets/appfilter.xml" || it.name == "assets/appfilter" }
                        .forEach { entry ->
                            output.putNextEntry(ZipEntry(entry.name))
                            source.getInputStream(entry).use { it.copyTo(output) }
                            output.closeEntry()
                        }
                }
            }
            ApkFileIconPack.open(compiledOnly).use { pack ->
                val text = assertNotNull(pack.readAppFilterText(), "编译版 appfilter 未读取")
                val document = AppFilterParser.parse(text.byteInputStream(Charsets.UTF_8))
                assertTrue(document.items.any { it.component.packageName == "com.tencent.mm" })
            }
        } finally {
            compiledOnly.delete()
        }
    }
}
