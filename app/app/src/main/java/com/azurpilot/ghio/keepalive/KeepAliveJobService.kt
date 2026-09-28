package com.azurpilot.ghio.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import timber.log.Timber

/**
 * 周期性系统作业保活服务 / Periodic JobScheduler keep-alive service
 *
 * 由 Android 系统级服务 [android.app.job.JobScheduler] 在设定的周期或网络条件达成时自动拉起。
 * 即使应用主进程完全被杀，系统服务也会重新加载本服务，唤醒应用进程并执行保活自愈。
 *
 * Executed periodically by Android's system_server JobScheduler, automatically reviving
 * the app process even if it has been killed.
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Timber.d("KeepAliveJobService: Job triggered by system JobScheduler")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
        // 任务无需异步长跑，检查完毕后立即结束
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Timber.d("KeepAliveJobService: Job stopped")
        return true // 若被打断则重新调度
    }
}
