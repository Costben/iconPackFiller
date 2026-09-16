package dev.artplus.iconpackfiller.pack

import com.reandroid.apk.ApkModule
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实 ARSCLib 端到端打包测试（桌面 JVM）。
 *
 * 用 fixture APK 走完整注入流程，再用 ARSCLib 重新读取产物校验。
 */
class IconPackPackerTest {

    private val fixture: File
        get() = File(javaClass.classLoader!!.getResource("test-iconpack.apk")!!.toURI())

    /** 带编译版 `res/xml/appfilter.xml` 的 fixture（Lawnchair 优先读的那份）。 */
    private val compiledFixture: File
        get() = File(javaClass.classLoader!!.getResource("test-iconpack-compiled.apk")!!.toURI())

    private fun tinyPng(seed: Int): ByteArray {
        val bitmap = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) {
            bitmap.setRGB(x, y, 0xFF000000.toInt() or (seed * 0x010101))
        }
        val output = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(bitmap, "png", output)
        return output.toByteArray()
    }

    /**
     * 回归（Lawnchair 整包不生效）：资源表包名必须跟 manifest 一起改成新包名。
     *
     * 启动器取图标走 `getIdentifier(name, "drawable", 已安装包名)`；defPackage 与资源表
     * 包名不一致时返回 0 → 整包一个图标都不生效，而非只缺新图标。
     */
    @Test
    fun `resources table package renamed with manifest`() {
        val output = File.createTempFile("packed-table-", ".apk")
        val result = IconPackPacker().pack(fixture, output, emptyList())
        ApkModule.loadApkFile(output).use { module ->
            val tablePkg = module.tableBlock!!.packages.next()!!
            assertEquals(
                result.packageName,
                tablePkg.name,
                "资源表包名没跟 manifest 同步，启动器 getIdentifier 会全部失败",
            )
            // 启动器的实际取用路径：按新包名从自己的资源表查 drawable
            val drawables = HashSet<String>()
            val iterator = tablePkg.getResources("drawable")
            while (iterator.hasNext()) {
                iterator.next().name?.let { drawables.add(it) }
            }
            assertTrue("ic_wechat" in drawables, "原 drawable 应仍在资源表：$drawables")
        }
        output.delete()
    }

    /**
     * 回归：Lawnchair 优先读 `res/xml/appfilter.xml`（编译版），新条目必须写进这份。
     */
    @Test
    fun `injects into compiled res xml appfilter`() {
        val output = File.createTempFile("packed-xml-", ".apk")
        IconPackPacker().pack(
            compiledFixture,
            output,
            listOf(
                IconInjection(
                    component = "ComponentInfo{com.example.newapp/com.example.newapp.MainActivity}",
                    drawableName = "ap_gen_0",
                    pngBytes = tinyPng(7),
                ),
            ),
        )
        ApkModule.loadApkFile(output).use { module ->
            val document = module.loadResXmlDocument("res/xml/appfilter.xml")
            val root = document.documentElement!!
            val components = root.listElements("item")
                .mapNotNull { it.searchAttributeByName("component")?.valueAsString }
            assertTrue(
                components.any { it.contains("com.example.newapp/com.example.newapp.MainActivity") },
                "编译版 appfilter 未注入新条目：$components",
            )
            // 原有条目保留
            assertTrue(components.any { it.contains("com.tencent.mm") }, "原条目丢失")
        }
        output.delete()
    }

    /** 回归：appfilter 条目必须带 activity（启动器按 ComponentName 匹配）。 */
    @Test
    fun `injected component carries activity name`() {
        val output = File.createTempFile("packed-comp-", ".apk")
        val component = PackNaming.componentInfoFor(
            "com.example.newapp",
            "com.example.newapp.MainActivity",
        )
        IconPackPacker().pack(
            compiledFixture,
            output,
            listOf(
                IconInjection(component, "ap_gen_0", tinyPng(8)),
            ),
        )
        assertEquals("ComponentInfo{com.example.newapp/com.example.newapp.MainActivity}", component)

        ApkModule.loadApkFile(output).use { module ->
            val text = module.getInputSource("assets/appfilter.xml")!!
                .openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            assertTrue(text.contains("com.example.newapp/com.example.newapp.MainActivity"))
            val parsed = AppFilterParser.parse(text.byteInputStream(StandardCharsets.UTF_8))
            val injected = parsed.items.first { it.rawComponent.contains("com.example.newapp") }
            val key = ComponentKey.parse(injected.rawComponent)!!
            assertTrue(!key.isPackageLevel, "注入条目不能是包级（启动器会跳过）")
            assertEquals("com.example.newapp", key.packageName)
            assertEquals("com.example.newapp.mainactivity", key.activityName)
        }
        output.delete()
    }

    @Test
    fun `packs fixture with injections`() {
        val output = File.createTempFile("packed-", ".apk")
        val injections = listOf(
            IconInjection(
                component = "ComponentInfo{com.example.newapp/com.example.newapp.MainActivity}",
                drawableName = "ap_gen_0",
                pngBytes = tinyPng(1),
            ),
            IconInjection(
                component = "ComponentInfo{com.example.other/com.example.other.Main}",
                drawableName = "ap_gen_1",
                pngBytes = tinyPng(2),
            ),
        )

        val result = IconPackPacker().pack(fixture, output, injections)
        assertTrue(output.isFile && output.length() > 0)
        assertTrue(result.packageName.startsWith("dev.artplus.iconpack."))
        assertEquals(2, result.injectedCount)

        ApkModule.loadApkFile(output).use { module ->
            val manifest = module.androidManifest!!
            assertEquals(result.packageName, manifest.packageName)
            assertEquals(result.versionCode, manifest.versionCode)
            assertEquals(result.label, manifest.applicationLabelString)

            val table = module.tableBlock!!
            val pkg = table.packages.next()
            val drawables = HashSet<String>()
            val iterator = pkg.getResources("drawable")
            while (iterator.hasNext()) {
                val resource = iterator.next()
                resource.name?.let { drawables.add(it) }
            }
            assertTrue("ap_gen_0" in drawables, "ap_gen_0 missing, got $drawables")
            assertTrue("ap_gen_1" in drawables)

            val appfilter = module.getInputSource("assets/appfilter.xml")!!
                .openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            assertTrue(appfilter.contains("ap_gen_0"))
            assertTrue(appfilter.contains("com.example.newapp"))
        }
        output.delete()
    }

    /**
     * 回归：注入必须落在原包的 density 目录，否则启动器缩放后新旧图标尺寸不一致。
     */
    @Test
    fun `injected icon lands in original density directory`() {
        val convention = DrawableDirectoryDetector.detect(fixture)
        val output = File.createTempFile("packed-density-", ".apk")
        IconPackPacker().pack(
            fixture,
            output,
            listOf(
                IconInjection(
                    component = "ComponentInfo{com.example.density/com.example.density.Main}",
                    drawableName = "ap_gen_9",
                    pngBytes = tinyPng(9),
                ),
            ),
        )
        java.util.zip.ZipFile(output).use { zip ->
            val expected = "${convention.directory}/ap_gen_9.png"
            assertTrue(
                zip.getEntry(expected) != null,
                "缺少 $expected；实际条目=" + zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.contains("ap_gen_9") }
                    .toList(),
            )
        }
        output.delete()
    }

    @Test
    fun `injected appfilter keeps original items`() {
        val output = File.createTempFile("packed-", ".apk")
        IconPackPacker().pack(
            fixture,
            output,
            listOf(
                IconInjection(
                    "ComponentInfo{com.example.newapp/com.example.newapp.Main}",
                    "ap_gen_0",
                    tinyPng(3),
                ),
            ),
        )
        ApkModule.loadApkFile(output).use { module ->
            val text = module.getInputSource("assets/appfilter.xml")!!
                .openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            // fixture 原有的 5 条条目应保留
            assertTrue(text.contains("com.tencent.mm"), "original items lost")
            assertTrue(text.contains("bin.mt.plus"))
            assertTrue(text.contains("ap_gen_0"))
        }
        output.delete()
    }

    @Test
    fun `duplicate component not injected twice`() {
        val output = File.createTempFile("packed-", ".apk")
        val appfilterText = fixture.let { file ->
            ApkModule.loadApkFile(file).use { module ->
                module.getInputSource("assets/appfilter.xml")!!
                    .openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            }
        }
        // 找一个已存在的 component
        val doc = AppFilterParser.parse(appfilterText.byteInputStream(StandardCharsets.UTF_8))
        val existing = doc.items.first().rawComponent

        IconPackPacker().pack(
            fixture,
            output,
            listOf(IconInjection(existing, "ap_gen_0", tinyPng(4))),
            originalAppFilter = appfilterText,
        )
        ApkModule.loadApkFile(output).use { module ->
            val text = module.getInputSource("assets/appfilter.xml")!!
                .openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val occurrences = Regex(Regex.escape(existing.replace("&", "&amp;"))).findAll(text).count()
            assertEquals(1, occurrences, "duplicate component injected")
        }
        output.delete()
    }

    @Test
    fun `main activity class follows new package`() {
        val output = File.createTempFile("packed-", ".apk")
        val result = IconPackPacker().pack(fixture, output, emptyList())
        ApkModule.loadApkFile(output).use { module ->
            val manifest = module.androidManifest!!
            val mainActivity = manifest.mainActivity
            if (mainActivity != null) {
                val name = mainActivity.searchAttributeByResourceId(com.reandroid.arsc.chunk.xml.AndroidManifestBlock.ID_name)
                    ?.valueAsString
                assertTrue(name?.startsWith(result.packageName) == true, "activity=$name")
            }
        }
        output.delete()
    }

    @Test
    fun `drawable collision preserves original and remaps injection`() {
        val first = File.createTempFile("packed-first-", ".apk")
        val output = File.createTempFile("packed-collision-", ".apk")
        try {
            IconPackPacker().pack(
                fixture,
                first,
                listOf(
                    IconInjection(
                        "ComponentInfo{com.example.first/com.example.first.Main}",
                        "ap_gen_0",
                        tinyPng(4),
                    ),
                ),
            )
            val original = ApkFileIconPack.open(first).use { pack ->
                pack.readDrawableBytes("ap_gen_0") ?: error("首次注入资源缺失")
            }

            IconPackPacker().pack(
                first,
                output,
                listOf(
                    IconInjection(
                        "ComponentInfo{com.example.second/com.example.second.Main}",
                        "ap_gen_0",
                        tinyPng(9),
                    ),
                ),
            )

            ApkFileIconPack.open(output).use { pack ->
                assertTrue(original.contentEquals(pack.readDrawableBytes("ap_gen_0")), "原 ap_gen_0 被覆盖")
                assertTrue("ap_gen_0_2" in pack.drawables, "冲突注入未改名：${pack.drawables.keys}")
            }
            ApkModule.loadApkFile(output).use { module ->
                val text = module.getInputSource("assets/appfilter.xml")!!
                    .openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
                assertTrue(text.contains("com.example.second"))
                assertTrue(text.contains("drawable=\"ap_gen_0_2\""), "appfilter 未使用改名资源：$text")
            }
        } finally {
            first.delete()
            output.delete()
        }
    }
}
