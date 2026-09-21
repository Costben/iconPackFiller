package dev.artplus.iconpackfiller.project

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectPathsTest {

    private lateinit var root: File
    private lateinit var paths: ProjectPaths

    @Before
    fun setUp() {
        root = Files.createTempDirectory("projects").toFile()
        paths = ProjectPaths(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `source snapshot lives at project root`() {
        assertEquals("source.apk", ProjectPaths.SOURCE_APK_RELATIVE)
        assertEquals(File(paths.projectDir("p1"), "source.apk"), paths.sourceApk("p1"))
    }

    @Test
    fun `generation output relative path nests under generations`() {
        assertEquals("generations/g1/out.apk", ProjectPaths.outputApkRelative("g1"))
        assertEquals(File(paths.generationDir("p1", "g1"), "out.apk"), paths.outputApk("p1", "g1"))
    }

    @Test
    fun `attempt relative path uses sanitized package`() {
        assertEquals(
            "generations/g1/att/com.app-1.png",
            ProjectPaths.attemptPngRelative("g1", "com.app", 1),
        )
        val weird = ProjectPaths.attemptPngRelative("g1", "com.app/Activity:with weird", 2)
        assertTrue(weird.startsWith("generations/g1/att/"))
        assertFalse(weird.substringAfterLast('/').contains("/"))
        assertFalse(weird.substringAfterLast('/').contains(":"))
    }

    @Test
    fun `safeName keeps readable names and hashes overlong ones`() {
        assertEquals("com.app", ProjectPaths.safeName("com.app"))
        val long = "a".repeat(200)
        val safe = ProjectPaths.safeName(long)
        assertTrue(safe.length <= 60)
        assertEquals(safe, ProjectPaths.safeName(long))
    }

    @Test
    fun `directories are created on demand`() {
        val genDir = paths.generationDir("p1", "g1")
        assertFalse(genDir.exists())
        paths.ensureGenerationDirs("p1", "g1")
        assertTrue(genDir.isDirectory)
        assertTrue(paths.attemptDir("p1", "g1").isDirectory)
    }
}
