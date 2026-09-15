package dev.artplus.iconpackfiller.reference

import dev.artplus.iconpackfiller.pack.IconStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReferencePairSelectorTest {

    private fun pair(
        pkg: String,
        category: Int? = null,
        dominant: Int = 0xFF000000.toInt(),
    ) = ReferencePair(
        packageName = pkg,
        label = pkg,
        category = category,
        originalStats = IconStats(512, 512, 0f, dominant, false),
        packStats = IconStats(512, 512, 0f, dominant, false),
    )

    private val target = pair("com.target", category = 1, dominant = 0xFFFF0000.toInt())

    @Test
    fun `picks requested count`() {
        val candidates = listOf(pair("a"), pair("b"), pair("c"), pair("d"))
        val picked = ReferencePairSelector.select(candidates, target, ReferencePairSelector.Config(pairCount = 3, randomSeed = 42))
        assertEquals(3, picked.size)
    }

    @Test
    fun `same category ranks first`() {
        val candidates = listOf(
            pair("com.other", category = 5, dominant = 0xFFFF0000.toInt()),
            pair("com.same", category = 1, dominant = 0xFF00FF00.toInt()),
        )
        val picked = ReferencePairSelector.select(
            candidates,
            target,
            ReferencePairSelector.Config(pairCount = 1, randomSeed = 42),
        )
        assertEquals("com.same", picked[0].packageName)
    }

    @Test
    fun `closer color ranks higher within same category`() {
        val candidates = listOf(
            pair("com.far", category = 1, dominant = 0xFF0000FF.toInt()),
            pair("com.near", category = 1, dominant = 0xFFEE0000.toInt()),
        )
        val picked = ReferencePairSelector.select(
            candidates,
            target,
            ReferencePairSelector.Config(pairCount = 1, randomSeed = 42),
        )
        assertEquals("com.near", picked[0].packageName)
    }

    @Test
    fun `never selects target itself`() {
        val candidates = listOf(target, pair("a"), pair("b"))
        val picked = ReferencePairSelector.select(candidates, target, ReferencePairSelector.Config(pairCount = 2, randomSeed = 1))
        assertTrue(picked.none { it.packageName == target.packageName })
    }

    @Test
    fun `never repeats same package`() {
        val candidates = listOf(pair("a"), pair("b"))
        val picked = ReferencePairSelector.select(candidates, target, ReferencePairSelector.Config(pairCount = 3, randomSeed = 1))
        assertEquals(picked.map { it.packageName }.distinct().size, picked.size)
    }

    @Test
    fun `empty candidates yields empty selection`() {
        assertTrue(ReferencePairSelector.select(emptyList(), target).isEmpty())
    }

    @Test
    fun `fewer candidates than requested returns all`() {
        val candidates = listOf(pair("a"), pair("b"))
        val picked = ReferencePairSelector.select(candidates, target, ReferencePairSelector.Config(pairCount = 3, randomSeed = 1))
        assertEquals(2, picked.size)
    }

    @Test
    fun `deterministic with seed`() {
        val candidates = listOf(pair("a"), pair("b"), pair("c"))
        val config = ReferencePairSelector.Config(pairCount = 2, randomSeed = 7)
        val first = ReferencePairSelector.select(candidates, target, config).map { it.packageName }
        val second = ReferencePairSelector.select(candidates, target, config).map { it.packageName }
        assertEquals(first, second)
    }
}

class ContactSheetComposerTest {

    @Test
    fun `layout single pair stays inside canvas`() {
        val layout = ContactSheetComposer.layout(pairCount = 1)
        assertTrue(ContactSheetComposer.validate(layout))
        assertEquals(1024, layout.canvasWidth)
        assertEquals(1, layout.referenceLeft.size)
        assertEquals(1, layout.referenceRight.size)
    }

    @Test
    fun `layout three pairs stays inside canvas`() {
        val layout = ContactSheetComposer.layout(pairCount = 3)
        assertTrue(ContactSheetComposer.validate(layout))
        assertEquals(3, layout.referenceLeft.size)
    }

