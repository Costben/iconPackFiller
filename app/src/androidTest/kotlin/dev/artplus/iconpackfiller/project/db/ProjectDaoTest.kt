package dev.artplus.iconpackfiller.project.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.artplus.iconpackfiller.project.GenerationStatus
import dev.artplus.iconpackfiller.project.PackEntryKind
import dev.artplus.iconpackfiller.project.PackEntryOrigin
import dev.artplus.iconpackfiller.project.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room DAO 端到端（in-memory）。需要设备/模拟器，Phase 7 真机验证时执行。
 *
 * 覆盖 Requirements 的核心查询：按生成筛选、跨生成对比、FK 级联删除。
 */
@RunWith(AndroidJUnit4::class)
class ProjectDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProjectDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.projectDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun project(id: String) = ProjectEntity(
        id = id,
        packLabel = "Aura",
        packPackage = "studio14.application.auraicons",
        packVersionCode = 1,
        sourceKind = SourceKind.APK_FILE,
        packHash = "hash",
        sourceApkFile = "source.apk",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun generation(id: String, projectId: String, createdAt: Long) = GenerationEntity(
        id = id,
        projectId = projectId,
        createdAt = createdAt,
        status = GenerationStatus.COMPLETED,
        plannedCount = 2,
        generatedCount = 2,
    )

    private fun icon(
        generationId: String,
        packageName: String,
        activityName: String,
        accepted: Boolean,
    ) = GenerationIconEntity(
        generationId = generationId,
        packageName = packageName,
        activityName = activityName,
        accepted = accepted,
    )

    @Test
    fun roundTripProjectAndPackEntryCount() = runBlocking {
        dao.upsertProject(project("p1"))
        dao.insertPackEntries(
            listOf(
                PackEntryEntity(
                    projectId = "p1",
                    componentRaw = "ComponentInfo{com.a/com.a.Main}",
                    packageName = "com.a",
                    activityName = "com.a.Main",
                    drawableName = "m_1",
                    resPath = "res/mipmap-xxhdpi/m_1.png",
                    kind = PackEntryKind.PNG,
                    density = "xxhdpi",
                    inPack = true,
                    origin = PackEntryOrigin.APPFILTER,
                ),
            ),
        )
        assertEquals(1, dao.packEntryCount("p1"))
    }

    @Test
    fun filtersIconsByGenerationAndStatus() = runBlocking {
        dao.upsertProject(project("p1"))
        dao.upsertGeneration(generation("g1", "p1", 1L))
        dao.insertGenerationIcons(
            listOf(
                icon("g1", "com.a", "com.a.Main", accepted = true),
                icon("g1", "com.b", "com.b.Main", accepted = false),
            ),
        )
        val all = dao.observeIconsByGeneration("g1", null, null, null).first()
        assertEquals(2, all.size)
        val accepted = dao.observeIconsByGeneration("g1", null, null, true).first()
        assertEquals(listOf("com.a"), accepted.map { it.packageName })
        val byPackage = dao.observeIconsByGeneration("g1", "com.b", null, null).first()
        assertEquals(listOf("com.b"), byPackage.map { it.packageName })
    }

    @Test
    fun comparesTargetAcrossGenerations() = runBlocking {
        dao.upsertProject(project("p1"))
        dao.upsertGeneration(generation("g1", "p1", 100L))
        dao.upsertGeneration(generation("g2", "p1", 200L))
        dao.insertGenerationIcons(listOf(icon("g1", "com.a", "com.a.Main", true)))
        dao.insertGenerationIcons(listOf(icon("g2", "com.a", "com.a.Main", true)))

        val series = dao.observeCompareTarget("p1", "com.a", "com.a.Main").first()
        assertEquals(listOf("g2", "g1"), series.map { it.icon.generationId })
        assertEquals(200L, series.first().generationCreatedAt)
    }

    @Test
    fun deletingProjectCascades() = runBlocking {
        dao.upsertProject(project("p1"))
        dao.upsertGeneration(generation("g1", "p1", 1L))
        dao.insertGenerationIcons(listOf(icon("g1", "com.a", "com.a.Main", true)))

        dao.deleteProject("p1")

        assertNull(dao.generation("g1"))
        assertEquals(0, dao.iconsByGeneration("g1", null, null, null).size)
    }
}
