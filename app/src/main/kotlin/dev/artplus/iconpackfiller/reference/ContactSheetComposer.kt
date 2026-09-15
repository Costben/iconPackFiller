package dev.artplus.iconpackfiller.reference

import kotlin.math.min

/**
 * ContactSheet 布局计算。纯数学，可 JVM 单测。
 *
 * 结构：**上排贴图标、下半区全部留给目标**。
 * - 上半区按 [pairCount] 等分：每格放一组「原图 → 包内风格图」，
 *   高度只比图标高一点点（iconSize + 2*pad），不占半张画布
 * - 下半区整块：放待生成的目标原图（尽可能大，模型能看清更多细节）
 *
 * 布局规则：
 * - 单元格铺满画布（只有 [SEPARATOR] 宽的细线分隔），不留大块空底
 * - 单元底色用浅灰 [GRAY]，白色图标才有可见轮廓
 *   （有些应用图标本身就是白底，纯白背景上会与背景融为一体）
 */
data class ContactSheetLayout(
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** 参考组内左（原图）位置。 */
    val referenceLeft: List<Cell>,
    /** 参考组内右（风格图）位置。 */
    val referenceRight: List<Cell>,
    /** 目标原图位置。 */
    val target: Cell,
    /** 田字格各单元（上排 pairCount 格 + 下方整块目标格），全部铺 [ContactSheetComposer.panelColor]。 */
    val panels: List<Rect>,
    /** 上半区（参考区）整体范围。 */
    val referencePanel: Rect,
    /** 下半区（目标区）整体范围。 */
    val targetPanel: Rect,
    val iconSize: Int,
    val gap: Int,
    val backgroundColor: Int,
    val panelColor: Int,
)

