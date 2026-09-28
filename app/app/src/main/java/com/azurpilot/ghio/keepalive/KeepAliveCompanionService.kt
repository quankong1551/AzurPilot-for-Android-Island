package com.azurpilot.ghio.keepalive

import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * 伴侣设备保活服务 / Companion device keep-alive service
 *
 * 在 Android 12+ (API 31+) 上，注册声明了 [android.Manifest.permission.BIND_COMPANION_DEVICE_SERVICE]
 * 权限及 [android.companion.CompanionDeviceService] intent-filter 的服务。
 * 配合 [android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND] 和
 * [android.Manifest.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND]，使系统将本应用
 * 认定为穿戴/伴侣设备管理服务，授予后台高优先级唤醒豁免、电池限制豁免与网络使用特权。
 *
 * On Android 12+ (API 31+), registers a CompanionDeviceService with BIND_COMPANION_DEVICE_SERVICE.
 * Together with companion permissions (REQUEST_COMPANION_RUN_IN_BACKGROUND & USE_DATA_IN_BACKGROUND),
 * the system treats the app as a companion manager, granting system-level wakefulness exemptions,
 * background execution rights, and battery optimization bypasses.
 */
@RequiresApi(Build.VERSION_CODES.S)
class KeepAliveCompanionService : CompanionDeviceService() {

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveCompanionService: Service created")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    @Deprecated("Deprecated in Java / API 33")
    override fun onDeviceAppeared(address: String) {
        Timber.d("KeepAliveCompanionService: onDeviceAppeared address=$address")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    @Deprecated("Deprecated in Java / API 33")
    override fun onDeviceDisappeared(address: String) {
        Timber.d("KeepAliveCompanionService: onDeviceDisappeared address=$address")
    }

    override fun onDestroy() {
        Timber.d("KeepAliveCompanionService: Service destroyed")
        super.onDestroy()
    }
}
