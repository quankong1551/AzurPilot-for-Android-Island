package com.azurpilot.ghio.ui.i18n

import com.azurpilot.ghio.R
import com.azurpilot.ghio.domain.DiagnosticSeverity
import com.azurpilot.ghio.domain.TaskCatalogGroup
import com.azurpilot.ghio.i18n.UiText
import com.azurpilot.ghio.i18n.uiTextFromProject
import com.azurpilot.ghio.i18n.uiTextOf
import com.azurpilot.ghio.i18n.uiTextPlural

/**
 * 领域值 -> 文案
 *
 * 一律返回 [UiText] 而不是 `@Composable fun …: String`：后者把文案锁死在组合里，
 * 通知栏、日志导出这些同样要展示同一句话的地方就得再写一遍
 *
 * Domain values -> copy.
 *
 * Everything returns [UiText] instead of a `@Composable fun …: String`: the latter
 * pins copy inside composition, forcing notification and log-export sites that
 * show the very same sentence to write it again.
 */

/**
 * 返回诊断严重级的文案
 *
 * Returns the copy for a diagnostic severity.
 */
fun DiagnosticSeverity.asUiText(): UiText = when (this) {
    DiagnosticSeverity.Warning -> uiTextOf(R.string.diagnostic_severity_warning)
    DiagnosticSeverity.Error -> uiTextOf(R.string.diagnostic_severity_error)
}

/**
 * 返回任务分组的文案；合成「未分组」组的名字走资源，真实分组 label 是 PI 数据，原样展示
 *
 * Returns the copy for a task catalog group; the synthesized "ungrouped" name
 * comes from a resource, while a real group's label is PI data shown verbatim.
 */
fun TaskCatalogGroup.asUiText(): UiText =
    if (isUngrouped) uiTextOf(R.string.tasks_ungrouped) else uiTextFromProject(label)

/**
 * 返回诊断摘要文案；有错误时才切到带错误数的那条，两条的选形数不同，不能合并
 *
 * Returns the diagnostics summary copy; switches to the error-counting variant
 * only when errors exist — the two plural forms count different things and cannot
 * merge.
 */
fun diagnosticsSummaryUiText(total: Int, errors: Int): UiText =
    if (errors > 0) {
        uiTextPlural(R.plurals.home_diagnostics_summary_with_errors, errors, total, errors)
    } else {
        uiTextOf(R.string.home_diagnostics_summary, total)
    }
