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
 * 前台 1px 微型透明悬浮像素 / 1-pixel transparent foreground overlay window
 *
 * 通过向 WindowManager 挂载一个尺寸为 1x1 像素的极小透明悬浮窗（不可触摸、不可聚焦、无限制），
 * 使 Android 系统（ActivityManagerService/WindowManagerService）将该进程判定为拥有可见窗口的
 * 前台活跃状态（PROCESS_STATE_VISIBLE），从而大幅提升进程在 Low Memory Killer (LMK) 中的免杀优先级。
 *
 * Mounts a 1x1 transparent overlay window via WindowManager (non-touchable, non-focusable).
 * This signals Android's AMS/WMS that the app process possesses an active visible window
 * (elevating to PROCESS_STATE_VISIBLE), significantly increasing LMK priority and preventing death.
 */
class KeepAlivePixelOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pixelView: View? = null

    private val _isAttached = MutableStateFlow(false)
    val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    /** 检查是否具备悬浮窗权限 / Check if SYSTEM_ALERT_WINDOW permission is granted */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 挂载 1px 浮窗 / Attach 1px overlay window
     * 需在主线程执行；若无悬浮窗权限则安全跳过 / Runs on main thread, skips gracefully if unpermitted
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

    /** 移除 1px 浮窗 / Detach and remove 1px overlay window */
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

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
