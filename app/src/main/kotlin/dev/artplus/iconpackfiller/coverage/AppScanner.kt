package dev.artplus.iconpackfiller.coverage

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Process
import android.os.UserManager

/**
 * 扫描本机可启动应用。
 *
 * 优先 `LauncherApps.getActivityList`（Android 5.0+，直接列出 launcher activity，
 * 不受 QUERY_ALL_PACKAGES 限制影响）。回退 `queryIntentActivities(MAIN/LAUNCHER)`。
 */
class AppScanner(private val context: Context) {

    fun scan(): List<LaunchableApp> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val fromLauncherApps = launcherApps?.let { scanViaLauncherApps(it) }.orEmpty()
        if (fromLauncherApps.isNotEmpty()) return fromLauncherApps
        return scanViaQueryIntent()
    }

    private fun scanViaLauncherApps(launcherApps: LauncherApps): List<LaunchableApp> {
        val result = ArrayList<LaunchableApp>()
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val users = runCatching { userManager?.userProfiles }.getOrNull().orEmpty()
            .ifEmpty { listOf(Process.myUserHandle()) }

        for (user in users) {
            val activities = runCatching {
                launcherApps.getActivityList(null, user)
            }.getOrDefault(emptyList())
            for (activity in activities) {
                val appInfo = activity.applicationInfo
                result.add(
                    LaunchableApp(
                        packageName = activity.componentName.packageName,
                        activityName = activity.componentName.className,
                        label = runCatching { activity.label?.toString() }.getOrNull(),
                        isSystemApp = appInfo.isSystem(),
                        hasIcon = runCatching { activity.getIcon(0) }.getOrNull() != null,
                        category = appInfo.categoryOrNull(),
                    ),
                )
            }
        }
        return aggregateAliases(result)
    }

    private fun scanViaQueryIntent(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL,
        )
        val result = ArrayList<LaunchableApp>(resolved.size)
        for (info in resolved) {
            val activity = info.activityInfo ?: continue
            val appInfo = activity.applicationInfo ?: continue
            result.add(
                LaunchableApp(
                    packageName = activity.packageName,
                    activityName = activity.name,
                    label = runCatching { info.loadLabel(context.packageManager)?.toString() }.getOrNull(),
                    isSystemApp = appInfo.isSystem(),
                    hasIcon = runCatching { info.loadIcon(context.packageManager) }.getOrNull() != null,
                    category = appInfo.categoryOrNull(),
                ),
            )
        }
        return aggregateAliases(result)
    }

    /**
     * 同包多别名：把组内条目折叠为 aliasCount，标记第一个为默认项。
     */
    private fun aggregateAliases(apps: List<LaunchableApp>): List<LaunchableApp> {
        return apps.groupBy { it.packageName.lowercase() }.flatMap { (_, group) ->
            if (group.size <= 1) {
                group
            } else {
                group.mapIndexed { index, app ->
                    app.copy(aliasCount = group.size, isDefaultAlias = index == 0)
                }
            }
        }
    }

    private fun ApplicationInfo.isSystem(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    private fun ApplicationInfo.categoryOrNull(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) category else null
}