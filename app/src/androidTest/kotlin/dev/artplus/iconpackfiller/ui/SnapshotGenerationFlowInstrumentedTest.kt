package dev.artplus.iconpackfiller.ui

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.artplus.iconpackfiller.project.db.AppDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「从项目快照生成」全链路（ViewModel → 快照来源 → 编排 → Room 落库）真机验证。
 *
 * 用本机 mock 网关（`adb reverse tcp:8123`）替代真实 provider：
 * 服务端把请求 contact sheet 的目标格裁出来当结果返回，因此必然通过本地校验。
 * 验证 D-SNAP-1 的核心症状——快照来源必须产生 Attempt 行与 accepted 图标。
 */
@RunWith(AndroidJUnit4::class)
class SnapshotGenerationFlowInstrumentedTest {

    private val ctx: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun snapshotApk(): File {
        val target = File(ctx.cacheDir, "snapshot-flow-source.apk")
        if (!target.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("test-iconpack-compiled.apk")
                .use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private fun waitUntil(timeoutMs: Long = 180_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        fail("等待条件超时（${timeoutMs}ms）")
    }

    @Test
    fun snapshotProjectGeneration_persistsAttemptsAndAcceptedIcons() {
        val store = dev.artplus.iconpackfiller.settings.SettingsStore(ctx)
        val originalSlot = store.activeSlot
        val originalKey = store.loadActiveApiKey()
        try {
            store.updateActiveSlot {
                it.copy(
                    baseUrl = "http://127.0.0.1:8123",
                    model = "mock-image",
                    kind = "OPENAI",
                    mode = "images",
                )
            }
            store.saveActiveApiKey("mock-key")
            store.referencePairCount = 2

            val vm = MainViewModel(ctx)
            val apk = snapshotApk()
            vm.selectApk(Uri.fromFile(apk), "测试图标包")
            waitUntil { vm.state.value.selectedProjectId != null }
            val projectId = vm.state.value.selectedProjectId!!

            vm.startGenerationFromProject(projectId)
            waitUntil { vm.state.value.report != null }
            val targets = vm.state.value.targets
            assertTrue("快照来源扫描不到待生成目标", targets.isNotEmpty())
            val target = targets.first()

            vm.setAllTargetsSelected(false)
            vm.toggleTarget(target)
            vm.run()
            waitUntil { vm.state.value.phase == Phase.IDLE && vm.state.value.hasResult }

            val dao = AppDatabase.build(ctx).projectDao()
            val generation = runBlocking {
                dao.allGenerations().filter { it.projectId == projectId }.maxByOrNull { it.createdAt }
            } ?: error("没有生成记录")
            val attempts = runBlocking { dao.attemptsByGeneration(generation.id) }
            val icons = runBlocking { dao.iconsByGeneration(generation.id, null, null, null) }

            assertTrue("快照来源必须产生 Attempt（实际 ${attempts.size}）", attempts.isNotEmpty())
            assertTrue("快照来源必须产出 accepted 图标", icons.any { it.accepted })
            assertTrue("generatedCount 必须 > 0", generation.generatedCount > 0)
        } finally {
            store.updateActiveSlot { it.copy(baseUrl = originalSlot.baseUrl, model = originalSlot.model) }
            store.saveActiveApiKey(originalKey)
        }
    }
}
