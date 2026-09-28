package com.azurpilot.ghio.keepalive

import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * 伴侣设备保活服务
 *
 * 在 Android 12+ (API 31+) 上，注册声明了 [android.Manifest.permission.BIND_COMPANION_DEVICE_SERVICE]
 * 权限及 [android.companion.CompanionDeviceService] intent-filter 的服务。
 * 配合 [android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND] 和
 * [android.Manifest.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND]，使系统将本应用
 * 认定为穿戴/伴侣设备管理服务，授予后台高优先级唤醒豁免、电池限制豁免与网络使用特权。
 *
 * 触发源：仅系统（system_server 持 BIND_COMPANION_DEVICE_SERVICE 权限）在存在伴侣设备
 * 关联且设备出现 / 消失时绑定并回调；本服务不自启，仓库内也没有创建关联的调用点，
 * 关联只能由系统侧建立。绑定与回调即触发 [KeepAliveManager.onKeepAlivePing] 自检自愈。
 *
 * Companion device keep-alive service.
 *
 * On Android 12+ (API 31+), registers a CompanionDeviceService with BIND_COMPANION_DEVICE_SERVICE.
 * Together with companion permissions (REQUEST_COMPANION_RUN_IN_BACKGROUND & USE_DATA_IN_BACKGROUND),
 * the system treats the app as a companion manager, granting system-level wakefulness exemptions,
 * background execution rights, and battery optimization bypasses.
 *
 * Trigger source: only the system (system_server holds BIND_COMPANION_DEVICE_SERVICE)
 * binds the service and calls back when a companion device association exists and the
 * device appears / disappears; the service never starts itself and the repo contains no
 * association-creation call site — associations can only be established from the system
 * side. Binding and callbacks both trigger [KeepAliveManager.onKeepAlivePing] for the
 * self-check and self-heal.
 */
@RequiresApi(Build.VERSION_CODES.S)
class KeepAliveCompanionService : CompanionDeviceService() {

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveCompanionService: Service created")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    /**
     * 伴侣设备出现（系统回调）；顺带触发一次保活自检
     *
     * Companion device appeared (system callback); also triggers a keep-alive
     * self-check.
     */
    @Deprecated("Deprecated in Java / API 33")
    override fun onDeviceAppeared(address: String) {
        Timber.d("KeepAliveCompanionService: onDeviceAppeared address=$address")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    /**
     * 伴侣设备消失（系统回调）；仅记录，不做保活动作
     *
     * Companion device disappeared (system callback); logs only, no keep-alive
     * action.
     */
    @Deprecated("Deprecated in Java / API 33")
    override fun onDeviceDisappeared(address: String) {
        Timber.d("KeepAliveCompanionService: onDeviceDisappeared address=$address")
    }

    override fun onDestroy() {
        Timber.d("KeepAliveCompanionService: Service destroyed")
        super.onDestroy()
    }
}
