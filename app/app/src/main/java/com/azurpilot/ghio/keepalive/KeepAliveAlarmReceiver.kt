package com.azurpilot.ghio.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 周期性定时闹钟唤醒接收器 / Periodic alarm wake-up receiver
 *
 * 接收来自 [KeepAliveAlarmScheduler] 的低功耗定时唤醒，
 * 在系统 Doze 模式下周期性拉起 CPU，执行保活健康检查并排期下一轮心跳。
 *
 * Receives periodic wake-up alarms from KeepAliveAlarmScheduler in Doze mode,
 * ensuring CPU wakefulness, triggering health pings, and scheduling the next alarm.
 */
class KeepAliveAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Timber.d("KeepAliveAlarmReceiver: Alarm heartbeat received")
        val manager = KeepAliveManager.getInstance()
        if (manager != null) {
            manager.onKeepAlivePing()
            manager.scheduleNextAlarm()
        }
    }
}
