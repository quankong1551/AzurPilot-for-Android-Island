package com.azurpilot.ghio.root

import android.content.IContentProvider
import android.os.Binder
import android.os.Bundle
import android.os.IBinder

import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager

object RootServiceBootstrapClient {

    /** 握手结果：app 生命周期 binder 与 app 进程 pid */
    data class BootstrapResult(val lifecycleBinder: IBinder, val appPid: Int)

    fun attachRemoteService(
        packageName: String,
        userId: Int,
        token: String,
        serviceBinder: IBinder
    ): BootstrapResult? {
        val authority = packageName + RootServiceBootstrapRegistry.AUTHORITY_SUFFIX
        val providerToken = Binder()
        var provider: IContentProvider? = null

        System.err.println("[BootstrapClient] attachRemoteService authority=" + authority + " userId=" + userId)
        try {
            provider = ServiceManager.getActivityManager()
                .getContentProviderExternal(authority, userId, providerToken, authority)
            if (provider == null) {
                Ln.e("Root bootstrap provider is null: " + authority + " user=" + userId)
                System.err.println("[BootstrapClient] getContentProviderExternal returned null")
                return null
            }
            if (!provider.asBinder().pingBinder()) {
                Ln.e("Root bootstrap provider is dead: " + authority + " user=" + userId)
                System.err.println("[BootstrapClient] provider binder is dead")
                return null
            }
            System.err.println("[BootstrapClient] provider ok, calling METHOD_ATTACH_REMOTE_SERVICE")

            val extras = Bundle()
            extras.putString(RootServiceBootstrapRegistry.KEY_TOKEN, token)
            extras.putBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER, serviceBinder)

            val reply = RootIContentProviderCompat.call(
                provider,
                null,
                null,
                authority,
                RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE,
                null,
                extras
            )
            if (reply == null) {
                Ln.e("Root bootstrap provider returned null")
                System.err.println("[BootstrapClient] provider.call() returned null")
                return null
            }

            val lifecycleBinder = reply.getBinder(RootServiceBootstrapRegistry.KEY_APP_BINDER)
            if (lifecycleBinder == null || !lifecycleBinder.pingBinder()) {
                Ln.e("Root bootstrap app lifecycle binder missing")
                System.err.println("[BootstrapClient] app lifecycle binder missing or dead")
                return null
            }
            val appPid = reply.getInt(RootServiceBootstrapRegistry.KEY_APP_PID, 0)
            System.err.println("[BootstrapClient] lifecycle binder received ok, appPid=" + appPid)
            return BootstrapResult(lifecycleBinder, appPid)
        } catch (tr: Throwable) {
            Ln.e("Failed to send binder back to app", tr)
            System.err.println("[BootstrapClient] exception: " + tr)
            tr.printStackTrace(System.err)
            return null
        } finally {
            if (provider != null) {
                ServiceManager.getActivityManager().removeContentProviderExternal(authority, providerToken)
            }
        }
    }
}
