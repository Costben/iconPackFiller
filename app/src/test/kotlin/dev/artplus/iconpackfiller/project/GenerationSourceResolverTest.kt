package dev.artplus.iconpackfiller.project

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GenerationSourceResolver] 的 JVM 单测：覆盖「用项目快照作为源」的准备路径。
 *
 * 关键约束（Requirement）：源包被卸载 / 更新 / 删除后，生成输入必须是项目目录内的
 * `source.apk`，并挂回同一项目（[GenerationSourceResolver.Resolved.reuseProjectId]），
 * 不得新建重复项目。
 */
class GenerationSourceResolverTest {

    private lateinit var dir: File
    private lateinit var snapshot: File
    private lateinit var imported: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("gen-source").toFile()
        snapshot = File(dir, "source.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        imported = File(dir, "imported.apk").apply { writeBytes(byteArrayOf(4, 5, 6)) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `project snapshot resolves to the snapshot file and reuses the same project`() {
        val resolved = assertNotNull(
            GenerationSourceResolver.resolve(
                snapshotProjectId = "proj-1",
                snapshotApk = snapshot,
                snapshotLabel = "Aura",
                installedPackage = "studio14.application.auraicons",
                apkFile = imported,
            ),
        )
        assertEquals(snapshot, resolved.apkFile)
        assertNull(resolved.installedPackage)
        assertEquals("proj-1", resolved.reuseProjectId)
        assertEquals("Aura", resolved.label)
        assertNull(resolved.liveSourceKind)
    }

    @Test
    fun `snapshot source wins over installed pack and imported apk`() {
        val resolved = assertNotNull(
            GenerationSourceResolver.resolve(
                snapshotProjectId = "proj-1",
                snapshotApk = snapshot,
                installedPackage = "com.pack",
                apkFile = imported,
            ),
        )
        assertEquals(snapshot, resolved.apkFile)
        assertEquals("proj-1", resolved.reuseProjectId)
    }

    @Test
    fun `deleted snapshot file falls back to live selection`() {
        val gone = File(dir, "deleted.apk")
        val resolved = assertNotNull(
            GenerationSourceResolver.resolve(
                snapshotProjectId = "proj-1",
                snapshotApk = gone,
                installedPackage = "com.pack",
                installedLabel = "Pack",
            ),
        )
        assertEquals("com.pack", resolved.installedPackage)
        assertNull(resolved.reuseProjectId)
        assertNull(resolved.apkFile)
        assertEquals(SourceKind.INSTALLED, resolved.liveSourceKind)
    }

    @Test
    fun `snapshot id without a snapshot file falls back to imported apk`() {
        val gone = File(dir, "deleted.apk")
        val resolved = assertNotNull(
            GenerationSourceResolver.resolve(
                snapshotProjectId = "proj-1",
                snapshotApk = gone,
                apkFile = imported,
                apkLabel = "imported.apk",
            ),
        )
        assertEquals(imported, resolved.apkFile)
        assertNull(resolved.reuseProjectId)
        assertEquals(SourceKind.APK_FILE, resolved.liveSourceKind)
    }

    @Test
    fun `installed pack takes priority over imported apk`() {
        val resolved = assertNotNull(
            GenerationSourceResolver.resolve(
                installedPackage = "com.pack",
                installedLabel = "Pack",
                apkFile = imported,
                apkLabel = "imported.apk",
            ),
        )
        assertEquals("com.pack", resolved.installedPackage)
        assertNull(resolved.apkFile)
        assertEquals(SourceKind.INSTALLED, resolved.liveSourceKind)
    }

    @Test
    fun `imported apk only resolves as an apk file source`() {
        val resolved = assertNotNull(
            GenerationSourceResolver.resolve(apkFile = imported, apkLabel = "imported.apk"),
        )
        assertEquals(imported, resolved.apkFile)
        assertNull(resolved.installedPackage)
        assertNull(resolved.reuseProjectId)
        assertEquals(SourceKind.APK_FILE, resolved.liveSourceKind)
    }

    @Test
    fun `no available source resolves to null`() {
        assertNull(GenerationSourceResolver.resolve())
    }

    @Test
    fun `blank labels fall back to the default pack label`() {
        val resolved = assertNotNull(
            GenerationSourceResolver.resolve(apkFile = imported, apkLabel = "   "),
        )
        assertTrue(resolved.label.isNotBlank())
        assertEquals("图标包", resolved.label)
    }
}
