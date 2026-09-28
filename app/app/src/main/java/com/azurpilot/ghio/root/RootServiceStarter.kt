package com.azurpilot.ghio.root

import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.third.Ln

/**
 * app_process 以 `--class=<本类名>` 拉起的根进程入口；类名在 R8 关键类清单内，不可重命名
 */
object RootServiceStarter {

    private const val TAG = "RootServiceStarter"
    private const val DESTROY_TRANSACTION_CODE = 16777115

    // linkToDeath 随 BinderProxy 被 GC 而失效，须持强引用保证死亡通知可送达
    private var appLifecycleBinder: IBinder? = null
    private var appDeathRecipient: IBinder.DeathRecipient? = null

    @JvmStatic
    fun main(args: Array<String>) {
        System.err.println("[RootServiceStarter] main() entry")
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        val createdService = RootUserService.create(args)
        if (createdService == null) {
            System.err.println("[RootServiceStarter] RootUserService.create() returned null")
            System.exit(1)
            return
        }
        System.err.println("[RootServiceStarter] RootUserService.create() ok, token=" + createdService.token)

        if (!sendBinder(createdService)) {
            System.err.println("[RootServiceStarter] sendBinder() failed")
            System.exit(1)
            return
        }
        System.err.println("[RootServiceStarter] sendBinder() ok, entering Looper")

        Looper.loop()
        System.exit(0)
    }

    private fun sendBinder(createdService: RootUserService.CreatedService): Boolean {
        val result = RootServiceBootstrapClient.attachRemoteService(
            createdService.packageName,
            createdService.userId,
            createdService.token,
            createdService.service
        )
        if (result == null) {
            return false
        }

        primeHeartbeat(createdService.service, result.appPid)

        try {
            val recipient = IBinder.DeathRecipient {
                Ln.i(TAG + ": app process died, destroying root service")
                destroyService(createdService.service)
                Ln.i(TAG + ": root service destroy signal sent, exiting")
                System.exit(0)
            }
            val lifecycleBinder = result.lifecycleBinder
            lifecycleBinder.linkToDeath(recipient, 0)
            appLifecycleBinder = lifecycleBinder
            appDeathRecipient = recipient
            return true
        } catch (tr: Throwable) {
            Ln.e(TAG + ": failed to link app lifecycle binder", tr)
            return false
        }
    }

    /**
     * 提前武装 RemoteServiceImpl 的 /proc 看门狗；
     * 非 RemoteService 的本地 binder 跳过
     */
    private fun primeHeartbeat(service: IBinder, appPid: Int) {
        if (appPid <= 0) {
            return
        }
        try {
            val local = service.queryLocalInterface(RemoteService::class.java.name)
            if (local is RemoteService) {
                local.heartbeat(appPid)
            }
        } catch (tr: Throwable) {
            Ln.w(TAG + ": prime heartbeat failed", tr)
        }
    }

    private fun destroyService(service: IBinder?) {
        if (service == null || !service.pingBinder()) {
            return
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val descriptor = service.interfaceDescriptor
            if (descriptor != null) {
                data.writeInterfaceToken(descriptor)
            }
            service.transact(DESTROY_TRANSACTION_CODE, data, reply, Binder.FLAG_ONEWAY)
        } catch (tr: Throwable) {
            Ln.w(TAG + ": destroy root remote service failed", tr)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
