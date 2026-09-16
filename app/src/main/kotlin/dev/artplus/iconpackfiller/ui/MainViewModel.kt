package dev.artplus.iconpackfiller.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.artplus.iconpackfiller.batch.BatchRecord
import dev.artplus.iconpackfiller.batch.BatchStatus
import dev.artplus.iconpackfiller.batch.BatchStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 业务阶段（与导航解耦）：Progress 页标题/内容由它驱动。
 */
enum class Phase { IDLE, ANALYZING, GENERATING, PACKING }

/** 「选择应用」弹窗缩略图的长边（px）。 */
private const val PREVIEW_ICON_SIZE = 128

/** 正在重新请求的目标（对比行上的长按操作）；null 表示空闲。 */
data class Regenerating(val packageName: String, val attempt: Int)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val packs: List<IconPackFinder.InstalledIconPackInfo> = emptyList(),
    val selectedPack: IconPackFinder.InstalledIconPackInfo? = null,
    val selectedApkUri: Uri? = null,
    val selectedApkName: String? = null,
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
    /** 当前正在执行的批次 id（用于增量落盘）；未运行时为 null。 */
    val activeBatchId: String? = null,
    /** 正在重新请求的对比行；null 表示空闲。 */
    val regenerating: Regenerating? = null,
    /** 历史批次列表（Pick 页展示入口；详情页用 [batchDetail]）。 */
    val batches: List<BatchRecord> = emptyList(),
    /** 当前查看的批次详情（Batches/BatchDetail/Done 页共用）。 */
    val batchDetail: BatchRecord? = null,
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val batches = BatchStore(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // 进程被杀后残留的 RUNNING 批次无法恢复，启动时标记为中断并刷新列表
        viewModelScope.launch {
            withContext(Dispatchers.IO) { batches.markInterrupted() }
            val list = withContext(Dispatchers.IO) { batches.list() }
            _state.value = _state.value.copy(batches = list)
        }
    }

    /** 返回栈（导航唯一事实源）。 */
    val navigator = Navigator(Route.START)

    private var runJob: Job? = null
    private var session: FillerOrchestrator.Session? = null

    /** 最近一次运行对应的批次 id（Done 页实时追加重新请求结果用）。 */
    private var sessionBatchId: String? = null

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
            error = null,
        )
    }

    fun selectApk(uri: Uri, name: String?) {
        clearSession()
        _state.value = _state.value.copy(
            selectedApkUri = uri,
            selectedApkName = name,
            selectedPack = null,
            error = null,
        )
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
                val apkFile = withContext(Dispatchers.IO) { copyApkToCache(state.selectedApkUri) }
                val source = withContext(Dispatchers.IO) {
                    when {
                        state.selectedPack != null ->
                            dev.artplus.iconpackfiller.generate.IconPackSource.open(context, state.selectedPack.packageName)
                        apkFile != null -> dev.artplus.iconpackfiller.generate.IconPackSource.open(context, apkFile)
                        else -> null
                    }
                } ?: error("无法打开图标包")
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

        // 创建批次记录：一次「执行任务」= 一个批次，之后进度/结果都能从列表点回来
        val packLabel = state.selectedPack?.label ?: state.selectedApkName ?: "图标包"
        // 导入 APK 时先为空，跑完从解析出的包名回填（「重新生成」要靠它找图标包源）
        var packPackage = state.selectedPack?.packageName ?: ""
        val batchId = batches.newId()
        sessionBatchId = batchId
        val batchStartedAt = System.currentTimeMillis()

        _state.value = state.copy(
            phase = Phase.GENERATING,
            error = null,
            statusText = "准备…",
            activeBatchId = batchId,
        )

        runJob = viewModelScope.launch {
            val scope = this
            val diagnostics = java.util.Collections.synchronizedList(arrayListOf<String>())
            // 每条 attempt 完成即落盘：取消/崩溃也保得住已生成的图
            val persistedAttempts = java.util.Collections.synchronizedList(arrayListOf<dev.artplus.iconpackfiller.batch.AttemptRecord>())

            fun persistBatch(status: BatchStatus, planCount: Int, generated: Int, failed: Int, outputApk: String?, outputApkName: String?) {
                val existing = batches.read(batchId)
                val record = BatchRecord(
                    id = batchId,
                    packLabel = packLabel,
                    packPackage = packPackage,
                    createdAt = batchStartedAt,
                    status = status,
                    plannedCount = if (planCount > 0) planCount else existing?.plannedCount ?: 0,
                    generatedCount = generated,
                    failedCount = failed,
                    outputApk = outputApk ?: existing?.outputApk,
                    outputApkName = outputApkName ?: existing?.outputApkName,
                    diagnostics = diagnostics.toList(),
                    attempts = persistedAttempts.toList(),
                )
                batches.save(record)
                // 批次进度同步给界面（列表/详情页实时看到）
                _state.value = _state.value.copy(
                    batches = batches.list(),
                    batchDetail = batches.read(batchId),
                )
            }

            // 先落一条 RUNNING，列表里立刻能看到这个批次
            withContext(Dispatchers.IO) {
                persistBatch(BatchStatus.RUNNING, planCount = 0, generated = 0, failed = 0, outputApk = null, outputApkName = null)
            }

            try {
                val context = getApplication<Application>()
                val workDir = File(context.cacheDir, "filler-work").apply { mkdirs() }
                val apkFile = withContext(Dispatchers.IO) { copyApkToCache(state.selectedApkUri) }
                val rules = CoverageRules.DEFAULT.copy(excludeSystemApps = settings.excludeSystemApps)
                // 确认范围页敲定的参考优先整批固定；没挑过则按目标配色自动选
                val referenceOverride = state.batchReferences.takeIf { it.isNotEmpty() }
                val input = when {
                    state.selectedPack != null -> FillerOrchestrator.Input(
                        installedPackage = state.selectedPack.packageName,
                        rules = rules,
                        referencePairCount = settings.referencePairCount,
                        maxGenerationAttempts = 1 + settings.maxRetries,
                        concurrency = config.concurrency,
                        callLimit = settings.callLimit.takeIf { it > 0 },
                        selectedTargets = selection,
                        referenceOverride = referenceOverride,
                        requestTransparentBackground = requestTransparent,
                    )
                    apkFile != null -> FillerOrchestrator.Input(
                        apkFile = apkFile,
                        rules = rules,
                        referencePairCount = settings.referencePairCount,
                        maxGenerationAttempts = 1 + settings.maxRetries,
                        concurrency = config.concurrency,
                        callLimit = settings.callLimit.takeIf { it > 0 },
                        selectedTargets = selection,
                        referenceOverride = referenceOverride,
                        requestTransparentBackground = requestTransparent,
                    )
                    else -> error("未选择图标包")
                }
                val provider = ImageProviderFactory.create(config)
                val activeSlot = settings.activeSlot
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
                        // 失败原因要能回看：诊断同步进批次记录（截断防止 JSON 过大）
                        diagnostics.add(message.take(300))
                    },
                )
                // 编排（位图合成/ARSCLib 打包/apksig 签名）全部在 IO 线程执行，避免主线程 ANR
                val result = withContext(Dispatchers.IO) {
                    orchestrator.run(
                        input = input,
                        onProgress = { progress ->
                            handleProgress(progress)
                            // 进度增量落盘：切后台/被杀后回来也能看到真实进度
                            when (progress) {
                                is FillerOrchestrator.Progress.PlanDone ->
                                    persistBatch(
                                        BatchStatus.RUNNING,
                                        planCount = progress.planCount,
                                        generated = 0,
                                        failed = 0,
                                        outputApk = null,
                                        outputApkName = null,
                                    )
                                is FillerOrchestrator.Progress.GenerationProgress ->
                                    persistBatch(
                                        BatchStatus.RUNNING,
                                        planCount = progress.total,
                                        generated = progress.succeeded,
                                        failed = progress.failed,
                                        outputApk = null,
                                        outputApkName = null,
                                    )
                                else -> Unit
                            }
                        },
                        shouldCancel = { !scope.isActive },
                        onAttempt = { attempt ->
                            val record = batches.persistAttempt(batchId, attempt)
                            persistedAttempts.add(record)
                            batches.read(batchId)?.let { current ->
                                batches.save(current.copy(attempts = persistedAttempts.toList()))
                            }
                        },
                    )
                }
                session = result
                if (packPackage.isEmpty()) packPackage = result.originalPackage
                withContext(Dispatchers.IO) {
                    persistBatch(
                        status = BatchStatus.COMPLETED,
                        planCount = result.plans.size,
                        generated = result.generated.size,
                        failed = result.plans.size - result.generated.size,
                        outputApk = batches.persistApk(batchId, result.signedApk),
                        // 导出时给用户看的文件名（不要暴露内部 signed.apk）
                        outputApkName = result.signedApk?.let {
                            dev.artplus.iconpackfiller.pack.SafExporter.fileNameFor(
                                result.originalPackage,
                                result.originalVersionCode,
                            )
                        },
                    )
                }
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    packLabel = result.packResult?.label,
                    outputApkPath = result.signedApk?.absolutePath,
                    attempts = result.attempts,
                    statusText = "",
                    hasResult = result.signedApk != null,
                    activeBatchId = null,
                    batchDetail = batches.read(batchId),
                )
                navigator.resetTo(Route.Pick, Route.Done)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消：保住已生成的图，批次标 CANCELLED 供回看
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    persistBatch(BatchStatus.CANCELLED, 0, 0, 0, null, null)
                }
                throw e
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    diagnostics.add("${e::class.simpleName}: ${e.message?.take(200)}")
                    persistBatch(BatchStatus.FAILED, 0, 0, 0, null, null)
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

    // ---------- 批次（历史任务） ----------

    /** 重新读取历史批次列表。 */
    private fun refreshBatches() {
        val list = batches.list()
        _state.value = _state.value.copy(batches = list)
    }

    /** 打开批次列表页。 */
    fun openBatches() {
        refreshBatches()
        navigator.push(Route.Batches)
    }

    /** 打开某个批次的详情（进度 + 对比）。 */
    fun openBatch(id: String) {
        _state.value = _state.value.copy(batchDetail = batches.read(id))
        navigator.push(Route.BatchDetail(id))
    }

    fun batchRecord(id: String): BatchRecord? = batches.read(id)

    /** 批次目录里的图/APK 文件（详情页渲染用）。 */
    fun batchAttemptFile(batchId: String, fileName: String?): File? =
        batches.attemptFile(batchId, fileName)

    fun batchOutputApk(batchId: String, fileName: String?): File? =
        batches.outputApk(batchId, fileName)

    /** 删除批次记录。 */
    fun deleteBatch(id: String) {
        batches.delete(id)
        if (_state.value.batchDetail?.id == id) {
            _state.value = _state.value.copy(batchDetail = null)
        }
        refreshBatches()
    }

    /**
     * 对批次里的一次生成重新发起请求（「生成详情」弹窗的「重新生成」）。
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
        val record = batches.read(batchId) ?: return
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
                    val record2 = batches.persistAttempt(batchId, result)
                    val latest = batches.read(batchId) ?: record
                    batches.save(latest.copy(attempts = latest.attempts + record2))
                    record2
                }
                _state.value = _state.value.copy(
                    regenerating = null,
                    batchDetail = batches.read(batchId),
                    batches = batches.list(),
                    // Done 页列表用的是内存 attempt：同批次的重新请求实时补进去
                    attempts = if (sessionBatchId == batchId) {
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
        val record = batches.read(batchId) ?: return@withContext emptyList()
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
     * 全程本地、不发起 provider 请求；池子按「批次 + 目标」缓存在内存，
     * 后续刷新只重渲染抽中的几组（弹窗里的「不断替换」靠它保持轻量）。
     */
    suspend fun sampleReferences(
        batchId: String,
        packageName: String,
        count: Int,
    ): SampledReferences? = withContext(Dispatchers.IO) {
        val record = batches.read(batchId) ?: return@withContext null
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

    /** 把落盘的 attempt 转回内存对象（Done 页预览用）。 */
    private fun buildsAttempt(
        batchId: String,
        record: dev.artplus.iconpackfiller.batch.AttemptRecord,
    ): GenerationAttempt = GenerationAttempt(
        packageName = record.packageName,
        label = record.label,
        attempt = record.attempt,
        accepted = record.accepted,
        reason = record.reason,
        pngBytes = record.pngFile?.let { batches.attemptFile(batchId, it)?.readBytes() } ?: ByteArray(0),
        references = record.references,
        sourcePngBytes = record.sourceFile?.let { batches.attemptFile(batchId, it)?.readBytes() },
        provenance = GenerationProvenance(
            model = record.model,
            slotId = record.slotId,
            slotName = record.slotName,
        ),
        prompt = record.prompt,
        referenceDetails = record.referenceDetails,
    )

    /** IO 线程执行：打开图标包 → 组装参考 → 调单图标生成管线。 */
    private suspend fun runRegeneration(
        record: BatchRecord,
        source: dev.artplus.iconpackfiller.batch.AttemptRecord,
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
            // 批次内的序号接着原记录排（列表 key 依赖 pkg+attempt 唯一）
            val next = (record.attempts.filter { it.packageName == source.packageName }
                .maxOfOrNull { it.attempt } ?: 0) + 1
            return attempt.copy(attempt = next)
        }
    }

    /** 重新请求用的图标包：已安装优先；导入的 APK 用缓存副本（包名需匹配）。 */
    private fun openPackForRegenerate(record: BatchRecord): IconPackSource? {
        val context = getApplication<Application>()
        if (record.packPackage.isNotEmpty()) {
            IconPackSource.open(context, record.packPackage)?.let { return it }
        }
        val cached = File(context.cacheDir, "source-iconpack.apk")
        if (!cached.exists()) return null
        val opened = IconPackSource.open(context, cached) ?: return null
        if (record.packPackage.isEmpty() || opened.packageName == record.packPackage) return opened
        opened.close()
        return null
    }

    /** 把历史批次设为当前导出对象（详情页「导出 APK」用）。 */
    fun exportBatch(batchId: String): File? {
        val record = batches.read(batchId) ?: return null
        return batches.outputApk(batchId, record.outputApk)
    }

    fun batchFileName(batchId: String): String {
        val record = batches.read(batchId) ?: return "iconpack_filler.apk"
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
        sessionBatchId = null
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
