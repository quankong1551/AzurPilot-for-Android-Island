package com.azurpilot.ghio.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

/**
 * 精准闹钟调度器 / Exact alarm scheduler for keep-alive
 *
 * 利用 [AlarmManager.setExactAndAllowWhileIdle] 突破系统 Doze 休眠限制，
 * 建立 5 分钟周期的不可休眠心跳唤醒环路。
 *
 * Employs setExactAndAllowWhileIdle to bypass Android Doze restrictions,
 * establishing a 5-minute recurring CPU wake-up heartbeat loop.
 */
class KeepAliveAlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(context, KeepAliveAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    fun scheduleNext(delayMs: Long = DEFAULT_INTERVAL_MS) {
        try {
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            val pendingIntent = getPendingIntent()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Timber.d("KeepAliveAlarmScheduler: Next wake-up alarm scheduled in ${delayMs / 1000}s")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveAlarmScheduler: Failed to schedule exact alarm; falling back")
            runCatching {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    getPendingIntent()
                )
            }
        }
    }

    fun cancel() {
        try {
            alarmManager.cancel(getPendingIntent())
            Timber.d("KeepAliveAlarmScheduler: Wake-up alarm cancelled")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveAlarmScheduler: Failed to cancel alarm")
        }
    }

    companion object {
        private const val REQUEST_CODE = 8848
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟
    }
}
