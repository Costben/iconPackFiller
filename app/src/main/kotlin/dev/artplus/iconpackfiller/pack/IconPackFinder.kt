package dev.artplus.iconpackfiller.pack

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dev.artplus.iconpackfiller.generate.AppIconLoader

/**
 * 已安装图标包发现：合并全部 launcher theme action 查询结果。
 */
object IconPackFinder {

    /** 与 [IconPackPacker.THEME_ACTIONS] 一致的查询集合。 */
    private val QUERY_ACTIONS = IconPackPacker.THEME_ACTIONS

    data class InstalledIconPackInfo(
        val packageName: String,
        val label: String,
        val versionCode: Int,
        /** 应用 launcher 图标（列表展示用；加载失败为 null）。 */
        val icon: android.graphics.Bitmap? = null,
    )

    fun find(context: Context): List<InstalledIconPackInfo> {
        val pm = context.packageManager
        val packages = LinkedHashSet<String>()
        for (action in QUERY_ACTIONS) {
            val intent = Intent(action)
            val resolved: List<ResolveInfo> = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList())
            for (info in resolved) {
                info.activityInfo?.packageName?.let { packages.add(it) }
            }
        }
        // 再按 appfilter asset 特征补充（部分启动器专用包不声明 action）
        val result = ArrayList<InstalledIconPackInfo>(packages.size)
        for (pkg in packages) {
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            if (!InstalledIconPack.isIconPack(pm, pkg)) continue
            result.add(
                InstalledIconPackInfo(
                    packageName = pkg,
                    label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: pkg,
                    versionCode = info.longVersionCode.toInt(),
                    icon = loadLauncherIcon(context, pkg),
                ),
            )
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /** 列表展示用小图标（128px 足够）。 */
    private const val ICON_PIXEL_SIZE = 128

    private fun loadLauncherIcon(context: Context, packageName: String): android.graphics.Bitmap? =
        runCatching {
            AppIconLoader.load(context, packageName, null, ICON_PIXEL_SIZE)
        }.getOrNull()
}