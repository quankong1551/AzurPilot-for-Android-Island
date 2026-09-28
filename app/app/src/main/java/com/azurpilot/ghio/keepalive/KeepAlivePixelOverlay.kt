package com.azurpilot.ghio.keepalive

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 前台 1px 微型透明悬浮像素
 *
 * 通过向 WindowManager 挂载一个尺寸为 1x1 像素的极小透明悬浮窗（不可触摸、不可聚焦、无限制），
 * 使 Android 系统（ActivityManagerService/WindowManagerService）将该进程判定为拥有可见窗口的
 * 前台活跃状态（PROCESS_STATE_VISIBLE），从而大幅提升进程在 Low Memory Killer (LMK) 中的免杀优先级。
 * 窗口 alpha 为 0 且位于屏幕原点，用户完全不可见；代价是常驻一个系统窗口。
 * 前置条件：SYSTEM_ALERT_WINDOW 悬浮窗权限（见 [canDrawOverlays]）。
 *
 * 1-pixel transparent foreground overlay window.
 *
 * Mounts a 1x1 transparent overlay window via WindowManager (non-touchable, non-focusable).
 * This signals Android's AMS/WMS that the app process possesses an active visible window
 * (elevating to PROCESS_STATE_VISIBLE), significantly increasing LMK priority and preventing death.
 * The window is fully invisible (alpha 0 at the screen origin); the cost is one resident
 * system window. Prerequisite: the SYSTEM_ALERT_WINDOW permission (see [canDrawOverlays]).
 */
class KeepAlivePixelOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pixelView: View? = null

    private val _isAttached = MutableStateFlow(false)

    /** 浮窗当前是否已挂载到 WindowManager / Whether the overlay is currently attached to the WindowManager. */
    val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    /**
     * 检查是否具备悬浮窗权限
     *
     * Checks whether the SYSTEM_ALERT_WINDOW permission is granted.
     */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 挂载 1px 浮窗；幂等，已挂载时直接返回
     *
     * 返回值仅代表权限具备且挂载请求已提交，不代表窗口已挂载成功——实际 addView 在
     * 主线程异步执行（已在主线程则同步），结果以 [isAttached] 为准
     *
     * Attaches the 1px overlay; idempotent, returns immediately when already attached.
     *
     * The return value only means the permission is granted and the attach request was
     * submitted, not that the window is on screen — the actual addView runs async on the
     * main thread (synchronously when already there); [isAttached] is the source of truth.
     *
     * @return 权限具备且请求已提交时 true，否则 false / true when the permission is
     *   granted and the request was submitted, false otherwise
     */
    fun attach(): Boolean {
        if (!canDrawOverlays()) {
            Timber.w("KeepAlivePixelOverlay: SYSTEM_ALERT_WINDOW permission not granted; cannot attach 1px overlay")
            return false
        }

        if (_isAttached.value && pixelView != null) {
            return true
        }

        runOnMainThread {
            if (pixelView != null) return@runOnMainThread

            try {
                val view = View(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                }

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    windowType,
                    flags,
                    PixelFormat.TRANSPARENT,
                ).apply {
                    gravity = Gravity.START or Gravity.TOP
                    x = 0
                    y = 0
                    alpha = 0.0f // 完全隐形
                }

                windowManager.addView(view, params)
                pixelView = view
                _isAttached.value = true
                Timber.d("KeepAlivePixelOverlay: 1px overlay attached successfully")
            } catch (e: Exception) {
                Timber.e(e, "KeepAlivePixelOverlay: Failed to add 1px overlay view to WindowManager")
                detach()
            }
        }
        return true
    }

    /**
     * 移除 1px 浮窗；幂等，未挂载时为空操作，实际移除切到主线程执行
     *
     * Detaches the 1px overlay; idempotent and a no-op when not attached, with the
     * actual removal dispatched to the main thread.
     */
    fun detach() {
        runOnMainThread {
            val view = pixelView
            pixelView = null
            _isAttached.value = false
            if (view != null) {
                try {
                    windowManager.removeViewImmediate(view)
                    Timber.d("KeepAlivePixelOverlay: 1px overlay detached")
                } catch (e: Exception) {
                    Timber.w(e, "KeepAlivePixelOverlay: Failed to remove 1px overlay view")
                }
            }
        }
    }

    /** 已在主线程则直接执行，否则 post 到 [mainHandler] / Runs inline when already on the main thread, otherwise posts to [mainHandler]. */
    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
