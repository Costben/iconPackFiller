package dev.artplus.iconpackfiller.project

/**
 * 图标包来源：已安装包 / 本地 APK 文件。
 */
enum class SourceKind { INSTALLED, APK_FILE }

/**
 * 一条 Generation（一次生成历史）的生命周期。
 *
 * 语义沿用旧批次状态：进程被杀后遗留的 RUNNING 记录在下次启动时标为 [INTERRUPTED]。
 */
enum class GenerationStatus {
    RUNNING,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
    FAILED,
    ;

    /** 是否已结束（不再变化）。 */
    val isTerminal: Boolean get() = this != RUNNING
}

/**
 * 包内资源文件类型。
 */
enum class PackEntryKind { PNG, VECTOR_XML, ADAPTIVE_XML, OTHER }

/**
 * PackEntry 的来源：来自 appfilter.xml，还是在包内扫描到但未被 appfilter 引用。
 */
enum class PackEntryOrigin { APPFILTER, SCANNED }

/**
 * 关系表名常量。实体与 schema 契约测试共用，避免两处各写一份字符串。
 */
object Tables {
    const val PROJECT = "projects"
    const val PACK_ENTRY = "pack_entries"
    const val GENERATION = "generations"
    const val GENERATION_ICON = "generation_icons"
    const val ATTEMPT = "attempts"
}
