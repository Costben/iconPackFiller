package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.pack.AppFilterDocument

/**
 * 把 appfilter 的 component↔drawable 与包内资源文件配对成 [PackEntrySpec]。
 *
 * 一行 = 一个 appfilter `<item>`；[resourcePath] 解析 drawable 的包内文件，
 * 解析不到时按 [exists] 判定是否在包内（kind/density 记 OTHER/null）。
 * 未被 appfilter 引用的资源不在此登记，保证行数 == appfilter item 数。
 */
object PackEntryBuilder {

    fun build(
        document: AppFilterDocument,
        resourcePath: (String) -> String?,
        exists: (String) -> Boolean = { false },
    ): List<PackEntrySpec> = document.items.map { item ->
        val path = resourcePath(item.drawableName)
        PackEntrySpec(
            componentRaw = item.rawComponent,
            packageName = item.component.packageName,
            activityName = item.component.activityName,
            drawableName = item.drawableName,
            resPath = path,
            kind = ResPath.kind(path),
            density = ResPath.density(path),
            inPack = path != null || exists(item.drawableName),
            origin = PackEntryOrigin.APPFILTER,
        )
    }
}
