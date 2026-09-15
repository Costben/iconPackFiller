package dev.artplus.iconpackfiller.reference

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptTemplateTest {

    /**
     * 提示词里的面板数量必须与实际拼接图一致：
     * 文字写 2 格而图上放 3 格时，模型会把多出来的那格当成"目标"，必错。
     */
    @Test
    fun `panel count matches the sheet layout`() {
        for (count in 1..3) {
            val prompt = PromptTemplate.contactSheetPrompt(count)
            assertTrue(
                prompt.contains("Reference strip (top): $count side-by-side panels"),
                "pairCount=$count 的提示词未描述对应格数",
            )
        }
    }

    /**
     * 超过 3 组时参考区是两排；提示词必须写明「两排、从上到下读」，
     * 否则模型可能把第二排的参考当成目标的一部分。
     */
    @Test
    fun `more than three pairs are described as two rows`() {
        for (count in 4..6) {
            val prompt = PromptTemplate.contactSheetPrompt(count)
            assertTrue(
                prompt.contains("Reference strip (top): $count panels in two rows"),
                "pairCount=$count 的提示词未描述两排布局",
            )
            assertTrue(
                prompt.contains("top to bottom"),
                "pairCount=$count 的提示词未说明阅读顺序",
            )
        }
    }

    /**
     * 目标格比参考格大约一倍，必须显式声明"不要因此放大图标"，
     * 否则模型会按面板尺寸猜图标大小，输出偏大。
     */
    @Test
    fun `prompt forbids scaling up along with the target panel`() {
        val prompt = PromptTemplate.contactSheetPrompt(2)
        assertTrue(prompt.contains("do not make the icon larger just because the target panel is bigger"))
        assertTrue(prompt.contains("Treat the panels, the gray gaps and the larger target panel as layout only"))
    }

    /**
     * 参考对两侧现在都按内容裁边放大，提示词必须说明这一点，
     * 否则模型可能把「右侧风格图留白多」误读成画风的一部分。
     */
    @Test
    fun `prompt says both sides are cropped to the artwork`() {
        val prompt = PromptTemplate.contactSheetPrompt(2)
        assertTrue(prompt.contains("cropped to the icon artwork and scaled to the same size"))
    }

    /**
     * 不透明模型需要高对比键色底方便抠图：调用方按目标图标三选一注入，
     * 图标内禁用该键色，并点名禁止棋盘格（provider 忽略 background 参数）。
     */
    @Test
    fun `prompt uses the selected key color`() {
        for (key in KeyColor.entries) {
            val prompt = PromptTemplate.contactSheetPrompt(1, key)
            assertTrue(prompt.contains("#${key.hex}"), "key=$key 缺少色值")
            assertTrue(
                prompt.contains("do NOT use ${key.displayName}"),
                "key=$key 缺少图标内禁用声明",
            )
            assertTrue(prompt.contains("Do NOT draw a checkerboard pattern"))
        }
    }

    @Test
    fun `prompt defaults to green key`() {
        assertTrue(PromptTemplate.contactSheetPrompt(1).contains("#00FF00"))
    }

    /**
     * 透明直出版：不让模型画任何背景（免抠图），也不提键色；
     * 棋盘格/阴影禁令保留（模型爱用棋盘格假冒透明）。
     */
    @Test
    fun `transparent variant requests no background at all`() {
        val prompt = PromptTemplate.contactSheetPrompt(2, KeyColor.RED, transparentBackground = true)
        assertTrue(prompt.contains("fully transparent"), "必须要求透明底")
        assertTrue(prompt.contains("do NOT paint"), "必须禁止绘制背景")
        assertFalse(prompt.contains("chroma-key"), "透明版不应出现键色底")
        assertFalse(prompt.contains("#FF0000"), "透明版不应出现键色值")
        assertTrue(prompt.contains("Do NOT draw a checkerboard pattern"))
        assertTrue(prompt.contains("no shadow"))
    }

    @Test
    fun `chroma variant is unchanged when transparency is off`() {
        val prompt = PromptTemplate.contactSheetPrompt(1, KeyColor.BLUE, transparentBackground = false)
        assertTrue(prompt.contains("#0000FF"))
        assertTrue(prompt.contains("do NOT use blue"))
    }

    /**
     * 软阴影在深色壁纸上会留下"抠图不干净"的浅灰光晕（本端只能吃掉键色，
     * 阴影灰吃不掉），因此源头上必须禁止模型画任何阴影/辉光。
     */
    @Test
    fun `prompt forbids any shadow or glow`() {
        val prompt = PromptTemplate.contactSheetPrompt(2)
        assertTrue(prompt.contains("no shadow"), "必须明确禁止阴影")
        assertFalse(prompt.contains("soft shadow"), "不得再允许软阴影例外")
    }

    @Test
    fun `style notes append measured palette and padding`() {
        val base = PromptTemplate.contactSheetPrompt(2)
        val withNotes = PromptTemplate.withStyleNotes(
            base = base,
            dominantColors = listOf(0xFF32A852.toInt()),
            paddingRatio = 0.18f,
        )
        assertTrue(withNotes.startsWith(base))
        assertTrue(withNotes.contains("#32A852"))
        assertTrue(withNotes.contains("18%"))
    }

    @Test
    fun `style notes omit empty measurements`() {
        val base = PromptTemplate.contactSheetPrompt(2)
        val withNotes = PromptTemplate.withStyleNotes(base, emptyList(), null)
        // 基础提示词本身含 "padding ratio" 一词，因此只在补充段落里判断
        val notes = withNotes.removePrefix(base)
        assertFalse(notes.contains("Dominant palette"))
        assertFalse(notes.contains("Typical subject padding ratio"))
    }

    @Test
    fun `legacy accessor keeps the two-panel wording`() {
        assertEquals(
            PromptTemplate.contactSheetPrompt(2),
            PromptTemplate.CONTACT_SHEET_PROMPT,
        )
    }
}
