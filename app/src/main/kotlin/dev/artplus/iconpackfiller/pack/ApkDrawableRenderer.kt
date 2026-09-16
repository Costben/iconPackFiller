package dev.artplus.iconpackfiller.pack

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/**
 * 为未安装 APK 构造临时 Resources，并把任意 drawable（位图、vector、adaptive/XML）
 * 栅格化成独立 Bitmap。生命周期与对应的 IconPackSource 一致。
 */
class ApkDrawableRenderer(
    context: Context,
    apkFile: File,
    private val packageName: String,
    private val size: Int = InstalledIconPack.DEFAULT_SIZE,
) : AutoCloseable {

    private val assets = newAssetManager()
    private val resources: Resources

    init {
        check(addAssetPath(assets, apkFile.absolutePath) != 0) {
            "无法加载 APK 资源：${apkFile.name}"
        }
        resources = Resources(assets, context.resources.displayMetrics, context.resources.configuration)
    }

    fun renderDrawable(drawableName: String): Bitmap? {
        val id = resources.getIdentifier(drawableName, "drawable", packageName)
        if (id == 0) return null
        val drawable = runCatching { resources.getDrawable(id, null) }.getOrNull() ?: return null
        return runCatching { drawable.toBitmap(size) }.getOrNull()
    }

    override fun close() {
        assets.close()
    }

    private fun Drawable.toBitmap(targetSize: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && !bitmap.isRecycled) {
            val source = bitmap
            if (source.width == targetSize && source.height == targetSize) {
                source.copy(Bitmap.Config.ARGB_8888, false)?.let { return it }
            } else {
                return Bitmap.createScaledBitmap(source, targetSize, targetSize, true)
            }
        }
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val oldBounds = bounds
        try {
            setBounds(0, 0, targetSize, targetSize)
            draw(canvas)
        } finally {
            bounds = oldBounds
        }
        return output
    }

    @Suppress("DiscouragedPrivateApi")
    private fun newAssetManager(): AssetManager =
        AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    @Suppress("DiscouragedPrivateApi")
    private fun addAssetPath(assetManager: AssetManager, path: String): Int {
        val method = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
        return method.invoke(assetManager, path) as Int
    }
}
