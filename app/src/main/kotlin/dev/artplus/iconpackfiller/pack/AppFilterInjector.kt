package dev.artplus.iconpackfiller.pack

/**
 * appfilter.xml 注入。
 *
 * 在保留原文件全部内容（含注释、iconback/iconmask/iconupon/scale 等节点）的前提下，
 * 把新条目插入 `</resources>` 之前；若原文件没有该闭合标签则追加到末尾。
 *
 * 纯字符串逻辑，可 JVM 单测。
 */
object AppFilterInjector {

    /** 新条目（component 原样字符串 + drawable 名）。 */
    data class NewItem(
        val component: String,
        val drawableName: String,
    )

    private val CLOSING_TAG = Regex("</resources\\s*>", RegexOption.IGNORE_CASE)

    /**
     * @param original 原 appfilter.xml 文本；null 表示原包没有该文件（从零生成）
     * @param items 要追加的条目
     * @return 注入后的完整文本
     */
    fun inject(original: String?, items: List<NewItem>): String {
        if (items.isEmpty()) return original ?: emptyDocument()
        val rendered = items.joinToString(separator = "\n", postfix = "\n") { render(it) }
        if (original == null || original.isBlank()) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources>\n$rendered</resources>\n"
        }
        val match = CLOSING_TAG.find(original)
        return if (match != null) {
            original.substring(0, match.range.first) + rendered + original.substring(match.range.first)
        } else {
            original.trimEnd() + "\n" + rendered
        }
    }

    /**
     * 已存在的 component（归一化小写）集合，用于跳过重复注入。
     */
    fun existingComponents(document: AppFilterDocument?): Set<String> =
        document?.items?.map { it.component.flatten().lowercase() }?.toSet() ?: emptySet()

    private fun render(item: NewItem): String =
        "    <item component=\"${escape(item.component)}\" drawable=\"${escape(item.drawableName)}\" />"

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun emptyDocument(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources>\n</resources>\n"
}