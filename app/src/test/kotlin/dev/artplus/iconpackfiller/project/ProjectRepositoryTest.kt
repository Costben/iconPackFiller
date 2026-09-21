package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.generate.GenerationAttempt
import dev.artplus.iconpackfiller.generate.GenerationProvenance
import dev.artplus.iconpackfiller.generate.IconPackSource
import dev.artplus.iconpackfiller.generate.ReferenceSnapshot
import dev.artplus.iconpackfiller.project.db.AttemptEntity
import dev.artplus.iconpackfiller.project.db.GenerationEntity
import dev.artplus.iconpackfiller.project.db.GenerationIconEntity
import dev.artplus.iconpackfiller.project.db.GenerationIconMatch
import dev.artplus.iconpackfiller.project.db.PackEntryEntity
import dev.artplus.iconpackfiller.project.db.ProjectDao
import dev.artplus.iconpackfiller.project.db.ProjectEntity
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ProjectRepository] 的 JVM 单测：用假 DAO + 临时目录验证映射与磁盘布局。
 *
 * 真 Room 端到端在 androidTest/ProjectDaoTest（真机 Phase 7）。
 */
class ProjectRepositoryTest {

    private lateinit var root: File
    private lateinit var dao: FakeProjectDao
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        root = Files.createTempDirectory("projects").toFile()
        dao = FakeProjectDao()
        repository = ProjectRepository(dao, ProjectPaths(root), clock = { 1_000L })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `ensureProject copies the source snapshot and records metadata`() = runBlocking {
        val source = File(root.parentFile, "src.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val project = repository.ensureProject(
                sourceKind = SourceKind.APK_FILE,
                packLabel = "Aura",
                packPackage = "studio14.application.auraicons",
                packVersionCode = 7,
                packHash = "abc",
                sourceApk = source,
            )
            assertEquals("Aura", project.packLabel)
            assertEquals("abc", project.packHash)
            assertEquals(listOf(project.id), dao.allProjects().map { it.id })
            val snapshot = ProjectPaths(root).sourceApk(project.id)
            assertTrue(snapshot.exists())
            assertEquals(listOf<Byte>(1, 2, 3), snapshot.readBytes().toList())
        } finally {
            source.delete()
        }
    }

    @Test
    fun `ensureProject reuses the same pack and hash`() = runBlocking {
        val source = File(root.parentFile, "src.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            val first = repository.ensureProject(
                SourceKind.APK_FILE, "Aura", "com.pack", 1, "hash", source,
            )
            val second = repository.ensureProject(
                SourceKind.APK_FILE, "Aura", "com.pack", 1, "hash", source,
            )
            assertEquals(first.id, second.id)
            assertEquals(1, dao.projects.size)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `different hash creates a new project`() = runBlocking {
        val source = File(root.parentFile, "src.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            val first = repository.ensureProject(SourceKind.APK_FILE, "Aura", "com.pack", 1, "hash-a", source)
            val second = repository.ensureProject(SourceKind.APK_FILE, "Aura", "com.pack", 1, "hash-b", source)
            assertNotEquals(first.id, second.id)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `sourceApk returns the copied snapshot of the project`() = runBlocking {
        val source = File(root.parentFile, "snap-src.apk").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        try {
            val project = repository.importProject(
                sourceKind = SourceKind.APK_FILE,
                packLabel = "Aura",
                packPackage = "com.pack",
                packVersionCode = 1,
                packHash = "hash-snap",
                sourceApk = source,
                entries = emptyList(),
            )
            val snapshot = assertNotNull(repository.sourceApk(project.id))
            assertEquals(listOf<Byte>(7, 8, 9), snapshot.readBytes().toList())
        } finally {
            source.delete()
        }
    }

    @Test
    fun `sourceApk is null when the project has no snapshot`() = runBlocking {
        val project = repository.ensureProject(
            SourceKind.INSTALLED, "Aura", "com.pack", 1, "hash-no-snap", sourceApk = null,
        )
        assertNull(repository.sourceApk(project.id))
    }

    @Test
    fun `resolveProjectForRun reuses the snapshot project without creating a duplicate`() = runBlocking {
        val project = newProject()
        // 快照来源下元数据可能已变（例如源包已卸载、元数据取自快照本身）；
        // 复用以 projectId 为准，绝不因来源不同而新建项目。
        val reused = assertNotNull(
            repository.resolveProjectForRun(
                reuseProjectId = project.id,
                sourceKind = SourceKind.APK_FILE,
                packLabel = "重命名",
                packPackage = "com.changed.pack",
                packVersionCode = 99,
                packHash = "different-hash",
                sourceApk = File(root.parentFile, "missing.apk"),
            ),
        )
        assertEquals(project.id, reused.id)
        assertEquals(project.packLabel, reused.packLabel)
        assertEquals(project.packPackage, reused.packPackage)
        assertEquals(1, dao.projects.size)
    }

    @Test
    fun `resolveProjectForRun returns null for an unknown snapshot project`() = runBlocking {
        assertNull(
            repository.resolveProjectForRun(
                reuseProjectId = "proj-does-not-exist",
                sourceKind = SourceKind.APK_FILE,
                packLabel = "Aura",
                packPackage = "com.pack",
                packVersionCode = 1,
                packHash = "hash",
                sourceApk = null,
            ),
        )
        assertEquals(0, dao.projects.size)
    }

    @Test
    fun `resolveProjectForRun falls back to ensureProject for live sources`() = runBlocking {
        val source = File(root.parentFile, "live-src.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            val created = assertNotNull(
                repository.resolveProjectForRun(
                    reuseProjectId = null,
                    sourceKind = SourceKind.APK_FILE,
                    packLabel = "Aura",
                    packPackage = "com.pack",
                    packVersionCode = 1,
                    packHash = "live-hash",
                    sourceApk = source,
                ),
            )
            assertEquals(1, dao.projects.size)
            assertNotNull(repository.sourceApk(created.id))
            assertEquals("live-hash", created.packHash)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `createGeneration creates dirs and a running row`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(
            projectId = project.id,
            model = "gemini-3.1-flash-image",
            slotId = "slot-1",
            slotName = "banana",
            paramsJson = GenerationJsonCodec.encodeParams(mapOf("referencePairCount" to 2)),
        )
        assertEquals(GenerationStatus.RUNNING, generation.status)
        assertTrue(ProjectPaths(root).attemptDir(project.id, generation.id).isDirectory)
        assertTrue(repository.generation(generation.id)?.paramsJson!!.contains("referencePairCount"))
    }

    @Test
    fun `persistAttempt writes png and source and projects the record`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        val attempt = GenerationAttempt(
            packageName = "bin.mt.plus",
            label = "MT管理器",
            attempt = 1,
            accepted = false,
            reason = "校验未通过",
            pngBytes = byteArrayOf(1, 2, 3),
            references = listOf("com.a"),
            sourcePngBytes = byteArrayOf(4, 5),
            provenance = GenerationProvenance("gpt-image-2", "slot-1", "gpt image 2"),
            prompt = "edit",
            referenceDetails = listOf(ReferenceSnapshot("com.a", label = "A")),
        )
        val entity = repository.persistAttempt(project.id, generation.id, attempt)

        assertEquals("bin.mt.plus-1.png", entity.pngFile)
        assertEquals("bin.mt.plus-src.png", entity.sourceFile)
        assertEquals("MT管理器", entity.label)
        assertNotNull(repository.attemptFile(project.id, generation.id, entity.pngFile))
        assertEquals(
            listOf<Byte>(1, 2, 3),
            repository.attemptFile(project.id, generation.id, entity.pngFile)!!.readBytes().toList(),
        )

        val record = repository.generationRecord(generation.id)!!
        val projected = record.attempts.single()
        assertEquals("MT管理器", projected.label)
        assertEquals(listOf("com.a"), projected.references)
        assertEquals("gpt-image-2", projected.model)
        assertEquals("com.a", projected.referenceDetails.single().packageName)
    }

    @Test
    fun `persistGenerationIcons stores one row per spec and replaces on re-persist`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)

        repository.persistGenerationIcons(
            generation.id,
            listOf(
                GenerationIconSpec("com.a", "com.a.Main", "A", "ap_gen_0", accepted = true),
                GenerationIconSpec("com.b", "com.b.Main", "B", null, accepted = false, reason = "校验未通过"),
            ),
        )

        val icons = repository.icons(generation.id)
        assertEquals(listOf("com.a", "com.b"), icons.map { it.packageName }.sorted())
        val accepted = icons.single { it.packageName == "com.a" }
        assertTrue(accepted.accepted)
        assertEquals("ap_gen_0", accepted.drawableName)
        assertEquals("com.a.Main", accepted.activityName)
        val rejected = icons.single { it.packageName == "com.b" }
        assertFalse(rejected.accepted)
        assertEquals("校验未通过", rejected.reason)

        // 重新落库是替换语义，不会翻倍
        repository.persistGenerationIcons(
            generation.id,
            listOf(GenerationIconSpec("com.a", "com.a.Main", "A", "ap_gen_0", accepted = true)),
        )
        assertEquals(listOf("com.a"), repository.icons(generation.id).map { it.packageName })
    }

    @Test
    fun `generation is queryable with all its icons and attempts`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        repository.persistAttempt(
            project.id,
            generation.id,
            GenerationAttempt("com.a", "A", 1, true, null, byteArrayOf(1), emptyList()),
        )
        repository.persistGenerationIcons(
            generation.id,
            listOf(GenerationIconSpec("com.a", "com.a.Main", "A", "ap_gen_0", accepted = true)),
        )

        assertEquals(generation.id, repository.generation(generation.id)?.id)
        assertEquals(listOf("com.a"), repository.icons(generation.id).map { it.packageName })
        assertEquals(listOf("com.a"), repository.attempts(generation.id).map { it.packageName })
    }

    @Test
    fun `refreshIconOutcome flips the icon once a retry is accepted`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        repository.persistGenerationIcons(
            generation.id,
            listOf(GenerationIconSpec("com.a", "com.a.Main", "A", null, accepted = false, reason = "校验未通过")),
        )
        repository.persistAttempt(
            project.id,
            generation.id,
            GenerationAttempt("com.a", "A", 1, false, "校验未通过", byteArrayOf(1), emptyList()),
        )
        repository.persistAttempt(
            project.id,
            generation.id,
            GenerationAttempt("com.a", "A", 2, true, null, byteArrayOf(2), emptyList()),
        )

        repository.refreshIconOutcome(generation.id, "com.a")

        val icon = repository.icons(generation.id).single()
        assertTrue(icon.accepted)
        assertNull(icon.reason)
    }

    // ---- Phase 5：筛选查询 + 跨生成对比 ----

    @Test
    fun `icons filter by package name`() = runBlocking {
        val generation = seededGeneration()

        val filtered = repository.icons(generation.id, packageName = "com.b")

        assertEquals(listOf("com.b"), filtered.map { it.packageName })
        assertEquals(listOf("com.b.Main"), filtered.map { it.activityName })
    }

    @Test
    fun `icons filter by component`() = runBlocking {
        val generation = seededGeneration()

        val filtered = repository.icons(generation.id, activityName = "com.a.Settings")

        assertEquals(listOf("com.a.Settings"), filtered.map { it.activityName })
        assertFalse(filtered.single().accepted)
    }

    @Test
    fun `icons filter by accepted status`() = runBlocking {
        val generation = seededGeneration()

        val accepted = repository.icons(generation.id, accepted = true)
        val rejected = repository.icons(generation.id, accepted = false)

        assertEquals(listOf("com.a.Main", "com.b.Main"), accepted.map { it.activityName })
        assertEquals(listOf("com.a.Settings"), rejected.map { it.activityName })
        assertEquals("校验未通过", rejected.single().reason)
    }

    @Test
    fun `icons combine filters and return everything when unfiltered`() = runBlocking {
        val generation = seededGeneration()

        assertEquals(3, repository.icons(generation.id).size)
        assertEquals(
            listOf("com.a.Main"),
            repository.icons(generation.id, packageName = "com.a", accepted = true).map { it.activityName },
        )
        assertTrue(repository.icons(generation.id, packageName = "com.a", activityName = "com.a.Settings", accepted = true).isEmpty())
        assertTrue(repository.icons(generation.id, packageName = "com.absent").isEmpty())
    }

    @Test
    fun `observeIcons emits the rows matching the filters`() = runBlocking {
        val generation = seededGeneration()

        assertEquals(listOf("com.b"), repository.observeIcons(generation.id, packageName = "com.b").first().map { it.packageName })
        assertEquals(listOf("com.a.Settings"), repository.observeIcons(generation.id, accepted = false).first().map { it.activityName })
        assertEquals(3, repository.observeIcons(generation.id).first().size)
    }

    @Test
    fun `observeCompareTarget returns the target across generations newest first`() = runBlocking {
        val project = newProject()
        val older = repository.createGeneration(project.id, now = 100L)
        val newer = repository.createGeneration(project.id, now = 200L, model = "gpt-image-2")
        repository.persistGenerationIcons(
            older.id,
            listOf(icon("com.a", "com.a.Main", "ap_gen_0", accepted = true)),
        )
        repository.persistGenerationIcons(
            newer.id,
            listOf(icon("com.a", "com.a.Main", "ap_gen_1", accepted = true)),
        )

        val series = repository.observeCompareTarget(project.id, "com.a", "com.a.Main").first()

        assertEquals(listOf(newer.id, older.id), series.map { it.icon.generationId })
        assertEquals(listOf("ap_gen_1", "ap_gen_0"), series.map { it.icon.drawableName })
        assertEquals(200L, series.first().generationCreatedAt)
        assertEquals(GenerationStatus.RUNNING, series.first().generationStatus)
        assertEquals("gpt-image-2", series.first().generationModel)
    }

    @Test
    fun `observeCompareTarget scopes to project, package and optional component`() = runBlocking {
        val project = newProject()
        val other = newProject(packLabel = "Other", packPackage = "com.other.pack", hash = "other-hash")
        val g1 = repository.createGeneration(project.id, now = 100L)
        val g2 = repository.createGeneration(other.id, now = 200L)
        repository.persistGenerationIcons(
            g1.id,
            listOf(
                icon("com.a", "com.a.Main", "ap_gen_0", accepted = true),
                icon("com.a", "com.a.Settings", "ap_gen_1", accepted = true),
                icon("com.b", "com.b.Main", "ap_gen_2", accepted = true),
            ),
        )
        repository.persistGenerationIcons(
            g2.id,
            listOf(icon("com.a", "com.a.Main", "ap_gen_0", accepted = true)),
        )

        val componentScoped = repository.observeCompareTarget(project.id, "com.a", "com.a.Main").first()
        assertEquals(listOf(g1.id), componentScoped.map { it.icon.generationId })

        val allActivities = repository.observeCompareTarget(project.id, "com.a", null).first()
        assertEquals(listOf("com.a.Main", "com.a.Settings"), allActivities.map { it.icon.activityName })

        assertEquals(
            listOf("com.b"),
            repository.observeCompareTarget(project.id, "com.b", null).first().map { it.icon.packageName },
        )
        assertEquals(
            listOf(g2.id),
            repository.observeCompareTarget(other.id, "com.a", null).first().map { it.icon.generationId },
        )
    }

    @Test
    fun `deleteGeneration cascades its icons`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        repository.persistGenerationIcons(
            generation.id,
            listOf(GenerationIconSpec("com.a", "com.a.Main", "A", "ap_gen_0", accepted = true)),
        )

        repository.deleteGeneration(generation.id)

        assertTrue(dao.icons.isEmpty())
        assertTrue(repository.icons(generation.id).isEmpty())
    }

    @Test
    fun `unsafe package names are sanitized inside the project dir`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        val attempt = GenerationAttempt(
            packageName = "com.app/Activity:with weird",
            label = null,
            attempt = 1,
            accepted = true,
            reason = null,
            pngBytes = byteArrayOf(9),
            references = emptyList(),
        )
        val entity = repository.persistAttempt(project.id, generation.id, attempt)
        assertFalse(entity.pngFile!!.contains("/"))
        assertTrue(repository.attemptFile(project.id, generation.id, entity.pngFile)!!.toPath().startsWith(root.toPath()))
    }

    @Test
    fun `persistApk copies into the generation dir`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        val apk = File(root.parentFile, "signed-test.apk").apply { writeBytes(byteArrayOf(7)) }
        try {
            val name = repository.persistApk(project.id, generation.id, apk)
            assertEquals("out.apk", name)
            assertTrue(repository.outputApk(project.id, generation.id, name) != null)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `markRunningInterrupted flips stale generations`() = runBlocking {
        val project = newProject()
        val running = repository.createGeneration(project.id, status = GenerationStatus.RUNNING)
        val done = repository.createGeneration(project.id, status = GenerationStatus.COMPLETED)

        val flipped = repository.markRunningInterrupted()

        assertEquals(1, flipped)
        assertEquals(GenerationStatus.INTERRUPTED, repository.generation(running.id)?.status)
        assertEquals(GenerationStatus.COMPLETED, repository.generation(done.id)?.status)
    }

    @Test
    fun `generationRecords are newest first and carry diagnostics`() = runBlocking {
        val project = newProject()
        val older = repository.createGeneration(project.id, now = 100L)
        val newer = repository.createGeneration(project.id, now = 200L).copy(
            diagnosticsJson = GenerationJsonCodec.encodeDiagnostics(listOf("com.x: 失败")),
        )
        repository.updateGeneration(newer)

        val records = repository.generationRecords()
        assertEquals(listOf(newer.id, older.id), records.map { it.id })
        assertEquals(listOf("com.x: 失败"), records.first().diagnostics)
        assertEquals("Aura", records.first().packLabel)
    }

    @Test
    fun `deleteGeneration removes the row and the directory`() = runBlocking {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        repository.persistAttempt(
            project.id,
            generation.id,
            GenerationAttempt("a", null, 1, true, null, byteArrayOf(1), emptyList()),
        )
        assertTrue(ProjectPaths(root).generationDir(project.id, generation.id).exists())

        assertTrue(repository.deleteGeneration(generation.id))

        assertNull(repository.generation(generation.id))
        assertTrue(dao.attempts.isEmpty())
        assertFalse(ProjectPaths(root).generationDir(project.id, generation.id).exists())
    }

    @Test
    fun `importProject persists one pack entry per appfilter item`() = runBlocking {
        val fixture = fixtureFile()
        val entries = fixtureEntries(fixture)

        val project = repository.importProject(
            sourceKind = SourceKind.APK_FILE,
            packLabel = "Fixture",
            packPackage = "com.example.iconpack",
            packVersionCode = 1,
            packHash = "fixture-hash",
            sourceApk = fixture,
            entries = entries,
        )

        assertEquals(5, entries.size)
        assertEquals(entries.size, dao.packEntryCount(project.id))
        assertEquals(entries.size, dao.packEntries.size)
        assertTrue(dao.packEntries.all { it.projectId == project.id })

        val wechat = dao.packEntries.single { it.drawableName == "ic_wechat" }
        assertEquals("res/drawable-xxxhdpi-v4/ic_wechat.png", wechat.resPath)
        assertEquals(PackEntryKind.PNG, wechat.kind)
        assertEquals("xxxhdpi-v4", wechat.density)
        assertTrue(wechat.inPack)
        assertEquals("com.tencent.mm", wechat.packageName)
        assertEquals("com.tencent.mm.ui.launcherui", wechat.activityName)
        assertEquals(PackEntryOrigin.APPFILTER, wechat.origin)
    }

    @Test
    fun `importProject reuses the same project and replaces its entries`() = runBlocking {
        val fixture = fixtureFile()
        val entries = fixtureEntries(fixture)
        val first = repository.importProject(
            SourceKind.APK_FILE, "Fixture", "com.example.iconpack", 1, "fixture-hash", fixture, entries,
        )
        val second = repository.importProject(
            SourceKind.APK_FILE, "Fixture", "com.example.iconpack", 1, "fixture-hash", fixture, entries,
        )

        assertEquals(first.id, second.id)
        assertEquals(entries.size, dao.packEntryCount(first.id))
    }

    @Test
    fun `importProject writes large packs in batched transactions off the caller thread`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pack-import-io") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val batching = ProjectRepository(
                dao = dao,
                paths = ProjectPaths(root),
                clock = { 1_000L },
                ioDispatcher = dispatcher,
                entryBatchSize = 1_000,
            )
            val entries = (1..2_500).map { i ->
                PackEntrySpec(
                    componentRaw = "ComponentInfo{com.app$i}",
                    packageName = "com.app$i",
                    activityName = null,
                    drawableName = "ic_$i",
                    resPath = "res/drawable-xxxhdpi-v4/ic_$i.png",
                    kind = PackEntryKind.PNG,
                    density = "xxxhdpi-v4",
                    inPack = true,
                    origin = PackEntryOrigin.APPFILTER,
                )
            }

            val project = batching.importProject(
                SourceKind.APK_FILE, "Big", "com.example.big", 1, "big-hash", null, entries,
            )

            assertEquals(2_500, dao.packEntryCount(project.id))
            assertEquals(listOf(1_000, 1_000, 500), dao.insertBatchSizes)
            assertTrue(dao.insertThreads.all { it.startsWith("pack-import-io") })
            assertTrue(dao.insertThreads.none { it.startsWith("Test worker") })
        } finally {
            dispatcher.close()
            executor.shutdown()
        }
    }

    private fun fixtureFile(): File {
        val url = javaClass.classLoader!!.getResource("test-iconpack.apk")
        assertNotNull(url, "test-iconpack.apk 缺失")
        return File(url.toURI())
    }

    private fun fixtureEntries(fixture: File): List<PackEntrySpec> {
        val source = IconPackSource.open(fixture)
        assertNotNull(source)
        return source.use { pack ->
            PackEntryBuilder.build(
                document = pack.document,
                resourcePath = { pack.resourcePath(it) },
                exists = { it in pack.availableDrawables },
            )
        }
    }

    private suspend fun newProject(
        packLabel: String = "Aura",
        packPackage: String = "com.pack",
        hash: String = "hash",
    ): ProjectEntity {
        val source = File(root.parentFile, "new-project-src-$hash.apk").apply { writeBytes(byteArrayOf(1)) }
        return try {
            repository.ensureProject(SourceKind.APK_FILE, packLabel, packPackage, 1, hash, source)
        } finally {
            source.delete()
        }
    }

    /** 一次生成里放三条覆盖三个筛选维度的图标行：包名 / 组件 / accepted。 */
    private suspend fun seededGeneration(): GenerationEntity {
        val project = newProject()
        val generation = repository.createGeneration(project.id)
        repository.persistGenerationIcons(
            generation.id,
            listOf(
                icon("com.a", "com.a.Main", "ap_gen_0", accepted = true),
                icon("com.a", "com.a.Settings", "ap_gen_1", accepted = false, reason = "校验未通过"),
                icon("com.b", "com.b.Main", "ap_gen_2", accepted = true),
            ),
        )
        return generation
    }

    private fun icon(
        packageName: String,
        activityName: String,
        drawableName: String?,
        accepted: Boolean,
        reason: String? = null,
    ): GenerationIconSpec = GenerationIconSpec(
        packageName = packageName,
        activityName = activityName,
        label = packageName,
        drawableName = drawableName,
        accepted = accepted,
        reason = reason,
    )
}

