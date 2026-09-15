package dev.artplus.iconpackfiller.generate

import android.content.Context
import android.graphics.Bitmap
import dev.artplus.iconpackfiller.coverage.CoverageReport
import dev.artplus.iconpackfiller.pack.IconStats
import dev.artplus.iconpackfiller.reference.PerceptualHash
import dev.artplus.iconpackfiller.reference.ReferencePair

/**
 * 参考池构建：从图标包 + 覆盖率报告生成「本机原图 ↔ 包内重绘」参考对。
 *
 * 完整任务（[FillerOrchestrator]）与单图标重生成（[SingleIconRegenerator]）共用，
 * 保证「重新取样」用的池子与整包运行的来源一致。
 */
object ReferencePoolBuilder {

    /**
     * 遍历已覆盖应用，渲染包内 drawable 并量化统计。
     *
     * 顺带输出「包内重绘 vs 源图」的感知距离标定（诊断），用于校准校验阈值。
     */
    fun build(
        context: Context,
        pack: IconPackSource,
        report: CoverageReport,
        onDiagnostic: (String) -> Unit = {},
    ): List<ReferencePair> {
        val pairs = ArrayList<ReferencePair>()
        val referenceDistances = ArrayList<Float>()
        for (covered in report.matched) {
            val original = AppIconLoader.load(context, covered.app.packageName, covered.app.activityName)
            if (original == null) {
                onDiagnostic("候选 ${covered.app.packageName}: 原图加载失败")
                continue
            }
            val packBitmap = pack.renderDrawable(covered.drawableName)
            if (packBitmap == null) {
                onDiagnostic("候选 ${covered.app.packageName}: drawable ${covered.drawableName} 渲染失败")
                continue
            }
            pairs.add(
                ReferencePair(
                    packageName = covered.app.packageName,
                    label = covered.app.label,
                    category = covered.app.category,
                    originalStats = original.stats(),
                    packStats = packBitmap.stats(),
                    packDrawableName = covered.drawableName,
                    activityName = covered.app.activityName,
                ),
            )
            // 标定输出：图标包**自带**的重绘版与源图的感知距离，
            // 是「正确重绘」的距离基准，用于校准 OutputValidator.maxHashDistance
            referenceDistances.add(
                PerceptualHash.distance(
                    original.hashOf(),
                    packBitmap.hashOf(),
                ),
            )
        }
        if (referenceDistances.isNotEmpty()) {
            val sorted = referenceDistances.sorted()
            onDiagnostic(
                "参考对感知距离标定（包内重绘 vs 源图，n=${sorted.size}）：" +
                    "min=${"%.2f".format(sorted.first())} " +
                    "中位=${"%.2f".format(sorted[sorted.size / 2])} " +
                    "max=${"%.2f".format(sorted.last())}",
            )
        }
        return pairs
    }

    /** 目标应用的统计信息（重试时重选参考用）；图标加载失败返回 null。 */
    fun loadTargetPair(context: Context, app: dev.artplus.iconpackfiller.coverage.LaunchableApp): ReferencePair? {
        val bitmap = AppIconLoader.load(context, app.packageName, app.activityName) ?: return null
        return ReferencePair(
            packageName = app.packageName,
            label = app.label,
            category = app.category,
            originalStats = bitmap.stats(),
            packStats = bitmap.stats(),
        )
    }

    /** 按参考对加载位图（源图 + 包内重绘）；任一缺失返回 null。 */
    fun loadReferenceBitmaps(
        context: Context,
        pack: IconPackSource,
        reference: ReferencePair,
        onDiagnostic: (String) -> Unit = {},
    ): Pair<Bitmap, Bitmap>? = loadPair(
        context = context,
        pack = pack,
        packageName = reference.packageName,
        activityName = reference.activityName,
        preferredDrawable = reference.packDrawableName,
        onDiagnostic = onDiagnostic,
    )

    /**
     * 按记录的快照恢复参考对（加载位图并统计），用于「保持原参考重新请求」。
     */
    fun pairFromSnapshot(
        context: Context,
        pack: IconPackSource,
        snapshot: ReferenceSnapshot,
        onDiagnostic: (String) -> Unit = {},
    ): ReferencePair? {
        val (original, packBitmap) = loadPair(
            context = context,
            pack = pack,
            packageName = snapshot.packageName,
            activityName = snapshot.activityName,
            preferredDrawable = snapshot.drawableName,
            onDiagnostic = onDiagnostic,
        ) ?: return null
        return ReferencePair(
            packageName = snapshot.packageName,
            label = snapshot.label,
            category = null,
            originalStats = original.stats(),
            packStats = packBitmap.stats(),
            packDrawableName = snapshot.drawableName,
            activityName = snapshot.activityName,
        )
    }

    private fun loadPair(
        context: Context,
        pack: IconPackSource,
        packageName: String,
        activityName: String?,
        preferredDrawable: String?,
        onDiagnostic: (String) -> Unit,
    ): Pair<Bitmap, Bitmap>? {
        val original = AppIconLoader.load(context, packageName, activityName)
        if (original == null) {
            onDiagnostic("参考 $packageName: 原图加载失败")
            return null
        }
        // 优先用扫描阶段已解析的 drawable 名；缺省时再用带 activity 的精确匹配兜底
        val drawableName = preferredDrawable
            ?: pack.document.match(packageName, activityName)?.drawableName
        if (drawableName == null) {
            onDiagnostic("参考 $packageName: appfilter 未命中")
            return null
        }
        val packBitmap = pack.renderDrawable(drawableName)
        if (packBitmap == null) {
            onDiagnostic("参考 $packageName: drawable $drawableName 渲染失败")
            return null
        }
        return original to packBitmap
    }

    fun Bitmap.stats(): IconStats {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return IconStats.fromPixels(pixels, width, height)
    }

    fun Bitmap.hashOf(): Long {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return PerceptualHash.hashOf(pixels, width, height)
    }
}
