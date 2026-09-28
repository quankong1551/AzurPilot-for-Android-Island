package com.azurpilot.ghio.overlay.screensaver

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.azurpilot.ghio.domain.RunMode
import com.azurpilot.ghio.overlay.OverlayViewModelOwner
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.settings.AppSettingsGateway
import com.azurpilot.ghio.theme.AzurPilotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 后台模式的环境期屏保
 *
 * 仅 [RunMode.BACKGROUND] 下工作：环境运行期间用一块全黑悬浮层压住屏幕
 * （TYPE_APPLICATION_OVERLAY，需 SYSTEM_ALERT_WINDOW），保屏保亮度双管齐下，
 * 让后台挂机看起来像熄屏。挂载与移除都在主线程；[setup] 后自观察运行模式
 * 与环境态，任务结束自动收起
 *
 * The screensaver shown over the environment while in background mode.
 *
 * Works in [RunMode.BACKGROUND] only: while the environment runs, a full-black
 * overlay layer (TYPE_APPLICATION_OVERLAY, requires SYSTEM_ALERT_WINDOW)
 * covers the screen, pairing keep-screen-on with minimum brightness so
 * background idle looks like the screen is off. Mount and removal happen on
 * the main thread; after [setup] the manager observes the run mode and the
 * environment state on its own, dismissing automatically when the task ends.
 */
class ScreenSaverOverlayManager(
    private val context: Context,
    private val hostState: HostState,
    private val appSettings: AppSettingsGateway,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 不复用 Koin 里那个 single：那份归控制层，生命周期跟着面板显隐推
     * 两处共用会互相把对方推回 CREATED，`collectAsStateWithLifecycle` 就此停摆
     */
    private val viewModelOwner = OverlayViewModelOwner()

    private var composeView: ComposeView? = null
    private var hostJob: Job? = null

    private val _isShowing = MutableStateFlow(false)

    /** 屏保当前是否盖在屏上 / Whether the screensaver currently covers the screen. */
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    /**
     * 开始观察运行模式：后台模式挂环境观察，前台模式撤观察并收起屏保；幂等
     *
     * Starts observing the run mode: background mode mounts the environment
     * watcher, foreground mode stops it and dismisses the screensaver;
     * idempotent.
     */
    fun setup() {
        scope.launch {
            appSettings.runMode.collect { mode ->
                when (mode) {
                    RunMode.BACKGROUND -> observeHost()
                    RunMode.FOREGROUND -> {
                        stopObservingHost()
                        hide()
                    }
                }
            }
        }
    }

    private fun observeHost() {
        if (hostJob != null) return
        hostJob = scope.launch {
            var wasUp = hostState.snapshot.value.environmentUp
            hostState.snapshot.collect { snapshot ->
                val up = snapshot.environmentUp
                when {
                    // 开关只管「自动盖」；手动盖上的那次不受它影响
                    !wasUp && up ->
                        if (appSettings.screenSaverEnabled.value) show()

                    // 环境撤了就收，别让用户回来面对一块黑屏还得先滑一下
                    wasUp && !up -> hide()
                }
                wasUp = up
            }
        }
    }

    /** 停止环境观察 / Stops watching the environment state. */
    private fun stopObservingHost() {
        hostJob?.cancel()
        hostJob = null
    }

    /**
     * 盖上屏保；已盖时直接返回 true，非后台模式拒绝并告警
     *
     * 返回是否真的盖上了；没有悬浮窗权限时 addView 会抛，这里吞掉并如实回 false
     *
     * Covers the screen with the screensaver; returns true immediately when
     * already showing, refuses with a warning outside background mode.
     *
     * The return states whether it really got shown; without the overlay
     * permission addView throws, which is swallowed here and honestly
     * reported as false.
     *
     * @return 盖上为 true，权限缺失或模式不符为 false / true when shown, false
     *   when the permission is missing or the mode does not match
     */
    suspend fun show(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (_isShowing.value) return@withContext true
        if (appSettings.runMode.value != RunMode.BACKGROUND) {
            Timber.w("Not in background mode; ignoring screen-saver show request")
            return@withContext false
        }

        val view = createView()
        runCatching { windowManager.addView(view, createLayoutParams()) }
            .onSuccess {
                composeView = view
                viewModelOwner.start()
                _isShowing.value = true
                Timber.d("Screen saver shown")
            }
            .onFailure { Timber.e(it, "Failed to show screen saver") }
        _isShowing.value
    }

    /**
     * 收起屏保；未盖时为 no-op，移除失败只记日志
     *
     * Dismisses the screensaver; a no-op when not showing, removal failures
     * are only logged.
     */
    suspend fun hide() = withContext(Dispatchers.Main.immediate) {
        val view = composeView ?: return@withContext
        composeView = null
        _isShowing.value = false
        viewModelOwner.stop()
        runCatching { windowManager.removeView(view) }
            .onSuccess { Timber.d("Screen saver dismissed") }
            .onFailure { Timber.e(it, "Failed to remove screen saver") }
    }

    /** 构建屏保视图：挂独立 owner、恒暗主题，并排除底部手势区 / Builds the screensaver view: own owner, always-dark theme, and a bottom gesture-exclusion zone. */
    private fun createView(): ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(viewModelOwner)
        setViewTreeViewModelStoreOwner(viewModelOwner)
        setViewTreeSavedStateRegistryOwner(viewModelOwner)
        setContent {
            // 屏保恒为暗色：整块屏幕本来就该压到最黑
            AzurPilotTheme(darkTheme = true) {
                ScreenSaverView(
                    // 桥没有日志通道，恒 null：View 落回 screensaver_idle 兜底文案
                    latestLog = MutableStateFlow<String?>(null),
                    onUnlock = { scope.launch { hide() } },
                )
            }
        }
        // 排掉底部手势区，不然系统的返回手势会把解锁条的横向拖拽抢走
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val excluded = (v.resources.displayMetrics.density * GESTURE_EXCLUSION_DP).toInt()
                v.systemGestureExclusionRects =
                    listOf(Rect(0, v.height - excluded, v.width, v.height))
            }
        }
    }

    /**
     * 不可聚焦：返回键与音量键要原样落给系统，屏保不该改变它们的行为
     * KEEP_SCREEN_ON + screenBrightness 压到最低是这层的核心——真息屏会让
     * 后台运行中断，所以只能保持点亮装作熄屏
     *
     * Not focusable: the back and volume keys must fall through to the system
     * unchanged; the screensaver must not alter their behavior.
     * KEEP_SCREEN_ON plus the minimum screenBrightness is the core of this
     * layer — a real screen-off would interrupt the background run, so the
     * display is kept lit and merely dressed as off.
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            screenBrightness = MIN_BRIGHTNESS
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private companion object {
        /** 0f 在部分 ROM 上被当成「跟随系统」，给一个够小但非零的值 / 0f is treated as "follow system" on some ROMs, so a small-but-nonzero value is used. */
        const val MIN_BRIGHTNESS = 0.01f

        /** 覆盖三大厂的手势条高度还有余量；解锁条本身离底 56dp，不会被这块挡住 / Covers the gesture-bar height of the three major OEMs with margin; the unlock bar itself sits 56dp above the bottom, clear of this zone. */
        const val GESTURE_EXCLUSION_DP = 120
    }
}
