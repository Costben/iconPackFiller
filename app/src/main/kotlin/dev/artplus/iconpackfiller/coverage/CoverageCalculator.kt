package dev.artplus.iconpackfiller.coverage

import dev.artplus.iconpackfiller.pack.AppFilterDocument
import dev.artplus.iconpackfiller.pack.ComponentKey

/**
 * 覆盖规则配置。
 */
data class CoverageRules(
    /** 排除系统应用。 */
    val excludeSystemApps: Boolean = true,
    /** 排除无法加载图标的应用。 */
    val excludeNoIcon: Boolean = true,
    /** 用户黑名单（包名，小写）。 */
    val blacklist: Set<String> = emptySet(),
    /** 同包多别名只取默认项。 */
    val onlyDefaultAlias: Boolean = true,
    /** 排除 appfilter 引用了不存在 drawable 的条目。 */
    val excludeMissingDrawable: Boolean = true,
    /** 排除已禁用应用。 */
    val excludeDisabled: Boolean = true,
) {
    companion object {
        val DEFAULT = CoverageRules()
    }
}

/**
 * 覆盖差集计算。纯函数，与 Android 解耦，可 JVM 单测。
 */
object CoverageCalculator {

    fun compute(
        apps: List<LaunchableApp>,
        appFilter: AppFilterDocument,
        /** 图标包内实际存在的 drawable 名集合。 */
        availableDrawables: Set<String>,
        rules: CoverageRules = CoverageRules.DEFAULT,
        /** 已禁用包名（小写）。 */
        disabledPackages: Set<String> = emptySet(),
    ): CoverageReport {
        val matched = ArrayList<CoveredApp>()
        val unmatched = ArrayList<LaunchableApp>()
        val excluded = ArrayList<ExcludedApp>()
        val blacklist = rules.blacklist.map { it.lowercase() }.toSet()
        val disabled = disabledPackages.map { it.lowercase() }.toSet()

        val byPackage = apps.groupBy { it.packageName.lowercase() }

        for ((pkgLower, group) in byPackage) {
            val candidates = if (rules.onlyDefaultAlias && group.size > 1) {
                val default = group.firstOrNull { it.isDefaultAlias }
                if (default != null) listOf(default) else group
            } else {
                group
            }
            val nonDefaults = group - candidates.toSet()

            for (app in candidates) {
                val reason = excludeReason(app, pkgLower, appFilter, availableDrawables, rules, blacklist, disabled)
                if (reason != null) {
                    excluded.add(ExcludedApp(app, reason))
                    continue
                }
                val hit = appFilter.matchDetailed(app.packageName, app.activityName)
                if (hit != null) {
                    matched.add(
                        CoveredApp(
                            app = app,
                            drawableName = hit.item.drawableName,
                            matchKind = hit.kind,
                        ),
                    )
                } else {
                    unmatched.add(app)
                }
            }

            for (app in nonDefaults) {
                excluded.add(ExcludedApp(app, ExcludeReason.NON_DEFAULT_ALIAS))
            }
        }

        return CoverageReport(
            matched = matched.sortedBy { it.app.packageName },
            unmatched = unmatched.sortedBy { app -> app.packageName },
            excluded = excluded.sortedBy { it.app.packageName },
        )
    }

    private fun excludeReason(
        app: LaunchableApp,
        pkgLower: String,
        appFilter: AppFilterDocument,
        availableDrawables: Set<String>,
        rules: CoverageRules,
        blacklist: Set<String>,
        disabled: Set<String>,
    ): ExcludeReason? {
        if (rules.excludeDisabled && pkgLower in disabled) return ExcludeReason.DISABLED
        if (rules.excludeSystemApps && app.isSystemApp) return ExcludeReason.SYSTEM_APP
        if (pkgLower in blacklist) return ExcludeReason.USER_BLACKLIST
        if (rules.excludeNoIcon && app.hasIcon == false) return ExcludeReason.NO_ICON
        if (rules.excludeMissingDrawable) {
            val hit = appFilter.match(app.packageName, app.activityName)
            if (hit != null && hit.drawableName !in availableDrawables) {
                return ExcludeReason.MISSING_DRAWABLE
            }
        }
        return null
    }
}