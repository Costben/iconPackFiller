package dev.artplus.iconpackfiller.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 紧凑下拉：收起状态强制单行，放不下时跑马灯滚动；展开的列表内允许换行。
 *
 * miuix 的 [top.yukonga.miuix.kmp.preference.OverlayDropdownPreference] 对选中值与
 * summary 不做行数限制（长文本自动换行）。这里用公共的 [BasicComponent] +
 * [OverlayDropdownPopup] 重组为「收起单行 + 展开换行」。
 *
 * @param items 列表项文本（同时作为收起状态的选中值）。
 * @param summary 收起状态的说明文字（单行，超长跑马灯）。
 */
@Composable
fun CompactDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }

    val entry = remember(items, selectedIndex, onSelectedIndexChange) {
        DropdownEntry(
            items.mapIndexed { index, item ->
                DropdownItem(
                    text = item,
                    selected = index == selectedIndex,
                    onClick = { onSelectedIndexChange(index) },
                )
            },
        )
    }

    val actualEnabled = enabled && entry.items.isNotEmpty()
    val actionColor = if (actualEnabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }
    val selectedText = entry.items.firstOrNull { it.selected }?.text.orEmpty()

    BasicComponent(
        modifier = modifier,
        interactionSource = interactionSource,
        onClick = {
            if (actualEnabled) {
                expanded = !expanded
                if (expanded) hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        },
        role = Role.DropdownList,
        enabled = actualEnabled,
        endActions = {
            if (selectedText.isNotEmpty()) {
                Text(
                    text = selectedText,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .align(Alignment.CenterVertically)
                        .padding(end = 8.dp)
                        .basicMarquee(iterations = Int.MAX_VALUE),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = actionColor,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                )
            }
            DropdownArrowEndAction(actionColor = actionColor)
            if (entry.items.isNotEmpty()) {
                OverlayDropdownPopup(
                    entry = entry,
                    show = expanded,
                    onDismiss = { expanded = false },
                    onDismissFinished = {},
                    maxHeight = null,
                    dropdownColors = DropdownDefaults.dropdownColors(),
                    renderInRootScaffold = true,
                )
            }
        },
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            },
        )
        if (summary != null) {
            Text(
                text = summary,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
