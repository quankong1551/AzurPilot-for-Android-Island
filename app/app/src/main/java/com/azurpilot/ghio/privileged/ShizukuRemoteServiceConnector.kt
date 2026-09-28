package com.azurpilot.ghio.privileged

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.remote.RemoteServiceImpl
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shizuku 后端的连接器：把 [RemoteServiceImpl] 托管为 Shizuku user service
 *
 * 走 `Shizuku.bindUserService`（进程 `:service`，非守护进程，生命周期跟随绑定）。
 * 这条路没有内建超时，连接兜底靠 [RemoteServiceManager] 的 20s 计时；bind 抛出
 * 与 binder 为 null 都直接进 [RemoteServiceConnectorBackend.Callbacks.onError]。
 *
 * 回调按 connection 身份比对，被新 connect 顶替的旧连接事件一律丢弃。
 *
 * The Shizuku-backend connector; hosts [RemoteServiceImpl] as a Shizuku user
 * service.
 *
 * Goes through `Shizuku.bindUserService` (process `:service`, not a daemon,
 * lifecycle follows the binding). This path has no built-in timeout; the
 * connection backstop is [RemoteServiceManager]'s 20 s timer. A bind throw and
 * a null binder both land straight in
 * [RemoteServiceConnectorBackend.Callbacks.onError].
 *
 * Callbacks are matched against the connection identity; stale connection
 * events superseded by a newer connect are dropped.
 */
object ShizukuRemoteServiceConnector : RemoteServiceConnectorBackend {

    override val backend: RemoteBackend = RemoteBackend.SHIZUKU

    // tag 唯一标识本 app 托管的服务实例，随机生成防跨安装复用
    private val serviceTag = UUID.randomUUID().toString()

    // version 每次连接递增：Shizuku 检测到版本变化会重启旧的 user service 进程，
    // 避免重绑时接到上一轮的陈旧实例
    private val serviceVersion = AtomicInteger(100)

    @Volatile
    private var activeBinding: ActiveBinding? = null

    /**
     * 发起一次 Shizuku user service 绑定
     *
     * 异步；成功、断开、失败经 [callbacks] 回报，bind 调用本身抛出也折算成
     * [RemoteServiceConnectorBackend.Callbacks.onError]。
     *
     * Starts one Shizuku user-service binding.
     *
     * Asynchronous; success, disconnection, and failure are reported through
     * [callbacks], and a throw from the bind call itself is converted into
     * [RemoteServiceConnectorBackend.Callbacks.onError].
     */
    override fun connect(callbacks: RemoteServiceConnectorBackend.Callbacks) {
        val args = createServiceArgs()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                ServiceBootLogger.event("SHIZUKU_ON_CONNECTED", "name=$name binderNull=${binder == null}")
                val binding = activeBinding
                if (binding?.connection !== this) {
                    Timber.w("Ignoring stale Shizuku connection: %s", name)
                    return
                }
                if (binder == null) {
                    callbacks.onError(
                        backend,
                        IllegalStateException("RemoteService binder is null")
                    )
                    return
                }
                Timber.i("RemoteService connected by Shizuku: %s", name)
                callbacks.onConnected(backend, binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                ServiceBootLogger.event("SHIZUKU_ON_DISCONNECTED", "name=$name")
                if (activeBinding?.connection !== this) {
                    return
                }
                Timber.i("RemoteService disconnected by Shizuku: %s", name)
                callbacks.onDisconnected(backend)
            }
        }

        val binding = ActiveBinding(args, connection)
        activeBinding = binding

        runCatching {
            ServiceBootLogger.event("SHIZUKU_BIND_CALL", "version=${serviceVersion.get()} tag=$serviceTag")
            Timber.i("Binding remote service via Shizuku: %s", args)
            Shizuku.bindUserService(args, connection)
        }.onFailure { throwable ->
            if (activeBinding == binding) {
                activeBinding = null
            }
            ServiceBootLogger.event("SHIZUKU_BIND_THROW", "${throwable.javaClass.simpleName}: ${throwable.message}")
            Timber.e(throwable, "bindUserService failed")
            callbacks.onError(backend, throwable)
        }
    }

    /**
     * 解绑 user service；在途绑定直接拆，服务进程随绑定消失（非守护进程）
     *
     * Unbinds the user service; an in-flight binding is torn down and the
     * service process goes away with it (not a daemon).
     */
    override fun disconnect(currentBinder: IBinder?) {
        val binding = activeBinding ?: return
        activeBinding = null
        runCatching {
            Shizuku.unbindUserService(binding.args, binding.connection, true)
        }.onFailure {
            Timber.w(it, "unbindUserService failed")
        }
    }

    /**
     * 组装 user service 参数：进程名带 `service` 后缀、非守护、debuggable 与
     * BuildConfig 对齐、version 递增触发旧进程重启
     *
     * Assembles the user-service args: a `service` suffix on the process name,
     * non-daemon, debuggable matching BuildConfig, and an incremented version
     * that restarts the old service process.
     */
    private fun createServiceArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, RemoteServiceImpl::class.java.name)
        ).apply {
            processNameSuffix("service")
            daemon(false)
            tag(serviceTag)
            version(serviceVersion.incrementAndGet())
            debuggable(BuildConfig.DEBUG)
        }
    }

    /**
     * 一次在途绑定；身份比对与解绑都要用同一对 args/connection
     *
     * One in-flight binding; identity checks and unbinding both need the same
     * args/connection pair.
     */
    private data class ActiveBinding(
        val args: Shizuku.UserServiceArgs,
        val connection: ServiceConnection
    )
}
