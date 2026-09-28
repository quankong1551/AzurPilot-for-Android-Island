package com.azurpilot.ghio.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 系统多事件广播监听接收器 / System events broadcast receiver for keep-alive
 *
 * 监听开机完成 ([Intent.ACTION_BOOT_COMPLETED])、用户解锁 ([Intent.ACTION_USER_PRESENT])、
 * 电源接入与拔出 ([Intent.ACTION_POWER_CONNECTED] / [Intent.ACTION_POWER_DISCONNECTED])
 * 以及屏幕亮灭等关键系统广播。一旦收到广播，立即触发保活检查与进程唤醒。
 *
 * Listens to boot completion, screen unlock/on/off, and power connect/disconnect broadcasts.
 * Triggers keep-alive health pings and revives background services when events arrive.
 */
class KeepAliveBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("KeepAliveBroadcastReceiver: Received system broadcast action=$action")

        val manager = KeepAliveManager.getInstance()
        if (manager != null) {
            manager.onKeepAlivePing()
        } else {
            // 若主控未初始化，尝试启动粘性服务触发进程自愈
            KeepAliveStickyService.start(context)
        }
    }
}
