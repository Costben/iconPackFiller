package dev.artplus.iconpackfiller.project.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.artplus.iconpackfiller.project.GenerationStatus
import kotlinx.coroutines.flow.Flow

/**
 * 一次 Generation 里的图标行，连同其所属 Generation 的元信息。
 * 用于「同一目标跨 Generation 对比」。
 */
data class GenerationIconMatch(
    @Embedded val icon: GenerationIconEntity,
    val generationCreatedAt: Long,
    val generationStatus: GenerationStatus,
    val generationModel: String?,
    val generationOutputApkFile: String?,
)

/**
 * 项目持久层的全部读写入口。
 *
 * 方法签名是 Phase 2+ 与 UI 的契约；若 Room/KSP 不可用改为手写 SQLiteOpenHelper，
 * 实现类需保持本接口不变。
 */
@Dao
interface ProjectDao {

    // ---- Project ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    /** 更新既有项目。不能复用 [upsertProject]：REPLACE 会删旧行并触发子表级联删除。 */
    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.PROJECT} WHERE id = :projectId")
    suspend fun project(projectId: String): ProjectEntity?

    @Query("SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.PROJECT} ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.PROJECT} ORDER BY updatedAt DESC")
    suspend fun allProjects(): List<ProjectEntity>

    @Query(
        "SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.PROJECT} " +
            "WHERE packPackage = :packPackage ORDER BY updatedAt DESC",
    )
    suspend fun projectsByPack(packPackage: String): List<ProjectEntity>

    @Query(
        "UPDATE ${dev.artplus.iconpackfiller.project.Tables.PROJECT} " +
            "SET activeGenerationId = :generationId, updatedAt = :updatedAt WHERE id = :projectId",
    )
    suspend fun setActiveGeneration(projectId: String, generationId: String?, updatedAt: Long)

    @Query("DELETE FROM ${dev.artplus.iconpackfiller.project.Tables.PROJECT} WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)

    // ---- PackEntry ----

    @Insert
    suspend fun insertPackEntries(entries: List<PackEntryEntity>)

    /** 清空某项目的对应表（重新导入前调用，保证替换语义）。 */
    @Query("DELETE FROM ${dev.artplus.iconpackfiller.project.Tables.PACK_ENTRY} WHERE projectId = :projectId")
    suspend fun deletePackEntries(projectId: String)

    @Query(
        "SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.PACK_ENTRY} " +
            "WHERE projectId = :projectId ORDER BY packageName, activityName, drawableName",
    )
    fun observePackEntries(projectId: String): Flow<List<PackEntryEntity>>

    @Query("SELECT COUNT(*) FROM ${dev.artplus.iconpackfiller.project.Tables.PACK_ENTRY} WHERE projectId = :projectId")
    suspend fun packEntryCount(projectId: String): Int

    // ---- Generation ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGeneration(generation: GenerationEntity)

    @Update
    suspend fun updateGeneration(generation: GenerationEntity)

    @Query("SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION} WHERE id = :generationId")
    suspend fun generation(generationId: String): GenerationEntity?

    @Query(
        "SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION} " +
            "WHERE projectId = :projectId ORDER BY createdAt DESC",
    )
    fun observeGenerations(projectId: String): Flow<List<GenerationEntity>>

    /** 全部生成历史，新在前（跨项目的历史列表用）。 */
    @Query("SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION} ORDER BY createdAt DESC")
    suspend fun allGenerations(): List<GenerationEntity>

    @Query("DELETE FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION} WHERE id = :generationId")
    suspend fun deleteGeneration(generationId: String)

    /** 启动清理：把上次进程遗留的 RUNNING 记录标为 INTERRUPTED，返回受影响行数。 */
    @Query(
        "UPDATE ${dev.artplus.iconpackfiller.project.Tables.GENERATION} " +
            "SET status = :interrupted WHERE status = :running",
    )
    suspend fun markRunningGenerationsInterrupted(running: String, interrupted: String): Int

    // ---- GenerationIcon ----

    @Insert
    suspend fun insertGenerationIcons(icons: List<GenerationIconEntity>)

    /** 清空某次生成的图标行（重新落库前调用，保证替换语义）。 */
    @Query(
        "DELETE FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION_ICON} " +
            "WHERE generationId = :generationId",
    )
    suspend fun deleteGenerationIcons(generationId: String)

    /** 按目标更新图标行的通过状态（重新生成后刷新）。 */
    @Query(
        "UPDATE ${dev.artplus.iconpackfiller.project.Tables.GENERATION_ICON} " +
            "SET accepted = :accepted, reason = :reason " +
            "WHERE generationId = :generationId AND packageName = :packageName",
    )
    suspend fun updateGenerationIconOutcome(
        generationId: String,
        packageName: String,
        accepted: Boolean,
        reason: String?,
    )

    /**
     * 按包名 / 组件 / accepted 状态筛选某次 Generation 的图标。
     * 传 null 表示该维度不过滤；结果按包名、组件排序。
     */
    @Query(
        "SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION_ICON} " +
            "WHERE generationId = :generationId " +
            "AND (:packageName IS NULL OR packageName = :packageName) " +
            "AND (:activityName IS NULL OR activityName = :activityName) " +
            "AND (:accepted IS NULL OR accepted = :accepted) " +
            "ORDER BY packageName, activityName",
    )
    suspend fun iconsByGeneration(
        generationId: String,
        packageName: String?,
        activityName: String?,
        accepted: Boolean?,
    ): List<GenerationIconEntity>

    /**
     * 按包名 / 组件 / accepted 状态筛选某次 Generation 的图标。
     * 传 null 表示该维度不过滤。
     */
    @Query(
        "SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION_ICON} " +
            "WHERE generationId = :generationId " +
            "AND (:packageName IS NULL OR packageName = :packageName) " +
            "AND (:activityName IS NULL OR activityName = :activityName) " +
            "AND (:accepted IS NULL OR accepted = :accepted) " +
            "ORDER BY packageName, activityName",
    )
    fun observeIconsByGeneration(
        generationId: String,
        packageName: String?,
        activityName: String?,
        accepted: Boolean?,
    ): Flow<List<GenerationIconEntity>>

    // ---- Attempt ----

    @Insert
    suspend fun insertAttempt(attempt: AttemptEntity): Long

    @Query(
        "SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.ATTEMPT} " +
            "WHERE generationId = :generationId ORDER BY packageName, attempt",
    )
    suspend fun attemptsByGeneration(generationId: String): List<AttemptEntity>

    /** 全部请求记录（历史列表投影用）。 */
    @Query("SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.ATTEMPT} ORDER BY packageName, attempt")
    suspend fun allAttempts(): List<AttemptEntity>

    @Query(
        "SELECT * FROM ${dev.artplus.iconpackfiller.project.Tables.ATTEMPT} " +
            "WHERE generationId = :generationId AND packageName = :packageName ORDER BY attempt",
    )
    suspend fun attemptsForTarget(generationId: String, packageName: String): List<AttemptEntity>

    // ---- 跨 Generation 对比 ----

    /**
     * 同一目标（包名 + 组件）在某项目下跨 Generation 的图标序列，新生成在前。
     */
    @Query(
        "SELECT gi.*, g.createdAt AS generationCreatedAt, g.status AS generationStatus, " +
            "g.model AS generationModel, g.outputApkFile AS generationOutputApkFile " +
            "FROM ${dev.artplus.iconpackfiller.project.Tables.GENERATION_ICON} gi " +
            "JOIN ${dev.artplus.iconpackfiller.project.Tables.GENERATION} g ON g.id = gi.generationId " +
            "WHERE g.projectId = :projectId AND gi.packageName = :packageName " +
            "AND (:activityName IS NULL OR gi.activityName = :activityName) " +
            "ORDER BY g.createdAt DESC",
    )
    fun observeCompareTarget(
        projectId: String,
        packageName: String,
        activityName: String?,
    ): Flow<List<GenerationIconMatch>>
}
