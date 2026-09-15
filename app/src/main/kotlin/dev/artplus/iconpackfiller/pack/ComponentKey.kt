package dev.artplus.iconpackfiller.pack

/**
 * 归一化的 ComponentInfo 匹配键。
 *
 * 支持三种书写形式：
 * - `ComponentInfo{com.foo.bar/com.foo.bar.MainActivity}` — 精确组件
 * - `ComponentInfo{com.foo.bar/.MainActivity}` — 短类名，补全为 `com.foo.bar.MainActivity`
 * - `ComponentInfo{com.foo.bar}` — 包级条目，activity 为 null
 *
 * 包名与 activity 均按小写归一（Android 实际区分大小写，但第三方包常有不一致写法，
 * 与启动器实现保持一致采用宽松匹配）。
 */
data class ComponentKey(
    val packageName: String,
    val activityName: String?,
) {
    val isPackageLevel: Boolean get() = activityName == null

    fun flatten(): String =
        if (activityName == null) packageName else "$packageName/$activityName"

    companion object {
        private const val PREFIX = "ComponentInfo{"

        fun parse(raw: String?): ComponentKey? {
            if (raw.isNullOrBlank()) return null
            val inner = if (raw.startsWith(PREFIX) && raw.endsWith("}")) {
                raw.substring(PREFIX.length, raw.length - 1).trim()
            } else {
                raw.trim()
            }
            if (inner.isEmpty()) return null
            val slash = inner.indexOf('/')
            if (slash < 0) {
                return ComponentKey(normalizePackage(inner), null)
            }
            val pkg = normalizePackage(inner.substring(0, slash))
            if (pkg.isEmpty()) return null
            val activity = normalizeActivity(pkg, inner.substring(slash + 1))
            if (activity.isEmpty()) return null
            return ComponentKey(pkg, activity)
        }

        fun normalizePackage(raw: String): String = raw.trim().lowercase()

        /**
         * `.MainActivity` 补全为 `pkg.MainActivity`；裸类名 `MainActivity` 补全为
         * `pkg.MainActivity`；全限定名保持原样。全部小写。
         */
        fun normalizeActivity(pkg: String, raw: String): String {
            val activity = raw.trim()
            return when {
                activity.isEmpty() -> ""
                activity.startsWith('.') -> (pkg + activity).lowercase()
                !activity.contains('.') -> "$pkg.$activity".lowercase()
                else -> activity.lowercase()
            }
        }
    }
}