/**
 * 内存版 [ProjectDao]：只需覆盖 [ProjectRepository] 用到的方法，
 * 未用的查询返回空，保持签名与真 DAO 一致。
 */
private class FakeProjectDao : ProjectDao {

    val projects = LinkedHashMap<String, ProjectEntity>()
    val generations = LinkedHashMap<String, GenerationEntity>()
    val attempts = mutableListOf<AttemptEntity>()
    val icons = mutableListOf<GenerationIconEntity>()
    val packEntries = mutableListOf<PackEntryEntity>()
    val insertBatchSizes = mutableListOf<Int>()
    val insertThreads = mutableListOf<String>()
    private var attemptId = 0L

    override suspend fun upsertProject(project: ProjectEntity) {
        projects[project.id] = project
    }

    override suspend fun updateProject(project: ProjectEntity) {
        projects[project.id] = project
    }

    override suspend fun project(projectId: String): ProjectEntity? = projects[projectId]

    override fun observeProjects(): Flow<List<ProjectEntity>> =
        MutableStateFlow(projects.values.sortedByDescending { it.updatedAt })

    override suspend fun allProjects(): List<ProjectEntity> =
        projects.values.sortedByDescending { it.updatedAt }

    override suspend fun projectsByPack(packPackage: String): List<ProjectEntity> =
        projects.values.filter { it.packPackage == packPackage }

