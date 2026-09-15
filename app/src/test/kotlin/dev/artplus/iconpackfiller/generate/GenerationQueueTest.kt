package dev.artplus.iconpackfiller.generate

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerationQueueTest {

    private fun task(
        key: String,
        onRun: suspend () -> Unit = {},
    ) = GenerationTask(
        key = key,
        run = {
            onRun()
            "result-$key"
        },
        encode = { it.toByteArray() },
        onResult = { _, _ -> },
    )

    @Test
    fun `runs all tasks and emits events in order`() = runBlocking {
        val queue = GenerationQueue(concurrency = 1)
        val events = queue.run(listOf(task("a"), task("b")))
        assertEquals(
            listOf(
                GenerationEvent.Started("a"),
                GenerationEvent.Succeeded("a"),
                GenerationEvent.Started("b"),
                GenerationEvent.Succeeded("b"),
            ),
            events,
        )
    }

    @Test
    fun `callLimit skips tasks beyond limit`() = runBlocking {
        val queue = GenerationQueue(concurrency = 1, callLimit = 1)
        val events = queue.run(listOf(task("a"), task("b"), task("c")))
        assertTrue(events.contains(GenerationEvent.LimitReached))
        assertTrue(events.contains(GenerationEvent.Skipped("b", "达到调用上限")))
        assertTrue(events.contains(GenerationEvent.Skipped("c", "达到调用上限")))
        assertTrue(events.contains(GenerationEvent.Succeeded("a")))
        assertEquals(1, events.count { it == GenerationEvent.LimitReached })
    }

    @Test
    fun `failure does not stop other tasks`() = runBlocking {
        val queue = GenerationQueue(concurrency = 1)
        val events = queue.run(
            listOf(
                task("a"),
                task("bad") { throw GenerationException("boom", retryable = true) },
                task("c"),
            ),
        )
        assertTrue(events.contains(GenerationEvent.Failed("bad", "boom", true)))
        assertTrue(events.contains(GenerationEvent.Succeeded("a")))
        assertTrue(events.contains(GenerationEvent.Succeeded("c")))
    }

    @Test
    fun `generic exception is non retryable`() = runBlocking {
        val queue = GenerationQueue()
        val events = queue.run(listOf(task("bad") { throw IllegalStateException("oops") }))
        assertEquals(
            GenerationEvent.Failed("bad", "oops", false),
            events.filterIsInstance<GenerationEvent.Failed>().single(),
        )
    }

    @Test
    fun `concurrency limit is respected`() = runBlocking {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val queue = GenerationQueue(concurrency = 2)
        val tasks = (1..6).map { i ->
            task("t$i") {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(30)
                running.decrementAndGet()
            }
        }
        queue.run(tasks)
        assertTrue(peak.get() <= 2, "peak=$peak")
    }

    @Test
    fun `cancellation emits cancelled and rethrows`() = runBlocking {
        val queue = GenerationQueue(concurrency = 1)
        val events = mutableListOf<GenerationEvent>()
        val q = GenerationQueue(concurrency = 1, onEvent = { events.add(it) })
        var caught = false
        try {
            withTimeout(80) {
                q.run(
                    listOf(
                        task("slow") { delay(10_000) },
                        task("later"),
                    ),
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            caught = true
        }
        assertTrue(caught)
        assertTrue(events.contains(GenerationEvent.Cancelled))
    }

    @Test
    fun `onResult receives encoded bytes`() = runBlocking {
        var received: ByteArray? = null
        val task = GenerationTask(
            key = "x",
            run = { 42 },
            encode = { it.toString().toByteArray() },
            onResult = { _, bytes -> received = bytes },
        )
        GenerationQueue().run(listOf(task))
        assertEquals("42", received?.decodeToString())
    }

    @Test
    fun `empty tasks returns empty events`() = runBlocking {
        assertTrue(GenerationQueue().run<Nothing>(emptyList()).isEmpty())
    }

    @Test
    fun `events callback is invoked for each event`() = runBlocking {
        val count = AtomicInteger(0)
        val queue = GenerationQueue(onEvent = { count.incrementAndGet() })
        val events = queue.run(listOf(task("a")))
        assertEquals(events.size, count.get())
    }
}