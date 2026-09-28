package com.azurpilot.ghio.keepalive

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * CPU 防休眠唤醒锁 / CPU partial wake lock manager
 *
 * 持有 [PowerManager.PARTIAL_WAKE_LOCK]，确保在屏幕关闭或后台挂机时，
 * 设备 CPU 依然保持活跃运算，防止系统将应用进程及其后台线程冻结进 Deep Sleep / Doze。
 *
 * Holds a PARTIAL_WAKE_LOCK ensuring the CPU remains active while screen is off,
 * preventing Android from suspending/freezing background execution in Doze mode.
 */
class KeepAliveWakeLock(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzurPilot:KeepAliveWakeLock").apply {
            setReferenceCounted(false)
        }

    private val _isHeld = MutableStateFlow(false)
    val isHeld: StateFlow<Boolean> = _isHeld.asStateFlow()

    @Synchronized
    fun acquire() {
        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                _isHeld.value = true
                Timber.d("KeepAliveWakeLock: WakeLock acquired")
            }
        } catch (e: Exception) {
            Timber.e(e, "KeepAliveWakeLock: Failed to acquire wake lock")
        }
    }

    @Synchronized
    fun release() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                _isHeld.value = false
                Timber.d("KeepAliveWakeLock: WakeLock released")
            }
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveWakeLock: Error while releasing wake lock")
        }
    }
}
