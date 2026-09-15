package dev.artplus.iconpackfiller.reference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect as AndroidRect

/**
 * ContactSheet 实际合成（Android Bitmap）。
 * 布局由 [ContactSheetComposer.layout] 计算，保证可单测。
 */
object BitmapContactSheet {

    /**
     * @param pairs 参考对（原图, 包内风格图），1..[ContactSheetComposer.MAX_PAIRS] 组
     *   （超过 3 组时自动排成两排）
     * @param target 目标原图
     * @param trimContent 是否按内容裁边后放大铺满格子（见 [ContentTrimmer]）。
     *   参考对两侧与目标图都裁，让同一组里左右两张的视觉尺寸一致——
     *   否则右侧风格图看起来格外小，模型会把「缩小 + 留白」学成画风的一部分。
     */
    fun compose(
        pairs: List<Pair<Bitmap, Bitmap>>,
        target: Bitmap,
        canvasSize: Int = ContactSheetComposer.DEFAULT_CANVAS,
        iconSize: Int = ContactSheetComposer.DEFAULT_ICON,
        backgroundColor: Int = ContactSheetComposer.SEPARATOR,
        trimContent: Boolean = true,
    ): Bitmap {
        require(pairs.isNotEmpty() && pairs.size <= ContactSheetComposer.MAX_PAIRS) {
            "参考对数量必须 1..${ContactSheetComposer.MAX_PAIRS}"
        }
        val layout = ContactSheetComposer.layout(
            pairCount = pairs.size,
            canvas = canvasSize,
            preferredIconSize = iconSize,
            backgroundColor = backgroundColor,
            panelColor = ContactSheetComposer.GRAY,
        )
        val output = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // 底色即分隔线色；田字格单元用浅灰铺满，缝隙自然形成分隔
        canvas.drawColor(backgroundColor)
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layout.panelColor }
        for (panel in layout.panels) {
            canvas.drawRect(panel.toRectF(), panelPaint)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (i in pairs.indices) {
            drawInto(canvas, pairs[i].first, layout.referenceLeft[i], paint, trimContent)
            drawInto(canvas, pairs[i].second, layout.referenceRight[i], paint, trimContent)
        }
        drawInto(canvas, target, layout.target, paint, trimContent)
        return output
    }

    private fun Rect.toRectF(): RectF = RectF(
        left.toFloat(),
        top.toFloat(),
        right.toFloat(),
        bottom.toFloat(),
    )

    private fun drawInto(
        canvas: Canvas,
        bitmap: Bitmap,
        cell: Cell,
        paint: Paint,
        trimContent: Boolean,
    ) {
        val box = if (trimContent) bitmap.contentBoxOrNull() else null
        val src = if (box != null) {
            AndroidRect(box.left, box.top, box.right, box.bottom)
        } else {
            AndroidRect(0, 0, bitmap.width, bitmap.height)
        }
        // 等比放大到格子能容纳的最大尺寸并居中：内容可能是非正方形
        // （宽 logo、更高的图形），直接拉伸到正方形格子会变形。
        val scale = minOf(
            cell.size.toFloat() / src.width(),
            cell.size.toFloat() / src.height(),
        )
        val drawWidth = src.width() * scale
        val drawHeight = src.height() * scale
        val left = cell.x + (cell.size - drawWidth) / 2f
        val top = cell.y + (cell.size - drawHeight) / 2f
        val dst = RectF(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    /**
     * 内容包围盒；整图即内容或不宜裁剪时返回 null。
     *
     * 位图按 [ContentTrimmer] 的规则判定：透明角走 alpha、不透明同色角走颜色
     * （且限制裁量），避免把满幅纯色底误裁掉。
     */
    private fun Bitmap.contentBoxOrNull(): ContentTrimmer.Box? {
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return ContentTrimmer.contentBox(pixels, width, height)
    }
}
