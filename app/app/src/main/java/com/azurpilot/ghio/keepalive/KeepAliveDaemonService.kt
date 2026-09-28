package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber

/**
 * 守护子进程服务 / Independent daemon process service
 *
 * 运行于独立的 `:daemon` 进程中。与主进程的 [KeepAliveLocalService] 建立双向 Binder 监听。
 * 当主进程因内存压力或系统策略被杀死导致 Binder 破裂（DeathRecipient 触发）时，
 * 本守护进程会立即调起主进程服务，实现双进程互拉自保。
 *
 * Runs in independent :daemon process. Binds mutually to the main process via Binder.
 * When the main process is killed, the death recipient detects binder termination and
 * immediately resurrects the main process.
 */
class KeepAliveDaemonService : Service() {

    private val binder = object : IKeepAliveDaemon.Stub() {
        override fun ping() {
            Timber.d("KeepAliveDaemonService: Ping received from main process")
        }
    }

    private var localService: IKeepAliveDaemon? = null

    private val deathRecipient = IBinder.DeathRecipient {
        Timber.w("KeepAliveDaemonService: Main process DIED! Resurrecting main process...")
        localService = null
        // 重新拉起主进程
        KeepAliveStickyService.start(this@KeepAliveDaemonService)
        bindLocalService()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.d("KeepAliveDaemonService: Connected to KeepAliveLocalService")
            runCatching {
                service?.linkToDeath(deathRecipient, 0)
                localService = IKeepAliveDaemon.Stub.asInterface(service)
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to link to death of main service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.w("KeepAliveDaemonService: KeepAliveLocalService disconnected unexpectedly")
            localService = null
            KeepAliveStickyService.start(this@KeepAliveDaemonService)
            bindLocalService()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveDaemonService: Daemon service created in process ${android.os.Process.myPid()}")
        bindLocalService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bindLocalService()
        return START_STICKY
    }

    private fun bindLocalService() {
        runCatching {
            val intent = Intent(this, KeepAliveLocalService::class.java)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Timber.w(it, "KeepAliveDaemonService: Failed to bind KeepAliveLocalService")
        }
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveDaemonService::class.java)
                context.startService(intent)
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to start daemon service")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveDaemonService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to stop daemon service")
            }
        }
    }
}
