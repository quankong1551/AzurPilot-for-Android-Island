package com.azurpilot.ghio.root

import android.content.AttributionSource
import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.system.Os

object RootIContentProviderCompat {

    private const val SHELL_PACKAGE = "com.android.shell"

    @Throws(RemoteException::class)
    fun call(
        provider: IContentProvider,
        attributeTag: String?,
        callingPkg: String?,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        val pkg = callingPkg ?: SHELL_PACKAGE
        return if (Build.VERSION.SDK_INT >= 31) {
            try {
                val attributionSource = AttributionSource.Builder(Os.getuid())
                    .setAttributionTag(attributeTag)
                    .setPackageName(pkg)
                    .build()
                provider.call(attributionSource, authority, method, arg, extras)
            } catch (e: RuntimeException) {
                provider.call(pkg, attributeTag, authority, method, arg, extras)
            } catch (e: LinkageError) {
                provider.call(pkg, attributeTag, authority, method, arg, extras)
            }
        } else if (Build.VERSION.SDK_INT == 30) {
            provider.call(pkg, attributeTag, authority, method, arg, extras)
        } else if (Build.VERSION.SDK_INT == 29) {
            provider.call(pkg, authority, method, arg, extras)
        } else {
            provider.call(pkg, method, arg, extras)
        }
    }
}
