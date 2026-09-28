package com.azurpilot.ghio.remote.internal
import com.azurpilot.ghio.AppDispatchers

import android.os.SystemClock
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.third.Ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 盯虚拟屏上的目标 app：从 [VirtualDisplayManager] 的 displayId 反推顶层包名作为目标，
 * 离屏则用 [ActivityUtils.repinAppToDisplay] 拉回；状态经 RemoteService.watchdogState() 暴露给 app。
 *
 * 与 参考实现 的差异：目标包不是外部告知，而是 getTopPackageOnDisplay 自取；
 * 判活与 onDisplay 合成一步（屏上有 app 即活）。全程 runCatching 宽松，不抛不误伤。
 *
 * 线程模型：单协程跑在 AppDispatchers.IO limitedParallelism(1) 上，[POLL_INTERVAL_MS] 一拍；
 * [state] 供 app 侧经 binder 查询，[targetPackage] volatile，binder 线程可读。
 *
 * Watches the target app on the virtual display: the target is inferred from the top package
 * on the [VirtualDisplayManager] display id, and an app that left the display is pulled back
 * via [ActivityUtils.repinAppToDisplay]; the state is exposed to the app through
 * RemoteService.watchdogState().
 *
 * Differences from the reference implementation: the target package is not told externally but
 * self-acquired via getTopPackageOnDisplay, and liveness plus on-display checks are fused into
 * one step (an app seen on the display is alive). Everything runs leniently under runCatching —
 * never throws, never misreports.
 *
 * Threading: a single coroutine on AppDispatchers.IO limitedParallelism(1) ticks every
 * [POLL_INTERVAL_MS]; [state] is queried by the app over binder and [targetPackage] is
 * volatile, readable from binder threads.
 */
object AppWatchdog {

    /** 未在盯防（无屏或尚未取到目标）/ Not watching (no display, or no target acquired yet) */
    const val STATE_IDLE = 0

    /** 目标在虚拟屏上正常运行 / Target is running normally on the virtual display */
    const val STATE_WATCHING = 1

    /** 窗口离开虚拟屏且拉回失败，进程还活着 / The window left the virtual display and repin failed; the process is still alive */
    const val STATE_DISPLAY_DRIFT = 2

    /** pidof 查不到进程 / pidof finds no process */
    const val STATE_APP_DIED = 3

    /** 轮询周期 / Poll period */
    private const val POLL_INTERVAL_MS = 5000L

    /** 离屏宽限期：短暂离屏不立即拉回，先给系统自己回正的机会 / Off-screen grace: a brief absence is not yanked back immediately, giving the system a chance to settle on its own */
    private const val REPIN_GRACE_MS = 5000L

    /** 拉回重试上限，超限上报 DISPLAY_DRIFT / Repin attempt cap; exceeding it raises DISPLAY_DRIFT */
    private const val MAX_REPIN_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow(STATE_IDLE)
    val state: StateFlow<Int> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * 运行期反推出来的目标包名；收尾要关它，而 app 侧不维护包名表
     *
     * Target package inferred at runtime; teardown must stop it, and the app side keeps no
     * package table of its own.
     */
    @Volatile
    var targetPackage: String? = null
        private set
    private var driftFirstSeenMs = 0L
    private var driftRepinAttempts = 0
    private var driftNotified = false
    private var diedNotified = false

