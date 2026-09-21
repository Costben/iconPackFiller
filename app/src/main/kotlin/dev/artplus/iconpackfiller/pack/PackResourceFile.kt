package dev.artplus.iconpackfiller.pack

/**
 * 包内一个候选图标资源文件（PNG / webp / XML）。
 *
 * [name] 是去掉扩展名的资源名（如 `ic_wechat`），[resPath] 是 zip 内完整路径
 * （如 `res/drawable-xxxhdpi-v4/ic_wechat.png`）。
 */
data class PackResourceFile(
    val resPath: String,
    val name: String,
)

/**
 * density 限定符排序：数值越高越优先（anydpi > xxxhdpi > … > 无后缀）。
 * 供「同一 drawable 多密度选一」使用。
 */
internal object ResourceDensity {

    fun rank(qualifiers: String): Int = when {
        qualifiers.contains("anydpi") -> 7
        qualifiers.contains("xxxhdpi") -> 6
        qualifiers.contains("xxhdpi") -> 5
        qualifiers.contains("xhdpi") -> 4
        qualifiers.contains("hdpi") -> 3
        qualifiers.contains("mdpi") -> 2
        qualifiers.contains("ldpi") -> 1
        else -> 0
    }

    /** `res/drawable-xxxhdpi-v4/ic.png` -> 按目录名排序。 */
    fun rankPath(resPath: String): Int {
        val dir = resPath.substringBeforeLast('/', "").substringAfterLast('/')
        return rank(dir)
    }
}

/** 同一 drawable 的多个密度文件中选最佳路径；不存在返回 null。 */
internal fun List<PackResourceFile>.bestResourcePath(drawableName: String): String? =
    filter { it.name == drawableName }
        .maxByOrNull { ResourceDensity.rankPath(it.resPath) }
        ?.resPath
