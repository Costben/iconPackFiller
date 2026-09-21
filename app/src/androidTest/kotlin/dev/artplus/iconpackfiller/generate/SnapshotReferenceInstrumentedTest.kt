package dev.artplus.iconpackfiller.generate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.artplus.iconpackfiller.coverage.AppScanner
import dev.artplus.iconpackfiller.coverage.CoverageCalculator
import dev.artplus.iconpackfiller.coverage.CoverageRules
import dev.artplus.iconpackfiller.provider.ImageProvider
import dev.artplus.iconpackfiller.provider.ImageRequest
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotReferenceInstrumentedTest {

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun snapshotApk(): File {
        val ctx = context()
        val target = File(ctx.cacheDir, "snapshot-test-source.apk")
        if (!target.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("test-iconpack-compiled.apk")
                .use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private val targets = setOf(
        "app.lawnchair/app.lawnchair.LawnchairLauncher",
        "app.lawnchair.lawnicons.play/app.lawnchair.lawnicons.MainActivity",
    )

    private fun runOrchestrator(
        ctx: android.content.Context,
        input: FillerOrchestrator.Input,
        diags: MutableList<String>,
    ): FillerOrchestrator.Session {
        val workDir = File(ctx.cacheDir, "snapshot-test-work").apply { deleteRecursively(); mkdirs() }
        val provider = object : ImageProvider {
            override val name = "fake"
            override suspend fun generate(request: ImageRequest): android.graphics.Bitmap {
                request.images.forEach { img ->
                    java.io.ByteArrayOutputStream().use { out ->
                        img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                return android.graphics.Bitmap.createBitmap(1024, 1024, android.graphics.Bitmap.Config.ARGB_8888)
            }
        }
        val orchestrator = FillerOrchestrator(
            context = ctx,
            workDir = workDir,
            provider = provider,
            onDiagnostic = { diags.add(it) },
        )
        return kotlinx.coroutines.runBlocking { orchestrator.run(input = input) }
    }

    @Test
    fun installedSourceAndApkFileSourceBehaveTheSame() {
        val ctx = context()
        org.junit.Assume.assumeTrue(
            "需要 fixture 包 dev.artplus.testiconpack 已安装",
            isPackInstalled(ctx, "dev.artplus.testiconpack"),
        )
        val installedDiags = mutableListOf<String>()
        val installed = runOrchestrator(
            ctx,
            FillerOrchestrator.Input(
                installedPackage = "dev.artplus.testiconpack",
                rules = CoverageRules.DEFAULT.copy(excludeSystemApps = true),
                selectedTargets = targets,
                maxGenerationAttempts = 1,
            ),
            installedDiags,
        )
        val apkDiags = mutableListOf<String>()
        val fromApk = runOrchestrator(
            ctx,
            FillerOrchestrator.Input(
                apkFile = snapshotApk(),
                rules = CoverageRules.DEFAULT.copy(excludeSystemApps = true),
                selectedTargets = targets,
                maxGenerationAttempts = 1,
            ),
            apkDiags,
        )
        android.util.Log.i(
            "SnapshotTest",
            "installed attempts=${installed.attempts.size} generated=${installed.generated.size} diags=$installedDiags",
        )
        android.util.Log.i(
            "SnapshotTest",
            "apkFile attempts=${fromApk.attempts.size} generated=${fromApk.generated.size} diags=$apkDiags",
        )
        assertTrue("installed 无 Attempt", installed.attempts.isNotEmpty())
        assertTrue("apkFile 无 Attempt", fromApk.attempts.isNotEmpty())
    }

    /** 快照来源 + 能通过校验的 provider：端到端应产出 accepted>0 并落 Attempt。 */
    @Test
    fun apkFileSnapshotProducesAcceptedIcon() {
        val ctx = context()
        val workDir = File(ctx.cacheDir, "snapshot-accept-work").apply { deleteRecursively(); mkdirs() }
        val diags = mutableListOf<String>()
        // 用目标原图本身当「模型输出」，保证通过 OutputValidator（同一张图，距离≈0）。
        val target = AppIconLoader.load(ctx, "app.lawnchair", "app.lawnchair.LawnchairLauncher")!!
        val provider = object : ImageProvider {
            override val name = "fake-target"
            override suspend fun generate(request: ImageRequest) =
                android.graphics.Bitmap.createScaledBitmap(target, 1024, 1024, true)
        }
        val orchestrator = FillerOrchestrator(
            context = ctx,
            workDir = workDir,
            provider = provider,
            onDiagnostic = { diags.add(it) },
        )
        val session = kotlinx.coroutines.runBlocking {
            orchestrator.run(
                input = FillerOrchestrator.Input(
                    apkFile = snapshotApk(),
                    rules = CoverageRules.DEFAULT.copy(excludeSystemApps = true),
                    selectedTargets = setOf("app.lawnchair/app.lawnchair.LawnchairLauncher"),
                    maxGenerationAttempts = 1,
                ),
            )
        }
        android.util.Log.i(
            "SnapshotTest",
            "accepted generated=${session.generated.size} attempts=${session.attempts.size} " +
                "reasons=${session.attempts.map { it.reason }} diags=$diags",
        )
        assertTrue("未产出 accepted>0，诊断=$diags", session.generated.isNotEmpty())
        assertTrue("未落 Attempt", session.attempts.any { it.accepted })
        target.recycle()
    }

    /** 复刻 ViewModel 在生成前对同一快照 APK 的多次 open/close，再跑编排器。 */
    @Test
    fun repeatedOpenCloseBeforeRunDoesNotBreakSnapshotSource() {
        val ctx = context()
        // analyze() / Review 采样 / openSourceMeta 都会 open + use(close) 同一文件
        repeat(4) {
            IconPackSource.open(ctx, snapshotApk())!!.use { pack ->
                pack.document.items.size
                pack.availableDrawables.size
                pack.sourceApk
            }
        }
        val diags = mutableListOf<String>()
        val session = runOrchestrator(
            ctx,
            FillerOrchestrator.Input(
                apkFile = snapshotApk(),
                rules = CoverageRules.DEFAULT.copy(excludeSystemApps = true),
                selectedTargets = targets,
                maxGenerationAttempts = 1,
            ),
            diags,
        )
        android.util.Log.i(
            "SnapshotTest",
            "repeated-open plans=${session.plans.size} attempts=${session.attempts.size} diags=$diags",
        )
        assertTrue("无 Attempt，诊断=$diags", session.attempts.isNotEmpty())
    }

    private fun isPackInstalled(ctx: android.content.Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    @Test
    fun apkFileSourceWithDbAttemptPersistenceKeepsAttempts() {
        val ctx = context()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, dev.artplus.iconpackfiller.project.db.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val paths = dev.artplus.iconpackfiller.project.ProjectPaths(File(ctx.cacheDir, "snapshot-db-projects"))
            val repo = dev.artplus.iconpackfiller.project.ProjectRepository(db.projectDao(), paths)
            val project = kotlinx.coroutines.runBlocking {
                repo.ensureProject(
                    sourceKind = dev.artplus.iconpackfiller.project.SourceKind.APK_FILE,
                    packLabel = "测试",
                    packPackage = "dev.artplus.testiconpack",
                    packVersionCode = 0,
                    packHash = "hash",
                    sourceApk = snapshotApk(),
                )
            }
            val generation = kotlinx.coroutines.runBlocking { repo.createGeneration(project.id) }

            val workDir = File(ctx.cacheDir, "snapshot-db-work").apply { deleteRecursively(); mkdirs() }
            val diags = mutableListOf<String>()
            val provider = object : ImageProvider {
                override val name = "fake"
                override suspend fun generate(request: ImageRequest) =
                    android.graphics.Bitmap.createBitmap(1024, 1024, android.graphics.Bitmap.Config.ARGB_8888)
            }
            val orchestrator = FillerOrchestrator(
                context = ctx,
                workDir = workDir,
                provider = provider,
                onDiagnostic = { diags.add(it) },
            )
            val session = kotlinx.coroutines.runBlocking {
                orchestrator.run(
                    input = FillerOrchestrator.Input(
                        apkFile = snapshotApk(),
                        rules = CoverageRules.DEFAULT.copy(excludeSystemApps = true),
                        selectedTargets = targets,
                        maxGenerationAttempts = 1,
                    ),
                    onAttempt = { attempt ->
                        kotlinx.coroutines.runBlocking {
                            repo.persistAttempt(project.id, generation.id, attempt)
                        }
                    },
                )
            }
            val persisted = kotlinx.coroutines.runBlocking { repo.attempts(generation.id) }
            android.util.Log.i(
                "SnapshotTest",
                "db-run sessionAttempts=${session.attempts.size} persisted=${persisted.size} diags=$diags",
            )
            assertTrue("session 无 Attempt，诊断=$diags", session.attempts.isNotEmpty())
            assertTrue("DB 无 Attempt", persisted.isNotEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun apkFileSourceBuildsReferencePoolAndLoadsBitmaps() {
        val ctx = context()
        val diags = mutableListOf<String>()
        val pack = IconPackSource.open(ctx, snapshotApk())!!
        pack.use { pack ->
            val apps = AppScanner(ctx).scan()
            val report = CoverageCalculator.compute(
                apps = apps,
                appFilter = pack.document,
                availableDrawables = pack.availableDrawables,
                rules = CoverageRules.DEFAULT,
            )
            val pool = ReferencePoolBuilder.build(ctx, pack, report) { diags.add(it) }
            assertTrue("参考池为空: $diags", pool.isNotEmpty())
            for (pair in pool) {
                val loaded = ReferencePoolBuilder.loadReferenceBitmaps(ctx, pack, pair) { diags.add(it) }
                assertTrue("参考图加载失败 ${pair.packageName}: $diags", loaded != null)
            }
        }
    }

    @Test
    fun apkFileSourceOrchestratorProducesAttempts() {
        val ctx = context()
        val workDir = File(ctx.cacheDir, "snapshot-test-work").apply { deleteRecursively(); mkdirs() }
        val diags = mutableListOf<String>()
        // 真实 provider 会对 sheet 做 PNG 编码；fake 也照做以覆盖该路径。
        val provider = object : ImageProvider {
            override val name = "fake"
            override suspend fun generate(request: ImageRequest): android.graphics.Bitmap {
                request.images.forEach { img ->
                    java.io.ByteArrayOutputStream().use { out ->
                        img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                return android.graphics.Bitmap.createBitmap(1024, 1024, android.graphics.Bitmap.Config.ARGB_8888)
            }
        }
        val targets = setOf(
            "app.lawnchair/app.lawnchair.LawnchairLauncher",
            "app.lawnchair.lawnicons.play/app.lawnchair.lawnicons.MainActivity",
        )
        // 复刻确认范围页的固定参考（referenceOverride）。
        val referenceOverride = IconPackSource.open(ctx, snapshotApk())!!.use { pack ->
            val report = CoverageCalculator.compute(
                apps = AppScanner(ctx).scan(),
                appFilter = pack.document,
                availableDrawables = pack.availableDrawables,
                rules = CoverageRules.DEFAULT.copy(excludeSystemApps = true),
            )
            ReferencePoolBuilder.build(ctx, pack, report).shuffled().distinctBy { it.packageName }.take(2)
        }
        val orchestrator = FillerOrchestrator(
            context = ctx,
            workDir = workDir,
            provider = provider,
            onDiagnostic = { diags.add(it) },
        )
        val session = kotlinx.coroutines.runBlocking {
            orchestrator.run(
                input = FillerOrchestrator.Input(
                    apkFile = snapshotApk(),
                    rules = CoverageRules.DEFAULT.copy(excludeSystemApps = true),
                    selectedTargets = targets,
                    referenceOverride = referenceOverride,
                    maxGenerationAttempts = 1,
                ),
            )
        }
        android.util.Log.i(
            "SnapshotTest",
            "plans=${session.plans.size} attempts=${session.attempts.size} " +
                "generated=${session.generated.size} diags=$diags",
        )
        assertTrue("无 Attempt，诊断=$diags", session.attempts.isNotEmpty())
    }
}
