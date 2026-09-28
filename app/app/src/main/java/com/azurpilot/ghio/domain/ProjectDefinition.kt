package com.azurpilot.ghio.domain

import kotlinx.serialization.json.JsonObject

/**
 * PI 加载合并后的不可变声明；不含用户状态
 *
 * ProjectInterface（PI）文件经解析与 import 合并后的最终产物，是纯「声明层」快照：
 * 加载完成即定型，之后只读；用户的选择、勾选等可变状态在
 * [com.azurpilot.ghio.domain.Resolved] 与 [com.azurpilot.ghio.domain.UserConfiguration]
 * 里，不混入本类。主线程构造、任意线程读取，字段全部不可变。
 *
 * The immutable declaration merged after PI loading; holds no user state.
 *
 * The final product of parsing and import-merging a ProjectInterface (PI)
 * file — a pure "declaration layer" snapshot: fixed once loading completes and
 * read-only afterwards. The user's selections and toggles live in
 * [com.azurpilot.ghio.domain.Resolved] and
 * [com.azurpilot.ghio.domain.UserConfiguration], never here. Built on the main
 * thread, readable from any thread; every field is immutable.
 */
data class ProjectDefinition(
    val name: String,
    val version: String?,
    val controller: ControllerDefinition,
    val resources: List<ResourceDefinition>,
    val tasks: List<TaskDefinition>,
    val groups: List<TaskGroupDefinition>,
    val options: Map<String, OptionDefinition>,
    /** PI v2.3.0 `global_option[]`：参与每个任务的 override，优先级最低，且不依赖任何选择 / PI v2.3.0 `global_option[]`: joins every task's override at the lowest priority and depends on no selection. */
    val globalOptionNames: List<String> = emptyList(),
    val templates: List<ConfigurationTemplate>,
    /** 顶层 agent 声明，按 PI 里的顺序；无 agent 的 PI 为空 / Top-level agent declarations in PI order; empty for a PI without agents. */
    val agents: List<AgentDefinition> = emptyList(),
    val metadata: ProjectMetadata = ProjectMetadata(),
    /** null = PI 没声明 telemetry，或声明了但 dsn 为空 / null = the PI declares no telemetry, or declares one with an empty dsn. */
    val telemetry: TelemetryDefinition? = null,
    /**
     * 当前语言的 `$key` 查表原样保留一份
     *
     * 声明层的 label/description 在加载期就物化完了，本不需要它；留着是为了 pipeline 的
     * `focus` 模板——那是运行期才随回调到达的正文，查表只能推迟到那时候
     *
     * A verbatim copy of the current language's `$key` lookup table.
     *
     * The declaration layer's label/description are materialized at load time,
     * so this table would be unneeded; it is kept for the pipeline's `focus`
     * template — text that only arrives at run time via callbacks, so the
     * lookup has to be deferred until then.
     */
    val translations: Map<String, String> = emptyMap(),
) {
    /** 按内部名查任务定义；未声明返回 null / Looks up a task definition by internal name; null when undeclared. */
    fun task(taskName: String): TaskDefinition? = taskIndex[taskName]

    /** 任务名索引，懒构建：任务列表在加载后不再变 / Task-name index, built lazily: the task list never changes after loading. */
    private val taskIndex: Map<String, TaskDefinition> by lazy { tasks.associateBy { it.name } }
}

/**
 * PI v2.9.0 `telemetry.sentry` 的声明投影
 *
 * [dsn] 由 PI 提供，外壳自己没有上报去处；外壳只把这份配置透传给运行时，
 * 不做任何本地上报。
 *
 * Projection of the PI v2.9.0 `telemetry.sentry` declaration.
 *
 * [dsn] comes from the PI; the shell has no reporting destination of its own
 * and merely forwards this configuration to the runtime, reporting nothing
 * locally.
 */
data class TelemetryDefinition(
    val dsn: String,
    val tracing: Boolean = true,
    val tracesSampleRate: Double = 1.0,
    val environment: String? = null,
)