    override suspend fun setActiveGeneration(projectId: String, generationId: String?, updatedAt: Long) {
        projects[projectId]?.let {
            projects[projectId] = it.copy(activeGenerationId = generationId, updatedAt = updatedAt)
        }
    }

    override suspend fun deleteProject(projectId: String) {
        projects.remove(projectId)
        val doomed = generations.values.filter { it.projectId == projectId }.map { it.id }
        doomed.forEach { deleteGeneration(it) }
    }

    override suspend fun deletePackEntries(projectId: String) {
        packEntries.removeAll { it.projectId == projectId }
    }

    override suspend fun insertPackEntries(entries: List<PackEntryEntity>) {
        insertBatchSizes += entries.size
        insertThreads += Thread.currentThread().name
        packEntries += entries
    }

    override fun observePackEntries(projectId: String): Flow<List<PackEntryEntity>> =
        MutableStateFlow(packEntries.filter { it.projectId == projectId })

    override suspend fun packEntryCount(projectId: String): Int =
        packEntries.count { it.projectId == projectId }

    override suspend fun upsertGeneration(generation: GenerationEntity) {
        generations[generation.id] = generation
    }

    override suspend fun updateGeneration(generation: GenerationEntity) {
        generations[generation.id] = generation
    }

    override suspend fun generation(generationId: String): GenerationEntity? = generations[generationId]

