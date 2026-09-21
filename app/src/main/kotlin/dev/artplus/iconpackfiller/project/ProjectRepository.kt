package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.generate.GenerationAttempt
import dev.artplus.iconpackfiller.project.db.AttemptEntity
import dev.artplus.iconpackfiller.project.db.GenerationEntity
import dev.artplus.iconpackfiller.project.db.GenerationIconEntity
import dev.artplus.iconpackfiller.project.db.GenerationIconMatch
import dev.artplus.iconpackfiller.project.db.PackEntryEntity
import dev.artplus.iconpackfiller.project.db.ProjectDao
import dev.artplus.iconpackfiller.project.db.ProjectEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 项目 / 生成历史的唯一持久化入口（Room 之上）。
 *
 * 取代旧批次存储：
 * - 元数据落 Room 关系表；
 * - 图 / APK 落 `filesDir/projects/<projectId>/generations/<generationId>/`（见 [ProjectPaths]）；
 * - 对 UI 暴露 [GenerationRecord] 投影，字段沿用旧批次模型，行为不变。
 *
 * DAO 可注入，便于 JVM 单测用假实现验证映射与文件布局。
 */
class ProjectRepository(
    private val dao: ProjectDao,
    private val paths: ProjectPaths,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 建项/落库的执行线程；默认 IO，单测可注入以便断言不占用调用线程。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 对应表分批事务的每批条数。 */
    private val entryBatchSize: Int = DEFAULT_ENTRY_BATCH_SIZE,
) {

    // ---- Project ----

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    suspend fun project(projectId: String): ProjectEntity? = dao.project(projectId)

    /**
     * 复用同源（包名 + 内容哈希）的既有项目；没有则新建并把源 APK 复制快照进项目目录。
     *
     * 「导入即建项目」走 [importProject]；这里是其底层步骤，也供 run 兜底复用项目。
     */
    suspend fun ensureProject(
        sourceKind: SourceKind,
        packLabel: String,
        packPackage: String,
        packVersionCode: Int,
        packHash: String,
        sourceApk: File?,
        now: Long = clock(),
    ): ProjectEntity {
        if (packPackage.isNotEmpty() && packHash.isNotEmpty()) {
            dao.projectsByPack(packPackage)
                .firstOrNull { it.sourceKind == sourceKind && it.packHash == packHash }
                ?.let { existing ->
                    val updated = existing.copy(
                        packLabel = packLabel,
                        packVersionCode = packVersionCode,
                        updatedAt = now,
                    )
                    if (updated != existing) dao.updateProject(updated)
                    return updated
                }
        }
        val id = newId("proj", now)
        sourceApk?.takeIf { it.exists() }?.let { source ->
            runCatching {
                val target = paths.sourceApk(id)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
        val entity = ProjectEntity(
            id = id,
            packLabel = packLabel,
            packPackage = packPackage,
            packVersionCode = packVersionCode,
            sourceKind = sourceKind,
            packHash = packHash,
            sourceApkFile = ProjectPaths.SOURCE_APK_RELATIVE,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertProject(entity)
        return entity
    }

    /**
     * 「导入或选定图标包即建项目」的正式入口：复用/新建项目后，把对应表落库。
     *
     * 全程在 [ioDispatcher] 上执行，条目按 [entryBatchSize] 分批（每批一个事务），
     * 大包（数万条）也不会阻塞调用线程。替换语义：先清该项目旧表再写入。
     */
    suspend fun importProject(
        sourceKind: SourceKind,
        packLabel: String,
        packPackage: String,
        packVersionCode: Int,
        packHash: String,
        sourceApk: File?,
        entries: List<PackEntrySpec>,
        now: Long = clock(),
    ): ProjectEntity = withContext(ioDispatcher) {
        val project = ensureProject(
            sourceKind = sourceKind,
            packLabel = packLabel,
            packPackage = packPackage,
            packVersionCode = packVersionCode,
            packHash = packHash,
            sourceApk = sourceApk,
            now = now,
        )
        dao.deletePackEntries(project.id)
        entries.chunked(entryBatchSize).forEach { chunk ->
            dao.insertPackEntries(chunk.map { it.toEntity(project.id) })
        }
        project
    }

    /**
     * 解析一次生成挂载的项目。
     *
     * - [reuseProjectId] 非空（项目快照来源）：只取既有项目，**不新建、不复制、不改元数据**，
     *   确保源包卸载 / 更新 / 删除后重打包仍挂回同一 Project；
     * - 否则按元数据 [ensureProject]（活体来源，同源复用或新建）。
     *
     * 找不到 [reuseProjectId] 对应项目时返回 null，由调用方报错。
     */
    suspend fun resolveProjectForRun(
        reuseProjectId: String?,
        sourceKind: SourceKind,
        packLabel: String,
        packPackage: String,
        packVersionCode: Int,
        packHash: String,
        sourceApk: File?,
        now: Long = clock(),
    ): ProjectEntity? {
        if (reuseProjectId != null) return dao.project(reuseProjectId)
        return ensureProject(
            sourceKind = sourceKind,
            packLabel = packLabel,
            packPackage = packPackage,
            packVersionCode = packVersionCode,
            packHash = packHash,
            sourceApk = sourceApk,
            now = now,
        )
    }

    /** 项目目录内的源 APK 快照文件；不存在返回 null。 */
    fun sourceApk(projectId: String): File? =
        paths.sourceApk(projectId).takeIf { it.exists() }

    suspend fun setActiveGeneration(projectId: String, generationId: String?) =
        dao.setActiveGeneration(projectId, generationId, clock())

    /** 项目对应表条数（项目详情概览用）。 */
    suspend fun packEntryCount(projectId: String): Int = dao.packEntryCount(projectId)

    /** 删除项目及其全部生成（FK 级联）与磁盘目录。 */
    suspend fun deleteProject(projectId: String) {
        dao.deleteProject(projectId)
        paths.projectDir(projectId).deleteRecursively()
    }

    // ---- Generation ----

    suspend fun createGeneration(
        projectId: String,
        status: GenerationStatus = GenerationStatus.RUNNING,
        model: String? = null,
        slotId: String? = null,
        slotName: String? = null,
        paramsJson: String? = null,
        now: Long = clock(),
    ): GenerationEntity {
        val entity = GenerationEntity(
            id = newId("gen", now),
            projectId = projectId,
            createdAt = now,
            status = status,
            model = model,
            slotId = slotId,
            slotName = slotName,
            paramsJson = paramsJson,
        )
        paths.ensureGenerationDirs(projectId, entity.id)
        dao.upsertGeneration(entity)
        return entity
    }

    /** 用 [dao.updateGeneration]（@Update）更新，避免 REPLACE 触发子表级联删除。 */
    suspend fun updateGeneration(generation: GenerationEntity) = dao.updateGeneration(generation)

    suspend fun generation(generationId: String): GenerationEntity? = dao.generation(generationId)

    fun observeGenerations(projectId: String): Flow<List<GenerationEntity>> =
        dao.observeGenerations(projectId)

    /** 启动清理：把上次进程遗留的 RUNNING 标为 INTERRUPTED，返回受影响行数。 */
    suspend fun markRunningInterrupted(): Int =
        dao.markRunningGenerationsInterrupted(
            GenerationStatus.RUNNING.name,
            GenerationStatus.INTERRUPTED.name,
        )

    /** 删除一条生成及其磁盘目录（Attempt / Icon 行由 FK 级联）。 */
    suspend fun deleteGeneration(generationId: String): Boolean {
        val generation = dao.generation(generationId) ?: return false
        dao.deleteGeneration(generationId)
        paths.generationDir(generation.projectId, generationId).deleteRecursively()
        return true
    }

    // ---- Attempt ----

    /**
     * 落盘一次 provider 请求的产物（生成图 + 目标原图）并写 Attempt 行。
     *
     * 由编排器的 `onAttempt` 增量调用：一旦写盘，用户取消或进程被杀也保得住已生成的图。
     * 写文件失败不抛异常（图丢了不该让整条生成崩掉），只是没有文件名。
     */
    suspend fun persistAttempt(
        projectId: String,
        generationId: String,
        attempt: GenerationAttempt,
    ): AttemptEntity {
        val attDir = paths.attemptDir(projectId, generationId).apply { mkdirs() }
        val base = ProjectPaths.safeName(attempt.packageName) + "-" + attempt.attempt
        val pngName = "$base.png"
        val sourceName = ProjectPaths.safeName(attempt.packageName) + "-src.png"

        val pngWritten = runCatching { File(attDir, pngName).writeBytes(attempt.pngBytes) }.isSuccess
        val sourceWritten = attempt.sourcePngBytes?.let { bytes ->
            runCatching { File(attDir, sourceName).writeBytes(bytes) }.isSuccess
        } ?: false

        val entity = AttemptEntity(
            generationId = generationId,
            packageName = attempt.packageName,
            label = attempt.label,
            attempt = attempt.attempt,
            accepted = attempt.accepted,
            reason = attempt.reason,
            pngFile = pngName.takeIf { pngWritten },
            sourceFile = sourceName.takeIf { sourceWritten },
            model = attempt.provenance.model,
            slotId = attempt.provenance.slotId,
            slotName = attempt.provenance.slotName,
            prompt = attempt.prompt,
            referencesJson = GenerationJsonCodec.encodeReferences(attempt.references),
            referenceDetailsJson = GenerationJsonCodec.encodeReferenceDetails(attempt.referenceDetails),
        )
        dao.insertAttempt(entity)
        return entity
    }

    suspend fun attempts(generationId: String): List<AttemptEntity> =
        dao.attemptsByGeneration(generationId)

    // ---- GenerationIcon ----

    /**
     * 落盘一次生成的图标行（每个目标一行）。
     *
     * 替换语义：先清本次生成的旧行再写入，重复调用不会翻倍。
     * 由外层在生成结束后调用，编排器核心不改。
     */
    suspend fun persistGenerationIcons(generationId: String, icons: List<GenerationIconSpec>) {
        dao.deleteGenerationIcons(generationId)
        if (icons.isEmpty()) return
        icons.chunked(entryBatchSize).forEach { chunk ->
            dao.insertGenerationIcons(chunk.map { it.toEntity(generationId) })
        }
    }

    /**
     * 选中某次 Generation → 按包名 / 组件 / accepted 状态筛选图标。
     * 传 null 表示该维度不过滤；结果按包名、组件排序。
     */
    suspend fun icons(
        generationId: String,
        packageName: String? = null,
        activityName: String? = null,
        accepted: Boolean? = null,
    ): List<GenerationIconEntity> =
        dao.iconsByGeneration(generationId, packageName, activityName, accepted)

    /**
     * [icons] 的响应式版本：筛选维度不变，供 UI / VM 直接 collect。
     */
    fun observeIcons(
        generationId: String,
        packageName: String? = null,
        activityName: String? = null,
        accepted: Boolean? = null,
    ): Flow<List<GenerationIconEntity>> =
        dao.observeIconsByGeneration(generationId, packageName, activityName, accepted)

    /**
     * 同一目标（包名 + 组件）在某项目下跨 Generation 的图标序列，新生成在前。
     * [activityName] 为 null 时返回该包名下的全部组件。
     */
    fun observeCompareTarget(
        projectId: String,
        packageName: String,
        activityName: String? = null,
    ): Flow<List<GenerationIconMatch>> =
        dao.observeCompareTarget(projectId, packageName, activityName)

    /**
     * 重新生成后刷新某目标图标行的通过状态：只要有一次请求通过即为 accepted。
     * 该目标没有请求记录时不动。
     */
    suspend fun refreshIconOutcome(generationId: String, packageName: String) {
        val attempts = dao.attemptsForTarget(generationId, packageName)
        if (attempts.isEmpty()) return
        val accepted = attempts.any { it.accepted }
        dao.updateGenerationIconOutcome(
            generationId = generationId,
            packageName = packageName,
            accepted = accepted,
            reason = if (accepted) null else attempts.last().reason,
        )
    }

    /**
     * 没有完整计划信息时（取消 / 失败）按已落盘的 Attempt 归并出图标行，
     * 让每条终态 Generation 都能查到本次观察到的图标。
     */
    suspend fun rebuildIconsFromAttempts(generationId: String) {
        val outcomes = dao.attemptsByGeneration(generationId).map {
            GenerationIconOutcome(
                packageName = it.packageName,
                label = it.label,
                attempt = it.attempt,
                accepted = it.accepted,
                reason = it.reason,
            )
        }
        persistGenerationIcons(generationId, GenerationIconBuilder.fromOutcomes(outcomes))
    }

    /** 把签名 APK 复制进生成目录。失败返回 null（不影响其他内容）。 */
    suspend fun persistApk(projectId: String, generationId: String, apk: File?): String? {
        if (apk == null || !apk.exists()) return null
        return runCatching {
            val target = paths.outputApk(projectId, generationId)
            target.parentFile?.mkdirs()
            apk.copyTo(target, overwrite = true)
            ProjectPaths.OUT_APK
        }.getOrNull()
    }

    // ---- UI 投影 ----

    /** 全部生成历史（跨项目），创建时间倒序。 */
    suspend fun generationRecords(): List<GenerationRecord> {
        val projects = dao.allProjects().associateBy { it.id }
        val attempts = dao.allAttempts().groupBy { it.generationId }
        return dao.allGenerations().mapNotNull { generation ->
            projects[generation.projectId]?.let { project ->
                generation.toRecord(project, attempts[generation.id].orEmpty())
            }
        }
    }

    suspend fun generationRecord(generationId: String): GenerationRecord? {
        val generation = dao.generation(generationId) ?: return null
        val project = dao.project(generation.projectId) ?: return null
        return generation.toRecord(project, dao.attemptsByGeneration(generationId))
    }

    // ---- 文件访问 ----

    fun attemptFile(projectId: String, generationId: String, fileName: String?): File? {
        if (fileName.isNullOrEmpty()) return null
        return File(paths.attemptDir(projectId, generationId), fileName).takeIf { it.exists() }
    }

    fun outputApk(projectId: String, generationId: String, fileName: String?): File? {
        if (fileName.isNullOrEmpty()) return null
        return File(paths.generationDir(projectId, generationId), fileName).takeIf { it.exists() }
    }

    companion object {
        /** 对应表每批写入条数（大包分批事务的批大小）。 */
        const val DEFAULT_ENTRY_BATCH_SIZE = 1_000

        /** `proj-<millis>-<8hex>` / `gen-<millis>-<8hex>`。 */
        fun newId(prefix: String, timestamp: Long): String =
            "$prefix-$timestamp-" + UUID.randomUUID().toString().take(8)
    }
}

private fun GenerationIconSpec.toEntity(generationId: String): GenerationIconEntity =
    GenerationIconEntity(
        generationId = generationId,
        packageName = packageName,
        activityName = activityName,
        label = label,
        drawableName = drawableName,
        accepted = accepted,
        reason = reason,
    )

private fun PackEntrySpec.toEntity(projectId: String): PackEntryEntity = PackEntryEntity(
    projectId = projectId,
    componentRaw = componentRaw,
    packageName = packageName,
    activityName = activityName,
    drawableName = drawableName,
    resPath = resPath,
    kind = kind,
    density = density,
    inPack = inPack,
    origin = origin,
)

private fun GenerationEntity.toRecord(
    project: ProjectEntity,
    attempts: List<AttemptEntity>,
): GenerationRecord = GenerationRecord(
    id = id,
    projectId = projectId,
    packLabel = project.packLabel,
    packPackage = project.packPackage,
    createdAt = createdAt,
    status = status,
    plannedCount = plannedCount,
    generatedCount = generatedCount,
    failedCount = failedCount,
    outputApk = outputApkFile,
    outputApkName = outputApkName,
    diagnostics = GenerationJsonCodec.decodeDiagnostics(diagnosticsJson),
    attempts = attempts.map { it.toRecord() },
)

private fun AttemptEntity.toRecord(): AttemptRecord = AttemptRecord(
    packageName = packageName,
    label = label,
    attempt = attempt,
    accepted = accepted,
    reason = reason,
    pngFile = pngFile,
    sourceFile = sourceFile,
    references = GenerationJsonCodec.decodeReferences(referencesJson),
    model = model,
    slotId = slotId,
    slotName = slotName,
    prompt = prompt,
    referenceDetails = GenerationJsonCodec.decodeReferenceDetails(referenceDetailsJson),
)