/**
 * 项目元信息；welcome 相关字段驱动欢迎弹窗
 *
 * [welcomeFingerprint] 算在物化前的原始声明上：算在正文上的话，切一次语言换了译文
 * 就会重弹 / [welcomeFingerprint] is computed over the raw declaration before
 * materialization: computing it over the rendered text would re-show the popup
 * whenever a language switch changes the translation.
 */
data class ProjectMetadata(
    val welcome: String? = null,
    val welcomeFingerprint: String? = null,
    val description: String? = null,
    val contact: String? = null,
    val license: String? = null,
    val github: String? = null,
)

/**
 * PI 声明的 controller 投影
 *
 * 设备上 type 为 Adb 的项由 Android native controller 实现，见 docs/pi-compatibility.md
 *
 * Projection of the controller declared by the PI.
 *
 * On-device, the entry whose type is Adb is implemented by the Android native
 * controller; see docs/pi-compatibility.md.
 */
data class ControllerDefinition(
    val name: String = "Android",
    val type: String = "ADB",
    /** 三者互斥，都缺省时由 Runner 按默认分辨率兜底 / The three are mutually exclusive; when all are absent the Runner falls back to a default resolution. */
    val displayShortSide: Int? = null,
    val displayLongSide: Int? = null,
    val displayRaw: Boolean = false,
    /**
     * PI 里这一条的原样对象，供 `PI_CONTROLLER` 整条透传（见 PiAgentEnv）
     *
     * 投影只留外壳用得上的字段，而协议要求交给 agent 的是完整条目；
     * 空对象表示该条不是 PI 声明的
     *
     * The verbatim object of this entry in the PI, forwarded whole via
     * `PI_CONTROLLER` (see PiAgentEnv).
     *
     * The projection keeps only fields the shell uses, while the protocol
     * requires handing the agent the complete entry; an empty object means the
     * entry was not declared by the PI.
     */
    val raw: JsonObject = JsonObject(emptyMap()),
)

/**
 * 资源（接口包）声明投影 / Projection of a resource (interface pack) declaration.
 */
data class ResourceDefinition(
    val name: String,
    val paths: List<String>,
    /** $i18n 已物化；匹配/持久化仍用 [name] / $i18n is materialized; matching and persistence still use [name]. */
    val label: String = name,
    /** 同 [ControllerDefinition.raw]，供 `PI_RESOURCE` 透传 / Same as [ControllerDefinition.raw], forwarded via `PI_RESOURCE`. */
    val raw: JsonObject = JsonObject(emptyMap()),
    val icon: String? = null,
    /** PI v2.3.0 `resource[].option`：当前选中这份时参与每个任务的 override / PI v2.3.0 `resource[].option`: joins every task's override while this resource is selected. */
    val optionNames: List<String> = emptyList(),
)

/**
 * 顶层 agent 启动命令声明：子进程可执行文件与其参数 / Top-level agent launch command: the child executable and its arguments.
 */
data class AgentDefinition(
    val childExec: String,
    val childArgs: List<String>,
)

/**
 * 任务声明投影 / Projection of a task declaration.
 */
data class TaskDefinition(
    val name: String,
    val entry: String,
    /** $i18n 已物化；缺省回落 name / $i18n is materialized; falls back to [name] when absent. */
    val label: String = name,
    val description: String?,
    val groups: List<String>,
    val optionNames: List<String>,
    val pipelineOverride: JsonObject,
    /** 空 = 全部 controller / resource 适用 / Empty = all controllers / resources apply. */
    val controllers: List<String>,
    val resources: List<String>,
    val defaultCheck: Boolean,
    val icon: String? = null,
)

/**
 * PI v2.4.0 顶层 `group[]` 的分组声明；label 缺省回落 name / Group declaration from
 * the PI v2.4.0 top-level `group[]`; label falls back to [name] when absent.
 */
