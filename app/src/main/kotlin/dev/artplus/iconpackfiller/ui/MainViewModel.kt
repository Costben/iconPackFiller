package dev.artplus.iconpackfiller.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.artplus.iconpackfiller.coverage.AppScanner
import dev.artplus.iconpackfiller.coverage.CoverageCalculator
import dev.artplus.iconpackfiller.coverage.CoverageReport
import dev.artplus.iconpackfiller.coverage.CoverageRules
import dev.artplus.iconpackfiller.coverage.LaunchableApp
import dev.artplus.iconpackfiller.generate.AppIconLoader
import dev.artplus.iconpackfiller.generate.FillerOrchestrator
import dev.artplus.iconpackfiller.generate.GeneratedIcon
import dev.artplus.iconpackfiller.generate.GenerationProvenance
import dev.artplus.iconpackfiller.generate.IconPackSource
import dev.artplus.iconpackfiller.generate.ReferencePoolBuilder
import dev.artplus.iconpackfiller.generate.ReferenceSnapshot
import dev.artplus.iconpackfiller.generate.SingleIconRegenerator
import dev.artplus.iconpackfiller.reference.BitmapContactSheet
import dev.artplus.iconpackfiller.reference.ContactSheetComposer
import dev.artplus.iconpackfiller.reference.ReferencePair
import dev.artplus.iconpackfiller.ui.component.SampledReferences
import dev.artplus.iconpackfiller.pack.IconPackFinder
import dev.artplus.iconpackfiller.project.AttemptRecord
import dev.artplus.iconpackfiller.project.GenerationIconBuilder
import dev.artplus.iconpackfiller.project.GenerationIconOutcome
import dev.artplus.iconpackfiller.project.GenerationJsonCodec
import dev.artplus.iconpackfiller.project.GenerationRecord
import dev.artplus.iconpackfiller.project.GenerationSourceResolver
import dev.artplus.iconpackfiller.project.GenerationStatus
import dev.artplus.iconpackfiller.project.PackEntryBuilder
import dev.artplus.iconpackfiller.project.ProjectPaths
import dev.artplus.iconpackfiller.project.ProjectRepository
import dev.artplus.iconpackfiller.project.SourceKind
import dev.artplus.iconpackfiller.project.db.AppDatabase
import dev.artplus.iconpackfiller.project.db.AttemptEntity
import dev.artplus.iconpackfiller.project.db.GenerationEntity
import dev.artplus.iconpackfiller.project.db.GenerationIconEntity
import dev.artplus.iconpackfiller.project.db.GenerationIconMatch
import dev.artplus.iconpackfiller.project.db.ProjectEntity
import dev.artplus.iconpackfiller.provider.ApiProtocol
import dev.artplus.iconpackfiller.provider.GatewayCatalog
import dev.artplus.iconpackfiller.provider.GatewayModelList
import dev.artplus.iconpackfiller.provider.ImageProviderFactory
import dev.artplus.iconpackfiller.provider.ProviderKind
import dev.artplus.iconpackfiller.provider.ProviderUrls
import dev.artplus.iconpackfiller.provider.SimpleHttpClient
import dev.artplus.iconpackfiller.provider.TransparencyPresets
import dev.artplus.iconpackfiller.generate.GenerationAttempt
import dev.artplus.iconpackfiller.generate.TargetSelection
import dev.artplus.iconpackfiller.settings.SettingsStore
import dev.artplus.iconpackfiller.ui.component.ReferenceIconPair
import dev.artplus.iconpackfiller.ui.navigation.Navigator
import dev.artplus.iconpackfiller.ui.navigation.Route
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 业务阶段（与导航解耦）：Progress 页标题/内容由它驱动。
 */
enum class Phase { IDLE, ANALYZING, GENERATING, PACKING }

/** 「选择应用」弹窗缩略图的长边（px）。 */
private const val PREVIEW_ICON_SIZE = 128

/** 正在重新请求的目标（对比行上的长按操作）；null 表示空闲。 */
data class Regenerating(val packageName: String, val attempt: Int)

/**
 * 「生成详情」页的图标筛选三维度：包名 / 组件 / accepted。
 * null 表示该维度不过滤。
 */
data class IconFilter(
    val packageName: String? = null,
    val activityName: String? = null,
    val accepted: Boolean? = null,
)

