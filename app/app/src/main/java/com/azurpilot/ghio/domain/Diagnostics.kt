package com.azurpilot.ghio.domain

import com.azurpilot.ghio.R
import com.azurpilot.ghio.i18n.UiText
import com.azurpilot.ghio.i18n.uiTextOf

/**
 * 诊断严重级别，仅用于诊断列表的展示标签 / Diagnostic severity, used only as the
 * display label in the diagnostics list.
 */
enum class DiagnosticSeverity {
    /** 较轻的问题，UI 展示为「警告」 / A lighter issue, rendered as "Warning". */
    Warning,

    /** 较重的问题，UI 展示为「错误」 / A heavier issue, rendered as "Error". */
    Error,
}

/**
 * 诊断条目：跨 ProjectLoad / Session / Runtime 各阶段汇总的结构化问题
 *
 * [source] 保留技术定位（出错文件、所属阶段等）；[message] 是延迟解析的展示文本，
 * 只在渲染时才查资源，切语言后自然出新文案。
 *
 * A diagnostic entry: a structured issue aggregated across the ProjectLoad /
 * Session / Runtime stages.
 *
 * [source] keeps the technical locator (failing file, owning stage, and so on);
 * [message] is the lazily resolved display text, looked up only at render time,
 * so switching the language naturally yields the new wording.
 *
 * @property severity 严重级别，见 [DiagnosticSeverity] / Severity, see
 *   [DiagnosticSeverity]
 * @property source 技术定位字符串，供排查用 / Technical locator string for
 *   troubleshooting
 * @property message 延迟解析的本地化文案 / Lazily resolved localized message
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val source: String,
    val message: UiText,
) {
    companion object {
        /** 返回 Error 级诊断 / Returns an Error-severity diagnostic. */
        fun error(source: String, message: UiText) =
            Diagnostic(DiagnosticSeverity.Error, source, message)

        /** 返回 Warning 级诊断 / Returns a Warning-severity diagnostic. */
        fun warning(source: String, message: UiText) =
            Diagnostic(DiagnosticSeverity.Warning, source, message)
    }
}

/**
 * 诊断文案的构造点：把资源 id 与占位参数收拢到有名字的工厂函数里
 *
 * 产出方（Parser / Loader / Resolver / Builder）散在四个文件里，若各自写
 * `uiTextOf(R.string.x, a, b)`，资源 id 与参数顺序就要在四处各记一遍，改一个占位符
 * 得全仓翻。收在这里之后，调用点只看得见有名字的参数。
 *
 * 临时的、一次性的诊断不必进这里，直接 `Diagnostic.error(source, uiTextOf(...))` 即可。
 *
 * 构造点本身无状态，任何线程都可调用；返回的 [UiText] 未解析，切语言即时生效。
 *
 * Construction point for diagnostic messages: gathers resource ids and
 * placeholder arguments into named factory functions.
 *
 * The producers (Parser / Loader / Resolver / Builder) are spread across four
 * files; if each wrote `uiTextOf(R.string.x, a, b)` directly, the resource id
 * and argument order would have to be remembered in four places, and changing
 * one placeholder would mean a repo-wide sweep. Centralizing it here keeps the
 * call sites to named parameters only.
 *
 * Ad-hoc, one-off diagnostics need not go through here;
 * `Diagnostic.error(source, uiTextOf(...))` directly is fine.
 *
 * The object itself is stateless and safe to call from any thread; the returned
 * [UiText] is unresolved, so language switches take effect immediately.
 */
object DiagnosticMessages {

