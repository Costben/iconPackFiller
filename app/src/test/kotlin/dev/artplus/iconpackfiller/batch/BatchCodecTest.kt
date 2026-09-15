package dev.artplus.iconpackfiller.batch

import dev.artplus.iconpackfiller.generate.ReferenceSnapshot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchCodecTest {

    private fun record(
        id: String = "batch-1",
        status: BatchStatus = BatchStatus.RUNNING,
        attempts: List<AttemptRecord> = emptyList(),
    ) = BatchRecord(
        id = id,
        packLabel = "Aura",
        packPackage = "studio14.application.auraicons",
        createdAt = 1_700_000_000_000L,
        status = status,
        plannedCount = 4,
        generatedCount = 2,
        failedCount = 1,
        outputApk = "out.apk",
        outputApkName = "studio14_filler_1.apk",
        diagnostics = listOf("com.x: 校验未通过"),
        attempts = attempts,
    )

    @Test
    fun `round trip keeps every field`() {
        val source = record(
            attempts = listOf(
                AttemptRecord(
                    packageName = "app.lawnchair.lawnicons.play",
                    label = "Lawnicons",
                    attempt = 1,
                    accepted = true,
                    reason = null,
                    pngFile = "app.lawnchair.lawnicons.play-1.png",
                    sourceFile = "app.lawnchair.lawnicons.play-src.png",
                    references = listOf("com.a", "com.b"),
                ),
                AttemptRecord(
                    packageName = "bin.mt.plus",
                    label = "MT管理器",
                    attempt = 1,
                    accepted = false,
                    reason = "与源图差异过大（感知距离 0.61）",
                    pngFile = "bin.mt.plus-1.png",
                    sourceFile = null,
                    references = listOf("com.c"),
                ),
            ),
        )
        val decoded = BatchCodec.decode(BatchCodec.encode(source))
        assertEquals(source, decoded)
    }

    @Test
    fun `round trip keeps provenance and reference details`() {
        val source = record(
            attempts = listOf(
                AttemptRecord(
                    packageName = "com.erl.blindcast",
                    label = "BlindCast",
                    attempt = 2,
                    accepted = false,
                    reason = "与源图差异过大（感知距离 0.63）",
                    pngFile = "com.erl.blindcast-2.png",
                    sourceFile = "com.erl.blindcast-src.png",
                    references = listOf("ginlemon.flowerfree", "bitpit.launcher"),
                    model = "gemini-3.1-flash-image",
                    slotId = "slot-2",
                    slotName = "banana 2",
                    prompt = "Edit the icon set …",
                    referenceDetails = listOf(
                        ReferenceSnapshot(
                            packageName = "ginlemon.flowerfree",
                            label = "Flower Free",
                            drawableName = "m_12",
                            activityName = "ginlemon.flowerfree.Main",
                        ),
                        ReferenceSnapshot(packageName = "bitpit.launcher"),
                    ),
                ),
            ),
        )
        val decoded = BatchCodec.decode(BatchCodec.encode(source))
        assertEquals(source, decoded)
        val attempt = decoded!!.attempts.single()
        assertEquals("gemini-3.1-flash-image", attempt.model)
        assertEquals("slot-2", attempt.slotId)
        assertEquals(2, attempt.referenceDetails.size)
        assertEquals("m_12", attempt.referenceDetails.first().drawableName)
    }

    @Test
    fun `old version json without provenance still decodes`() {
        val legacy = """
            {"version":1,"id":"batch-old","packLabel":"Aura","packPackage":"p","createdAt":1,
             "status":"COMPLETED","planned":1,"generated":1,"failed":0,
             "attempts":[{"pkg":"a.b","label":"AB","attempt":1,"accepted":true,"reason":null,
                          "png":"a-1.png","src":"a-src.png","refs":["c.d"]}]}
        """.trimIndent()
        val decoded = BatchCodec.decode(legacy)
        val attempt = decoded!!.attempts.single()
        assertNull(attempt.model)
        assertNull(attempt.prompt)
        assertEquals(emptyList(), attempt.referenceDetails)
        assertEquals(listOf("c.d"), attempt.references)
    }

    @Test
    fun `decode tolerates garbage`() {
        assertNull(BatchCodec.decode(null))
        assertNull(BatchCodec.decode(""))
        assertNull(BatchCodec.decode("{"))
        assertNull(BatchCodec.decode("{}"))
    }

    @Test
    fun `unknown status becomes interrupted`() {
        val json = BatchCodec.encode(record()).replace("\"RUNNING\"", "\"WEIRD\"")
        assertEquals(BatchStatus.INTERRUPTED, BatchCodec.decode(json)?.status)
    }

    @Test
    fun `progress accounts generated and failed`() {
        val record = record(status = BatchStatus.RUNNING)
        assertEquals(0.75f, record.progress)
        val done = record.copy(generatedCount = 4, failedCount = 0, status = BatchStatus.COMPLETED)
        assertEquals(1f, done.progress)
        assertTrue(done.finished)
    }

    @Test
    fun `empty attempts survive the round trip`() {
        val decoded = BatchCodec.decode(BatchCodec.encode(record()))
        assertEquals(emptyList(), decoded?.attempts)
        assertEquals(listOf("com.x: 校验未通过"), decoded?.diagnostics)
    }
}
