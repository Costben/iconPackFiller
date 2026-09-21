package dev.artplus.iconpackfiller.generate

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.artplus.iconpackfiller.coverage.LaunchableApp
import dev.artplus.iconpackfiller.pack.IconStats
import dev.artplus.iconpackfiller.provider.ImageProvider
import dev.artplus.iconpackfiller.provider.ImageRequest
import dev.artplus.iconpackfiller.reference.ReferencePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 参考图加载失败必须留痕：不能静默吞异常（D-SNAP-1）。
 */
@RunWith(AndroidJUnit4::class)
class IconGenerationPipelineDiagnosticsInstrumentedTest {

    private fun stats() = IconStats.fromPixels(IntArray(4) { 0xFF3366CC.toInt() }, 2, 2)

    private fun pair(pkg: String) = ReferencePair(
        packageName = pkg,
        label = pkg,
        category = null,
        originalStats = stats(),
        packStats = stats(),
        packDrawableName = "ic_$pkg",
        activityName = "$pkg.Main",
    )

    private fun plan(reference: ReferencePair) = GenerationPlan(
        target = LaunchableApp(
            packageName = "app.target",
            activityName = "app.target.Main",
            label = "Target",
            isSystemApp = false,
        ),
        references = listOf(reference),
    )

    @Test
    fun loadReferenceExceptionIsReportedInDiagnostics() {
        val target = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF112233.toInt()) }
        val reference = pair("com.ref")
        val diagnostics = mutableListOf<String>()
        val provider = object : ImageProvider {
            override val name = "never-called"
            override suspend fun generate(request: ImageRequest): Bitmap = error("provider 不应被调用")
        }
        val pipeline = IconGenerationPipeline(
            provider = provider,
            maxAttempts = 1,
            budget = GenerationBudget(null),
            onDiagnostic = { diagnostics.add(it) },
        )

        val error = runCatching {
            kotlinx.coroutines.runBlocking {
                pipeline.generate(
                    targetIcon = target,
                    targetPair = reference,
                    initialPlan = plan(reference),
                    referencePool = listOf(reference),
                    loadReference = { throw IllegalStateException("boom-recycled-bitmap") },
                )
            }
        }.exceptionOrNull()

        assertTrue("应抛 GenerationException，实际 $error", error is GenerationException)
        assertTrue(
            "错误信息应说明参考图不可加载：${error!!.message}",
            error.message!!.contains("没有可加载的参考图"),
        )
        assertTrue(
            "诊断应包含底层异常信息，实际=$diagnostics",
            diagnostics.any { it.contains("boom-recycled-bitmap") },
        )
        target.recycle()
    }

    /** 多色图案，避免被 OutputValidator 判成纯色/空白。 */
    private fun pattern(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF112233.toInt())
        val canvas = android.graphics.Canvas(bmp)
        val circle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFAA00.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 4f, circle)
        val bar = android.graphics.Paint().apply { color = 0xFF33CC66.toInt() }
        canvas.drawRect(size * 0.1f, size * 0.6f, size * 0.9f, size * 0.8f, bar)
        return bmp
    }

    @Test
    fun acceptedAttemptIsProducedWhenReferenceLoads() {
        val target = pattern(64)
        val reference = pair("com.ref")
        val provider = object : ImageProvider {
            override val name = "fake"
            override suspend fun generate(request: ImageRequest) = pattern(1024)
        }
        val attempts = mutableListOf<GenerationAttempt>()
        val pipeline = IconGenerationPipeline(
            provider = provider,
            maxAttempts = 1,
            budget = GenerationBudget(null),
            onAttempt = { attempts.add(it) },
        )

        val icon = kotlinx.coroutines.runBlocking {
            pipeline.generate(
                targetIcon = target,
                targetPair = reference,
                initialPlan = plan(reference),
                referencePool = listOf(reference),
                loadReference = { pattern(64) to pattern(1024) },
            )
        }

        assertEquals(1, attempts.size)
        assertTrue("校验原因：${attempts.single().reason}", attempts.single().accepted)
        assertEquals("app.target", icon.packageName)
        target.recycle()
    }
}
