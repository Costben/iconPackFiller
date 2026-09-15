package dev.artplus.iconpackfiller.ui.screen

import android.widget.Toast
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.reference.ContactSheetComposer
import dev.artplus.iconpackfiller.settings.ProviderSlot
import dev.artplus.iconpackfiller.provider.ApiProtocol
import dev.artplus.iconpackfiller.provider.GatewayModel
import dev.artplus.iconpackfiller.provider.TransparencyPreference
import dev.artplus.iconpackfiller.provider.TransparencyPresets
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.component.CompactDropdownPreference
import dev.artplus.iconpackfiller.ui.component.ContactSheetPreview
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import dev.artplus.iconpackfiller.ui.component.ModelPickerDialog
import dev.artplus.iconpackfiller.ui.theme.ColorMode
import dev.artplus.iconpackfiller.ui.theme.ThemeMode
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 设置页（独立 NavKey）。
 *
 * 除 AI 供应商区域的「保存」按钮外，其余设置全部自动保存。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onColorModeChange: (ColorMode) -> Unit,
    onBack: () -> Unit,
) {
    val settings = remember { viewModel.settingsStore() }
    var slots by remember { mutableStateOf(settings.providerSlots) }
    val active = slots.active

    // 已解密 Key 缓存：Keystore 解密耗时，不能放进组合期反复调用
    var keysById by remember {
        mutableStateOf(settings.providerSlots.slots.associate { it.id to settings.loadApiKey(it.id) })
    }
    fun reloadKeys() {
        keysById = settings.providerSlots.slots.associate { it.id to settings.loadApiKey(it.id) }
    }

    // 槽位切换时重建输入态（未保存的编辑随槽位切换丢弃）
    val slotKey = active.id
    val slotName = remember(slotKey) { TextFieldState(active.name) }
    val baseUrl = remember(slotKey) { TextFieldState(active.baseUrl) }
    val modelName = remember(slotKey) { TextFieldState(active.model) }
    val apiKey = remember(slotKey) { TextFieldState(keysById[slotKey].orEmpty()) }

    var protocol by remember(slotKey) { mutableStateOf(settings.apiProtocol) }
    var transparency by remember(slotKey) { mutableStateOf(settings.transparencyPreference) }
    var gatewayModels by remember { mutableStateOf<List<GatewayModel>>(emptyList()) }
    var modelSourceLabel by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }

    // Key 输入框：默认掩码显示，聚焦时显示明文便于校对（不用 password 键盘类型，避免自动填充提示）
    val keyInteraction = remember { MutableInteractionSource() }
    val keyFocused by keyInteraction.collectIsFocusedAsState()
    val keyMask = remember {
        OutputTransformation {
            if (length > 0) replace(0, length, "•".repeat(length))
        }
    }

    var concurrency by remember { mutableStateOf(settings.concurrency.toFloat()) }
    var refPairs by remember { mutableStateOf(settings.referencePairCount.toFloat()) }
    var callLimit by remember { mutableStateOf(settings.callLimit.toFloat()) }
    var excludeSystem by remember { mutableStateOf(settings.excludeSystemApps) }
    var colorMode by remember { mutableStateOf(ColorMode.fromValue(settings.colorMode)) }
    var statusText by remember { mutableStateOf("") }
    val layoutDirection = LocalLayoutDirection.current

    MiuixScreen(
        title = "设置",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = colorScheme.onSurface,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                        },
                )
            }
        },
    ) {
        item(key = "api-title") {
            SmallTitle(text = "AI 模型设置", modifier = Modifier.padding(top = 8.dp))
        }

        // 一个供应商是一整块：顶层下拉切供应商，下面是它的协议/透明/表单/按钮。
        item(key = "api-supplier") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Column {
                CompactDropdownPreference(
                    title = "供应商",
                    items = slots.slots.map { slot ->
                        val suffix = if (keysById[slot.id].isNullOrBlank()) "（未配置 Key）" else ""
                        "${slot.name}$suffix"
                    },
                    selectedIndex = slots.slots.indexOfFirst { it.id == active.id }.coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val target = slots.slots.getOrNull(index)
                        if (target != null) {
                            settings.setActiveSlot(target.id)
                            slots = settings.providerSlots
                            statusText = ""
                        }
                    },
                )
                CompactDropdownPreference(
                    title = "接口协议",
                    summary = protocol.summaryLine,
                    items = ApiProtocol.entries.map { it.menuLabel },
                    selectedIndex = ApiProtocol.entries.indexOf(protocol).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val picked = ApiProtocol.entries.getOrNull(index)
                            ?: return@CompactDropdownPreference
                        protocol = picked
                        settings.apiProtocol = picked
                    },
                )
                CompactDropdownPreference(
                    title = "透明直出",
                    summary = TransparencyPresets.summary(
                        model = modelName.text.toString(),
                        preference = transparency,
                    ),
                    items = TransparencyPreference.entries.map { it.label },
                    selectedIndex = TransparencyPreference.entries.indexOf(transparency).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val picked = TransparencyPreference.entries.getOrNull(index)
                            ?: return@CompactDropdownPreference
                        transparency = picked
                        settings.transparencyPreference = picked
                    },
                )
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextField(
                        state = slotName,
                        label = "名称",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        state = baseUrl,
                        label = "Base URL",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            state = modelName,
                            label = "模型",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        )
                        TextButton(
                            text = "获取",
                            onClick = {
                                statusText = "正在获取模型列表…"
                                viewModel.fetchGatewayModels(
                                    baseUrl = baseUrl.text.toString().trim(),
                                    apiKey = apiKey.text.toString(),
                                    protocol = protocol,
                                ) { result ->
                                    result.onSuccess { list ->
                                        gatewayModels = list.models
                                        modelSourceLabel = list.sourceLabel
                                        showModelPicker = true
                                        statusText = "已获取 ${list.models.size} 个模型"
                                    }.onFailure { e ->
                                        statusText = "获取模型失败：${e.message?.take(120) ?: e::class.simpleName}"
                                    }
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    TextField(
                        state = apiKey,
                        label = "API Key",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth(),
                        // 单行 + 掩码显示；keyboardOptions 保持普通文本，避免触发密码管理器/自动填充
                        lineLimits = TextFieldLineLimits.SingleLine,
                        interactionSource = keyInteraction,
                        outputTransformation = if (keyFocused) null else keyMask,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val typedName = slotName.text.toString().trim().take(32)
                            settings.updateActiveSlot {
                                it.copy(
                                    name = typedName.ifBlank { it.name },
                                    baseUrl = baseUrl.text.toString().trim(),
                                    model = modelName.text.toString().trim(),
                                )
                            }
                            val raw = apiKey.text.toString()
                            val savedKey = settings.saveActiveApiKey(raw)
                            // 刷新槽位列表与 Key 缓存，让下拉「（未配置 Key）」同步
                            slots = settings.providerSlots
                            reloadKeys()
                            statusText = when {
                                savedKey.isBlank() -> "已保存（Key 为空）"
                                savedKey != raw.trim() -> "已保存（已过滤非 ASCII 字符）"
                                else -> "已保存"
                            }
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存")
                    }
                    TextButton(
                        text = if (slots.slots.size >= ProviderSlot.MAX_SLOTS) {
                            "已达上限"
                        } else {
                            "另存为"
                        },
                        enabled = slots.slots.size < ProviderSlot.MAX_SLOTS,
                        onClick = {
                            // 先把当前槽位的编辑落盘，再整体复制为新槽位
                            // （名称/URL/模型/Key/透明声明全保留，新槽位名自动加 " (2)"）
                            val typedName = slotName.text.toString().trim().take(32)
                            settings.updateActiveSlot {
                                it.copy(
                                    name = typedName.ifBlank { it.name },
                                    baseUrl = baseUrl.text.toString().trim(),
                                    model = modelName.text.toString().trim(),
                                )
                            }
                            settings.saveActiveApiKey(apiKey.text.toString())
                            val dup = settings.duplicateActiveSlot()
                            if (dup != null) {
                                slots = settings.providerSlots
                                reloadKeys()
                                statusText = "已另存为「${dup.name}」（含 Key 与全部设置）"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    TextButton(
                        text = "删除当前",
                        enabled = slots.slots.size > ProviderSlot.MIN_SLOTS,
                        colors = ButtonDefaults.textButtonColors(
                            color = colorScheme.error,
                            textColor = Color.White,
                        ),
                        onClick = {
                            val removedName = active.name
                            if (settings.removeSlot(slotKey)) {
                                slots = settings.providerSlots
                                reloadKeys()
                                statusText = "已删除「$removedName」"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if ("失败" in statusText || "错误" in statusText) {
                            colorScheme.error
                        } else {
                            colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                }
                }
            }
        }

        item(key = "gen-title") {
            SmallTitle(text = "生成参数", modifier = Modifier.padding(top = 8.dp))
        }
        item(key = "gen") {
            val context = LocalContext.current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Column {
                    SliderPreference(
                        title = "并发数",
                        value = concurrency,
                        onValueChange = {
                            concurrency = it
                            settings.concurrency = it.toInt()
                        },
                        valueRange = 1f..4f,
                        steps = 2,
                        valueText = concurrency.toInt().toString(),
                    )
                    SliderPreference(
                        title = "参考对数量",
                        value = refPairs,
                        onValueChange = {
                            val step = it.roundToInt().coerceIn(1, ContactSheetComposer.MAX_PAIRS)
                            // 划过 3 组继续往上时提醒：参考越多越分散模型注意力
                            if (step > ContactSheetComposer.ROW_CAPACITY &&
                                refPairs.roundToInt() <= ContactSheetComposer.ROW_CAPACITY
                            ) {
                                Toast.makeText(
                                    context,
                                    "更多的参考对数量会影响注意力，请谨慎选择",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            refPairs = it
                            settings.referencePairCount = step
                        },
                        valueRange = 1f..ContactSheetComposer.MAX_PAIRS.toFloat(),
                        steps = ContactSheetComposer.MAX_PAIRS - 2,
                        valueText = refPairs.roundToInt().toString(),
                    )
                    SliderPreference(
                        title = "调用上限",
                        value = callLimit,
                        onValueChange = {
                            callLimit = it
                            settings.callLimit = it.toInt()
                        },
                        valueRange = 0f..200f,
                        steps = 0,
                        valueText = if (callLimit.toInt() == 0) "不限" else callLimit.toInt().toString(),
                    )
                }
            }
        }

        item(key = "sheet-title") {
            SmallTitle(text = "上传给模型的图片", modifier = Modifier.padding(top = 8.dp))
        }
        item(key = "sheet") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                ContactSheetPreview(
                    pairCount = refPairs.roundToInt()
                        .coerceIn(1, ContactSheetComposer.MAX_PAIRS),
                    loadSheet = { count -> viewModel.sampleContactSheet(count) },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        item(key = "appearance-title") {
            SmallTitle(text = "外观", modifier = Modifier.padding(top = 8.dp))
        }
        item(key = "appearance-tab") {
            TabRow(
                tabs = ThemeMode.entries.map { it.label() },
                selectedTabIndex = colorMode.themeMode.ordinal,
                onTabSelected = { index ->
                    val mode = ThemeMode.entries[index]
                    val updated = ColorMode.of(mode, colorMode.monet)
                    colorMode = updated
                    settings.colorMode = updated.value
                    onColorModeChange(updated)
                },
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        item(key = "appearance-monet") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                SwitchPreference(
                    title = "莫奈取色",
                    summary = "从壁纸提取主题色（Android 12+）",
                    checked = colorMode.monet,
                    onCheckedChange = { monet ->
                        val updated = ColorMode.of(colorMode.themeMode, monet)
                        colorMode = updated
                        settings.colorMode = updated.value
                        onColorModeChange(updated)
                    },
                )
            }
        }

        item(key = "rules-title") {
            SmallTitle(text = "扫描规则", modifier = Modifier.padding(top = 8.dp))
        }
        item(key = "rules") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                SwitchPreference(
                    title = "排除系统应用",
                    summary = "系统应用通常不需要补全",
                    checked = excludeSystem,
                    onCheckedChange = {
                        excludeSystem = it
                        settings.excludeSystemApps = it
                    },
                )
            }
        }
    }

    ModelPickerDialog(
        show = showModelPicker,
        models = gatewayModels,
        currentModel = modelName.text.toString().trim(),
        sourceLabel = modelSourceLabel,
        onPick = { model ->
            showModelPicker = false
            modelName.setTextAndPlaceCursorAtEnd(model.name)
            val picked = model.preferredProtocol
            val transparentHint = if (TransparencyPresets.supports(model.name)) {
                "；该模型预设支持透明直出（透明直出：自动判断即可）"
            } else {
                ""
            }
            if (picked != null) {
                protocol = picked
                settings.apiProtocol = picked
                statusText = "已选「${model.name}」→ 协议：${picked.label}$transparentHint；点「保存」写入模型"
            } else {
                statusText = "已选「${model.name}」；网关未声明协议，请手动确认接口协议$transparentHint；点「保存」写入模型"
            }
        },
        onDismiss = { showModelPicker = false },
    )
}
