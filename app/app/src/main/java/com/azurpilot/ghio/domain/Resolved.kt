package com.azurpilot.ghio.domain

import com.azurpilot.ghio.R
import com.azurpilot.ghio.i18n.UiText
import com.azurpilot.ghio.i18n.uiTextOf

/**
 * 定义 × 用户状态的只读投影；不持久化、非执行结果
 *
 * Resolver 把 [ProjectDefinition] 与 [UserConfiguration] 合并后的 UI 视图模型：
 * 每次配置或环境变化重算，UI 只读不回写；要执行另走 Builder 编译出的 RunPlan。
 *
 * The read-only projection of definitions × user state; not persisted and not
 * an execution result.
 *
 * The UI view model produced by the Resolver merging [ProjectDefinition] with
 * [UserConfiguration]: recomputed on every configuration or environment
 * change, read-only for the UI; execution goes through the RunPlan compiled by
 * the Builder instead.
 */
data class ResolvedProjectSession(
    val configurationList: List<ResolvedRunConfiguration>,
    val activeConfiguration: ResolvedRunConfiguration?,
    val taskCatalog: List<TaskCatalogGroup>,
    /** PI `global_option[]` 的编辑投影，按声明顺序；不随运行配置走 / The editor projection of the PI `global_option[]`, in declaration order; does not follow the run configuration. */
    val globalOptions: List<OptionEditorState>,
    /** 当前选中 resource 的 `option[]`；换资源换这份，值按 resource name 分桶 / The selected resource's `option[]`; swapping the resource swaps this list, values are bucketed by resource name. */
    val resourceOptions: List<OptionEditorState> = emptyList(),
    val environment: ResolvedEnvironment,
    val diagnostics: List<Diagnostic>,
)

/**
 * 当前解析出的运行环境：controller 名与资源选择 / The resolved run environment:
 * the controller name and the resource selection.
 */
data class ResolvedEnvironment(
    val controllerName: String,
    val resource: ResolvedResource?,
    val resourceCandidates: List<ResolvedResource>,
)

/**
 * 资源的解析投影；[name] 是匹配用内部名，UI 展示 [label] / A resource's resolved
 * projection; [name] is the internal name used for matching, the UI shows [label].
 */
data class ResolvedResource(
    val name: String,
    val label: String,
    val icon: String? = null,
)

/**
 * 一条运行配置的解析投影 / The resolved projection of one run configuration.
 */
data class ResolvedRunConfiguration(
    val id: RunConfigurationId,
    val name: String,
    val isActive: Boolean,
    val tasks: List<ResolvedConfiguredTask>,
)

/**
 * 任务不适用原因的文案构造点 / Construction point for "task unavailable" reason
 * texts.
 *
 * `applicable` 才是判定位；这里只承担展示，切语言后由 UI 重新解析
 * / The [ResolvedConfiguredTask.applicable] bit is the actual predicate; this
 * object only handles display, and the UI re-resolves it after a language
 * switch.
 */
object UnavailableReasons {
    /** 返回「任务定义缺失」文案 / Returns the "task definition missing" text. */
    fun missingDefinition(): UiText = uiTextOf(R.string.task_unavailable_missing)

    /** 返回「controller 不满足」文案，列出缺失项 / Returns the "controller mismatch" text listing the required ones. */
    fun controllerMismatch(required: List<String>): UiText =
        uiTextOf(R.string.task_unavailable_controller, required.joinToString())

    /** 返回「resource 不满足」文案，列出缺失项 / Returns the "resource mismatch" text listing the required ones. */
    fun resourceMismatch(required: List<String>): UiText =
        uiTextOf(R.string.task_unavailable_resource, required.joinToString())
}

/**
 * 配置内单任务的解析投影：意图（enabled）与环境判定（applicable）分离
 *
 * The resolved projection of one task inside a configuration: the intent
 * ([enabled]) and the environment verdict ([applicable]) are kept apart.
 */
data class ResolvedConfiguredTask(
    val instanceId: String,
    val taskName: String,
    val label: String,
    val description: String?,
    val enabled: Boolean,
    val applicable: Boolean,
    val missingDefinition: Boolean,
    val unavailableReason: UiText?,
    val options: List<OptionEditorState>,
    val icon: String? = null,
) {
    /** 派生态，不写回；环境恢复后 enabled 意图自动生效 / Derived state, never written back; once the environment recovers the enabled intent takes effect again. */
    val effectiveEnabled: Boolean get() = enabled && applicable && !missingDefinition

    /** 是否带有可编辑 option / Whether the task has editable options. */
    val hasOptions: Boolean get() = options.isNotEmpty()
}

