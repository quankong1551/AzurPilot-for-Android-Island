package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber

/**
 * 双进程互拉守护：主进程端服务
 *
 * 运行于主进程（默认进程），与 `:daemon` 进程的 [KeepAliveDaemonService] 互相绑定并
 * 监听对方 Binder 死亡：守护进程一旦被杀，本服务通过 [KeepAliveDaemonService.start]
 * 重拉守护进程并重新绑定，反之守护进程也会在主进程死亡时重拉本侧，形成双向看门狗。
 *
 * onStartCommand 返回 START_STICKY，主进程被杀后服务由系统重建；
 * onCreate 与 onStartCommand 都会重试绑定，保证断连自愈。
 *
 * Main-process side of the dual-process watchdog.
 *
 * Runs in the main (default) process and mutually binds [KeepAliveDaemonService] in the
 * `:daemon` process, each side listening for the other's binder death: when the daemon
 * dies this service restarts it via [KeepAliveDaemonService.start] and rebinds; the
 * daemon likewise revives this side when the main process dies, forming a two-way
 * watchdog.
 *
 * onStartCommand returns START_STICKY, so the system recreates the service after the
 * main process is killed; onCreate and onStartCommand both retry the binding for
 * disconnect self-heal.
 */
class KeepAliveLocalService : Service() {

    private val binder = object : IKeepAliveDaemon.Stub() {
        override fun ping() {
            Timber.d("KeepAliveLocalService: Ping received from daemon process")
        }
    }

    /** 守护进程服务的远端句柄；断连 / 死亡时清空 / Remote handle to the daemon service; cleared on disconnect / death. */
    private var daemonService: IKeepAliveDaemon? = null

    /**
     * 守护进程 Binder 死亡回调：立即重拉 [KeepAliveDaemonService] 并重新绑定
     *
     * Daemon binder death callback: immediately restarts [KeepAliveDaemonService]
     * and rebinds.
     */
    private val deathRecipient = IBinder.DeathRecipient {
        Timber.w("KeepAliveLocalService: Daemon process DIED! Resurrecting daemon process...")
        daemonService = null
        KeepAliveDaemonService.start(this@KeepAliveLocalService)
        bindDaemonService()
    }

    /**
     * 绑定回调：连接成功时挂上 [deathRecipient] 监听守护进程死亡；
     * 意外断连时立即重拉并重绑
     *
     * Binding callbacks: on connect, attaches [deathRecipient] to watch for daemon
     * death; on unexpected disconnect, immediately restarts and rebinds.
     */
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

    /** 以 BIND_AUTO_CREATE 绑定守护服务：绑定本身即可把 `:daemon` 进程拉起 / Binds the daemon service with BIND_AUTO_CREATE: binding alone spawns the `:daemon` process. */
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
        /** 启动主进程端守护服务；失败仅记录，如后台启动限制 / Starts the main-process watchdog service; failures such as background start limits are logged. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveLocalService::class.java)
                context.startService(intent)
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to start local service")
            }
        }

        /** 停止主进程端守护服务；未启动时亦安全 / Stops the main-process watchdog service; safe when not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveLocalService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to stop local service")
            }
        }
    }
}
