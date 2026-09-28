package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.azurpilot.ghio.theme.AppTokens

/**
 * 渲染单选 chip 组
 *
 * 用 M3 的 [FilterChip] 平铺：选项多、标签长短不一时 FlowRow 能逐颗换行，
 * 换成等分的 SegmentedButton 长标签会把整行挤变形
 *
 * Renders a single-choice chip group.
 *
 * Lays out M3 [FilterChip]s in a flow: with many options and labels of uneven
 * length, FlowRow wraps chip by chip, whereas equally divided SegmentedButtons
 * would squeeze the whole row out of shape.
 *
 * @param options 选项列表：first 为回传值、second 为展示标签 / options where first
 *   is the value passed to [onSelect] and second is the displayed label
 * @param selected 当前选中值 / currently selected value
 * @param onSelect 点选回调，回传所选值 / invoked with the picked value on tap
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> AppSingleChoiceFlow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    arrangement: Alignment.Horizontal = Alignment.Start,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm, arrangement),
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label) },
                // 勾是 M3 filter chip 选中态的规范画法：底色深浅这一条线索太弱，
                // 一眼扫过去分不出选的是哪颗
                leadingIcon = if (selected == value) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
