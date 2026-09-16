package dev.artplus.iconpackfiller.pack

import java.util.zip.ZipFile

/**
 * 探测图标包内图标资源目录的约定（density + 像素边长）。
 *
 * 为什么需要：注入必须落在**与原包相同的 density 目录**，否则
 * 启动器按 density 缩放时新图标会比原图标大/小一截。
 * Aura 用 `drawable-nodpi-v4/`（192px），fixture 用 `drawable-xxxhdpi-v4/`。
 */
object DrawableDirectoryDetector {

    /** 默认回退（原实现硬编码值）。 */
    const val FALLBACK_DIRECTORY = "drawable-xxxhdpi-v4"
    const val FALLBACK_DENSITY = "xxxhdpi"

    data class Convention(
        /** 形如 `res/drawable-nodpi-v4`。 */
        val directory: String,
        /** 形如 `nodpi` / `xxxhdpi`；用于 ARSCLib `getOrCreateTypeBlock`；无 density 后缀为 null。 */
        val density: String?,
        /** 包内图标像素边长（多数值）；采样失败为 null。 */
        val pixelSize: Int?,
        /** 采样到的图标数量。 */
        val sampleCount: Int,
    ) {
        /** 新建资源在 APK 内的完整路径。 */
        fun resPath(drawableName: String): String = "$directory/$drawableName.png"
    }

    /**
     * 从 APK 的中央目录统计 `res/drawable*` 下的资源分布，取条目最多的目录作为约定。
     *
     * 只读 zip 目录 + PNG 头，不解析 resources.arsc，因此纯 JVM 可测。
     */
    fun detect(apk: java.io.File): Convention {
        if (!apk.isFile) return fallback()
        val counts = HashMap<String, Int>()
        // 每个目录最多采样这么多个 PNG 头，避免大包全量解压
        val sizeSamples = HashMap<String, MutableList<Int>>()
        val maxSamplesPerDirectory = 12
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (!name.startsWith("res/drawable") || !name.endsWith(".png")) continue
                    val dir = name.substringBeforeLast('/')
                    counts[dir] = (counts[dir] ?: 0) + 1

                    val samples = sizeSamples.getOrPut(dir) { ArrayList() }
                    if (samples.size < maxSamplesPerDirectory && entry.size > 24) {
                        runCatching {
                            zip.getInputStream(entry).use { stream ->
                                pngPixelSize(stream.readAtMost(PNG_HEADER_SIZE))
                            }
                        }.getOrNull()?.let { samples.add(it) }
                    }
                }
            }
        }.getOrNull()
        if (counts.isEmpty()) return fallback()

        val best = counts.entries.maxByOrNull { it.value } ?: return fallback()
        val size = sizeSamples[best.key]
            .orEmpty()
            .filter { it > 0 }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        return Convention(
            directory = best.key,
            density = densityOf(best.key),
            pixelSize = size,
            sampleCount = best.value,
        )
    }

    /** `res/drawable-nodpi-v4` -> `nodpi`；无 density 后缀返回 null。 */
    fun densityOf(directory: String): String? {
        val name = directory.substringAfterLast('/')
        val stripped = name.removePrefix("drawable")
        if (!stripped.startsWith("-")) return null
        return stripped.removePrefix("-").removeSuffix("-v4").takeIf { it.isNotBlank() }
    }

    /**
     * 从 PNG 头读 IHDR 宽高（字节 16-23，大端）。
     */
    fun pngPixelSize(head: ByteArray): Int? {
        if (head.size < 24) return null
        // 校验 PNG 签名
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in signature.indices) if (head[i] != signature[i]) return null
        val width = readInt(head, 16)
        val height = readInt(head, 20)
        if (width != height || width <= 0) return null
        return width
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** API 26 兼容的 InputStream 前缀读取；EOF 时返回实际读取字节。 */
    private fun java.io.InputStream.readAtMost(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) break
            if (read == 0) continue
            offset += read
        }
        return if (offset == count) bytes else bytes.copyOf(offset)
    }

    private fun fallback() = Convention(
        directory = "res/$FALLBACK_DIRECTORY",
        density = FALLBACK_DENSITY,
        pixelSize = null,
        sampleCount = 0,
    )

    private const val PNG_HEADER_SIZE = 24
}
