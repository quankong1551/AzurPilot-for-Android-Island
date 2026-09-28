package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

/**
 * START_STICKY 粘性守护服务
 *
 * 触发源：[KeepAliveManager]、各接收器与双进程守护服务的 start() 显式拉起
 * （manifest 中 exported=false）。onStartCommand 返回 [Service.START_STICKY]：
 * 服务因内存压力被杀后，系统在资源允许时以 null intent 自动重建本服务，带动宿主
 * 进程复活。
 *
 * 生命周期全程自检：onCreate 与 onStartCommand 都会调
 * [KeepAliveManager.onKeepAlivePing]，重建后立即修复其余保活子系统。
 *
 * Sticky background daemon service.
 *
 * Trigger source: explicit start() calls from [KeepAliveManager], the receivers, and
 * the dual-process watchdog services (exported=false in the manifest). onStartCommand
 * returns [Service.START_STICKY]: after the service is killed under memory pressure,
 * the system recreates it automatically with a null intent once resources allow,
 * reviving the host process.
 *
 * Self-checks for its whole lifecycle: onCreate and onStartCommand both invoke
 * [KeepAliveManager.onKeepAlivePing], so a rebuild immediately repairs the other
 * keep-alive subsystems.
 */
class KeepAliveStickyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveStickyService: Service created")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("KeepAliveStickyService: onStartCommand flags=$flags startId=$startId")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("KeepAliveStickyService: Service destroyed")
        super.onDestroy()
    }

    companion object {
        /** 显式拉起粘性服务；失败（如后台启动服务限制）仅记录 / Starts the sticky service explicitly; failures (e.g. background-start limits) are logged. */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, KeepAliveStickyService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveStickyService: Failed to start service")
            }
        }

        /** 停止粘性服务；未启动时亦安全 / Stops the sticky service; safe when not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveStickyService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveStickyService: Failed to stop service")
            }
        }
    }
}
