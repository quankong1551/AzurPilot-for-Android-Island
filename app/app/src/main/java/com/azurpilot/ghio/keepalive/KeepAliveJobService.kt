package com.azurpilot.ghio.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import timber.log.Timber

/**
 * 周期性系统作业保活服务
 *
 * 由系统级服务 [android.app.job.JobScheduler] 在设定的周期到达时自动拉起
 * （manifest 声明 BIND_JOB_SERVICE 权限），即使主进程已被完全杀死，系统也会重新
 * 实例化本服务唤醒应用进程。动作即触发 [KeepAliveManager.onKeepAlivePing]
 * 执行保活自愈。
 *
 * onStartJob 返回 false：自检同步完成，无需系统保持 wakelock 等待异步结果；
 * onStopJob 返回 true：作业被系统中断时请求重新调度。
 *
 * Periodic JobScheduler keep-alive service.
 *
 * Executed automatically by the system [android.app.job.JobScheduler] when the period
 * elapses (the manifest declares the BIND_JOB_SERVICE permission); the system
 * re-instantiates this service to wake the app process even after the main process has
 * been killed entirely. The work itself is a [KeepAliveManager.onKeepAlivePing]
 * self-heal pass.
 *
 * onStartJob returns false: the check completes synchronously, so the system need not
 * hold its wakelock for async work. onStopJob returns true: request a reschedule when
 * the job is interrupted by the system.
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Timber.d("KeepAliveJobService: Job triggered by system JobScheduler")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
        // 自检同步完成即可返回 false，无需系统保持 wakelock 等待异步结果
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Timber.d("KeepAliveJobService: Job stopped")
        // 返回 true 请求系统在约束允许时重新调度本次作业
        return true
    }
}
