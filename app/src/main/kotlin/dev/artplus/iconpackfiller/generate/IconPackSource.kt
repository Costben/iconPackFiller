package dev.artplus.iconpackfiller.generate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.artplus.iconpackfiller.pack.ApkFileIconPack
import dev.artplus.iconpackfiller.pack.AppFilterDocument
import dev.artplus.iconpackfiller.pack.AppFilterParser
import dev.artplus.iconpackfiller.pack.InstalledIconPack
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
                versionCode = info?.longVersionCode?.toInt() ?: 0,
            )
        }

        fun open(apkFile: File): IconPackSource? {
            val pack = ApkFileIconPack.open(apkFile)
            val text = pack.readAppFilterText()
                ?: run { pack.close(); return null }
            val document = runCatching {
                AppFilterParser.parse(text.byteInputStream(Charsets.UTF_8))
            }.getOrElse { pack.close(); return null }
            val drawables = document.items.map { it.drawableName }.filter { it in pack.drawables }.toSet()
            val (pkg, versionCode) = readManifestInfo(apkFile)
            return ApkSource(
                pack = pack,
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
    override val packageName: String get() = pack.packageName
    override val sourceApk: File get() = pack.sourceApk()
    override fun renderDrawable(drawableName: String): Bitmap? = pack.renderDrawable(drawableName)
    override fun close() = Unit
}

private class ApkSource(
    private val pack: ApkFileIconPack,
    private val apkFile: File,
    override val document: AppFilterDocument,
    override val availableDrawables: Set<String>,
    override val packageName: String,
    override val versionCode: Int,
) : IconPackSource {
    override val sourceApk: File get() = apkFile
    override fun renderDrawable(drawableName: String): Bitmap? {
        val bytes = pack.readDrawableBytes(drawableName) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun close() = pack.close()
}