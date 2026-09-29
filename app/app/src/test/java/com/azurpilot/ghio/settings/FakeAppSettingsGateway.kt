package com.azurpilot.ghio.settings

import com.azurpilot.ghio.domain.OverlayControlMode
import com.azurpilot.ghio.domain.RunMode
import com.azurpilot.ghio.runner.ResolutionPreference
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存态的应用设置网关测试替身；读写在同一进程内直接可见
 *
 * In-memory fake of [AppSettingsGateway]; shared by
 * [com.azurpilot.ghio.runner.EnvironmentHooksTest] and
 * [com.azurpilot.ghio.session.SessionViewModelTest]. Writes are immediately
 * visible within the same process.
 */
class FakeAppSettingsGateway : AppSettingsGateway {

    override val runMode = MutableStateFlow(RunMode.BACKGROUND)

    override suspend fun setRunMode(mode: RunMode) {
        runMode.value = mode
    }

    override val overlayControlMode = MutableStateFlow(OverlayControlMode.FLOAT_BALL)

    override suspend fun setOverlayControlMode(mode: OverlayControlMode) {
        overlayControlMode.value = mode
    }

    override val screenSaverEnabled = MutableStateFlow(false)

    override suspend fun setScreenSaverEnabled(enabled: Boolean) {
        screenSaverEnabled.value = enabled
    }

    override val closeAppAfterTask = MutableStateFlow(false)

    override suspend fun setCloseAppAfterTask(enabled: Boolean) {
        closeAppAfterTask.value = enabled
    }

    override val touchPreviewEnabled = MutableStateFlow(true)

    override suspend fun setTouchPreviewEnabled(enabled: Boolean) {
        touchPreviewEnabled.value = enabled
    }

    override val resolutionPreference = MutableStateFlow(ResolutionPreference.P720)

    override suspend fun setResolutionPreference(preference: ResolutionPreference) {
        resolutionPreference.value = preference
    }

    override val autoCleanLogs = MutableStateFlow(true)

    override suspend fun setAutoCleanLogs(enabled: Boolean) {
        autoCleanLogs.value = enabled
    }

    override val keepAliveEnabled = MutableStateFlow(false)

    override suspend fun setKeepAliveEnabled(enabled: Boolean) {
        keepAliveEnabled.value = enabled
    }

    override val appLockEnabled = MutableStateFlow(true)

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        appLockEnabled.value = enabled
    }


    override val wakeUnlockEnabled = MutableStateFlow(false)

    override suspend fun setWakeUnlockEnabled(enabled: Boolean) {
        wakeUnlockEnabled.value = enabled
    }

    override val wakeCredential = MutableStateFlow("")

    override suspend fun setWakeCredential(credential: String) {
        wakeCredential.value = credential.filter(Char::isDigit)
    }

    override val telemetryEnabled = MutableStateFlow(false)

    override suspend fun setTelemetryEnabled(enabled: Boolean) {
        telemetryEnabled.value = enabled
    }
}
