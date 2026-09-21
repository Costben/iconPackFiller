package dev.artplus.iconpackfiller.project.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.artplus.iconpackfiller.project.GenerationStatus
import dev.artplus.iconpackfiller.project.PackEntryKind
import dev.artplus.iconpackfiller.project.PackEntryOrigin
import dev.artplus.iconpackfiller.project.SourceKind
import dev.artplus.iconpackfiller.project.Tables

/**
 * 一个持久项目：导入或选定图标包即产生。
 *
 * `sourceApkFile` 是相对项目目录的路径（见 ProjectPaths），DB 不存绝对路径。
 */
@Entity(tableName = Tables.PROJECT)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val packLabel: String,
    val packPackage: String,
    val packVersionCode: Int,
    val sourceKind: SourceKind,
    /** 源 APK 快照内容哈希（辨识更新）。 */
    val packHash: String,
    /** 相对路径，固定为 `source.apk`。 */
    val sourceApkFile: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 用户手动指向的 Generation；null 表示无。 */
    val activeGenerationId: String? = null,
)

/**
 * 图标包对应表的一行：component ↔ drawable ↔ 包内资源文件。
 */
@Entity(
    tableName = Tables.PACK_ENTRY,
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("packageName")],
)
data class PackEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    /** appfilter 里原样的 component 字符串；扫描项为空串。 */
    val componentRaw: String,
    val packageName: String,
    val activityName: String? = null,
    val drawableName: String,
    /** 包内资源路径，如 `res/mipmap-xxhdpi/ic_launcher.png`。 */
    val resPath: String? = null,
    val kind: PackEntryKind,
    /** density 限定符，如 `xxhdpi`、`anydpi-v26`。 */
    val density: String? = null,
    /** 该 drawable 是否真实存在于包内。 */
    val inPack: Boolean,
    val origin: PackEntryOrigin,
    /** 在某次 Generation 中发现的条目；导入期条目为 null。 */
    val generationId: String? = null,
)

/**
 * 项目下的一条生成历史。
 */
@Entity(
    tableName = Tables.GENERATION,
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class GenerationEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val createdAt: Long,
    val status: GenerationStatus,
    /** 生成参数快照。 */
    val model: String? = null,
    val slotId: String? = null,
    val slotName: String? = null,
    val plannedCount: Int = 0,
    val generatedCount: Int = 0,
    val failedCount: Int = 0,
    val outputPackageName: String? = null,
    /** 相对路径，如 `generations/<id>/out.apk`。 */
    val outputApkFile: String? = null,
    val outputApkName: String? = null,
    /** 其余参数的 JSON 快照。 */
    val paramsJson: String? = null,
    /** 批次级诊断信息（标定/参考池/调用上限等）的 JSON 数组。 */
    val diagnosticsJson: String? = null,
) {
    /** 已完成比例（0..1），供进度条使用。 */
    val progress: Float
        get() {
            if (plannedCount <= 0) return if (status == GenerationStatus.RUNNING) 0f else 1f
            val done = generatedCount + failedCount
            return (done.toFloat() / plannedCount).coerceIn(0f, 1f)
        }

    val finished: Boolean get() = status.isTerminal
}

/**
 * 某次 Generation 产出的一个图标行（用于按生成筛选）。
 */
@Entity(
    tableName = Tables.GENERATION_ICON,
    foreignKeys = [
        ForeignKey(
            entity = GenerationEntity::class,
            parentColumns = ["id"],
            childColumns = ["generationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("generationId"), Index("packageName"), Index("accepted")],
)
data class GenerationIconEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val generationId: String,
    val packageName: String,
    val activityName: String? = null,
    val label: String? = null,
    val drawableName: String? = null,
    val accepted: Boolean,
    val reason: String? = null,
)

/**
 * 一次 provider 请求的落库记录（含未通过校验的）。
 */
@Entity(
    tableName = Tables.ATTEMPT,
    foreignKeys = [
        ForeignKey(
            entity = GenerationEntity::class,
            parentColumns = ["id"],
            childColumns = ["generationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("generationId"), Index("packageName")],
)
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true)     val id: Long = 0,
    val generationId: String,
    val packageName: String,
    /** 目标应用的展示名；加载失败时为 null。 */
    val label: String? = null,
    val attempt: Int,
    val accepted: Boolean,
    val reason: String? = null,
    /** 生成图相对路径。 */
    val pngFile: String? = null,
    /** 目标原图相对路径。 */
    val sourceFile: String? = null,
    val model: String? = null,
    val slotId: String? = null,
    val slotName: String? = null,
    val prompt: String? = null,
    /** 参考应用包名列表的 JSON。 */
    @ColumnInfo(name = "referencesJson") val referencesJson: String? = null,
    /** 参考对明细的 JSON。 */
    val referenceDetailsJson: String? = null,
)