    override fun observeGenerations(projectId: String): Flow<List<GenerationEntity>> =
        MutableStateFlow(generations.values.filter { it.projectId == projectId }.sortedByDescending { it.createdAt })

    override suspend fun allGenerations(): List<GenerationEntity> =
        generations.values.sortedByDescending { it.createdAt }

    override suspend fun deleteGeneration(generationId: String) {
        generations.remove(generationId)
        attempts.removeAll { it.generationId == generationId }
        icons.removeAll { it.generationId == generationId }
    }

    override suspend fun markRunningGenerationsInterrupted(running: String, interrupted: String): Int {
        var count = 0
        generations.values.filter { it.status.name == running }.forEach {
            generations[it.id] = it.copy(status = GenerationStatus.valueOf(interrupted))
            count++
        }
        return count
    }

    override suspend fun insertGenerationIcons(icons: List<GenerationIconEntity>) {
        this.icons += icons
    }

    override suspend fun deleteGenerationIcons(generationId: String) {
        icons.removeAll { it.generationId == generationId }
    }

    override suspend fun updateGenerationIconOutcome(
        generationId: String,
        packageName: String,
        accepted: Boolean,
        reason: String?,
    ) {
        icons.replaceAll {
            if (it.generationId == generationId && it.packageName == packageName) {
                it.copy(accepted = accepted, reason = reason)
            } else {
                it
            }
        }
    }

