package dev.artplus.iconpackfiller.generate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 单个生成任务。与 Bitmap/Provider 解耦，便于 JVM 单测。
 *
 * @param key 唯一标识（通常为包名或组件 key）
 * @param run 执行生成（调用 provider、校验、重试都在这里）
 * @param encode 结果编码为字节（Android 端为 PNG）
 * @param onResult 成功回调（Bitmap, PNG bytes）
 */
data class GenerationTask<T>(
    val key: String,
    val run: suspend () -> T,
    val encode: suspend (T) -> ByteArray,
    val onResult: suspend (T, ByteArray) -> Unit,
)

sealed class GenerationEvent {
    data class Started(val key: String) : GenerationEvent()
    data class Succeeded(val key: String) : GenerationEvent()
    data class Failed(val key: String, val message: String, val retryable: Boolean) : GenerationEvent()
    data class Skipped(val key: String, val reason: String) : GenerationEvent()
    data object LimitReached : GenerationEvent()
    data object Cancelled : GenerationEvent()
}

/**
 * 批量生成执行器。
 *
 * - 并发上限 [concurrency]（信号量）
 * - 全局调用上限 [callLimit]，按任务列表顺序取前 N 个；超限发一次 [GenerationEvent.LimitReached] 并逐个跳过
 * - 单任务失败不影响其他任务（[GenerationTask.run] 抛异常即记 Failed）
 * - 协程取消即中断；已发出的事件保留
 */
class GenerationQueue(
    private val concurrency: Int = 1,
    private val callLimit: Int? = null,
    private val onEvent: (GenerationEvent) -> Unit = {},
) {

    suspend fun <T> run(tasks: List<GenerationTask<T>>): List<GenerationEvent> {
        val events = java.util.Collections.synchronizedList(ArrayList<GenerationEvent>())
        fun emit(event: GenerationEvent) {
            events.add(event)
            onEvent(event)
        }

        if (tasks.isEmpty()) return events

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val limitNotified = java.util.concurrent.atomic.AtomicBoolean(false)

        try {
            coroutineScope {
                tasks.mapIndexed { index, task ->
                    async {
                        if (callLimit != null && index >= callLimit) {
                            if (limitNotified.compareAndSet(false, true)) {
                                emit(GenerationEvent.LimitReached)
                            }
                            emit(GenerationEvent.Skipped(task.key, "达到调用上限"))
                            return@async
                        }
                        semaphore.withPermit {
                            emit(GenerationEvent.Started(task.key))
                            try {
                                val result = task.run()
                                val bytes = task.encode(result)
                                task.onResult(result, bytes)
                                emit(GenerationEvent.Succeeded(task.key))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: GenerationException) {
                                emit(GenerationEvent.Failed(task.key, e.message ?: "失败", e.retryable))
                            } catch (e: Exception) {
                                emit(GenerationEvent.Failed(task.key, e.message ?: "失败", false))
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            emit(GenerationEvent.Cancelled)
            throw e
        }
        return events
    }
}

/** 生成流程异常（校验失败、provider 失败等统一封装）。 */
class GenerationException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
