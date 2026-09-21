package dev.artplus.iconpackfiller.pack

import dev.artplus.iconpackfiller.generate.IconPackSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 包内资源枚举：导入建项目时把 component↔drawable 配到具体资源文件。
 */
class PackResourceEnumerationTest {

    private fun fixtureFile(): File {
        val url = javaClass.classLoader!!.getResource("test-iconpack.apk")
        assertNotNull(url, "test-iconpack.apk 缺失")
        return File(url.toURI())
    }

    @Test
    fun `apk file icon pack enumerates every in-pack icon resource`() {
        ApkFileIconPack.open(fixtureFile()).use { pack ->
            assertEquals(
                listOf(
                    "res/drawable-xxxhdpi-v4/ic_ghost.png",
                    "res/drawable-xxxhdpi-v4/ic_mt.png",
                    "res/drawable-xxxhdpi-v4/ic_syncthing.png",
                    "res/drawable-xxxhdpi-v4/ic_vlc.png",
                    "res/drawable-xxxhdpi-v4/ic_wechat.png",
                    "res/drawable-xxxhdpi-v4/pack_icon.png",
                ),
                pack.resourceFiles.map { it.resPath }.sorted(),
            )
            assertEquals(
                "ic_wechat",
                pack.resourceFiles.first { it.resPath.endsWith("ic_wechat.png") }.name,
            )
        }
    }

    @Test
    fun `icon pack source resolves the best density path per drawable`() {
        val source = IconPackSource.open(fixtureFile())
        assertNotNull(source)
        source.use { pack ->
            assertEquals("res/drawable-xxxhdpi-v4/ic_wechat.png", pack.resourcePath("ic_wechat"))
            assertEquals("res/drawable-xxxhdpi-v4/pack_icon.png", pack.resourcePath("pack_icon"))
            assertNull(pack.resourcePath("no_such_drawable"))
            assertEquals(6, pack.resources.size)
        }
    }
}
