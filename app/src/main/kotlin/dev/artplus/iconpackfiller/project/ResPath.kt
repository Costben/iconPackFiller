package dev.artplus.iconpackfiller.project

/**
 * 包内资源路径（如 `res/mipmap-xxhdpi/ic_launcher.png`）的纯解析。
 *
 * 不依赖 Android：导入时按路径就能判定 [PackEntryKind] 与 density，
 * 不必解出位图。
 */
object ResPath {

    private val RASTER_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")

    /** 资源路径 -> 类型；无法判定时 [PackEntryKind.OTHER]。 */
    fun kind(path: String?): PackEntryKind {
        if (path.isNullOrBlank()) return PackEntryKind.OTHER
        val lower = path.lowercase()
        val extension = lower.substringAfterLast('.', missingDelimiterValue = "")
        if (extension in RASTER_EXTENSIONS) return PackEntryKind.PNG
        if (extension != "xml") return PackEntryKind.OTHER
        return if (typeDir(lower)?.startsWith("mipmap") == true) {
            PackEntryKind.ADAPTIVE_XML
        } else {
            PackEntryKind.VECTOR_XML
        }
    }

    /**
     * 资源路径 -> density 限定符（如 `xxhdpi`、`anydpi-v26`）；无限定符返回 null。
     */
    fun density(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val dir = typeDir(path.lowercase()) ?: return null
        val qualifier = dir.substringAfter('-', missingDelimiterValue = "")
        return qualifier.takeIf { it.isNotEmpty() }
    }

    /**
     * `res/<type-dir>/<file>` 里的 `<type-dir>`；路径不含 `res/` 时取倒数第二段。
     */
    private fun typeDir(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return if (segments.size >= 2) segments[segments.size - 2] else null
    }
}
