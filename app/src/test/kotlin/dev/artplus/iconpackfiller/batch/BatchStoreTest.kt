package dev.artplus.iconpackfiller.batch

import dev.artplus.iconpackfiller.generate.GenerationAttempt
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchStoreTest {

    private lateinit var root: File
    private lateinit var store: BatchStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("batches").toFile()
        store = BatchStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun record(id: String, createdAt: Long = 1L) = BatchRecord(
        id = id,
        packLabel = "Aura",
        packPackage = "studio14.application.auraicons",
        createdAt = createdAt,
        status = BatchStatus.RUNNING,
        plannedCount = 2,
        generatedCount = 0,
        failedCount = 0,
        outputApk = null,
        outputApkName = null,
        diagnostics = emptyList(),
        attempts = emptyList(),
    )

    @Test
    fun `save read round trip`() {
        store.save(record("b1"))
        assertEquals("b1", store.read("b1")?.id)
    }

    @Test
    fun `list sorts newest first`() {
        store.save(record("old", createdAt = 100))
        store.save(record("new", createdAt = 200))
        assertEquals(listOf("new", "old"), store.list().map { it.id })
    }

    @Test
    fun `missing id returns null`() {
        assertNull(store.read("nope"))
    }

    /**
     * 进程被杀后 RUNNING 记录会永远显示「运行中」，启动时必须转成 INTERRUPTED。
     */
    @Test
    fun `markInterrupted flips stale running records`() {
        store.save(record("running"))
        store.save(record("done").copy(status = BatchStatus.COMPLETED))

        val flipped = store.markInterrupted()

        assertEquals(listOf("running"), flipped.map { it.id })
        assertEquals(BatchStatus.INTERRUPTED, store.read("running")?.status)
        assertEquals(BatchStatus.COMPLETED, store.read("done")?.status)
    }

    @Test
    fun `persistAttempt writes png and source and records names`() {
        val attempt = GenerationAttempt(
            packageName = "bin.mt.plus",
            label = "MT管理器",
            attempt = 1,
            accepted = false,
            reason = "校验未通过",
            pngBytes = byteArrayOf(1, 2, 3),
            references = listOf("com.a"),
            sourcePngBytes = byteArrayOf(4, 5),
        )
        val record = store.persistAttempt("b1", attempt)

        assertEquals("bin.mt.plus-1.png", record.pngFile)
        assertEquals("bin.mt.plus-src.png", record.sourceFile)
        assertNotNull(store.attemptFile("b1", record.pngFile))
        assertEquals(byteArrayOf(1, 2, 3).toList(), store.attemptFile("b1", record.pngFile)!!.readBytes().toList())
    }

    /**
     * 包名里的斜杠/冒号必须被清洗，否则会写到目录外。
     */
    @Test
    fun `unsafe package names are sanitized`() {
        val attempt = GenerationAttempt(
            packageName = "com.app/Activity:with weird",
            label = null,
            attempt = 1,
            accepted = true,
            reason = null,
            pngBytes = byteArrayOf(9),
            references = emptyList(),
        )
        val record = store.persistAttempt("b1", attempt)
        assertFalse(record.pngFile!!.contains("/"))
        assertTrue(store.attemptFile("b1", record.pngFile)!!.toPath().startsWith(root.toPath()))
    }

    @Test
    fun `persistApk copies into batch dir`() {
        val apk = File(root.parentFile, "signed-test.apk").apply { writeBytes(byteArrayOf(7)) }
        try {
            val name = store.persistApk("b1", apk)
            assertEquals("out.apk", name)
            assertNotNull(store.outputApk("b1", name))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `delete removes the whole batch`() {
        store.save(record("b1"))
        store.persistAttempt(
            "b1",
            GenerationAttempt("a", null, 1, true, null, byteArrayOf(1), emptyList()),
        )
        assertTrue(store.delete("b1"))
        assertNull(store.read("b1"))
        assertNull(store.attemptFile("b1", "a-1.png"))
    }
}