/**
 * 任务目录的分组条目 / A group entry of the task catalog.
 */
data class TaskCatalogGroup(
    val groupName: String,
    val label: String,
    val tasks: List<TaskCatalogItem>,
    val icon: String? = null,
    /** 未分组兜底；UI 用资源显示组名，不展示 label 原文 / The ungrouped fallback; the UI resolves the group name from resources instead of showing the raw label. */
    val isUngrouped: Boolean = false,
)

/**
 * 任务目录里的单条目 / A single entry of the task catalog.
 */
data class TaskCatalogItem(
    val taskName: String,
    val label: String,
    val description: String?,
    val applicable: Boolean,
    val unavailableReason: UiText?,
    val defaultChecked: Boolean,
    val icon: String? = null,
)

/**
 * option 控件种类，与 [OptionDefinition] 的四个子类一一对应
 *
 * The option control kind, one-to-one with the four subtypes of
 * [OptionDefinition].
 */
enum class OptionKind { Select, Switch, Checkbox, Input }

/**
 * option 编辑投影；UI 按 [kind] 选控件，不递归解释原始 PI JSON
 *
 * The editor projection of an option; the UI picks its control by [kind]
 * instead of recursively interpreting the raw PI JSON.
 */
data class OptionEditorState(
    val name: String,
    val label: String,
    val description: String?,
    val kind: OptionKind,
    /** 子 option 嵌套深度，0 为顶层；用于缩进展示 / Nesting depth among child options, 0 for the top level; used for indentation. */
    val depth: Int,
    /** null = Unset / null means Unset. */
    val value: OptionValue?,
    val cases: List<OptionCaseState>,
    val inputs: List<InputFieldState>,
    val icon: String? = null,
) {
    /** 含默认回退；Select/Switch 至多一个，Checkbox 按声明序 / Includes the default fallback; at most one for Select/Switch, in declaration order for Checkbox. */
    val activeCases: List<OptionCaseState> get() = cases.filter { it.active }
}

/**
 * 编辑态里的单个 case；[children] 只在 active 时物化
 *
 * One case in the editor state; [children] is materialized only while active.
 */
data class OptionCaseState(
    val name: String,
    val label: String,
    val description: String?,
    val icon: String? = null,
    val active: Boolean,
    /** 仅 active branch 物化子树；dormant 值留在持久层 / Only the active branch materializes its subtree; dormant values stay in the persistence layer. */
    val children: List<OptionEditorState>,
)

// Switch 的 case 名按这些词识别 on 态；覆盖生态里常见的写法（yes/on/true/enable 等）
private val SWITCH_ON_NAMES = setOf("yes", "y", "on", "true", "enable")

/**
 * 返回标准两态 Switch 的 (on, off) 对；非标准形态返回 null，UI 回落 chip 平铺
 *
 * Returns the (on, off) pair of a standard two-state Switch; returns null for
 * a non-standard shape, where the UI falls back to laying the cases out as
 * chips.
 */
fun OptionEditorState.standardSwitchCases(): Pair<OptionCaseState, OptionCaseState>? {
    if (kind != OptionKind.Switch || cases.size != 2) return null
    val onCase = cases.firstOrNull { it.name.lowercase() in SWITCH_ON_NAMES } ?: return null
    val offCase = cases.firstOrNull { it != onCase } ?: return null
    return onCase to offCase
}

/**
 * Input 单字段的编辑态 / The editor state of one Input field.
 */
data class InputFieldState(
    val name: String,
    val label: String,
    val pipelineType: PipelineType,
    val value: String,
    val default: String,
    val verify: Regex?,
    val patternMessage: String?,
    val description: String?,
)

/**
 * 校验输入候选值；UI 即时校验与 Builder 复验共用（docs/domain-model.md §6.6）
 *
 * Validates an input candidate; shared by the UI's immediate validation and
 * the Builder's re-validation (docs/domain-model.md §6.6).
 *
 * @param type 字段的目标类型 / The field's target type
 * @param verify 声明的正则约束，null 表示无 / The declared regex constraint, null
 *   for none
 * @param candidate 待校验的字符串值 / The string value to validate
 * @return 类型与正则都通过时为 true / true when both the type and the regex pass
 */
fun validateInputCandidate(type: PipelineType, verify: Regex?, candidate: String): Boolean {
    val typeOk = when (type) {
        PipelineType.StringType -> true
        PipelineType.IntType -> candidate.isEmpty() || candidate.toLongOrNull() != null
        PipelineType.BoolType -> candidate == "true" || candidate == "false"
    }
    if (!typeOk) return false
    return verify == null || verify.matches(candidate)
}