    override suspend fun iconsByGeneration(
        generationId: String,
        packageName: String?,
        activityName: String?,
        accepted: Boolean?,
    ): List<GenerationIconEntity> = icons.matching(generationId, packageName, activityName, accepted)

    override fun observeIconsByGeneration(
        generationId: String,
        packageName: String?,
        activityName: String?,
        accepted: Boolean?,
    ): Flow<List<GenerationIconEntity>> =
        MutableStateFlow(icons.matching(generationId, packageName, activityName, accepted))

    override suspend fun insertAttempt(attempt: AttemptEntity): Long {
        attemptId++
        attempts += attempt.copy(id = attemptId)
        return attemptId
    }

    override suspend fun attemptsByGeneration(generationId: String): List<AttemptEntity> =
        attempts.filter { it.generationId == generationId }.sortedWith(compareBy({ it.packageName }, { it.attempt }))

    override suspend fun attemptsForTarget(generationId: String, packageName: String): List<AttemptEntity> =
        attempts.filter { it.generationId == generationId && it.packageName == packageName }

    override suspend fun allAttempts(): List<AttemptEntity> = attempts.toList()

    override fun observeCompareTarget(
        projectId: String,
        packageName: String,
        activityName: String?,
    ): Flow<List<GenerationIconMatch>> = MutableStateFlow(
        icons
            .filter { it.packageName == packageName && (activityName == null || it.activityName == activityName) }
            .mapNotNull { icon ->
                generations[icon.generationId]
                    ?.takeIf { it.projectId == projectId }
                    ?.let { generation ->
                        GenerationIconMatch(
                            icon = icon,
                            generationCreatedAt = generation.createdAt,
                            generationStatus = generation.status,
                            generationModel = generation.model,
                            generationOutputApkFile = generation.outputApkFile,
                        )
                    }
            }
            .sortedByDescending { it.generationCreatedAt },
    )
}

/** 与 [ProjectDao.iconsByGeneration] / [ProjectDao.observeIconsByGeneration] 的 SQL 语义一致的内存实现。 */
private fun List<GenerationIconEntity>.matching(
    generationId: String,
    packageName: String?,
    activityName: String?,
    accepted: Boolean?,
): List<GenerationIconEntity> = filter {
    it.generationId == generationId &&
        (packageName == null || it.packageName == packageName) &&
        (activityName == null || it.activityName == activityName) &&
        (accepted == null || it.accepted == accepted)
}.sortedWith(compareBy({ it.packageName }, { it.activityName }))