/** 跨生成对比的目标（项目 + 包名 + 组件；activityName 为 null 表示整包）。 */
data class CompareKey(
    val projectId: String,
    val packageName: String,
    val activityName: String?,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val packs: List<IconPackFinder.InstalledIconPackInfo> = emptyList(),
    val selectedPack: IconPackFinder.InstalledIconPackInfo? = null,
    val selectedApkUri: Uri? = null,
    val selectedApkName: String? = null,
    /** 选定图标包后立即建出的项目 id；导入未完成/失败时为 null。 */
    val selectedProjectId: String? = null,
    /**
     * 从项目目录内 `source.apk` 快照发起生成 / 重打包时指向的项目 id。
     *
     * 非空时覆盖活体选择（已安装包 / 导入 APK），生成结果挂回该项目（不新建）。
     * 源包被卸载 / 更新 / 删除后仍可用。
     */
    val snapshotProjectId: String? = null,
    val report: CoverageReport? = null,
    /**
     * 「确认范围」页勾选的目标 key 集合（`package/activity`）。
     *
     * null 表示尚未初始化，语义为「全选」——这样插件式新增应用不会默认漏掉，
     * 也避免在扫描完成前就 materialize 一份可能过期的列表。
     */
    val selectedTargets: Set<String>? = null,
    val generatedCount: Int = 0,
    val failedCount: Int = 0,
    val totalToGenerate: Int = 0,
    val packLabel: String? = null,
    val outputApkPath: String? = null,
    val error: String? = null,
    val statusText: String = "",
    /** 是否已有完成的生成结果（可在 Pick 页重新进入 Done 导出）。 */
    val hasResult: Boolean = false,
    /**
     * 「确认范围」页固定下来的参考对（重新抽样 / 选择应用的结果）。
     *
     * 非空时整批都用这组参考；空 = 按目标配色自动选。
     */
    val batchReferences: List<ReferencePair> = emptyList(),
    /**
     * 每次 provider 请求的结果（含未通过校验的），供 Done 页预览。
     *
     * 保留被拒结果的用途：模型对同一目标可能给出风格差异很大的结果，
     * 本地校验判为不合格的那张未必是废图；也让「网关成功、界面失败」可直接对照。
     */
    val attempts: List<GenerationAttempt> = emptyList(),
    /** 当前正在执行的生成 id（用于增量落库）；未运行时为 null。 */
    val activeBatchId: String? = null,
    /** 正在重新请求的对比行；null 表示空闲。 */
    val regenerating: Regenerating? = null,
    /** 历史生成列表（Pick 页展示入口；详情页用 [batchDetail]）。 */
    val batches: List<GenerationRecord> = emptyList(),
    /** 当前查看的生成详情（列表/详情/Done 页共用）。 */
    val batchDetail: GenerationRecord? = null,
    // ---------- 项目 / 生成（Phase 6） ----------
    /** 全部项目（导入即建项），更新时间倒序。 */
    val projects: List<ProjectEntity> = emptyList(),
    /** 当前打开项目的对应表条数（项目详情概览）。 */
    val projectPackEntryCount: Int = 0,
    /** 当前打开项目的生成历史，新在前。 */
    val generations: List<GenerationEntity> = emptyList(),
    /** 当前打开生成的全部图标行（未筛选，用于推导筛选候选项）。 */
    val allGenerationIcons: List<GenerationIconEntity> = emptyList(),
    /** 当前打开生成按 [iconFilter] 筛选后的图标行。 */
    val generationIcons: List<GenerationIconEntity> = emptyList(),
    /** 生成详情页当前的筛选条件。 */
    val iconFilter: IconFilter = IconFilter(),
    /** 当前对比目标跨生成的图标序列，新生成在前。 */
    val compareMatches: List<GenerationIconMatch> = emptyList(),
) {
    val coveredCount: Int get() = report?.matched?.size ?: 0
    val uncoveredCount: Int get() = report?.unmatched?.size ?: 0
    val excludedCount: Int get() = report?.excluded?.size ?: 0
    val running: Boolean get() = phase != Phase.IDLE

    /** 待生成应用列表（未截断）。 */
    val targets: List<LaunchableApp> get() = report?.unmatched.orEmpty()

    /** 勾选数量。 */
    val selectedCount: Int
        get() = dev.artplus.iconpackfiller.generate.TargetSelection
            .selectedCount(targets, selectedTargets)

    /** 是否已全选。 */
    val allSelected: Boolean
        get() = dev.artplus.iconpackfiller.generate.TargetSelection
            .isAllSelected(targets, selectedTargets)

    /** Progress 页标题。 */
    val progressTitle: String
        get() = when (phase) {
            Phase.PACKING -> "打包中"
            Phase.GENERATING, Phase.ANALYZING -> "生成中"
            Phase.IDLE -> "处理中"
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)

    /** Room 项目库：一次进程一个实例（View 模型随 Activity 存活）。 */
    private val database by lazy { AppDatabase.build(application) }
    private val repository by lazy {
        ProjectRepository(database.projectDao(), ProjectPaths(application))
    }

    /** 生成历史的内存投影缓存（详情页与文件访问需要 projectId）。 */
    private val records = ConcurrentHashMap<String, GenerationRecord>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 当前打开的项目 id（驱动生成历史 Flow）。 */
    private val openedProjectId = MutableStateFlow<String?>(null)

    /** 当前打开的生成 id（驱动图标 Flow）。 */
    private val openedGenerationId = MutableStateFlow<String?>(null)

    /** 生成详情页的筛选条件（驱动筛选后的图标 Flow）。 */
    private val iconFilterFlow = MutableStateFlow(IconFilter())

    /** 当前对比目标（驱动跨生成序列 Flow）。 */
    private val compareKeyFlow = MutableStateFlow<CompareKey?>(null)

    init {
        // 进程被杀后残留的 RUNNING 生成无法恢复，启动时标记为中断并刷新列表
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.markRunningInterrupted() }
            reloadRecords()
        }
        // 项目列表（导入即建项，响应式）
        viewModelScope.launch {
            repository.observeProjects().collect { projects ->
                _state.value = _state.value.copy(projects = projects)
            }
        }
        // 当前项目的生成历史
        viewModelScope.launch {
            openedProjectId.flatMapLatest { projectId ->
                if (projectId == null) flowOf(emptyList()) else repository.observeGenerations(projectId)
            }.collect { generations ->
                _state.value = _state.value.copy(generations = generations)
            }
        }
        // 当前生成的全部图标（未筛选）
        viewModelScope.launch {
            openedGenerationId.flatMapLatest { generationId ->
                if (generationId == null) flowOf(emptyList()) else repository.observeIcons(generationId)
            }.collect { icons ->
                _state.value = _state.value.copy(allGenerationIcons = icons)
            }
        }
        // 当前生成按筛选条件过滤后的图标
        viewModelScope.launch {
            combine(openedGenerationId, iconFilterFlow) { generationId, filter ->
                generationId to filter
            }.flatMapLatest { (generationId, filter) ->
                if (generationId == null) {
                    flowOf(emptyList())
                } else {
                    repository.observeIcons(
                        generationId = generationId,
                        packageName = filter.packageName,
                        activityName = filter.activityName,
                        accepted = filter.accepted,
                    )
                }
            }.collect { icons ->
                _state.value = _state.value.copy(generationIcons = icons)
            }
        }
        // 同一目标跨 Generation 的图标序列
        viewModelScope.launch {
            compareKeyFlow.flatMapLatest { key ->
                if (key == null) {
                    flowOf(emptyList())
                } else {
                    repository.observeCompareTarget(key.projectId, key.packageName, key.activityName)
                }
            }.collect { matches ->
                _state.value = _state.value.copy(compareMatches = matches)
            }
        }
    }

    /** 返回栈（导航唯一事实源）。 */
    val navigator = Navigator(Route.START)

    private var runJob: Job? = null

    /** 「导入即建项目」的后台任务；换包时取消上一个。 */
    private var importJob: Job? = null

    private var session: FillerOrchestrator.Session? = null

    /** 最近一次运行对应的生成 id（Done 页实时追加重新请求结果用）。 */
    private var sessionGenerationId: String? = null

    fun refreshPacks() {
        viewModelScope.launch {
            val packs = withContext(Dispatchers.IO) { IconPackFinder.find(getApplication()) }
            _state.value = _state.value.copy(packs = packs)
        }
    }

    fun selectPack(pack: IconPackFinder.InstalledIconPackInfo) {
        clearSession()
        _state.value = _state.value.copy(
            selectedPack = pack,
            selectedApkUri = null,
            selectedApkName = null,
            selectedProjectId = null,
            snapshotProjectId = null,
            error = null,
        )
        startProjectImport(_state.value)
    }

    fun selectApk(uri: Uri, name: String?) {
        clearSession()
        _state.value = _state.value.copy(
            selectedApkUri = uri,
            selectedApkName = name,
            selectedPack = null,
            selectedProjectId = null,
            snapshotProjectId = null,
            error = null,
        )
        startProjectImport(_state.value)
    }

    /**
     * 「导入或选定图标包即建项目」：解析源元数据、复制快照并把对应表落库。
     *
     * 全程在 IO 线程执行，大包分批事务，不阻塞主线程；失败只记 [UiState.error]，
     * 不阻断后续流程（run() 仍会兜底 ensureProject）。
     */
    private fun startProjectImport(state: UiState) {
        if (state.selectedPack == null && state.selectedApkUri == null) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val apkFile = copyApkToCache(state.selectedApkUri)
                    val source = when {
                        state.selectedPack != null ->
                            IconPackSource.open(context, state.selectedPack.packageName)
                        apkFile != null -> IconPackSource.open(context, apkFile)
                        else -> null
                    } ?: return@withContext
                    source.use { pack ->
                        val entries = PackEntryBuilder.build(
                            document = pack.document,
                            resourcePath = { pack.resourcePath(it) },
                            exists = { it in pack.availableDrawables },
                        )
                        val project = repository.importProject(
                            sourceKind = if (state.selectedPack != null) {
                                SourceKind.INSTALLED
                            } else {
                                SourceKind.APK_FILE
                            },
                            packLabel = state.selectedPack?.label ?: state.selectedApkName ?: "图标包",
                            packPackage = pack.packageName,
                            packVersionCode = pack.versionCode,
                            packHash = sha256(pack.sourceApk),
                            sourceApk = pack.sourceApk,
                            entries = entries,
                        )
                        _state.value = _state.value.copy(selectedProjectId = project.id)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "建立项目失败")
            }
        }
    }

    fun settingsStore(): SettingsStore = settings

    /**
     * 切换单个目标的勾选状态。首次操作时把「未初始化」展开为全选快照，
     * 否则第一次取消勾选会把其余全部清空。
     */
    fun toggleTarget(app: LaunchableApp) {
        val state = _state.value
        val current = state.selectedTargets ?: TargetSelection.allKeys(state.targets)
        val key = TargetSelection.keyOf(app)
        val updated = if (key in current) current - key else current + key
        _state.value = state.copy(selectedTargets = updated)
    }

    /** 全选 / 取消全选。 */
    fun setAllTargetsSelected(selected: Boolean) {
        val state = _state.value
        _state.value = state.copy(
            selectedTargets = if (selected) TargetSelection.allKeys(state.targets) else emptySet(),
        )
    }

    /**
     * 拉取网关模型目录（设置页「获取」按钮）。结果经 [onResult] 回调到 UI。
     *
     * new-api 系网关会带上每个模型的 `supported_endpoint_types`，可用于自动定协议；
     * 其他网关回退到通用模型列表（仅名称）。
     */
    fun fetchGatewayModels(
        baseUrl: String,
        apiKey: String,
        protocol: ApiProtocol,
        onResult: (Result<GatewayModelList>) -> Unit,
    ) {
        if (baseUrl.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Base URL 为空")))
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                val client = SimpleHttpClient(connectTimeoutMs = 15_000, readTimeoutMs = 20_000)
                GatewayCatalog.fetch(client, baseUrl.trim(), apiKey, protocol.kind)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    /**
     * 扫描 + 覆盖率。扫描期间停留在 Pick 页，完成后 push Review。
     */
    fun analyze() {
        if (_state.value.running) return
        clearBatchReferences()
        val state = _state.value
        _state.value = state.copy(phase = Phase.ANALYZING, error = null, statusText = "扫描中…")
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val source = withContext(Dispatchers.IO) { openResolvedSource(context, state) }
                    ?: error("无法打开图标包")
                source.use { pack ->
                    val apps = withContext(Dispatchers.IO) {
                        dev.artplus.iconpackfiller.coverage.AppScanner(context).scan()
                    }
                    val report = dev.artplus.iconpackfiller.coverage.CoverageCalculator.compute(
                        apps = apps,
                        appFilter = pack.document,
                        availableDrawables = pack.availableDrawables,
                        rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps),
                    )
                    _state.value = _state.value.copy(
                        report = report,
                        // 新报告 -> 重新回到「全选」
                        selectedTargets = null,
                        phase = Phase.IDLE,
                        statusText = "",
                    )
                    navigator.push(Route.Review)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    statusText = "",
                    error = e.message ?: "扫描失败",
                )
            }
        }
    }

    /**
     * 从项目目录内的 `source.apk` 快照发起生成 / 重打包。
     *
     * 源包被卸载 / 更新 / 删除后快照仍可用；生成结果挂回同一 Project（不新建重复项目）。
     * 复用与活体选择相同的 Review → run 流程，仅来源换成快照。
     */
    fun startGenerationFromProject(projectId: String) {
        if (_state.value.running) return
        if (repository.sourceApk(projectId) == null) {
            _state.value = _state.value.copy(error = "项目源快照缺失，无法生成")
            return
        }
        clearSession()
        _state.value = _state.value.copy(
            snapshotProjectId = projectId,
            selectedPack = null,
            selectedApkUri = null,
            selectedApkName = null,
            selectedProjectId = null,
            error = null,
        )
        analyze()
    }

    /**
     * 把 UI 状态解析为本次生成来源（项目快照 > 已安装包 > 导入 APK）。
     *
     * 快照路径读取项目目录内的 `source.apk`；文件缺失时自动回退到活体选择，
     * 不因快照丢失而中断现有流程。
     */
    private suspend fun resolveSource(state: UiState): GenerationSourceResolver.Resolved? {
        val snapshotProjectId = state.snapshotProjectId
        val snapshotApk = snapshotProjectId?.let { repository.sourceApk(it) }
        val snapshotLabel = snapshotProjectId?.let { pid ->
            state.projects.firstOrNull { it.id == pid }?.packLabel
        }
        val apkFile = copyApkToCache(state.selectedApkUri)
        return GenerationSourceResolver.resolve(
            snapshotProjectId = snapshotProjectId,
            snapshotApk = snapshotApk,
            snapshotLabel = snapshotLabel,
            installedPackage = state.selectedPack?.packageName,
            installedLabel = state.selectedPack?.label,
            apkFile = apkFile,
            apkLabel = state.selectedApkName,
        )
    }

    /** 按当前状态解析并打开图标包来源；返回 null 表示没有可用来源。 */
    private suspend fun openResolvedSource(context: Application, state: UiState): IconPackSource? {
        val resolved = resolveSource(state) ?: return null
        return when {
            resolved.installedPackage != null -> IconPackSource.open(context, resolved.installedPackage)
            resolved.apkFile != null -> IconPackSource.open(context, resolved.apkFile)
            else -> null
        }
    }

    /**
     * 完整执行：生成 → 打包 → 签名。
     *
     * 成功后返回栈重置为 [Pick, Done]；中途取消/失败回到 Review。
     */
    fun run() {
        val state = _state.value
        if (state.running) return

        val config = settings.toProviderConfig()
        if (config.apiKey.isBlank()) {
            _state.value = state.copy(error = "请先在设置中填写 API Key")
            return
        }
        // 透明直出是否生效：槽位声明优先，否则按模型预设；整批固定（中途切槽位不影响进行中的批次）
        val requestTransparent = TransparencyPresets.effective(config.model, config.transparency)

        // 未初始化 / 全选时传 null，避免下游做一次无谓的集合比对
        val selection = state.selectedTargets
            ?.takeIf { !TargetSelection.isAllSelected(state.targets, it) }
        if (selection != null && selection.isEmpty()) {
            _state.value = state.copy(error = "请至少勾选一个要生成的应用")
            return
        }

        navigator.push(Route.Progress)

        val startedAt = System.currentTimeMillis()

        _state.value = state.copy(
            phase = Phase.GENERATING,
            error = null,
            statusText = "准备…",
            activeBatchId = null,
        )

        runJob = viewModelScope.launch {
            val scope = this
            val diagnostics = java.util.Collections.synchronizedList(arrayListOf<String>())
            var projectId: String? = null
            var generationId: String? = null

            // 进度回调是非 suspend 的（编排器逐图标调用），落库走 runBlocking——
            // 与旧实现「在 IO 线程同步写批次文件」同构，且在 IO 线程上不会阻塞主线程。
            fun persistProgress(
                status: GenerationStatus,
                planCount: Int,
                generated: Int,
                failed: Int,
            ) {
                val pid = projectId ?: return
                val gid = generationId ?: return
                runBlocking {
                    val existing = repository.generation(gid) ?: return@runBlocking
                    repository.updateGeneration(
                        existing.copy(
                            status = status,
                            plannedCount = if (planCount > 0) planCount else existing.plannedCount,
                            generatedCount = generated,
                            failedCount = failed,
                            diagnosticsJson = GenerationJsonCodec.encodeDiagnostics(diagnostics.toList()),
                        ),
                    )
                    refreshGeneration(gid)
                }
            }

            try {
                val context = getApplication<Application>()
                val workDir = File(context.cacheDir, "filler-work").apply { mkdirs() }
                val rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps)
                // 确认范围页敲定的参考优先整批固定；没挑过则按目标配色自动选
                val referenceOverride = state.batchReferences.takeIf { it.isNotEmpty() }
                // 来源解析：项目快照优先（源包卸载/更新/删除后仍可重打包），否则活体选择
                val resolved = resolveSource(state) ?: error("未选择图标包")
                val input = FillerOrchestrator.Input(
                    installedPackage = resolved.installedPackage,
                    apkFile = resolved.apkFile,
                    rules = rules,
                    referencePairCount = settings.referencePairCount,
                    maxGenerationAttempts = 1 + settings.maxRetries,
                    concurrency = config.concurrency,
                    callLimit = settings.callLimit.takeIf { it > 0 },
                    selectedTargets = selection,
                    referenceOverride = referenceOverride,
                    requestTransparentBackground = requestTransparent,
                )

                // 1) 解析源元数据 → 建/复用项目（含源 APK 快照）
                val meta = withContext(Dispatchers.IO) { openSourceMeta(context, resolved) }
                    ?: error("无法打开图标包")
                // 快照来源复用既有项目（不新建）；活体来源按元数据 ensure
                val project = withContext(Dispatchers.IO) {
                    repository.resolveProjectForRun(
                        reuseProjectId = resolved.reuseProjectId,
                        sourceKind = resolved.liveSourceKind ?: SourceKind.APK_FILE,
                        packLabel = resolved.label,
                        packPackage = meta.packageName,
                        packVersionCode = meta.versionCode,
                        packHash = meta.hash,
                        sourceApk = meta.apkFile,
                        now = startedAt,
                    )
                } ?: error("项目不存在")
                projectId = project.id

                // 2) 建一条 Generation（RUNNING），run 全程挂在它下面
                val activeSlot = settings.activeSlot
                val generation = withContext(Dispatchers.IO) {
                    repository.createGeneration(
                        projectId = project.id,
                        status = GenerationStatus.RUNNING,
                        model = activeSlot.model,
                        slotId = activeSlot.id,
                        slotName = activeSlot.name,
                        paramsJson = GenerationJsonCodec.encodeParams(
                            mapOf(
                                "referencePairCount" to settings.referencePairCount,
                                "maxAttempts" to (1 + settings.maxRetries),
                                "concurrency" to config.concurrency,
                                "callLimit" to settings.callLimit.takeIf { it > 0 },
                                "transparent" to requestTransparent,
                                "excludeSystemApps" to settings.excludeSystemApps,
                            ),
                        ),
                        now = startedAt,
                    ).also { repository.setActiveGeneration(project.id, it.id) }
                }
                generationId = generation.id
                sessionGenerationId = generation.id
                _state.value = _state.value.copy(activeBatchId = generation.id)
                reloadRecords()

                val provider = ImageProviderFactory.create(config)
                val orchestrator = FillerOrchestrator(
                    context,
                    workDir,
                    provider,
                    provenance = GenerationProvenance(
                        model = activeSlot.model,
                        slotId = activeSlot.id,
                        slotName = activeSlot.name,
                    ),
                    onDiagnostic = { message ->
                        android.util.Log.w("IconPackFiller", message)
                        // 失败原因要能回看：诊断同步进生成记录（截断防止 JSON 过大）
                        diagnostics.add(message.take(300))
                    },
                )
                // 编排（位图合成/ARSCLib 打包/apksig 签名）全部在 IO 线程执行，避免主线程 ANR
                val result = withContext(Dispatchers.IO) {
                    orchestrator.run(
                        input = input,
                        onProgress = { progress ->
                            handleProgress(progress)
                            // 进度增量落库：切后台/被杀后回来也能看到真实进度
                            when (progress) {
                                is FillerOrchestrator.Progress.PlanDone ->
                                    persistProgress(
                                        GenerationStatus.RUNNING,
                                        planCount = progress.planCount,
                                        generated = 0,
                                        failed = 0,
                                    )
                                is FillerOrchestrator.Progress.GenerationProgress ->
                                    persistProgress(
                                        GenerationStatus.RUNNING,
                                        planCount = progress.total,
                                        generated = progress.succeeded,
                                        failed = progress.failed,
                                    )
                                else -> Unit
                            }
                        },
                        shouldCancel = { !scope.isActive },
                        onAttempt = { attempt ->
                            runBlocking {
                                repository.persistAttempt(project.id, generation.id, attempt)
                                refreshGeneration(generation.id)
                            }
                        },
                    )
                }
                session = result
                withContext(Dispatchers.IO) {
                    val pid = project.id
                    val gid = generation.id
                    val existing = repository.generation(gid) ?: return@withContext
                    val outputApk = repository.persistApk(pid, gid, result.signedApk)
                    repository.updateGeneration(
                        existing.copy(
                            status = GenerationStatus.COMPLETED,
                            plannedCount = result.plans.size,
                            generatedCount = result.generated.size,
                            failedCount = result.plans.size - result.generated.size,
                            outputPackageName = result.originalPackage,
                            outputApkFile = outputApk ?: existing.outputApkFile,
                            // 导出时给用户看的文件名（不要暴露内部 signed.apk）
                            outputApkName = result.signedApk?.let {
                                dev.artplus.iconpackfiller.pack.SafExporter.fileNameFor(
                                    result.originalPackage,
                                    result.originalVersionCode,
                                )
                            } ?: existing.outputApkName,
                            diagnosticsJson = GenerationJsonCodec.encodeDiagnostics(diagnostics.toList()),
                        ),
                    )
                    // 目标与结果落 GenerationIcon：accepted/reason/label/drawableName
                    val acceptedKeys = result.generated.mapTo(HashSet()) {
                        TargetSelection.keyOf(it.packageName, it.activityName)
                    }
                    repository.persistGenerationIcons(
                        gid,
                        GenerationIconBuilder.build(
                            plans = result.plans,
                            acceptedKeys = acceptedKeys,
                            outcomes = result.attempts.map { attempt ->
                                GenerationIconOutcome(
                                    packageName = attempt.packageName,
                                    label = attempt.label,
                                    attempt = attempt.attempt,
                                    accepted = attempt.accepted,
                                    reason = attempt.reason,
                                )
                            },
                            // 0 Attempt 的失败（参考加载 / provider 异常）也要留原因
                            failures = result.failures,
                        ),
                    )
                    refreshGeneration(gid)
                }
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    packLabel = result.packResult?.label,
                    outputApkPath = result.signedApk?.absolutePath,
                    attempts = result.attempts,
                    statusText = "",
                    hasResult = result.signedApk != null,
                    activeBatchId = null,
                    // Done 页的「重新生成」要拿到本次生成的记录
                    batchDetail = records[generation.id] ?: _state.value.batchDetail,
                )
                navigator.resetTo(Route.Pick, Route.Done)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消：保住已生成的图，生成标 CANCELLED 供回看
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    val gid = generationId
                    if (gid != null) {
                        val existing = repository.generation(gid)
                        if (existing != null) {
                            repository.updateGeneration(
                                existing.copy(
                                    status = GenerationStatus.CANCELLED,
                                    diagnosticsJson = GenerationJsonCodec.encodeDiagnostics(diagnostics.toList()),
                                ),
                            )
                            repository.rebuildIconsFromAttempts(gid)
                            refreshGeneration(gid)
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    diagnostics.add("${e::class.simpleName}: ${e.message?.take(200)}")
                    val gid = generationId
                    if (gid != null) {
                        val existing = repository.generation(gid)
                        if (existing != null) {
                            repository.updateGeneration(
                                existing.copy(
                                    status = GenerationStatus.FAILED,
                                    diagnosticsJson = GenerationJsonCodec.encodeDiagnostics(diagnostics.toList()),
                                ),
                            )
                            repository.rebuildIconsFromAttempts(gid)
                            refreshGeneration(gid)
                        }
                    }
                }
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    statusText = "",
                    error = e.message ?: "执行失败",
                    activeBatchId = null,
                )
                navigator.popTo(Route.Review)
            }
        }
    }

    /** 用户主动取消（Progress 页按钮或确认框确认后调用）。 */
    fun cancel() {
        runJob?.cancel()
        runJob = null
        _state.value = _state.value.copy(
            phase = Phase.IDLE,
            statusText = "",
            error = null,
            activeBatchId = null,
        )
        navigator.popTo(Route.Review)
    }

    /** 从 Done 页重新进入查看结果（Pick 页的"上次结果"卡片）。 */
    fun openResult() {
        if (session?.signedApk != null) {
            navigator.push(Route.Done)
        }
    }

    // ---------- 生成历史 ----------

    /** 从 Room 重新加载全部生成历史（含 Attempt），并刷新内存投影缓存。 */
    private suspend fun reloadRecords() {
        val list = withContext(Dispatchers.IO) { repository.generationRecords() }
        records.clear()
        list.forEach { records[it.id] = it }
        val current = _state.value
        _state.value = current.copy(
            batches = list,
            batchDetail = current.batchDetail?.id?.let { records[it] } ?: current.batchDetail,
        )
    }

    /** 只刷新一条生成（进度回调高频路径，避免全量重载）。 */
    private suspend fun refreshGeneration(generationId: String) {
        val record = withContext(Dispatchers.IO) { repository.generationRecord(generationId) } ?: return
        records[generationId] = record
        val current = _state.value
        val updatedList = if (current.batches.any { it.id == generationId }) {
            current.batches.map { if (it.id == generationId) record else it }
        } else {
            listOf(record) + current.batches
        }
        _state.value = current.copy(
            batches = updatedList,
            batchDetail = if (current.batchDetail?.id == generationId) record else current.batchDetail,
        )
    }

    // ---------- 项目 / 生成导航（Phase 6） ----------

    /** 打开项目列表页。 */
    fun openProjects() {
        navigator.push(Route.Projects)
    }

    /** 打开项目详情：加载对应表条数并订阅其生成历史。 */
    fun openProject(id: String) {
        openedProjectId.value = id
        navigator.push(Route.Project(id))
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { repository.packEntryCount(id) }
            _state.value = _state.value.copy(projectPackEntryCount = count)
        }
    }

    /** 打开某次生成详情：订阅全部图标，并载入记录（进度 / 对比）。 */
    fun openGeneration(id: String) {
        openedGenerationId.value = id
        iconFilterFlow.value = IconFilter()
        _state.value = _state.value.copy(iconFilter = IconFilter())
        navigator.push(Route.Generation(id))
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { repository.generationRecord(id) }
            if (record != null) {
                openedProjectId.value = record.projectId
                records[id] = record
                _state.value = _state.value.copy(batchDetail = record)
            }
        }
    }

    /** 生成详情页：切换包名筛选（同时重置组件筛选）。 */
    fun setIconPackageFilter(packageName: String?) {
        iconFilterFlow.value = iconFilterFlow.value.copy(
            packageName = packageName,
            activityName = null,
        )
        _state.value = _state.value.copy(iconFilter = iconFilterFlow.value)
    }

    /** 生成详情页：切换组件筛选。 */
    fun setIconActivityFilter(activityName: String?) {
        iconFilterFlow.value = iconFilterFlow.value.copy(activityName = activityName)
        _state.value = _state.value.copy(iconFilter = iconFilterFlow.value)
    }

    /** 生成详情页：切换 accepted 筛选（null=全部）。 */
    fun setIconAcceptedFilter(accepted: Boolean?) {
        iconFilterFlow.value = iconFilterFlow.value.copy(accepted = accepted)
        _state.value = _state.value.copy(iconFilter = iconFilterFlow.value)
    }

    /** 打开同一目标跨生成对比。 */
    fun openCompare(projectId: String, packageName: String, activityName: String?) {
        compareKeyFlow.value = CompareKey(projectId, packageName, activityName)
        navigator.push(Route.Compare(projectId, packageName, activityName))
    }

    /** 对比页每行懒加载某次生成产出的图（取该目标最新一次请求的 PNG）。 */
    suspend fun compareTargetIcon(projectId: String, match: GenerationIconMatch): ByteArray? =
        withContext(Dispatchers.IO) {
            val attempts = repository.attempts(match.icon.generationId)
                .filter { it.packageName == match.icon.packageName }
            val chosen = attempts.lastOrNull { it.accepted } ?: attempts.lastOrNull()
            chosen?.pngFile
                ?.let { repository.attemptFile(projectId, match.icon.generationId, it) }
                ?.readBytes()
        }

    /** 用户手动把项目当前指向设为某次生成（null=清除）。 */
    fun setActiveGeneration(projectId: String, generationId: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setActiveGeneration(projectId, generationId) }
        }
    }

    /** 删除项目及其全部生成（磁盘目录一并清理）。 */
    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteProject(id) }
            if (openedProjectId.value == id) openedProjectId.value = null
        }
    }

    fun batchRecord(id: String): GenerationRecord? = records[id]

    /** 生成目录里的图/APK 文件（详情页渲染用）。 */
    fun batchAttemptFile(batchId: String, fileName: String?): File? {
        val record = records[batchId] ?: return null
        return repository.attemptFile(record.projectId, batchId, fileName)
    }

    fun batchOutputApk(batchId: String, fileName: String?): File? {
        val record = records[batchId] ?: return null
        return repository.outputApk(record.projectId, batchId, fileName)
    }

    /** 删除一条生成记录。 */
    fun deleteBatch(id: String) {
        if (_state.value.batchDetail?.id == id) {
            _state.value = _state.value.copy(batchDetail = null)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteGeneration(id) }
            records.remove(id)
            reloadRecords()
        }
    }

    /**
     * 对生成里的一次请求重新发起（「生成详情」弹窗的「重新生成」）。
     *
     * @param referencesOverride 「重新取样」当下敲定的参考；null=沿用原请求的参考
     * @param slotId 指定模型（槽位 id）；null 表示沿用原请求的槽位
     *
     * 校验驳回也会追加一条新 attempt（带原因）供对比；网络失败等设 [UiState.error]。
     */
    fun regenerateAttempt(
        batchId: String,
        packageName: String,
        attempt: Int,
        referencesOverride: List<ReferenceSnapshot>? = null,
        slotId: String? = null,
    ) {
        if (_state.value.regenerating != null) return
        val record = records[batchId] ?: return
        val source = record.attempts.firstOrNull {
            it.packageName == packageName && it.attempt == attempt
        } ?: return

        val targetSlotId = slotId ?: source.slotId
        val config = targetSlotId?.let { settings.toProviderConfig(it) }
        if (config == null) {
            _state.value = _state.value.copy(
                error = if (slotId != null) "所选供应商不可用" else "原供应商已不存在，请为「重新生成」选择模型",
            )
            return
        }
        if (config.apiKey.isBlank()) {
            _state.value = _state.value.copy(error = "供应商「${source.slotName ?: targetSlotId}」未配置 API Key")
            return
        }
        val references = referencesOverride ?: source.referenceDetails
        if (references.isEmpty()) {
            _state.value = _state.value.copy(error = "该记录缺少参考明细（旧版本），请先用「重新取样」选一组参考")
            return
        }
        val provenance = GenerationProvenance(
            model = config.model,
            slotId = targetSlotId,
            slotName = settings.providerSlots.slots.firstOrNull { it.id == targetSlotId }?.name,
        )

        _state.value = _state.value.copy(
            regenerating = Regenerating(packageName, attempt),
            error = null,
        )

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runRegeneration(record, source, references, config, provenance)
                }
                val persisted = withContext(Dispatchers.IO) {
                    val entity = repository.persistAttempt(record.projectId, batchId, result)
                    // 重新生成可能改变该目标图标行的通过状态，保持筛选结果一致
                    repository.refreshIconOutcome(batchId, result.packageName)
                    refreshGeneration(batchId)
                    entity
                }
                _state.value = _state.value.copy(
                    regenerating = null,
                    // Done 页列表用的是内存 attempt：同批次的重新请求实时补进去
                    attempts = if (sessionGenerationId == batchId) {
                        _state.value.attempts + buildsAttempt(batchId, persisted)
                    } else {
                        _state.value.attempts
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    regenerating = null,
                    error = (e.message ?: e::class.simpleName ?: "重新请求失败").take(300),
                )
            }
        }
    }

    /**
     * 渲染一组参考对的采样图（原图 + 包内重绘），供「生成详情」弹窗回显。
     *
     * 旧记录没有 drawable 明细时按包名兜底匹配；图标包不可用则返回空表（弹窗退回文字）。
     */
    suspend fun referenceIcons(
        batchId: String,
        snapshots: List<ReferenceSnapshot>,
    ): List<ReferenceIconPair> = withContext(Dispatchers.IO) {
        if (snapshots.isEmpty()) return@withContext emptyList()
        val record = records[batchId] ?: return@withContext emptyList()
        val pack = openPackForRegenerate(record) ?: return@withContext emptyList()
        pack.use {
            snapshots.map { snap ->
                val original = AppIconLoader.load(getApplication(), snap.packageName, snap.activityName)
                val drawableName = snap.drawableName
                    ?: pack.document.match(snap.packageName, snap.activityName)?.drawableName
                val packBitmap = drawableName?.let { name -> pack.renderDrawable(name) }
                ReferenceIconPair(
                    original = original?.let { bm -> pngBytes(bm).also { bm.recycle() } },
                    pack = packBitmap?.let { bm -> pngBytes(bm).also { bm.recycle() } },
                )
            }
        }
    }

    /** 设置页的参考对数，供「重新取样」按当前配置抽组。 */
    val referencePairCount: Int get() = settings.referencePairCount

    /**
     * 「重新取样」的一次刷新：从参考池随机抽 [count] 组并渲染采样图。
     *
     * 全程本地、不发起 provider 请求；池子按「生成 + 目标」缓存在内存，
     * 后续刷新只重渲染抽中的几组（弹窗里的「不断替换」靠它保持轻量）。
     */
    suspend fun sampleReferences(
        batchId: String,
        packageName: String,
        count: Int,
    ): SampledReferences? = withContext(Dispatchers.IO) {
        val record = records[batchId] ?: return@withContext null
        val context = getApplication<Application>()
        val key = "$batchId/$packageName"
        val pool = shufflePool?.takeIf { it.key == key }?.pairs ?: run {
            val pack = openPackForRegenerate(record) ?: return@withContext null
            pack.use {
                val report = CoverageCalculator.compute(
                    apps = AppScanner(context).scan(),
                    appFilter = pack.document,
                    availableDrawables = pack.availableDrawables,
                    rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps),
                )
                ReferencePoolBuilder.build(context, pack, report)
            }.also { shufflePool = ShufflePool(key, it) }
        }
        val candidates = pool.filter { it.packageName != packageName }
        if (candidates.isEmpty()) return@withContext null
        val picked = candidates.shuffled().take(count.coerceAtLeast(1))
        val pack = openPackForRegenerate(record) ?: return@withContext null
        pack.use {
            SampledReferences(
                references = picked.map {
                    ReferenceSnapshot(
                        packageName = it.packageName,
                        label = it.label,
                        drawableName = it.packDrawableName,
                        activityName = it.activityName,
                    )
                },
                icons = picked.map { ref ->
                    val bitmaps = ReferencePoolBuilder.loadReferenceBitmaps(context, pack, ref)
                    ReferenceIconPair(
                        original = bitmaps?.first?.let { bm -> pngBytes(bm).also { bm.recycle() } },
                        pack = bitmaps?.second?.let { bm -> pngBytes(bm).also { bm.recycle() } },
                    )
                },
            )
        }
    }

    /**
     * 「上传给模型的图片」采样图预览：按当前参考对数拼一张与生成时一致的 ContactSheet。
     *
     * 取当前选中的图标包（内置包或导入 APK 的缓存副本，没有则取首个已安装的），
     * 挑 [count] 组参考对 + 一个目标应用原图；结果按「包名 + 参考对数」缓存，
     * 滑动参数/切换页面时不重复拼接。
     *
     * @param reportOverride 已有覆盖率报告（「确认范围」页刚扫描过时传入，避免重复扫描）
     */
    suspend fun sampleContactSheet(
        count: Int,
        reportOverride: CoverageReport? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val pairCount = count.coerceIn(1, ContactSheetComposer.MAX_PAIRS)
        val pack = openSamplePack(context) ?: return@withContext null
        val key = "${pack.packageName}/$pairCount"
        contactSheetCache?.takeIf { it.key == key }?.let { return@withContext it.png }
        val png = pack.use {
            val report = reportOverride ?: CoverageCalculator.compute(
                apps = AppScanner(context).scan(),
                appFilter = pack.document,
                availableDrawables = pack.availableDrawables,
                rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps),
            )
            val pool = ReferencePoolBuilder.build(context, pack, report)
            val pairs = ArrayList<Pair<android.graphics.Bitmap, android.graphics.Bitmap>>()
            for (ref in pool) {
                if (pairs.size >= pairCount) break
                ReferencePoolBuilder.loadReferenceBitmaps(context, pack, ref)?.let { pairs.add(it) }
            }
            val targetApp = report.unmatched.firstOrNull()
                ?: report.matched
                    .firstOrNull { m -> pool.none { it.packageName == m.app.packageName } }
                    ?.app
            val target = targetApp?.let { AppIconLoader.load(context, it.packageName, it.activityName) }
            try {
                if (pairs.isEmpty() || target == null) {
                    null
                } else {
                    pngBytes(BitmapContactSheet.compose(pairs = pairs, target = target))
                }
            } finally {
                pairs.forEach { (a, b) ->
                    a.recycle()
                    b.recycle()
                }
                target?.recycle()
            }
        }
        if (png != null) contactSheetCache = ContactSheetCache(key, png)
        png
    }

    /**
     * 采样图预览用的图标包来源：当前已选来源优先，都没有时退回首个已安装包
     * （设置页在未跑流程时也要有示例可看）。
     */
    private fun openSamplePack(context: Application): IconPackSource? {
        val state = _state.value
        // 项目快照优先：源包卸载/更新后，确认页与设置页的示例图仍可渲染
        state.snapshotProjectId?.let { projectId ->
            repository.sourceApk(projectId)?.let { snapshot ->
                IconPackSource.open(context, snapshot)?.let { return it }
            }
        }
        state.selectedPack?.let { info ->
            IconPackSource.open(context, info.packageName)?.let { return it }
        }
        if (state.selectedApkUri != null) {
            val cached = File(context.cacheDir, "source-iconpack.apk")
            if (cached.exists()) IconPackSource.open(context, cached)?.let { return it }
        }
        val fallback = state.packs.firstOrNull()
            ?: IconPackFinder.find(context).firstOrNull()
            ?: return null
        return IconPackSource.open(context, fallback.packageName)
    }

    /** 设置页示例拼接图缓存（同包同参考对数只合成一次）。 */
    private data class ContactSheetCache(val key: String, val png: ByteArray)

    private var contactSheetCache: ContactSheetCache? = null

    // ---------- 批次参考（确认范围页：重新抽样 / 选择应用） ----------

    /** 参考池缓存（同包只构建一次；采样图与「选择应用」候选共用）。 */
    private var referencePoolCache: Pair<String, List<ReferencePair>>? = null

    /** 当前批次参考是按哪个「上传数量」抽的；数量改变时重抽。 */
    private var batchReferenceCount = 0

    /**
     * 「选择应用」候选：图标包已覆盖的应用（原图 ↔ 包内重绘）。
     *
     * 与生成用参考池同源；[reportOverride] 传确认范围页刚扫描的报告，避免重复扫描。
     */
    suspend fun referenceCandidates(reportOverride: CoverageReport?): List<ReferencePair> =
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            val pack = openSamplePack(context) ?: return@withContext emptyList()
            pack.use { ensureReferencePool(context, it, reportOverride) }
        }

    /**
     * 「选择应用」弹窗的参考缩略图：每个候选项的「原图 + 包内生效图」。
     *
     * 缩到 [PREVIEW_ICON_SIZE] 再编码，长列表也不至于吃内存；渲染失败为 null。
     */
    suspend fun referencePreviews(candidates: List<ReferencePair>): Map<String, ReferenceIconPair> =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext emptyMap()
            val context = getApplication<Application>()
            val pack = openSamplePack(context) ?: return@withContext emptyMap()
            pack.use { source ->
                candidates.associate { ref ->
                    val original = AppIconLoader.load(context, ref.packageName, ref.activityName)
                    val packBitmap = ref.packDrawableName?.let { name -> source.renderDrawable(name) }
                    ref.packageName to ReferenceIconPair(
                        original = original?.let { bm -> previewPng(bm).also { bm.recycle() } },
                        pack = packBitmap?.let { bm -> previewPng(bm).also { bm.recycle() } },
                    )
                }
            }
        }

    /** 「重新抽样」：随机换一组参考对（不同包名去重）。 */
    suspend fun resampleBatchReferences(count: Int, reportOverride: CoverageReport?): Boolean =
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            val pack = openSamplePack(context) ?: return@withContext false
            pack.use {
                val picked = sampleDistinct(ensureReferencePool(context, it, reportOverride), count)
                if (picked.isEmpty()) return@withContext false
                batchReferenceCount = count
                _state.value = _state.value.copy(batchReferences = picked)
                true
            }
        }

    /** 「选择应用」确认：写入固定参考（数量可少于设置的上传数量，按用户所选）。 */
    fun setBatchReferences(picked: List<ReferencePair>, count: Int) {
        batchReferenceCount = count
        _state.value = _state.value.copy(batchReferences = picked)
    }

    /**
     * 确认范围页采样图：确保已有当前数量的固定参考（没有或数量变了就随机抽一组），
     * 用它们 + 首个勾选目标拼出与生成时一致的 ContactSheet。
     */
    suspend fun batchReferenceSheet(
        count: Int,
        reportOverride: CoverageReport?,
        selectedTargets: Set<String>?,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val pack = openSamplePack(context) ?: return@withContext null
        pack.use { source ->
            val current = _state.value.batchReferences
                .takeIf { it.isNotEmpty() && batchReferenceCount == count }
                ?: sampleDistinct(ensureReferencePool(context, source, reportOverride), count).also {
                    if (it.isEmpty()) return@withContext null
                    batchReferenceCount = count
                    _state.value = _state.value.copy(batchReferences = it)
                }
            val report = reportOverride ?: CoverageCalculator.compute(
                apps = AppScanner(context).scan(),
                appFilter = source.document,
                availableDrawables = source.availableDrawables,
                rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps),
            )
            // 目标用首个勾选应用，让采样图更贴近实际会跑的那些目标
            val targetApp = TargetSelection.filter(report.unmatched, selectedTargets).firstOrNull()
                ?: report.unmatched.firstOrNull()
                ?: report.matched.firstOrNull()?.app
            val target = targetApp?.let { AppIconLoader.load(context, it.packageName, it.activityName) }
                ?: return@withContext null
            try {
                composeSheet(context, source, current, target)
            } finally {
                target.recycle()
            }
        }
    }

    /** 构建（或取缓存）参考池。 */
    private fun ensureReferencePool(
        context: Application,
        pack: IconPackSource,
        reportOverride: CoverageReport?,
    ): List<ReferencePair> {
        referencePoolCache?.takeIf { it.first == pack.packageName }?.let { return it.second }
        val report = reportOverride ?: CoverageCalculator.compute(
            apps = AppScanner(context).scan(),
            appFilter = pack.document,
            availableDrawables = pack.availableDrawables,
            rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps),
        )
        return ReferencePoolBuilder.build(context, pack, report).also {
            referencePoolCache = pack.packageName to it
        }
    }

    /** 随机抽 [count] 组（包名去重，池不足时返回实际数量）。 */
    private fun sampleDistinct(pool: List<ReferencePair>, count: Int): List<ReferencePair> =
        pool.shuffled()
            .distinctBy { it.packageName }
            .take(count.coerceIn(1, ContactSheetComposer.MAX_PAIRS))

    /** 用固定参考 + 目标原图合成 ContactSheet（PNG 字节）。 */
    private fun composeSheet(
        context: Application,
        pack: IconPackSource,
        references: List<ReferencePair>,
        target: android.graphics.Bitmap,
    ): ByteArray? {
        val pairs = ArrayList<Pair<android.graphics.Bitmap, android.graphics.Bitmap>>()
        for (ref in references) {
            ReferencePoolBuilder.loadReferenceBitmaps(context, pack, ref)?.let { pairs.add(it) }
        }
        if (pairs.isEmpty()) return null
        try {
            return pngBytes(BitmapContactSheet.compose(pairs = pairs, target = target))
        } finally {
            pairs.forEach { (a, b) ->
                a.recycle()
                b.recycle()
            }
        }
    }

    /** 清空批次参考（换包/重扫/新一轮时调用）。 */
    private fun clearBatchReferences() {
        referencePoolCache = null
        batchReferenceCount = 0
        if (_state.value.batchReferences.isNotEmpty()) {
            _state.value = _state.value.copy(batchReferences = emptyList())
        }
    }

    /** 重新取样的参考池缓存（同批同目标只扫一次）。 */
    private data class ShufflePool(val key: String, val pairs: List<ReferencePair>)

    private var shufflePool: ShufflePool? = null


    private fun pngBytes(bitmap: android.graphics.Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** 缩到预览尺寸（长边 [PREVIEW_ICON_SIZE]）后编码 PNG。 */
    private fun previewPng(bitmap: android.graphics.Bitmap): ByteArray {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= PREVIEW_ICON_SIZE) return pngBytes(bitmap)
        val scale = PREVIEW_ICON_SIZE.toFloat() / maxSide
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        return pngBytes(scaled).also {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** 把落库的 attempt 转回内存对象（Done 页预览用）。 */
    private fun buildsAttempt(generationId: String, record: AttemptEntity): GenerationAttempt {
        val projectId = records[generationId]?.projectId
        return GenerationAttempt(
            packageName = record.packageName,
            label = record.label,
            attempt = record.attempt,
            accepted = record.accepted,
            reason = record.reason,
            pngBytes = record.pngFile
                ?.let { projectId?.let { pid -> repository.attemptFile(pid, generationId, it) } }
                ?.readBytes() ?: ByteArray(0),
            references = GenerationJsonCodec.decodeReferences(record.referencesJson),
            sourcePngBytes = record.sourceFile
                ?.let { projectId?.let { pid -> repository.attemptFile(pid, generationId, it) } }
                ?.readBytes(),
            provenance = GenerationProvenance(
                model = record.model,
                slotId = record.slotId,
                slotName = record.slotName,
            ),
            prompt = record.prompt,
            referenceDetails = GenerationJsonCodec.decodeReferenceDetails(record.referenceDetailsJson),
        )
    }

    /** IO 线程执行：打开图标包 → 组装参考 → 调单图标生成管线。 */
    private suspend fun runRegeneration(
        record: GenerationRecord,
        source: AttemptRecord,
        references: List<ReferenceSnapshot>,
        config: dev.artplus.iconpackfiller.provider.ProviderConfig,
        provenance: GenerationProvenance,
    ): GenerationAttempt {
        val context = getApplication<Application>()
        val pack = openPackForRegenerate(record)
            ?: error("图标包源不可用（未安装且本地副本缺失），无法重新请求")
        pack.use {
            val app = AppScanner(context).scan().firstOrNull { a -> a.packageName == source.packageName }
                ?: error("应用未安装或不可见：${source.packageName}")
            val provider = ImageProviderFactory.create(config)
            val attempt = SingleIconRegenerator(context) { message ->
                android.util.Log.w("IconPackFiller", message)
            }.regenerate(
                pack = pack,
                target = app,
                mode = SingleIconRegenerator.Mode.KeepOriginal(references),
                provider = provider,
                provenance = provenance,
                rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps),
                maxAttempts = 1 + settings.maxRetries,
                callLimit = settings.callLimit.takeIf { it > 0 },
                requestTransparentBackground = TransparencyPresets.effective(config.model, config.transparency),
            )
            // 生成内的序号接着原记录排（列表 key 依赖 pkg+attempt 唯一）
            val next = (record.attempts.filter { it.packageName == source.packageName }
                .maxOfOrNull { it.attempt } ?: 0) + 1
            return attempt.copy(attempt = next)
        }
    }

    /**
     * 重新请求用的图标包：已安装优先；其次项目目录内的 `source.apk` 快照
     * （源包卸载/更新后仍可重新请求）；最后退回导入 APK 的缓存副本（包名需匹配）。
     */
    private fun openPackForRegenerate(record: GenerationRecord): IconPackSource? {
        val context = getApplication<Application>()
        if (record.packPackage.isNotEmpty()) {
            IconPackSource.open(context, record.packPackage)?.let { return it }
        }
        repository.sourceApk(record.projectId)?.let { snapshot ->
            IconPackSource.open(context, snapshot)?.let { return it }
        }
        val cached = File(context.cacheDir, "source-iconpack.apk")
        if (!cached.exists()) return null
        val opened = IconPackSource.open(context, cached) ?: return null
        if (record.packPackage.isEmpty() || opened.packageName == record.packPackage) return opened
        opened.close()
        return null
    }

    /** 把历史生成设为当前导出对象（详情页「导出 APK」用）。 */
    fun exportBatch(batchId: String): File? {
        val record = records[batchId] ?: return null
        return repository.outputApk(record.projectId, batchId, record.outputApk)
    }

    fun batchFileName(batchId: String): String {
        val record = records[batchId] ?: return "iconpack_filler.apk"
        return record.outputApkName ?: "iconpack_filler.apk"
    }

    /** 开始新一轮：清空 session 与结果状态，回到 Pick。 */
    fun startOver() {
        clearSession()
        _state.value = UiState(packs = _state.value.packs)
        navigator.resetTo(Route.Pick)
    }

    /** 已签名的输出文件。 */
    fun signedApk(): File? = session?.signedApk

    fun suggestedFileName(): String {
        val session = session ?: return "iconpack_filler.apk"
        return dev.artplus.iconpackfiller.pack.SafExporter.fileNameFor(
            session.originalPackage,
            session.originalVersionCode,
        )
    }

    fun generatedIcons(): List<GeneratedIcon> = session?.generated.orEmpty()

    private fun clearSession() {
        session = null
        sessionGenerationId = null
        shufflePool = null
        clearBatchReferences()
        _state.value = _state.value.copy(
            hasResult = false,
            packLabel = null,
            outputApkPath = null,
            generatedCount = 0,
            failedCount = 0,
            totalToGenerate = 0,
        )
    }

    private fun handleProgress(progress: FillerOrchestrator.Progress) {
        val current = _state.value
        _state.value = when (progress) {
            is FillerOrchestrator.Progress.ScanDone ->
                current.copy(report = progress.report, statusText = "已扫描 ${progress.report.total} 个应用")
            is FillerOrchestrator.Progress.PlanDone ->
                current.copy(
                    totalToGenerate = progress.planCount,
                    statusText = "计划生成 ${progress.planCount} 个图标（参考 ${progress.referenceCount} 组）",
                )
            is FillerOrchestrator.Progress.GenerationStarted ->
                current.copy(totalToGenerate = progress.total, statusText = "开始生成…")
            is FillerOrchestrator.Progress.LimitReached ->
                current.copy(statusText = "已达调用上限（${progress.description}），未处理的应用已跳过")
            is FillerOrchestrator.Progress.GenerationProgress ->
                current.copy(
                    generatedCount = progress.succeeded,
                    failedCount = progress.failed,
                    totalToGenerate = progress.total,
                    statusText = "生成中 ${progress.done}/${progress.total}",
                )
            is FillerOrchestrator.Progress.GenerationDone ->
                current.copy(
                    generatedCount = progress.generated,
                    failedCount = progress.failed,
                    statusText = "生成完成：成功 ${progress.generated}，失败 ${progress.failed}",
                )
            is FillerOrchestrator.Progress.PackStarted ->
                current.copy(phase = Phase.PACKING, statusText = "打包 ${progress.injecting} 个图标…")
            is FillerOrchestrator.Progress.PackDone ->
                current.copy(statusText = "打包完成：${progress.result.label}")
            is FillerOrchestrator.Progress.Failed ->
                current.copy(error = "${progress.stage}: ${progress.message}")
        }
    }

    private data class SourceMeta(
        val packageName: String,
        val versionCode: Int,
        val hash: String,
        val apkFile: File,
    )

    /** 打开已解析的图标包来源解析源元数据（包名/版本/内容哈希/源 APK 文件），用于建项目与快照。 */
    private fun openSourceMeta(
        context: Application,
        resolved: GenerationSourceResolver.Resolved,
    ): SourceMeta? {
        val pack = when {
            resolved.installedPackage != null -> IconPackSource.open(context, resolved.installedPackage)
            resolved.apkFile != null -> IconPackSource.open(context, resolved.apkFile)
            else -> null
        } ?: return null
        return pack.use {
            val apk = it.sourceApk
            SourceMeta(
                packageName = it.packageName,
                versionCode = it.versionCode,
                hash = sha256(apk),
                apkFile = apk,
            )
        }
    }

    private fun sha256(file: File): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private suspend fun copyApkToCache(uri: Uri?): File? {
        if (uri == null) return null
        val context = getApplication<Application>()
        return withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, "source-iconpack.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target
        }
    }
}
