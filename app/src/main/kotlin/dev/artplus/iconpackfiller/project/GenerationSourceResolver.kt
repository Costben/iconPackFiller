package dev.artplus.iconpackfiller.project

import java.io.File

/**
 * 「本次生成用哪个图标包源」的纯解析（无 Android 依赖，JVM 可测）。
 *
 * 来源优先级：**项目快照 > 已安装包 > 导入的 APK 文件**。
 *
 * 项目快照路径的意义（Requirement）：源包被卸载 / 更新 / 删除后，项目目录内的
 * `source.apk` 仍可用于重新生成 / 重打包；该路径必须挂回同一
 * [Resolved.reuseProjectId]，不得因来源不同而新建重复项目。
 */
object GenerationSourceResolver {

    /**
     * 已解析出的生成来源。
     *
     * [installedPackage] 与 [apkFile] 恰有一个非空，满足
     * `FillerOrchestrator.Input` 的互斥约束。
     */
    data class Resolved(
        /** 已安装图标包包名；与 [apkFile] 互斥。 */
        val installedPackage: String?,
        /** 本地 APK 文件（含项目快照）；与 [installedPackage] 互斥。 */
        val apkFile: File?,
        /** 非空表示复用该既有项目（快照来源），不新建。 */
        val reuseProjectId: String?,
        /** 展示用图标包名。 */
        val label: String,
    ) {
        /** 活体来源对应的 [SourceKind]；快照来源复用既有项目，返回 null。 */
        val liveSourceKind: SourceKind?
            get() = when {
                reuseProjectId != null -> null
                installedPackage != null -> SourceKind.INSTALLED
                apkFile != null -> SourceKind.APK_FILE
                else -> null
            }
    }

    /**
     * @param snapshotProjectId 从项目页发起时的项目 id；null 表示活体选择路径
     * @param snapshotApk 项目目录内的 `source.apk` 快照；文件不存在视为不可用
     * @param snapshotLabel 项目展示名
     * @param installedPackage 当前选中的已安装图标包包名
     * @param installedLabel 已安装包展示名
     * @param apkFile 当前导入 APK 的缓存副本
     * @param apkLabel 导入 APK 展示名
     * @return 解析结果；三个来源都不可用时返回 null
     */
    fun resolve(
        snapshotProjectId: String? = null,
        snapshotApk: File? = null,
        snapshotLabel: String? = null,
        installedPackage: String? = null,
        installedLabel: String? = null,
        apkFile: File? = null,
        apkLabel: String? = null,
    ): Resolved? {
        if (snapshotProjectId != null && snapshotApk?.exists() == true) {
            return Resolved(
                installedPackage = null,
                apkFile = snapshotApk,
                reuseProjectId = snapshotProjectId,
                label = snapshotLabel.orDefaultLabel(),
            )
        }
        if (!installedPackage.isNullOrBlank()) {
            return Resolved(
                installedPackage = installedPackage,
                apkFile = null,
                reuseProjectId = null,
                label = installedLabel.orDefaultLabel(),
            )
        }
        if (apkFile != null) {
            return Resolved(
                installedPackage = null,
                apkFile = apkFile,
                reuseProjectId = null,
                label = apkLabel.orDefaultLabel(),
            )
        }
        return null
    }

    private fun String?.orDefaultLabel(): String = this?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL

    private const val DEFAULT_LABEL = "图标包"
}
