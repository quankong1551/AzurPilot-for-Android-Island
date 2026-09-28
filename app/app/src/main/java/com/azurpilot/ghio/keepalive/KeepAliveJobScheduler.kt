package com.azurpilot.ghio.keepalive

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * 系统作业调度器 / JobScheduler helper for keep-alive
 *
 * 调度系统级 15 分钟周期性作业，由 system_server 持久化并在周期到达时拉起进程。
 *
 * Schedules a persistent 15-minute periodic system job executed by system_server to resurrect the app.
 */
class KeepAliveJobScheduler(private val context: Context) {

    private val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private val componentName = ComponentName(context, KeepAliveJobService::class.java)

    fun schedule() {
        try {
            val builder = JobInfo.Builder(JOB_ID, componentName)
                // 15 分钟周期 (Android JobScheduler 周期性任务最短间隔为 15 分钟)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true) // 开机保持

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setRequiresBatteryNotLow(false)
                builder.setRequiresCharging(false)
            }

            val result = jobScheduler.schedule(builder.build())
            if (result == JobScheduler.RESULT_SUCCESS) {
                Timber.d("KeepAliveJobScheduler: Job scheduled successfully")
            } else {
                Timber.w("KeepAliveJobScheduler: Job schedule returned failure code $result")
            }
        } catch (e: Exception) {
            Timber.e(e, "KeepAliveJobScheduler: Failed to schedule Job")
        }
    }

    fun cancel() {
        try {
            jobScheduler.cancel(JOB_ID)
            Timber.d("KeepAliveJobScheduler: Job cancelled")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveJobScheduler: Failed to cancel Job")
        }
    }

    companion object {
        private const val JOB_ID = 9527
    }
}
