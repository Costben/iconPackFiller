package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.project.db.GenerationEntity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerationEntityTest {

    private fun generation(
        status: GenerationStatus,
        planned: Int,
        generated: Int,
        failed: Int,
    ) = GenerationEntity(
        id = "g1",
        projectId = "p1",
        createdAt = 1L,
        status = status,
        plannedCount = planned,
        generatedCount = generated,
        failedCount = failed,
    )

    @Test
    fun `progress accounts generated and failed`() {
        val running = generation(GenerationStatus.RUNNING, planned = 4, generated = 2, failed = 1)
        assertEquals(0.75f, running.progress)
        assertFalse(running.finished)

        val done = generation(GenerationStatus.COMPLETED, planned = 4, generated = 4, failed = 0)
        assertEquals(1f, done.progress)
        assertTrue(done.finished)
    }

    @Test
    fun `running with no plan reports zero progress`() {
        assertEquals(0f, generation(GenerationStatus.RUNNING, 0, 0, 0).progress)
    }

    @Test
    fun `terminal statuses`() {
        assertTrue(GenerationStatus.COMPLETED.isTerminal)
        assertTrue(GenerationStatus.FAILED.isTerminal)
        assertTrue(GenerationStatus.CANCELLED.isTerminal)
        assertTrue(GenerationStatus.INTERRUPTED.isTerminal)
        assertFalse(GenerationStatus.RUNNING.isTerminal)
    }
}