    @Test
    fun `reference row above target`() {
        val layout = ContactSheetComposer.layout(pairCount = 2)
        val referenceBottom = layout.referenceLeft.maxOf { it.y + it.size }
        assertTrue(layout.target.y >= referenceBottom)
    }

    @Test
    fun `left and right within group do not overlap`() {
        val layout = ContactSheetComposer.layout(pairCount = 2)
        for (i in 0 until 2) {
            val l = layout.referenceLeft[i]
            val r = layout.referenceRight[i]
            assertTrue(l.x + l.size <= r.x, "pair $i overlap")
        }
    }

    @Test
    fun `groups do not overlap`() {
        val layout = ContactSheetComposer.layout(pairCount = 3)
        for (i in 0 until 2) {
            val right = layout.referenceRight[i]
            val nextLeft = layout.referenceLeft[i + 1]
            assertTrue(right.x + right.size <= nextLeft.x, "group $i overlap")
        }
    }

    @Test
    fun `golden snapshot two pairs`() {
        val layout = ContactSheetComposer.layout(pairCount = 2)
        val snapshot = buildString {
            appendLine("canvas=${layout.canvasWidth}x${layout.canvasHeight}")
            appendLine("iconSize=${layout.iconSize} gap=${layout.gap}")
            layout.referenceLeft.forEachIndexed { i, c -> appendLine("L$i=${c.x},${c.y},${c.size}") }
            layout.referenceRight.forEachIndexed { i, c -> appendLine("R$i=${c.x},${c.y},${c.size}") }
            appendLine("T=${layout.target.x},${layout.target.y},${layout.target.size}")
        }
        // 上排高度 = 图标 + 2*pad = 263，目标吃掉剩余空间 = 729（是参考图的 3 倍）
        val expected = """
            canvas=1024x1024
            iconSize=239 gap=24
            L0=12,12,239
            L1=528,12,239
            R0=257,12,239
            R1=773,12,239
            T=147,283,729
        """.trimIndent() + "\n"
        assertEquals(expected, snapshot)
    }

    /**
     * 旧实现把目标固定压在下半区，2 组时有 355px（35%）画布是死白。
     * 现在内容整体居中、目标吃掉纵向余量。
     */
    @Test
    fun `no large dead band between reference row and target`() {
        for (pairs in 1..6) {
            val layout = ContactSheetComposer.layout(pairCount = pairs)
            val referenceBottom = layout.referenceLeft.maxOf { it.y + it.size }
            val band = layout.target.y - referenceBottom
            assertTrue(
                band < ContactSheetComposer.DEFAULT_CANVAS / 4,
                "pairs=$pairs 参考行与目标间距 $band 过大",
            )
        }
    }

    @Test
    fun `cells are gray so white icons keep an outline`() {
        val layout = ContactSheetComposer.layout(pairCount = 2)
        assertEquals(ContactSheetComposer.GRAY, layout.panelColor)
        assertTrue(layout.backgroundColor != layout.panelColor, "分隔线色需与单元底色可区分")
    }

    /**
     * 田字格必须铺满整张画布：上排 pairCount 格 + 下方整块。
     *
     * 铺满 = 画布面积 - 分隔线面积(横线满宽 + 上排竖线)，误差 0。
     */
    @Test
    fun `grid tiles the whole canvas`() {
        val separator = ContactSheetComposer.SEPARATOR_WIDTH.toLong()
        for (pairs in 1..3) {
            val layout = ContactSheetComposer.layout(pairCount = pairs)
            assertEquals(pairs + 1, layout.panels.size)
            val area = layout.panels.sumOf { it.width.toLong() * it.height.toLong() }
            val canvasArea = layout.canvasWidth.toLong() * layout.canvasHeight
            val topHeight = layout.referencePanel.bottom.toLong()
            val loss = layout.canvasWidth * separator + (pairs - 1) * topHeight * separator
            assertEquals(
                canvasArea - loss,
                area,
                "pairs=$pairs 面板未铺满：$area / ${canvasArea - loss}",
            )
            assertTrue(ContactSheetComposer.validate(layout))
        }
    }