    /**
     * 开始盯防：清空上一轮目标与漂移计数后起轮询协程；重复调用会先停旧的
     *
     * Starts watching: clears the previous round's target and drift counters, then starts the
     * polling coroutine; a repeated call stops the old loop first.
     */
    fun startWatching() {
        stopWatching()
        targetPackage = null
        driftFirstSeenMs = 0L
        driftRepinAttempts = 0
        driftNotified = false
        diedNotified = false
        _state.value = STATE_IDLE
        Ln.i("AppWatchdog: start watching display ${VirtualDisplayManager.getDisplayId()}")
        job = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                tick()
            }
        }
    }

    /**
     * 停止轮询并回到 IDLE；**保留** [targetPackage]——收尾强停目标（stopTargetApp）仍要用它
     *
     * Stops polling and returns to IDLE; **keeps** [targetPackage] — teardown still needs it
     * to force-stop the target (stopTargetApp).
     */
    fun stopWatching() {
        job?.cancel()
        job = null
        _state.value = STATE_IDLE
    }

    /**
     * 单拍决策树：屏上取到顶层包 → 记目标、清漂移计数；屏空则先判活
     * （被杀与漂移是两回事），进程还活着的离屏才进入宽限→拉回→超限上报的漂移流程
     */
    private fun tick() {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            _state.value = STATE_IDLE
            return
        }
        val top = runCatching { ActivityUtils.getTopPackageOnDisplay(displayId) }.getOrNull()
        if (top != null) {
            if (targetPackage == null) Ln.i("AppWatchdog: target acquired: $top")
            targetPackage = top
            driftFirstSeenMs = 0L
            driftRepinAttempts = 0
            driftNotified = false
            diedNotified = false
            _state.value = STATE_WATCHING
            return
        }

        val pkg = targetPackage
        if (pkg == null) {
            _state.value = STATE_IDLE
            return
        }

        // 屏上空了先问进程还在不在：被杀和"窗口跑到主屏去了"是两回事，
        // 拿后者的文案去讲前者，用户会照着去改前台模式而问题根本不在那
        when (isAlive(pkg)) {
            ALIVE_NO -> {
                if (!diedNotified) {
                    diedNotified = true
                    _state.value = STATE_APP_DIED
                    Ln.w("AppWatchdog: $pkg process is gone")
                }
                return
            }
            // 判不出就不下结论，等下一拍
            ALIVE_UNKNOWN -> return
        }

        // 进程还在，那就是窗口飘了 → 宽限后 repin，超限上报
        if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS) return
        val now = SystemClock.elapsedRealtime()
        if (driftFirstSeenMs == 0L) {
            driftFirstSeenMs = now
            Ln.i("AppWatchdog: $pkg left the virtual display, grace ${REPIN_GRACE_MS}ms")
            return
        }
        if (now - driftFirstSeenMs < REPIN_GRACE_MS) return

        Ln.w("AppWatchdog: $pkg drifted, repin attempt ${driftRepinAttempts + 1}/$MAX_REPIN_ATTEMPTS")
        val ok = runCatching { ActivityUtils.repinAppToDisplay(pkg, displayId) }.getOrDefault(false)
        val back = ok && runCatching { ActivityUtils.isAppOnDisplay(pkg, displayId) }.getOrDefault(true)
        if (back) {
            Ln.i("AppWatchdog: $pkg moved back to the virtual display")
            driftFirstSeenMs = 0L
            driftRepinAttempts = 0
            return
        }
        driftRepinAttempts++
        if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS && !driftNotified) {
            driftNotified = true
            _state.value = STATE_DISPLAY_DRIFT
            Ln.w("AppWatchdog: $pkg left the virtual display and repin failed")
        }
    }

    /** 进程在 / process alive */
    private const val ALIVE_YES = 0

    /** 确认死亡 / confirmed dead */
    private const val ALIVE_NO = 1

    /** 判不出，宁漏报不误报 / undeterminable; better to under-report than misreport */
    private const val ALIVE_UNKNOWN = 2

    /**
     * 判活走 pidof（与 参考实现 同法）：本对象跑在特权进程里，shell 身份直接 exec 即可
     *
     * 只有"退出码 1 且两个流都空"才算确认死亡——ROM 换了 pidof 实现、或权限被挡时，
     * 输出形态五花八门，一律当判不出，宁可漏报也不要把还活着的应用报成死了
     *
     * Liveness via pidof (same as the reference implementation): this object runs inside the
     * privileged process, so a direct exec under the shell identity works.
     *
     * Only "exit code 1 with both streams empty" counts as confirmed death — when a ROM ships a
     * different pidof implementation or the exec is blocked, the output shape varies wildly and
     * is treated as undeterminable; better to under-report than to report a live app as dead.
     *
     * @return [ALIVE_YES] / [ALIVE_NO] / [ALIVE_UNKNOWN]
     */
    private fun isAlive(packageName: String): Int = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
        val exitCode = process.waitFor()
        val out = process.inputStream.bufferedReader().readText().trim()
        val err = process.errorStream.bufferedReader().readText().trim()
        when {
            exitCode == 0 && out.isNotEmpty() -> ALIVE_YES
            exitCode == 1 && out.isEmpty() && err.isEmpty() -> ALIVE_NO
            else -> {
                Ln.w("AppWatchdog: pidof $packageName unexpected: exit=$exitCode out=$out err=$err")
                ALIVE_UNKNOWN
            }
        }
    }.getOrElse {
        Ln.w("AppWatchdog: pidof $packageName failed: ${it.message}")
        ALIVE_UNKNOWN
    }
}
