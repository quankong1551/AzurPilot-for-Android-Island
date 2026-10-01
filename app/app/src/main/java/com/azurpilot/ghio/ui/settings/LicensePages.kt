package com.azurpilot.ghio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.domain.license.LicenseRepository
import com.azurpilot.ghio.domain.license.OpenSourceComponent
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppFieldLabel
import com.azurpilot.ghio.ui.components.AppInfoRow
import com.azurpilot.ghio.ui.components.AppNavigationRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 渲染开源组件与许可证列表页
 *
 * 展示应用内收录的所有核心内置组件与 Android 运行时依赖项，支持按关键字搜索和按类别筛选；
 * 点击任意条目推入该组件的协议原文详情页。
 *
 * Renders the open-source components and licenses list page.
 *
 * Displays all core bundled components and Android runtime dependencies included
 * in the application, supporting keyword search and category filtering; tapping any
 * entry navigates to its full license text detail page.
 *
 * @param onOpenDetail 导航到特定组件协议详情页的回调 / Callback navigating to component license detail
 * @param onBack 返回上一级回调 / Back callback
 */
@Composable
fun OpenSourceLicensesPage(
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: LicenseRepository = koinInject(),
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 0: 全部 / All, 1: 核心与内置 / Core, 2: 运行库 / Libraries
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }

    val allComponents = remember { repository.getAllComponents() }
    val filteredComponents = remember(searchQuery, filterIndex, allComponents) {
        val base = when (filterIndex) {
            1 -> allComponents.filter { it.isCore }
            2 -> allComponents.filter { !it.isCore }
            else -> allComponents
        }
        if (searchQuery.isBlank()) {
            base
        } else {
            val q = searchQuery.trim()
            base.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.group.contains(q, ignoreCase = true) ||
                    it.artifact.contains(q, ignoreCase = true) ||
                    it.licenseId.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true)
            }
        }
    }

    SettingsSubPage(
        titleRes = R.string.settings_open_source_licenses,
        onBack = onBack,
        modifier = modifier,
        scrollable = false,
    ) {
        // 顶部搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.settings_license_search_placeholder)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null,
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )

        Spacer(modifier = Modifier.height(AppTokens.Spacing.sm))

        // 分类筛选 Chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
        ) {
            FilterChip(
                selected = filterIndex == 0,
                onClick = { filterIndex = 0 },
                label = { Text(stringResource(R.string.settings_license_filter_all)) },
            )
            FilterChip(
                selected = filterIndex == 1,
                onClick = { filterIndex = 1 },
                label = { Text(stringResource(R.string.settings_license_filter_core)) },
            )
            FilterChip(
                selected = filterIndex == 2,
                onClick = { filterIndex = 2 },
                label = { Text(stringResource(R.string.settings_license_filter_libs)) },
            )
        }

        Spacer(modifier = Modifier.height(AppTokens.Spacing.sm))

        if (filteredComponents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppTokens.Spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_license_component_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                items(filteredComponents, key = { it.id }) { component ->
                    OpenSourceComponentCardRow(
                        component = component,
                        onClick = { onOpenDetail(component.id) },
                    )
                }
            }
        }
    }
}

/**
 * 列表中的单项开源组件卡片行
 */
@Composable
private fun OpenSourceComponentCardRow(
    component: OpenSourceComponent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
                ) {
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "v${component.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${component.group}:${component.artifact}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (component.description.isNotBlank()) {
                    Text(
                        text = component.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 许可证 Badge
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (component.isCore) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (component.isCore) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            ) {
                Text(
                    text = component.licenseId,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTokens.IconSize.md),
            )
        }
    }
}

/**
 * 渲染单个开源组件的协议详情页
 *
 * 呈现该组件的完整元数据、官方源码链接以及完整的法律协议正文（包含一键复制协议文本与文本自由选中）。
 *
 * Renders the detail page for one open-source component.
 *
 * Displays full component metadata, official project URL, and the complete legal
 * license text (with copy-to-clipboard action and free text selection).
 *
 * @param componentId 组件唯一标识 / Unique component identifier
 * @param onBack 返回上一级回调 / Back callback
 */
@Suppress("DEPRECATION")
@Composable
fun LicenseDetailPage(
    componentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: LicenseRepository = koinInject(),
    snackbarHostState: SnackbarHostState? = null,
) {
    val component = remember(componentId) { repository.getComponent(componentId) }
    val license = remember(component) { component?.let { repository.getLicense(it.licenseId) } }
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.settings_license_copied)

    if (component == null || license == null) {
        SettingsSubPage(
            title = stringResource(R.string.settings_open_source_licenses),
            onBack = onBack,
            modifier = modifier,
        ) {
            AppCard {
                Text(
                    text = stringResource(R.string.settings_license_component_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    SettingsSubPage(
        title = component.name,
        onBack = onBack,
        modifier = modifier,
        scrollable = true,
    ) {
        // 组件概览卡片
        AppCard {
            Text(
                text = component.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            AppInfoRow(stringResource(R.string.settings_version), component.version)
            AppInfoRow(stringResource(R.string.settings_project), "${component.group}:${component.artifact}")
            AppInfoRow(stringResource(R.string.settings_license_spdx), "${license.name} (${component.licenseId})")
            if (component.description.isNotBlank()) {
                Text(
                    text = component.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppTokens.Spacing.xs),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = AppTokens.Spacing.xs),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AppNavigationRow(
                label = stringResource(R.string.settings_license_view_source),
                description = component.url,
                onClick = { uriHandler.openUri(component.url) },
            )
        }

        // 协议正文卡片
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppFieldLabel(stringResource(R.string.settings_license_text_title))
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(license.text))
                        snackbarHostState?.let { host ->
                            scope.launch { host.showSnackbar(copiedText) }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.settings_license_text_title),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                SelectionContainer {
                    Text(
                        text = license.text,
                        modifier = Modifier.padding(AppTokens.Spacing.md),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3f,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
