package dev.artplus.iconpackfiller.generate

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerationBudgetTest {

    @Test
    fun `null limit is unlimited`() {
        val budget = GenerationBudget(null)
        assertTrue(budget.unlimited)
        repeat(1000) { assertTrue(budget.tryAcquire()) }
    }

    @Test
    fun `zero and negative limit treated as unlimited`() {
        for (limit in listOf(0, -1, -100)) {
            val budget = GenerationBudget(limit)
            assertTrue(budget.unlimited, "limit=$limit")
            assertTrue(budget.tryAcquire())
        }
    }

    @Test
    fun `limit one allows exactly one call`() {
        val budget = GenerationBudget(1)
        assertFalse(budget.unlimited)
        assertTrue(budget.hasRemaining())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.hasRemaining())
        assertFalse(budget.tryAcquire())
    }

    @Test
    fun `limit three allows exactly three calls`() {
        val budget = GenerationBudget(3)
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
    }

    @Test
    fun `concurrent acquires never exceed limit`() {
        val limit = 50
        val budget = GenerationBudget(limit)
        val granted = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        repeat(16) {
            pool.submit {
                start.await()
                repeat(100) { if (budget.tryAcquire()) granted.incrementAndGet() }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals(limit, granted.get())
    }

    @Test
    fun `limit description is human readable`() {
        assertEquals("不限", GenerationBudget(null).limitDescription())
        assertEquals("不限", GenerationBudget(0).limitDescription())
        assertEquals("5", GenerationBudget(5).limitDescription())
    }
}
