package dev.artplus.iconpackfiller.generate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.PackageInfoCompat
import dev.artplus.iconpackfiller.pack.ApkFileIconPack
import dev.artplus.iconpackfiller.pack.ApkDrawableRenderer
import dev.artplus.iconpackfiller.pack.AppFilterDocument
import dev.artplus.iconpackfiller.pack.AppFilterParser
import dev.artplus.iconpackfiller.pack.InstalledIconPack
import dev.artplus.iconpackfiller.pack.PackResourceFile
import dev.artplus.iconpackfiller.pack.bestResourcePath
import java.io.File

/**
 * 图标包读取抽象：已安装包 / 本地 APK 文件统一接口。
 *
 * 持有资源句柄，须在会话结束后 [close]。
 */
sealed interface IconPackSource : AutoCloseable {

    val packageName: String
    val versionCode: Int
    val sourceApk: File
    val document: AppFilterDocument
    val availableDrawables: Set<String>

    /** 包内全部候选图标资源文件（含未被 appfilter 引用的）。 */
    val resources: List<PackResourceFile>

    /** drawable 名 -> 最佳（最高密度）包内资源路径；不存在返回 null。 */
    fun resourcePath(drawableName: String): String? = resources.bestResourcePath(drawableName)

    fun renderDrawable(drawableName: String): Bitmap?

    companion object {
        fun open(context: Context, installedPackage: String): IconPackSource? {
            val pack = InstalledIconPack(context, installedPackage)
            val document = pack.openAppFilter() ?: return null
            val info = runCatching { context.packageManager.getPackageInfo(installedPackage, 0) }.getOrNull()
            val drawables = document.items.map { it.drawableName }.filter { pack.drawableExists(it) }.toSet()
            return InstalledSource(
                pack = pack,
                document = document,
                availableDrawables = drawables,
                versionCode = info?.let {
                    PackageInfoCompat.getLongVersionCode(it)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                } ?: 0,
            )
        }

        /** 本地 APK 的纯读取入口，适用于 JVM 元数据测试和位图资源。 */
        fun open(apkFile: File): IconPackSource? = openLocal(apkFile, null)

        /** 本地 APK 的 Android 入口，额外支持 vector/adaptive/XML drawable 栅格化。 */
        fun open(context: Context, apkFile: File): IconPackSource? = openLocal(apkFile, context)

        private fun openLocal(apkFile: File, context: Context?): IconPackSource? {
            val pack = runCatching { ApkFileIconPack.open(apkFile) }.getOrNull() ?: return null
            val text = pack.readAppFilterText()
                ?: run { pack.close(); return null }
            val document = runCatching {
                AppFilterParser.parse(text.byteInputStream(Charsets.UTF_8))
            }.getOrElse { pack.close(); return null }
            val drawables = document.items.map { it.drawableName }.filter { it in pack.drawables }.toSet()
            val (pkg, versionCode) = readManifestInfo(apkFile)
            val renderer = context?.let {
                runCatching { ApkDrawableRenderer(it, apkFile, pkg) }.getOrNull()
            }
            return ApkSource(
                pack = pack,
                renderer = renderer,
                apkFile = apkFile,
                document = document,
                availableDrawables = drawables,
                packageName = pkg,
                versionCode = versionCode,
            )
        }

        private fun readManifestInfo(apkFile: File): Pair<String, Int> {
            com.reandroid.apk.ApkModule.loadApkFile(apkFile).use { module ->
                val manifest = module.androidManifest
                return (manifest?.packageName ?: "unknown") to (manifest?.versionCode ?: 0)
            }
        }
    }
}

private class InstalledSource(
    private val pack: InstalledIconPack,
    override val document: AppFilterDocument,
    override val availableDrawables: Set<String>,
    override val versionCode: Int,
) : IconPackSource {

    /** 已安装包的资源枚举：直接读源 APK 的 zip 目录；失败退回空表。 */
    private val filePackLazy = lazy {
        runCatching { ApkFileIconPack.open(pack.sourceApk()) }.getOrNull()
    }

    private val filePack: ApkFileIconPack? get() = filePackLazy.value

    override val packageName: String get() = pack.packageName
    override val sourceApk: File get() = pack.sourceApk()
    override val resources: List<PackResourceFile> get() = filePack?.resourceFiles.orEmpty()
    override fun resourcePath(drawableName: String): String? =
        filePack?.resourceFiles?.bestResourcePath(drawableName)

    override fun renderDrawable(drawableName: String): Bitmap? = pack.renderDrawable(drawableName)

    override fun close() {
        // 只关真正打开过的枚举句柄，避免 close 时反向触发一次无谓的 APK 打开。
        if (filePackLazy.isInitialized()) filePackLazy.value?.close()
    }
}

private class ApkSource(
    private val pack: ApkFileIconPack,
    private val renderer: ApkDrawableRenderer?,
    private val apkFile: File,
    override val document: AppFilterDocument,
    override val availableDrawables: Set<String>,
    override val packageName: String,
    override val versionCode: Int,
) : IconPackSource {
    override val sourceApk: File get() = apkFile
    override val resources: List<PackResourceFile> get() = pack.resourceFiles

    override fun renderDrawable(drawableName: String): Bitmap? {
        val bytes = pack.readDrawableBytes(drawableName)
        if (bytes != null) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        return renderer?.renderDrawable(drawableName)
    }

    override fun close() {
        renderer?.close()
        pack.close()
    }
}