data class TaskGroupDefinition(
    val name: String,
    val label: String = name,
    val description: String? = null,
    val icon: String? = null,
    val defaultExpand: Boolean = true,
    /** 加载器合成的未分组兜底；用标记判定，避免与真实同名 group 冲突 / Loader-synthesized ungrouped fallback; decided by this flag to avoid clashing with a real same-named group. */
    val isUngrouped: Boolean = false,
)

/**
 * option 的适用范围（PI v2.3.0 的 `controller` / `resource`）；空列表 = 不限
 *
 * v2.3.1 起这是硬约束而不只是展示提示：不满足时该 option **连同其子 option** 都不产生
 * pipeline_override；协议允许客户端隐藏或灰显，本项目选隐藏（docs/domain-model.md §6.3），
 * Resolver 与 Builder 各判一次同一条件；已保存的值不删，环境切回来就重新露面
 *
 * An option's applicability scope (the PI v2.3.0 `controller` / `resource`);
 * an empty list means unrestricted.
 *
 * Since v2.3.1 this is a hard constraint, not just a display hint: when it is
 * not satisfied, the option **together with its child options** produces no
 * pipeline_override. The protocol lets the client hide or gray out such
 * options; this project hides them (docs/domain-model.md §6.3). Resolver and
 * Builder each evaluate the same condition once; saved values are not deleted,
 * so the option reappears once the environment switches back.
 */
data class OptionApplicability(
    val controllers: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
) {
    /**
     * 判定该 option 在给定环境下是否适用 / Decides whether the option applies in
     * the given environment.
     *
     * @param controllerName 当前 controller 内部名 / Current controller internal name
     * @param resourceName 当前 resource 内部名，未选资源时为 null / Current resource
     *   internal name, null when none is selected
     */
    fun matches(controllerName: String, resourceName: String?): Boolean =
        (controllers.isEmpty() || controllerName in controllers) &&
                (resources.isEmpty() || resourceName in resources)

    companion object {
        /** 全限定的共享缺省实例 / The shared unrestricted default instance. */
        val Unrestricted = OptionApplicability()
    }
}

/**
 * option 声明的封闭层级：Select / Switch / Checkbox / Input 四种控件语义
 *
 * The closed hierarchy of option declarations: the four control semantics
 * Select / Switch / Checkbox / Input.
 */
sealed interface OptionDefinition {
    val name: String
    val label: String
    val description: String?
    val icon: String?
    val applicability: OptionApplicability

    /**
     * Select/Switch 共享 cases + defaultCase / Select/Switch share cases + defaultCase.
     */
    sealed interface Choice : OptionDefinition {
        val cases: List<OptionCaseDefinition>
        val defaultCase: String?

        /**
         * 未设值时的落点：PI 的 `default_case`，缺了退到首个 case
         *
         * 生态里绝大多数 option 不写 `default_case`，逼用户逐个选不现实；与 MXU 的
         * `default_case || cases[0]` 同语义。只有 cases 为空才是 null——那是 PI 自己
         * 写坏，留给编译期报诊断。Resolver 与 RunPlanBuilder 必须共用这一处：
         * 两边算法不同就会「显示的」与「跑的」分叉
         *
         * Where an unset value lands: the PI's `default_case`, falling back to
         * the first case when absent.
         *
         * Most options in the ecosystem omit `default_case`, and forcing users
         * to pick each one is unrealistic; this matches MXU's
         * `default_case || cases[0]` semantics. Only an empty [cases] yields
         * null — that means the PI itself is broken and is left for the
         * compile stage to diagnose. Resolver and RunPlanBuilder must share
         * this one implementation: divergent algorithms would split "what is
         * shown" from "what runs".
         */
        val effectiveDefaultCase: String? get() = defaultCase ?: cases.firstOrNull()?.name
    }

