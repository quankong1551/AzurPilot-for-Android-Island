package com.azurpilot.ghio.di

import com.azurpilot.ghio.provision.RootfsProvisioner
import com.azurpilot.ghio.settings.AppSettingsManager
import org.koin.android.ext.koin.androidApplication
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 运行时供给绑定：[RootfsProvisioner] 单例（首启 rootfs 部署与升级状态机）
 *
 * Provisioning binding: the [RootfsProvisioner] singleton (the first-boot
 * rootfs deployment and upgrade state machine).
 */
val provisionModule = module {
    single { RootfsProvisioner(androidApplication(), get(named<AppCoroutineScope>()), get<AppSettingsManager>()) }
}