    @Test
    fun `target stays inside bottom half`() {
        for (pairs in 1..6) {
            val layout = ContactSheetComposer.layout(pairCount = pairs)
            val bottomTop = layout.targetPanel.top
            assertTrue(
                layout.target.y >= bottomTop,
                "pairs=$pairs 目标越出下半格（y=${layout.target.y} < $bottomTop）",
            )
            assertTrue(
                layout.target.y + layout.target.size <= layout.canvasHeight,
                "pairs=$pairs 目标超出画布底部",
            )
        }
    }

    @Test
    fun `target is larger than reference icons`() {
        for (pairs in 1..6) {
            val layout = ContactSheetComposer.layout(pairCount = pairs)
            assertTrue(layout.target.size > layout.iconSize, "pairs=$pairs 目标应大于参考图")
        }
    }

    @Test
    fun `more than three pairs wrap to a second row`() {
        for (pairs in 4..6) {
            val layout = ContactSheetComposer.layout(pairCount = pairs)
            assertTrue(ContactSheetComposer.validate(layout), "pairs=$pairs 布局校验失败")
            assertEquals(pairs, layout.referenceLeft.size)
            // 前三组在第一排，后面的换到第二排
            val row1Y = layout.referenceLeft[0].y
            for (i in 0 until 3) {
                assertEquals(row1Y, layout.referenceLeft[i].y, "第一排第 $i 组应同行")
            }
            assertTrue(
                layout.referenceLeft[3].y > row1Y,
                "pairs=$pairs 第 4 组应换到下一排",
            )
            // 第二排仍在参考区内、不与目标重叠
            val referenceBottom = layout.referenceLeft.maxOf { it.y + it.size }
            assertTrue(referenceBottom <= layout.referencePanel.bottom)
            assertTrue(layout.target.y >= referenceBottom)
        }
    }

    /**
     * 两排网格同样铺满参考区：损失 = 上下排间隙与排内竖缝。
     */
    @Test
    fun `two-row grid tiles the whole canvas`() {
        val separator = ContactSheetComposer.SEPARATOR_WIDTH.toLong()
        for (pairs in 4..6) {
            val layout = ContactSheetComposer.layout(pairCount = pairs)
            // 两排 × 3 列（不足补空格子）+ 目标
            assertEquals(7, layout.panels.size)
            val area = layout.panels.sumOf { it.width.toLong() * it.height.toLong() }
            val canvasArea = layout.canvasWidth.toLong() * layout.canvasHeight
            val rowHeight = layout.panels[0].height.toLong()
            // 横缝：排间 1 条 + 参考/目标间 1 条；竖缝：每排 2 条
            val loss = 2 * layout.canvasWidth * separator + 4 * rowHeight * separator
            assertEquals(
                canvasArea - loss,
                area,
                "pairs=$pairs 两排面板未铺满：$area / ${canvasArea - loss}",
            )
            assertTrue(ContactSheetComposer.validate(layout))
        }
    }

    @Test
    fun `panels separate reference and target areas`() {
        val layout = ContactSheetComposer.layout(pairCount = 2)
        assertTrue(layout.referencePanel.bottom <= layout.targetPanel.top, "两块面板必须分开")
        assertTrue(ContactSheetComposer.validate(layout))
    }

    @Test
    fun `three pairs auto shrink to fit`() {
        val layout = ContactSheetComposer.layout(pairCount = 3)
        assertTrue(layout.iconSize < ContactSheetComposer.DEFAULT_ICON)
        assertTrue(ContactSheetComposer.validate(layout))
    }

    @Test
    fun `single pair keeps preferred icon size`() {
        val layout = ContactSheetComposer.layout(pairCount = 1)
        assertEquals(ContactSheetComposer.DEFAULT_ICON, layout.iconSize)
    }

    @Test
    fun `rejects invalid pair count`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ContactSheetComposer.layout(pairCount = 7)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ContactSheetComposer.layout(pairCount = 0)
        }
    }
}