    /** 单选 option / Single-choice option. */
    data class Select(
        override val name: String,
        override val label: String,
        override val description: String?,
        override val cases: List<OptionCaseDefinition>,
        override val defaultCase: String?,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : Choice

    /** 双态 option；on/off 判定走 [OptionEditorState.standardSwitchCases] / Two-state option; on/off detection goes through [OptionEditorState.standardSwitchCases]. */
    data class Switch(
        override val name: String,
        override val label: String,
        override val description: String?,
        override val cases: List<OptionCaseDefinition>,
        override val defaultCase: String?,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : Choice

    /** 多选 option：可同时勾选多个 case / Multi-select option: several cases can be checked at once. */
    data class Checkbox(
        override val name: String,
        override val label: String,
        override val description: String?,
        val cases: List<OptionCaseDefinition>,
        val defaultCases: List<String>,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : OptionDefinition

    /** 自由输入 option：无 case，直接编辑字段值 / Free-form input option: no cases, the field values are edited directly. */
    data class Input(
        override val name: String,
        override val label: String,
        override val description: String?,
        val fields: List<InputFieldDefinition>,
        val pipelineOverride: JsonObject,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : OptionDefinition
}

/**
 * 返回 option 的 case 列表；Input 无 cases，返回 empty
 *
 * Returns the option's case list; Input has no cases and gets an empty list.
 */
fun OptionDefinition.casesOrEmpty(): List<OptionCaseDefinition> = when (this) {
    is OptionDefinition.Choice -> cases
    is OptionDefinition.Checkbox -> cases
    is OptionDefinition.Input -> emptyList()
}

/**
 * 单个 case：选中时把 [pipelineOverride] 并进任务管线，并可展开子 option
 *
 * One case: when selected, [pipelineOverride] is merged into the task pipeline
 * and the child options become expandable.
 */
data class OptionCaseDefinition(
    val name: String,
    val label: String,
    val description: String?,
    val pipelineOverride: JsonObject,
    val childOptionNames: List<String>,
    val icon: String? = null,
)

/**
 * 输入字段值的类型标记：决定写入 pipeline_override 前如何校验与转换
 *
 * The value-type marker of an input field: decides how the value is validated
 * and converted before being written into the pipeline_override.
 */
enum class PipelineType {
    /** 字符串：原样写入，无类型校验 / String: written verbatim, no type validation. */
    StringType,

    /** 整数：须能 `toLongOrNull` 解析，失败报 integer_conversion_failed 诊断 / Integer: must parse via `toLongOrNull`; failure raises the integer_conversion_failed diagnostic. */
    IntType,

    /** 布尔：仅接受字面 "true" / "false" / Boolean: only the literals "true" / "false" are accepted. */
    BoolType,
}

/**
 * Input 的单字段声明 / A single field declaration of an Input option.
 */
data class InputFieldDefinition(
    val name: String,
    val pipelineType: PipelineType,
    val default: String,
    /** 编译失败的 regex 在 load 期报诊断；此处为已编译结果 / A regex that fails to compile is diagnosed at load time; this holds the compiled result. */
    val verify: Regex?,
    val patternMessage: String?,
    val description: String?,
    /** $i18n 已物化；placeholder 仍用 [name] / $i18n is materialized; the placeholder still uses [name]. */
    val label: String = name,
)

/**
 * PI preset 一次性模板；name 标识，label 展示
 *
 * One-shot preset template from the PI; [name] identifies it, [label] displays it.
 */
data class ConfigurationTemplate(
    val name: String,
    val label: String,
    val description: String?,
    val tasks: List<TemplateTask>,
    val icon: String? = null,
) {
    /** 同名任务保留先声明的一条；resolver 与 UI 必须一致 / For duplicate task names the first declaration wins; resolver and UI must agree. */
    val distinctTasks: List<TemplateTask> get() = tasks.distinctBy { it.taskName }
}

/**
 * 模板里的单任务条目：应用模板时对某任务的启用与取值预设 / One per-task entry in a
 * template: the enablement and value preset applied to a task when the
 * template is applied.
 */
data class TemplateTask(
    val taskName: String,
    val enabled: Boolean,
    val optionValues: Map<String, OptionValue>,
    /** 加载期由任务定义回填；缺定义回落 taskName / Backfilled from the task definition at load time; falls back to [taskName] when the definition is missing. */
    val label: String = taskName,
)
