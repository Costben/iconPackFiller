package dev.artplus.iconpackfiller.pack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkDrawableRendererInstrumentedTest {

    @Test
    fun parsesCompiledAppfilterResource() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val id = context.resources.getIdentifier("test_compiled_appfilter", "xml", context.packageName)
        assertTrue(id != 0)
        val parser = context.resources.getXml(id)
        try {
            val document = AppFilterParser.parse(parser)
            assertTrue(document.items.any { it.component.packageName == "com.example.compiled" })
        } finally {
            parser.close()
        }
    }

    @Test
    fun rendersVectorDrawableFromApkArchive() {
        val context = InstrumentationRegistry.getInstrumentation().context
        ApkDrawableRenderer(context, File(context.applicationInfo.sourceDir), context.packageName).use { renderer ->
            val bitmap = renderer.renderDrawable("test_external_vector") ?: error("vector 未渲染")
            assertNotNull(bitmap)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
    }

    @Test
    fun rendersAdaptiveDrawableFromApkArchive() {
        val context = InstrumentationRegistry.getInstrumentation().context
        ApkDrawableRenderer(context, File(context.applicationInfo.sourceDir), context.packageName).use { renderer ->
            val bitmap = renderer.renderDrawable("test_external_adaptive") ?: error("adaptive 未渲染")
            assertNotNull(bitmap)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
    }
}
