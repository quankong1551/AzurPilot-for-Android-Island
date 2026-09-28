package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber

/**
 * 双进程互拉守护：独立守护子进程端服务
 *
 * 运行于独立的 `:daemon` 进程（manifest `android:process=":daemon"`），与主进程的
 * [KeepAliveLocalService] 互相绑定并监听对方 Binder 死亡：主进程因内存压力或系统
 * 策略被杀导致 Binder 破裂（[deathRecipient] 或 onServiceDisconnected 触发）时，
 * 本守护进程立即启动主进程的 [KeepAliveStickyService] 并重新绑定，反之亦然（见对方
 * 类文档），形成双向看门狗。
 *
 * onStartCommand 返回 START_STICKY，守护进程自身被杀后同样由系统重建；
 * onCreate 与 onStartCommand 都会重试绑定，保证断连自愈。
 *
 * Main side of the dual-process watchdog: independent daemon process service.
 *
 * Runs in its own `:daemon` process (manifest `android:process=":daemon"`) and mutually
 * binds [KeepAliveLocalService] in the main process, each side listening for the other's
 * binder death: when the main process is killed by memory pressure or system policy the
 * binder breaks ([deathRecipient] or onServiceDisconnected fires) and this daemon
 * immediately starts [KeepAliveStickyService] in the main process and rebinds; the main
 * process does the same in reverse (see its class doc), forming a two-way watchdog.
 *
 * onStartCommand returns START_STICKY, so the system rebuilds the daemon process too if
 * it is killed; onCreate and onStartCommand both retry the binding for disconnect
 * self-heal.
 */
class KeepAliveDaemonService : Service() {

    private val binder = object : IKeepAliveDaemon.Stub() {
        override fun ping() {
            Timber.d("KeepAliveDaemonService: Ping received from main process")
        }
    }

    /** 主进程服务的远端句柄；断连 / 死亡时清空 / Remote handle to the main-process service; cleared on disconnect / death. */
    private var localService: IKeepAliveDaemon? = null

    /**
     * 主进程 Binder 死亡回调：立即重拉 [KeepAliveStickyService] 并重新绑定，
     * 借助 startService 带动主进程复活
     *
     * Main-process binder death callback: immediately restarts [KeepAliveStickyService]
     * and rebinds, reviving the main process via startService.
     */
    private val deathRecipient = IBinder.DeathRecipient {
        Timber.w("KeepAliveDaemonService: Main process DIED! Resurrecting main process...")
        localService = null
        // 重新拉起主进程
        KeepAliveStickyService.start(this@KeepAliveDaemonService)
        bindLocalService()
    }

    /**
     * 绑定回调：连接成功时挂上 [deathRecipient] 监听主进程死亡；
     * 意外断连时立即重拉并重绑
     *
     * Binding callbacks: on connect, attaches [deathRecipient] to watch for main-process
     * death; on unexpected disconnect, immediately restarts and rebinds.
     */
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

    /** 以 BIND_AUTO_CREATE 绑定主进程服务：绑定本身即可把主进程服务拉起 / Binds the main-process service with BIND_AUTO_CREATE: binding alone creates the main-process service. */
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
        /** 启动守护服务（带动 `:daemon` 进程）；失败仅记录，如后台启动限制 / Starts the daemon service (spawning the `:daemon` process); failures such as background start limits are logged. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveDaemonService::class.java)
                context.startService(intent)
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to start daemon service")
            }
        }

        /** 停止守护服务；未启动时亦安全 / Stops the daemon service; safe when not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveDaemonService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to stop daemon service")
            }
        }
    }
}
