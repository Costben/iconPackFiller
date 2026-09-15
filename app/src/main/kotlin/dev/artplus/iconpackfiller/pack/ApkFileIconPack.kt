package dev.artplus.iconpackfiller.pack

import com.reandroid.apk.ApkModule
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import java.io.File

/**
 * 未安装图标包 APK 的 drawable 通道（ARSCLib 直读）。
 *
 * 不依赖 PackageManager / Android 运行时，可在桌面 JVM 单测中验证。
 * 返回的 `drawableName -> 资源文件路径` 映射；位图字节由 [readDrawableBytes] 读取。
 */
class ApkFileIconPack private constructor(
    private val module: ApkModule,
    private val packageBlock: PackageBlock,
) : AutoCloseable {

    /** drawable 名 -> 最佳匹配的 zip 内路径（优先高密度，其次默认 config）。 */
    val drawables: Map<String, String> by lazy { collectDrawables() }

    fun readDrawableBytes(drawableName: String): ByteArray? {
        val path = drawables[drawableName] ?: return null
        val source: InputSource = module.getInputSource(path) ?: return null
        return source.openStream().use { it.readBytes() }
    }

    /** 读取 assets/appfilter.xml 文本；不存在返回 null。 */
    fun readAppFilterText(): String? {
        val source = module.getInputSource("assets/${InstalledIconPack.APPFILTER}")
            ?: module.getInputSource("assets/${InstalledIconPack.APPFILTER_NO_EXT}")
            ?: return null
        return source.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 读取 assets 下任意文件。 */
    fun readAsset(path: String): ByteArray? {
        val source = module.getInputSource("assets/$path") ?: return null
        return source.openStream().use { it.readBytes() }
    }

    override fun close() {
        runCatching { module.close() }
    }

    private fun collectDrawables(): Map<String, String> {
        val result = HashMap<String, String>()
        val iterator = packageBlock.getResources("drawable")
        while (iterator.hasNext()) {
            val resource = iterator.next()
            val name = resource.name ?: continue
            val best = pickBestEntry(resource) ?: continue
            val value = best.valueAsString ?: continue
            if (value.isBlank()) continue
            if (best.valueType != ValueType.STRING) continue
            result.putIfAbsent(name, value)
        }
        return result
    }

    /**
     * 优先返回高密度（xxxhdpi > xxhdpi > xhdpi > hdpi > mdpi > default）的 entry。
     * 密度比较按 qualifiers 字符串包含判断（够用且稳定，避免依赖 ARSCLib 内部常量）。
     */
    private fun pickBestEntry(resource: com.reandroid.arsc.model.ResourceEntry): Entry? {
        var best: Entry? = null
        var bestRank = -1
        for (entry in resource) {
            val qualifiers = (entry.parent as? TypeBlock)?.qualifiers ?: ""
            val rank = densityRank(qualifiers)
            if (rank > bestRank) {
                bestRank = rank
                best = entry
            }
        }
        return best
    }

    private fun densityRank(qualifiers: String): Int = when {
        qualifiers.contains("anydpi") -> 7
        qualifiers.contains("xxxhdpi") -> 6
        qualifiers.contains("xxhdpi") -> 5
        qualifiers.contains("xhdpi") -> 4
        qualifiers.contains("hdpi") -> 3
        qualifiers.contains("mdpi") -> 2
        qualifiers.contains("ldpi") -> 1
        else -> 0
    }

    companion object {
        fun open(apkFile: File): ApkFileIconPack {
            val module = ApkModule.loadApkFile(apkFile)
            val table: TableBlock = module.tableBlock
                ?: error("APK 不含 resources.arsc: ${apkFile.name}")
            val packageBlock = table.packages.nextOrNull()
                ?: error("resources.arsc 不含 package: ${apkFile.name}")
            return ApkFileIconPack(module, packageBlock)
        }
    }
}

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null