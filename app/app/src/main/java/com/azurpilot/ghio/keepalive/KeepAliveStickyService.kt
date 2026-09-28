package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

/**
 * 粘性守护服务 / Sticky background daemon service
 *
 * 通过在 [onStartCommand] 中返回 [Service.START_STICKY]，
 * 指示 Android 操作系统在因内存压力被杀死后，在资源充足时自动重新实例化本服务，
 * 从而使应用进程获得由系统驱动的自愈与重生能力。
 *
 * Returns START_STICKY in onStartCommand, instructing Android to automatically
 * recreate the service and resuscitate the app process once memory pressure eases.
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
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, KeepAliveStickyService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveStickyService: Failed to start service")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveStickyService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveStickyService: Failed to stop service")
            }
        }
    }
}
