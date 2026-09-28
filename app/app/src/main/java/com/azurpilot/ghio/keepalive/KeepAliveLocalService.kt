package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber

/**
 * 主进程守护联动服务 / Main process local watchdog service
 *
 * 运行于主进程中，与 `:daemon` 进程的 [KeepAliveDaemonService] 建立双向 Binder 监听。
 * 一旦守护进程被杀，本服务立即感知并重新拉起守护进程，确保双进程永不断连。
 *
 * Runs in main process, binding to KeepAliveDaemonService in :daemon.
 * If the daemon process is terminated, this service instantly resurrects it.
 */
class KeepAliveLocalService : Service() {

    private val binder = object : IKeepAliveDaemon.Stub() {
        override fun ping() {
            Timber.d("KeepAliveLocalService: Ping received from daemon process")
        }
    }

    private var daemonService: IKeepAliveDaemon? = null

    private val deathRecipient = IBinder.DeathRecipient {
        Timber.w("KeepAliveLocalService: Daemon process DIED! Resurrecting daemon process...")
        daemonService = null
        KeepAliveDaemonService.start(this@KeepAliveLocalService)
        bindDaemonService()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.d("KeepAliveLocalService: Connected to KeepAliveDaemonService")
            runCatching {
                service?.linkToDeath(deathRecipient, 0)
                daemonService = IKeepAliveDaemon.Stub.asInterface(service)
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to link to death of daemon service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.w("KeepAliveLocalService: KeepAliveDaemonService disconnected")
            daemonService = null
            KeepAliveDaemonService.start(this@KeepAliveLocalService)
            bindDaemonService()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveLocalService: Local service created in main process")
        bindDaemonService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bindDaemonService()
        return START_STICKY
    }

    private fun bindDaemonService() {
        runCatching {
            val intent = Intent(this, KeepAliveDaemonService::class.java)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Timber.w(it, "KeepAliveLocalService: Failed to bind KeepAliveDaemonService")
        }
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveLocalService::class.java)
                context.startService(intent)
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to start local service")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveLocalService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to stop local service")
            }
        }
    }
}
