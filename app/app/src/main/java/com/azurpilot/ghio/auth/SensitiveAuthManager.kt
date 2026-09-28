package com.azurpilot.ghio.auth

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.azurpilot.ghio.settings.AppSettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 敏感操作目标类型
 * Sensitive operation target types
 */
enum class SensitiveTarget {
    SETTINGS,
    SCREEN,
}

/**
 * 敏感操作认证与会话管理器
 * Sensitive operation authentication and session manager
 *
 * 职责：
 * 1. 检测设备原生锁屏状态（PIN 码/密码/图案/生物识别）；
 * 2. 根据用户设置与设备安全状态决定是否需要鉴权（未设置锁屏时免密）；
 * 3. 维护已解锁会话状态（离开敏感页或切后台立即锁定）；
 * 4. 调用 Android 原生 BiometricPrompt 进行指纹/面部或锁屏 PIN 码验证。
 *
 * Responsibilities:
 * 1. Inspect device native screen lock status (PIN/password/pattern/biometrics);
 * 2. Determine whether authentication is required based on settings and device security (bypassed if no lock set);
 * 3. Maintain unlocked session state (re-locks on leaving the page or going to background);
 * 4. Invoke Android native BiometricPrompt for biometrics or screen lock credential verification.
 */
class SensitiveAuthManager(
    private val appSettings: AppSettingsManager,
) {

    private val _unlockedTarget = MutableStateFlow<SensitiveTarget?>(null)

    /**
     * 当前已解锁的目标；为 null 表示当前所有敏感目标均处于锁定状态
     * Currently unlocked target; null indicates all sensitive targets are locked
     */
    val unlockedTarget: StateFlow<SensitiveTarget?> = _unlockedTarget.asStateFlow()

    /**
     * 判断设备是否配置了系统锁屏（PIN、图案或密码）
     * Checks if the device is secured with a PIN, pattern, or password
     */
    fun isDeviceSecure(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceSecure == true
    }

    /**
     * 是否需要进行身份验证
     * Whether authentication is required
     *
     * 只有在「设置开启」且「设备已配置系统锁」时才要求验证；未设置 PIN 码或锁屏时免密放行。
     * Only required when enabled in settings AND device has a screen lock configured;
     * bypassed if no PIN or screen lock is set.
     */
    fun isAuthRequired(context: Context): Boolean {
        return appSettings.sensitiveAuthEnabled.value && isDeviceSecure(context)
    }

    /**
     * 目标当前是否已解锁
     * Checks if the target is currently unlocked
     */
    fun isUnlocked(context: Context, target: SensitiveTarget): Boolean {
        if (!isAuthRequired(context)) return true
        return _unlockedTarget.value == target
    }

    /**
     * 标记目标为已解锁
     * Marks the target as unlocked
     */
    fun markUnlocked(target: SensitiveTarget) {
        _unlockedTarget.value = target
    }

    /**
     * 锁定所有目标（例如应用切入后台、息屏时调用）
     * Locks all targets (e.g. called when app goes to background or screen turns off)
     */
    fun lockAll() {
        _unlockedTarget.value = null
    }

    /**
     * 离开指定目标时调用，使该目标的解锁状态失效
     * Invalidate unlocked state when leaving the specified target
     */
    fun onLeaveTarget(target: SensitiveTarget) {
        if (_unlockedTarget.value == target) {
            _unlockedTarget.value = null
        }
    }

    /**
     * 发起系统原生锁验证（生物识别 + 系统 PIN/图案/密码）
     * Prompt native Android authentication (biometrics + device credentials)
     *
     * @param activity 宿主 FragmentActivity
     * @param title 提示标题
     * @param subtitle 提示副标题
     * @param onSuccess 验证成功回调
     * @param onError 异常错误回调
     * @param onCancel 用户取消或关闭回调
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: ((errorCode: Int, errString: CharSequence) -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ) {
        if (!isAuthRequired(activity)) {
            onSuccess()
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onCancel?.invoke()
                    } else {
                        Timber.w("BiometricPrompt error code=$errorCode message=$errString")
                        onError?.invoke(errorCode, errString) ?: onCancel?.invoke()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // 单次指纹识别未通过，系统界面保持并等待重试，不中断流程
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch BiometricPrompt")
            onError?.invoke(-1, e.message.orEmpty()) ?: onCancel?.invoke()
        }
    }
}
