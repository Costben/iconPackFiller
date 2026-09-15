package dev.artplus.iconpackfiller.pack

import java.security.MessageDigest

/**
 * 打包命名策略（M0 ADR 决定）。
 *
 * - 新包名：`dev.artplus.iconpack.<hash8>`，hash8 = 原包名 SHA-256 前 8 位 hex；
 *   首字符是数字时补 `p`（Android 包名每段必须以字母开头，
 *   否则安装器报 `INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME`）
 * - versionCode：原包 + 10000
 * - label：原名 + "（补全）"
 * - 新 drawable 名：`ap_gen_<index>`（不与原包冲突，可读）
 *
 * 纯逻辑，可 JVM 单测。
 */
object PackNaming {

    const val PACKAGE_PREFIX = "dev.artplus.iconpack."
    const val DRAWABLE_PREFIX = "ap_gen_"
    const val VERSION_CODE_OFFSET = 10_000
    const val LABEL_SUFFIX = "（补全）"

    fun packageNameFor(originalPackage: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(originalPackage.trim().lowercase().toByteArray(Charsets.UTF_8))
        val hash8 = digest.take(4).joinToString("") { "%02x".format(it) }
        // 包名段不能以数字开头：hex 首位是数字时加字母前缀
        val segment = if (hash8.first().isDigit()) "p$hash8" else hash8
        return PACKAGE_PREFIX + segment
    }

    fun versionCodeFor(originalVersionCode: Int?): Int =
        (originalVersionCode ?: 0) + VERSION_CODE_OFFSET

    fun versionNameFor(originalVersionName: String?): String {
        val base = originalVersionName?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
        return "$base-filler"
    }

    fun labelFor(originalLabel: String?): String {
        val base = originalLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "图标包"
        return if (base.endsWith(LABEL_SUFFIX)) base else base + LABEL_SUFFIX
    }

    fun drawableNameFor(index: Int): String = "$DRAWABLE_PREFIX$index"

    /**
     * appfilter 条目里的 component 字符串。
     *
     * 必须带 activity：启动器按 `ComponentName` 匹配（`unflattenFromString` 对
     * 无 `/` 的包级字符串返回 null，条目会被直接跳过）。
     */
    fun componentInfoFor(packageName: String, activityName: String): String =
        "ComponentInfo{$packageName/$activityName}"

    /**
     * 避免与原包已有 drawable 冲突：若名字已存在，追加 `_<n>` 直到唯一。
     */
    fun uniqueDrawableName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("${base}_$i" in existing) i++
        return "${base}_$i"
    }

    /** 主 Activity 新类名（跟随新包名）。 */
    fun mainActivityClassFor(newPackage: String): String = "$newPackage.MainActivity"
}