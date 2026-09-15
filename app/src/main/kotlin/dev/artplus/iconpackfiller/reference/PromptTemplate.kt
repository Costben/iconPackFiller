package dev.artplus.iconpackfiller.reference

/**
 * 图像生成提示词模板（M4 §7 初版）。
 */
object PromptTemplate {

    /**
     * 参考图布局与 [BitmapContactSheet] 一致：
     * 上方是 N 个对比格（每格 左=原图 右=图标包重绘；超过 3 组时分成两排），
     * 下半区整块放目标原图（比参考图标大得多）。
     *
     * 文字必须与实际布局一致：错位的布局描述会让模型误判参考与目标的关系。
     */
    /**
     * @param keyColor 抠像键色（调用方按目标图标用 [KeyColorSelector] 选好传入；
     *   缺省绿幕；[transparentBackground] 为 true 时忽略）。
     * @param transparentBackground 模型可透明直出时为 true：不让模型画任何背景，
     *   直接出透明底，免掉抠图。
     */
    fun contactSheetPrompt(
        pairCount: Int,
        keyColor: KeyColor = KeyColor.DEFAULT,
        transparentBackground: Boolean = false,
    ): String {
        val strip = if (pairCount <= ContactSheetComposer.ROW_CAPACITY) {
            "Reference strip (top): $pairCount side-by-side panels."
        } else {
            "Reference strip (top): $pairCount panels in two rows of up to " +
                "${ContactSheetComposer.ROW_CAPACITY} (read left to right, top to bottom)."
        }
        val backgroundSpec = if (transparentBackground) {
            "The area outside the icon shape must stay fully transparent: do NOT paint " +
                "any background, backdrop, solid fill, gradient or ground — only the icon " +
                "artwork itself may contain pixels."
        } else {
            "Fill the area outside the icon shape with one flat solid uniform chroma-key " +
                "color (pure ${keyColor.displayName} #${keyColor.hex}). This background is only " +
                "a cut-out aid and will be removed afterwards, so it must contrast as strongly " +
                "as possible with the icon: do NOT use ${keyColor.displayName} anywhere " +
                "inside the icon itself."
        }
        return """
        You are an Android icon designer. The image is a contact sheet: a narrow strip of reference pairs at the top, and one large target panel below, separated by thin light-gray gaps.

        $strip In each panel the LEFT icon is an app's original icon and the RIGHT icon is the same app redesigned in a specific icon-pack style. Both sides are cropped to the icon artwork and scaled to the same size, so compare them as designs, not as sizes. Study the style transformation rules: silhouette simplification, geometry, corner radius, stroke weight, gradient direction, lighting, shadow, texture, color grading, background treatment and padding ratio.

        Target panel (below): one full-width panel showing the original icon of a new app, drawn much larger than the reference icons only so you can see its details.

        Task: redraw the target icon as if the same designer created it in the same icon-pack style, following the exact same transformation rules. Preserve the target's brand identity: core symbol, distinctive shapes, dominant brand colors.

        Treat the panels, the gray gaps and the larger target panel as layout only, and ignore them in the output.

        Output: ONE single square icon, centered, same canvas size and same padding ratio as the reference pack icons (do not copy the reference panels' size difference and do not make the icon larger just because the target panel is bigger). $backgroundSpec Do NOT draw a checkerboard pattern, transparency grid, gradient, texture, border, shadow, glow or frame — no shadow or glow under the icon either; use flat even lighting and crisp clean-cut edges. No text, no labels, no watermark, no extra elements. Do not include any reference icon in the output. Only the redrawn target icon.
        """.trimIndent()
    }

    /** 单组参考时的提示词（兼容旧调用点）。 */
    val CONTACT_SHEET_PROMPT: String get() = contactSheetPrompt(2)

    /**
     * 带画风统计补充的提示词（可选增强）。
     */
    fun withStyleNotes(
        base: String,
        dominantColors: List<Int>,
        paddingRatio: Float?,
    ): String {
        val notes = buildString {
            appendLine()
            appendLine()
            appendLine("Additional style notes measured from the pack icons:")
            if (dominantColors.isNotEmpty()) {
                append("Dominant palette: ")
                append(dominantColors.joinToString(", ") { hex(it) })
                appendLine(".")
            }
            if (paddingRatio != null) {
                append("Typical subject padding ratio: about ")
                append((paddingRatio * 100).toInt())
                append("%.")
            }
        }
        return base + notes
    }

    fun hex(color: Int): String =
        "#%06X".format(color and 0xFFFFFF)
}