data class Cell(val x: Int, val y: Int, val size: Int)

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object ContactSheetComposer {

    const val DEFAULT_CANVAS = 1024
    const val DEFAULT_ICON = 256
    const val DEFAULT_GAP = 24
    const val WHITE = 0xFFFFFFFF.toInt()

    /** 单排最多参考组数；超过则换到下一排。 */
    const val ROW_CAPACITY = 3

    /** 参考对上限（两排）。 */
    const val MAX_PAIRS = ROW_CAPACITY * 2

    /** 单元底色：浅灰而非纯白，保证白色图标仍有可见轮廓。 */
    const val GRAY = 0xFFE8E8E8.toInt()

    /** 田字格分隔线（比单元底色略深，让格子边界可见）。 */
    const val SEPARATOR = 0xFFD8D8D8.toInt()

    const val BLACK = 0xFF000000.toInt()

    /**
     * 计算布局：田字格铺满整张画布。
     *
     * 参考区按排排列：每排最多 [ROW_CAPACITY] 组，超过 3 组时新开一排放在下面
     * （第二排不足时补空格子，保持 3 列网格对齐）；
     * 下半区整块放目标原图。分隔线宽 [SEPARATOR_WIDTH]。
     *
     * @param pairCount 1..[MAX_PAIRS]
     * @param canvas 正方形边长；默认 1024
     * @param preferredIconSize 期望单图尺寸；格子放不下则自动收缩
     */
    fun layout(
        pairCount: Int,
        canvas: Int = DEFAULT_CANVAS,
        preferredIconSize: Int = DEFAULT_ICON,
        gap: Int = DEFAULT_GAP,
        backgroundColor: Int = SEPARATOR,
        panelColor: Int = GRAY,
    ): ContactSheetLayout {
        require(pairCount in 1..MAX_PAIRS) { "pairCount 必须在 1..$MAX_PAIRS" }
        require(canvas > 0 && preferredIconSize > 0 && gap > 0)

        val separator = SEPARATOR_WIDTH
        // 超过一排（3 组）时排成两排，网格保持 3 列对齐
        val columns = if (pairCount <= ROW_CAPACITY) pairCount else ROW_CAPACITY
        val rows = (pairCount + ROW_CAPACITY - 1) / ROW_CAPACITY

        // ---- 参考区：rows 排、每排 columns 格，格间用分隔线隔开 ----
        val cellWidth = (canvas - separator * (columns - 1)) / columns

        // 每格内并排两张图，格内留 padding 与图间 gap
        val pad = gap / 2
        val innerGap = gap / 4
        val maxIconByWidth = (cellWidth - 2 * pad - innerGap) / 2
        val iconSize = min(preferredIconSize, maxIconByWidth).coerceAtLeast(16)
        val rowHeight = iconSize + 2 * pad
        val topHeight = rowHeight * rows + separator * (rows - 1)

        val left = ArrayList<Cell>(pairCount)
        val right = ArrayList<Cell>(pairCount)
        val pairWidth = iconSize * 2 + innerGap
        for (i in 0 until pairCount) {
            val row = i / ROW_CAPACITY
            val col = i % ROW_CAPACITY
            val cellLeft = col * (cellWidth + separator)
            val cellTop = row * (rowHeight + separator)
            val groupX = cellLeft + (cellWidth - pairWidth) / 2
            // 垂直居中于所在排的格子
            val y = cellTop + (rowHeight - iconSize) / 2
            left.add(Cell(groupX, y, iconSize))
            right.add(Cell(groupX + iconSize + innerGap, y, iconSize))
        }

        // ---- 下半区：整块放目标 ----
        val bottomTop = topHeight + separator
        val bottomHeight = canvas - bottomTop
        // 目标必须完全落在下半格内（含 padding），并适当大于参考图
        val targetSize = minOf(
            bottomHeight - 2 * pad,
            canvas - 2 * pad,
        ).coerceAtLeast(iconSize)
        val targetX = (canvas - targetSize) / 2
        val targetY = bottomTop + (bottomHeight - targetSize) / 2

        // ---- 田字格单元：参考区每排每格都铺面板（空格子也铺，保持网格整齐）----
        val panels = ArrayList<Rect>(rows * columns + 1)
        for (row in 0 until rows) {
            val cellTop = row * (rowHeight + separator)
            val bottom = cellTop + rowHeight
            for (col in 0 until columns) {
                val cellLeft = col * (cellWidth + separator)
                val right0 = if (col == columns - 1) canvas else cellLeft + cellWidth
                panels.add(Rect(cellLeft, cellTop, right0, bottom))
            }
        }
        panels.add(Rect(0, bottomTop, canvas, canvas))

        return ContactSheetLayout(
            canvasWidth = canvas,
            canvasHeight = canvas,
            referenceLeft = left,
            referenceRight = right,
            target = Cell(targetX, targetY, targetSize),
            panels = panels,
            referencePanel = Rect(0, 0, canvas, topHeight),
            targetPanel = Rect(0, bottomTop, canvas, canvas),
            iconSize = iconSize,
            gap = gap,
            backgroundColor = backgroundColor,
            panelColor = panelColor,
        )
    }

    /**
     * 校验：所有图标在画布内、左右不重叠、参考区与目标区不重叠、面板铺满画布。
     */
    fun validate(layout: ContactSheetLayout): Boolean {
        val all = layout.referenceLeft + layout.referenceRight + listOf(layout.target)
        for (cell in all) {
            if (cell.x < 0 || cell.y < 0) return false
            if (cell.x + cell.size > layout.canvasWidth) return false
            if (cell.y + cell.size > layout.canvasHeight) return false
        }
        // 每组内左右两张不重叠
        for (i in layout.referenceLeft.indices) {
            val l = layout.referenceLeft[i]
            if (l.x + l.size > layout.referenceRight[i].x) return false
        }
        // 相邻组的图标不重叠（只比较同一排内；换行后的组不比较）
        for (i in 0 until layout.referenceRight.size - 1) {
            val r = layout.referenceRight[i]
            val nextLeft = layout.referenceLeft[i + 1]
            if (r.y != nextLeft.y) continue
            if (r.x + r.size > nextLeft.x) return false
        }
        // 参考区与目标区垂直分离
        val referenceBottom = (layout.referenceLeft + layout.referenceRight).maxOfOrNull { it.y + it.size } ?: 0
        if (layout.target.y < referenceBottom) return false
        // 目标图不与下半区边界冲突
        if (layout.target.y + layout.target.size > layout.canvasHeight) return false
        // 面板覆盖整张画布（田字格）：损失仅为分隔线面积
        val panelsArea = layout.panels.sumOf { it.width.toLong() * it.height.toLong() }
        val canvasArea = layout.canvasWidth.toLong() * layout.canvasHeight
        val referenceCells = layout.panels.size - 1
        val rows = if (referenceCells <= ROW_CAPACITY) {
            1
        } else {
            (referenceCells + ROW_CAPACITY - 1) / ROW_CAPACITY
        }
        if (panelsArea < canvasArea - layout.canvasWidth.toLong() * SEPARATOR_WIDTH * (rows + 2)) return false
        return true
    }

    /** 田字格分隔线宽度。 */
    const val SEPARATOR_WIDTH = 8
}
