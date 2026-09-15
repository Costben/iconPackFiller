package dev.artplus.iconpackfiller.generate

/**
 * 全局调用预算（线程安全）。
 *
 * 统计的是**真实 provider 调用次数**（每次重试都算一次），而非应用数量——
 * 成本控制必须按 API 调用计。
 *
 * @param limit null 或 <= 0 表示不限
 */
class GenerationBudget(private val limit: Int?) {

    private val remaining = java.util.concurrent.atomic.AtomicInteger(
        if (limit == null || limit <= 0) Int.MAX_VALUE else limit,
    )

    val unlimited: Boolean = limit == null || limit <= 0

    /** 是否还有可用调用。 */
    fun hasRemaining(): Boolean = remaining.get() > 0

    /** 人类可读的上限描述（进度提示用）。 */
    fun limitDescription(): String = if (unlimited) "不限" else "${limit ?: 0}"

    /**
     * 尝试占用一次调用。
     *
     * @return 成功返回 true；预算耗尽返回 false
     */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = remaining.get()
            if (current <= 0) return false
            if (remaining.compareAndSet(current, current - 1)) return true
        }
    }
}
