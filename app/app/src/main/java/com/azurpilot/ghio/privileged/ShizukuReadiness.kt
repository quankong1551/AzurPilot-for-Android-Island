package com.azurpilot.ghio.privileged

/**
 * Shizuku 引导的分阶段结论；驱动首页的引导卡片
 *
 * The staged Shizuku onboarding verdict; drives the home onboarding card.
 */
enum class ShizukuReadinessStage {
    /** 未安装 Shizuku，也没检测到 Sui / Shizuku not installed and no Sui detected */
    NotInstalled,

    /** 已安装但服务未启动 / installed but the service is not running */
    NotRunning,

    /**
     * 装的是官方版 Shizuku：与 shizuku-m 同包名不同签名，必须先卸载再装 shizuku-m
     *
     * The official Shizuku is installed: same package as shizuku-m, different
     * signature; uninstall before installing shizuku-m.
     */
    OfficialConflict,

    /** 检测到 Sui（Magisk 模块）提供服务 / Sui (a Magisk module) is serving the API */
    SuiAvailable,

    /** 服务运行中但未授权 / service running but not granted */
    NeedAuth,

    /**
     * 就绪：已授权、当前后端不是 Shizuku，或用户已选跳过
     *
     * Ready: granted, the configured backend is not Shizuku, or the user
     * opted out.
     */
    Ready,
}

/**
 * 判别 moe.shizuku.privileged.api 这个包名下面装的到底是谁（shizuku-m 与官方版同包名）
 *
 * Tells who is actually installed under the moe.shizuku.privileged.api
 * package (shizuku-m and the official build share it).
 */
enum class ShizukuFlavor {
    /** 包不存在 / the package is absent */
    NONE,

    /** 官方版（label "Shizuku"）/ the official build (label "Shizuku") */
    OFFICIAL,

    /** shizuku-m（label "Shizuku-m"）/ shizuku-m (label "Shizuku-m") */
    MOD,
}

/**
 * Shizuku 引导的当下结论；[needsGuidance] 决定要不要弹引导
 *
 * The current Shizuku onboarding verdict; [needsGuidance] decides whether to
 * show guidance.
 *
 * @param stage 当前所处阶段 / the current stage
     * @param canSwitchToRoot Root 后端可用，引导里可建议切换
     *   / the Root backend is available and the guidance may suggest switching
 */
data class ShizukuReadiness(
    val stage: ShizukuReadinessStage = ShizukuReadinessStage.Ready,
    val canSwitchToRoot: Boolean = false,
) {
    /** 非 Ready 即需要引导 / anything but Ready needs guidance */
    val needsGuidance: Boolean
        get() = stage != ShizukuReadinessStage.Ready
}
