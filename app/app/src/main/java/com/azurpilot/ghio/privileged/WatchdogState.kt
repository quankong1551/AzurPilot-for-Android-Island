package com.azurpilot.ghio.privileged

/**
 * 汇报目标 app 在虚拟屏上的看门狗状态
 *
 * 两种坏结局分开：进程没了([APP_DIED])与进程还在但窗口跑了([DISPLAY_DRIFT])，
 * 用户要做的事完全不同——前者是应用被杀，后者多半是 ROM 把窗口挪回了主屏。
 * 名字对齐 参考实现 的 `appDiedEvent` / `displayDriftEvent`
 *
 * [aidlValue] 对齐 RemoteService.watchdogState() 的 AIDL 契约（特权进程返回 int），
 * app 侧用 [fromAidl] 映射回枚举；PermissionGateway 把它投影给 ViewModel/预览徽标
 *
 * Reports the watchdog state of the target app on the virtual display.
 *
 * The two bad endings are kept apart: the process is gone ([APP_DIED]) versus
 * the process is alive but its window wandered off ([DISPLAY_DRIFT]) — what
 * the user must do differs entirely; the former is a killed app, the latter
 * is usually the ROM moving the window back to the primary display. Names
 * mirror the reference implementation's `appDiedEvent` / `displayDriftEvent`.
 *
 * [aidlValue] matches the AIDL contract of RemoteService.watchdogState() (the
 * privileged process returns an int); the app side maps it back via
 * [fromAidl], and PermissionGateway projects it to the ViewModel / preview
 * badge.
 */
enum class WatchdogState(val aidlValue: Int) {
    /** 未在盯防目标 / no target under watch */
    IDLE(0),

    /** 正常盯防中 / watching the target normally */
    WATCHING(1),

    /**
     * 窗口离开虚拟屏且自动拉回失败；进程还活着
     *
     * The window left the virtual display and the auto pull-back failed; the
     * process is alive.
     */
    DISPLAY_DRIFT(2),

    /** pidof 查不到进程 / pidof finds no process */
    APP_DIED(3);

    /**
     * 两者都表示这一轮已经跑不下去了，UI 与运行日志按同一档处理
     *
     * Both mean this round cannot continue; the UI and run logs treat them as
     * one bucket.
     */
    val isLost: Boolean get() = this == DISPLAY_DRIFT || this == APP_DIED

    companion object {
        /**
         * int → 枚举；未知值兜底回 [IDLE]，老 app 读到新协议的状态值不至于崩
         *
         * int → enum; unknown values fall back to [IDLE] so an old app reading
         * a state value from a newer protocol does not crash.
         */
        fun fromAidl(value: Int): WatchdogState =
            entries.firstOrNull { it.aidlValue == value } ?: IDLE
    }
}