    /** 返回「接口定义读取失败」诊断，PI 解析阶段用 / Returns the "interface read failed" diagnostic, used by PI parsing. */
    fun interfaceReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_interface_read_failed, detail)

    /** 返回「缺少接口版本号」诊断，PI 解析阶段用 / Returns the "missing interface version" diagnostic, used by PI parsing. */
    fun missingInterfaceVersion(): UiText = uiTextOf(R.string.diagnostic_missing_interface_version)

    /** 返回「接口版本不受支持」诊断，PI 解析阶段用 / Returns the "unsupported interface version" diagnostic, used by PI parsing. */
    fun unsupportedInterfaceVersion(version: Long): UiText =
        uiTextOf(R.string.diagnostic_unsupported_interface_version, version)

    /** 返回「JSON 解析失败」诊断，PI 解析阶段用 / Returns the "JSON parse failed" diagnostic, used by PI parsing. */
    fun jsonParseFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_json_parse_failed, detail)

    /** 返回「翻译 JSON 解析失败」诊断，PI 解析阶段用 / Returns the "translation JSON parse failed" diagnostic, used by PI parsing. */
    fun translationJsonParseFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_translation_json_parse_failed, detail)

    /** 返回「声明条目不是对象」诊断，PI 解析阶段用 / Returns the "declaration entry is not an object" diagnostic, used by PI parsing. */
    fun entryNotObject(kind: String): UiText = uiTextOf(R.string.diagnostic_entry_not_object, kind)

    /**
     * 返回「必填字段缺失」诊断，PI 解析阶段用
     *
     * owner 非空时把它并进 kind 一起显示，如 `task "Foo"` / When [owner] is
     * non-null it is merged into [kind] for display, as in `task "Foo"`.
     */
    fun requiredFieldMissing(kind: String, field: String, owner: String? = null): UiText =
        uiTextOf(
            R.string.diagnostic_required_field_missing,
            owner?.let { "$kind \"$it\"" } ?: kind,
            field,
        )

    /** 返回「资源路径缺失」诊断，PI 解析阶段用 / Returns the "resource path missing" diagnostic, used by PI parsing. */
    fun resourcePathMissing(resource: String): UiText =
        uiTextOf(R.string.diagnostic_resource_path_missing, resource)

    /**
     * 返回「无 Adb controller」诊断
     *
     * PI 未声明 Adb controller：该 PI 不面向 Android，带 controller 限定的任务都会
     * 不适用 / The PI declares no Adb controller: it does not target Android, so
     * every task qualified by a controller is inapplicable.
     */
    fun noAdbController(): UiText = uiTextOf(R.string.diagnostic_no_adb_controller)

    /** 返回「语言目录路径非法」诊断，PI 解析阶段用 / Returns the "language path invalid" diagnostic, used by PI parsing. */
    fun languagePathInvalid(language: String): UiText =
        uiTextOf(R.string.diagnostic_language_path_invalid, language)

    /** 返回「导入读取失败」诊断，PI 解析阶段用 / Returns the "import read failed" diagnostic, used by PI parsing. */
    fun importReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_import_read_failed, detail)

    /** 返回「项目无任务」诊断，PI 解析阶段用 / Returns the "project has no tasks" diagnostic, used by PI parsing. */
    fun projectHasNoTasks(): UiText = uiTextOf(R.string.diagnostic_project_has_no_tasks)

    /** 返回「重复声明」诊断，PI 解析阶段用 / Returns the "duplicate declaration" diagnostic, used by PI parsing. */
    fun duplicateDeclaration(kind: String, name: String): UiText =
        uiTextOf(R.string.diagnostic_duplicate_declaration, kind, name)

    /** 返回「翻译文件读取失败」诊断，PI 解析阶段用 / Returns the "translation read failed" diagnostic, used by PI parsing. */
    fun translationReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_translation_read_failed, detail)

    /** 返回「描述文件读取失败」诊断，PI 解析阶段用 / Returns the "description read failed" diagnostic, used by PI parsing. */
    fun descriptionReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_description_read_failed, detail)

    /** 返回「目录枚举失败」诊断，PI 解析阶段用 / Returns the "directory enumeration failed" diagnostic, used by PI parsing. */
    fun directoryEnumerationFailed(directory: String, detail: String): UiText =
        uiTextOf(R.string.diagnostic_directory_enumeration_failed, directory, detail)

    /** 返回「引用缺失」诊断，PI 解析阶段用 / Returns the "missing reference" diagnostic, used by PI parsing. */
    fun missingReference(kind: String, name: String): UiText =
        uiTextOf(R.string.diagnostic_missing_reference, kind, name)

    /** 返回「默认分支缺失」诊断，option 声明校验用 / Returns the "default case missing" diagnostic, used by option declaration checks. */
    fun defaultCaseMissing(option: String, case: String): UiText =
        uiTextOf(R.string.diagnostic_default_case_missing, option, case)

    /** 返回「input 无字段」诊断，option 声明校验用 / Returns the "input has no fields" diagnostic, used by option declaration checks. */
    fun inputHasNoFields(option: String): UiText =
        uiTextOf(R.string.diagnostic_input_has_no_fields, option)

    /** 返回「option 类型不支持」诊断，option 声明校验用 / Returns the "unsupported option type" diagnostic, used by option declaration checks. */
    fun unsupportedOptionType(option: String, type: String): UiText =
        uiTextOf(R.string.diagnostic_unsupported_option_type, option, type)

    /** 返回「option 类型非法」诊断，option 声明校验用 / Returns the "invalid option type" diagnostic, used by option declaration checks. */
    fun invalidOptionType(option: String, type: String?): UiText =
        uiTextOf(R.string.diagnostic_invalid_option_type, option, type ?: "null")

    /** 返回「option 分支不是对象」诊断，option 声明校验用 / Returns the "option case is not an object" diagnostic, used by option declaration checks. */
    fun optionCaseNotObject(option: String): UiText =
        uiTextOf(R.string.diagnostic_option_case_not_object, option)

    /** 返回「option 分支缺名字」诊断，option 声明校验用 / Returns the "option case name missing" diagnostic, used by option declaration checks. */
    fun optionCaseNameMissing(option: String): UiText =
        uiTextOf(R.string.diagnostic_option_case_name_missing, option)

    /** 返回「pipeline 类型非法」诊断，option 声明校验用 / Returns the "invalid pipeline type" diagnostic, used by option declaration checks. */
    fun invalidPipelineType(option: String, input: String, type: String): UiText =
        uiTextOf(R.string.diagnostic_invalid_pipeline_type, option, input, type)

    /** 返回「正则编译失败」诊断，option 声明校验用 / Returns the "regex compile failed" diagnostic, used by option declaration checks. */
    fun regexCompileFailed(option: String, input: String, detail: String): UiText =
        uiTextOf(R.string.diagnostic_regex_compile_failed, option, input, detail)

    /** 返回「option 循环引用」诊断，option 声明校验用 / Returns the "option cycle" diagnostic, used by option declaration checks. */
    fun optionCycle(path: String): UiText = uiTextOf(R.string.diagnostic_option_cycle, path)

    /** 返回「无可用资源」诊断，会话解析阶段用 / Returns the "no available resource" diagnostic, used by session resolution. */
    fun noAvailableResource(): UiText = uiTextOf(R.string.diagnostic_no_available_resource)

    /**
     * 返回「资源选择缺失」诊断，会话解析阶段用
     *
     * fallback 为空时占位符仍要有东西可填，用「无」而不是空串 / When [fallback]
     * is null the placeholder still needs something to render, so "none" is
     * used instead of an empty string.
     */
    fun resourceSelectionMissing(selected: String, fallback: String?): UiText =
        uiTextOf(
            R.string.diagnostic_resource_selection_missing,
            selected,
            // args 是 Any?，裸 String 与 UiText 混着传都行，解析时各走各的分支
            fallback ?: uiTextOf(R.string.diagnostic_none),
        )

    /** 返回「缺少活动配置」诊断，会话解析阶段用 / Returns the "active configuration missing" diagnostic, used by session resolution. */
    fun activeConfigurationMissing(): UiText =
        uiTextOf(R.string.diagnostic_active_configuration_missing)

    /** 返回「配置的任务缺失」诊断，会话解析阶段用 / Returns the "configured task missing" diagnostic, used by session resolution. */
    fun configuredTaskMissing(task: String): UiText =
        uiTextOf(R.string.diagnostic_configured_task_missing, task)

    /** 返回「运行时无资源」诊断，运行时编译阶段用 / Returns the "runtime has no resource" diagnostic, used by runtime compilation. */
    fun runtimeNoResource(): UiText = uiTextOf(R.string.diagnostic_runtime_no_resource)

    /** 返回「启用任务缺定义」诊断，运行时编译阶段用 / Returns the "enabled task missing definition" diagnostic, used by runtime compilation. */
    fun enabledTaskMissingDefinition(task: String): UiText =
        uiTextOf(R.string.diagnostic_enabled_task_missing_definition, task)

    /** 返回「option 未设置且无默认值」诊断，运行时编译阶段用 / Returns the "option unset without default" diagnostic, used by runtime compilation. */
    fun optionUnsetWithoutDefault(option: String): UiText =
        uiTextOf(R.string.diagnostic_option_unset_without_default, option)

    /** 返回「所选分支缺失」诊断，运行时编译阶段用 / Returns the "selected case missing" diagnostic, used by runtime compilation. */
    fun selectedCaseMissing(option: String, case: String): UiText =
        uiTextOf(R.string.diagnostic_selected_case_missing, option, case)

    /** 返回「输入非法」诊断，运行时编译阶段用 / Returns the "invalid input" diagnostic, used by runtime compilation. */
    fun invalidInput(option: String, input: String, detail: String): UiText =
        uiTextOf(R.string.diagnostic_invalid_input, option, input, detail)

    /** 返回「整数转换失败」诊断，运行时编译阶段用 / Returns the "integer conversion failed" diagnostic, used by runtime compilation. */
    fun integerConversionFailed(option: String, value: String): UiText =
        uiTextOf(R.string.diagnostic_integer_conversion_failed, option, value)

    /** 返回「布尔转换失败」诊断，运行时编译阶段用 / Returns the "boolean conversion failed" diagnostic, used by runtime compilation. */
    fun booleanConversionFailed(option: String, value: String): UiText =
        uiTextOf(R.string.diagnostic_boolean_conversion_failed, option, value)
}
