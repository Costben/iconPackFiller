package dev.artplus.iconpackfiller.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.provider.GatewayModel
import dev.artplus.iconpackfiller.provider.ModelVendor
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「选择模型」对话框：列出网关返回的模型，带协议角标。
 *
 * 选择后由调用方把模型写回输入框，并按 [GatewayModel.preferredProtocol]
 * 自动切换接口协议（网关未声明协议时保持用户当前选择）。
 */
@Composable
fun ModelPickerDialog(
    show: Boolean,
    models: List<GatewayModel>,
    currentModel: String,
    sourceLabel: String,
    onPick: (GatewayModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val filterState = remember(show) { TextFieldState() }
    // 默认只列图片模型（文本模型选了也跑不起来）；关掉可看全量，漏网的生图模型仍可手动填写
    var imageOnly by remember(show) { mutableStateOf(true) }
    val query = filterState.text.toString().trim().lowercase()
    val filtered = remember(models, query, imageOnly) {
        models
            .filter { !imageOnly || it.isImageModel }
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
    }

    WindowDialog(
        show = show,
        title = "选择模型",
        summary = sourceLabel,
        onDismissRequest = onDismiss,
    ) {
        SwitchPreference(
            title = "仅显示图像模型",
            summary = "关闭后显示网关全部模型",
            checked = imageOnly,
            onCheckedChange = { imageOnly = it },
        )
        Spacer(Modifier.height(8.dp))
        TextField(
            state = filterState,
            label = "筛选模型",
            useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text(
                text = "没有匹配的模型",
                style = MiuixTheme.textStyles.footnote1,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(filtered, key = { it.name }) { model ->
                    // 行：厂商徽 | 文本 | Radio（最右）。
                    // RadioButtonPreference 的 radio 位置只能 Start（标题左侧），
                    // 把徽标包在外面会变成「徽 | radio | 文本」，radio 被夹中间，故用自建行。
                    val selected = model.name == currentModel
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onPick(model) }
                            .padding(vertical = 8.dp),
                    ) {
                        VendorBadge(
                            vendor = ModelVendor.forModel(model.name),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.name,
                                style = MiuixTheme.textStyles.body1,
                                color = colorScheme.onSurface,
                            )
                            Text(
                                text = modelSummary(model),
                                style = MiuixTheme.textStyles.footnote1,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        RadioButton(
                            selected = selected,
                            onClick = { onPick(model) },
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(top = 12.dp)) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun modelSummary(model: GatewayModel): String {
    val protocol = when {
        model.protocols.isNotEmpty() -> model.protocols.joinToString(" / ") { it.shortLabel }
        model.endpointTypes.isNotEmpty() -> "协议不支持：" + model.endpointTypes.joinToString()
        else -> "协议未声明"
    }
    return "${ModelVendor.forModel(model.name).label} · $protocol"
}
