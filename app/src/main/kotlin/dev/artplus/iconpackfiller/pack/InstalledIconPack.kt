package dev.artplus.iconpackfiller.pack

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/**
 * 已安装图标包的 drawable 通道。
 *
 * - appfilter：`createPackageContext(pkg).assets.open("appfilter.xml")`
 * - 位图：`pm.getResourcesForApplication(pkg)` + `getIdentifier(name, "drawable", pkg)`，
 *   渲染到 Bitmap（含 adaptive icon 的 XML drawable，`Drawable.draw` 会走平台渲染路径）。
 */
class InstalledIconPack(
    private val context: Context,
    val packageName: String,
) {
    private val packageContext: Context =
        context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)

    private val resources = context.packageManager.getResourcesForApplication(packageName)

    fun openAppFilter(): AppFilterDocument? {
        val stream = runCatching {
            packageContext.assets.open(APPFILTER)
        }.getOrElse {
            runCatching { packageContext.assets.open(APPFILTER_NO_EXT) }.getOrNull()
        } ?: return null
        return stream.use { AppFilterParser.parse(it) }
    }

    /**
     * 按 drawable 名渲染位图。adaptive icon XML 由平台渲染到目标尺寸。
     *
     * @param size 目标边长（px），默认 512。
     * @return 位图；资源不存在时返回 null。
     */
    fun renderDrawable(drawableName: String, size: Int = DEFAULT_SIZE): Bitmap? {
        val id = resources.getIdentifier(drawableName, "drawable", packageName)
        if (id == 0) return null
        val drawable = runCatching { resources.getDrawable(id, null) }.getOrNull() ?: return null
        // toBitmap 内部可能因缓存位图竞态失败，统一折成 null（调用方本就处理资源缺失）。
        return runCatching { drawable.toBitmap(size) }.getOrNull()
    }

    fun drawableExists(drawableName: String): Boolean =
        resources.getIdentifier(drawableName, "drawable", packageName) != 0

    /** 图标包 APK 的 sourceDir，供 ARSCLib 直读（打包场景）。 */
    fun sourceApk(): File {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        return File(info.sourceDir)
    }

    private fun Drawable.toBitmap(size: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && !bitmap.isRecycled) {
            val bitmap = bitmap
            if (bitmap.width == size && bitmap.height == size) {
                // 必须拷贝：这是 Resources 缓存的共享实例，调用方（管线）会回收
                // 拿到的位图；直接返回会毒化缓存，下次渲染同一图标时 draw()
                // 崩溃（Canvas: trying to use a recycled bitmap）。
                // 拷贝失败（极小的回收竞态）则穿透到下面的重绘分支。
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.let { return it }
            } else {
                return Bitmap.createScaledBitmap(bitmap, size, size, true)
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = bounds
        setBounds(0, 0, size, size)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    companion object {
        const val APPFILTER = "appfilter.xml"
        const val APPFILTER_NO_EXT = "appfilter"
        const val DEFAULT_SIZE = 512

        fun isIconPack(pm: PackageManager, packageName: String): Boolean {
            return runCatching {
                val context = pm.getPackageInfo(packageName, 0)
                context != null
            }.isSuccess && hasAppFilter(pm, packageName)
        }

        private fun hasAppFilter(pm: PackageManager, packageName: String): Boolean {
            return runCatching {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val assets = File(appInfo.sourceDir)
                if (!assets.isFile) return@runCatching false
                java.util.zip.ZipFile(assets).use { zip ->
                    zip.getEntry("assets/$APPFILTER") != null ||
                        zip.getEntry("assets/$APPFILTER_NO_EXT") != null
                }
            }.getOrDefault(false)
        }
    }
}