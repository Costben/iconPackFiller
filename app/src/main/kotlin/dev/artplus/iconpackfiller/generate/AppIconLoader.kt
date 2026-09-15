package dev.artplus.iconpackfiller.generate

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process

/**
 * 应用原始图标加载（目标图 / 参考对左图）。
 */
object AppIconLoader {

    const val DEFAULT_SIZE = 512

    /**
     * 加载应用 launcher 图标并渲染为正方形位图。
     *
     * 优先 LauncherApps（返回用户实际看到的图标，含 adaptive 渲染），回退 PackageManager。
     */
    fun load(context: Context, packageName: String, activityName: String?, size: Int = DEFAULT_SIZE): Bitmap? {
        val drawable = loadDrawable(context, packageName, activityName) ?: return null
        return drawable.toBitmap(size)
    }

    private fun loadDrawable(context: Context, packageName: String, activityName: String?): Drawable? {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps != null) {
            val user = Process.myUserHandle()
            val activities = runCatching { launcherApps.getActivityList(packageName, user) }.getOrNull()
            val match = activities?.firstOrNull { activityName == null || it.componentName.className == activityName }
                ?: activities?.firstOrNull()
            if (match != null) {
                runCatching { match.getIcon(0) }.getOrNull()?.let { return it }
            }
        }
        return runCatching {
            val pm = context.packageManager
            if (activityName != null) {
                pm.getActivityIcon(android.content.ComponentName(packageName, activityName))
            } else {
                pm.getApplicationIcon(packageName)
            }
        }.getOrNull()
    }

    private fun Drawable.toBitmap(size: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && !bitmap.isRecycled) {
            val source = bitmap
            if (source.width == size && source.height == size) return source
            return Bitmap.createScaledBitmap(source, size, size, true)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = bounds
        setBounds(0, 0, size, size)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    /** 用于测试/调试：判断包名是否存在且可加载图标。 */
    fun canLoad(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_ALL) != null
    }.getOrDefault(false)
}