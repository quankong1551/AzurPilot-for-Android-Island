package com.azurpilot.ghio.third.wrappers

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.IBinder
import android.os.IInterface

import com.azurpilot.ghio.third.FakeContext

import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 特权进程的系统 binder 服务定位器（进程级单例）
 *
 * 经隐藏 `android.os.ServiceManager.getService` 按名取 binder，再反射
 * `<type>$Stub.asInterface` 包成 [IInterface]，各包装类在其上做方法级反射。
 * 所有 manager 均懒加载缓存；[getDisplayManager] 加了同步——调用方注释明确
 * 它会被 Controller 线程与视频（主）线程并发使用。
 *
 * 从 scrcpy 服务端的 `third/wrappers/ServiceManager.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * System binder service locator for the privileged process (process-level
 * singleton).
 *
 * Fetches binders by name via the hidden `android.os.ServiceManager.getService`
 * and wraps them as [IInterface]s through reflective `<type>$Stub.asInterface`
 * calls; the wrapper classes layer method-level reflection on top. Every
 * manager is lazily created and cached; [getDisplayManager] is synchronized —
 * its callers documented that both the controller thread and the video (main)
 * thread use it concurrently.
 *
 * Ported from scrcpy's server `third/wrappers/ServiceManager.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
object ServiceManager {

    private val GET_SERVICE_METHOD: Method

    init {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null
    private var inputManager: InputManager? = null
    private var powerManager: PowerManager? = null
    private var statusBarManager: StatusBarManager? = null
    private var activityManager: ActivityManager? = null
    private var cameraManager: CameraManager? = null

    /**
     * 按名取 binder 服务并包成 [IInterface]
     *
     * @param service 服务注册名（如 "power"）/ service registration name
     *   (e.g. "power")
     * @param type AIDL 接口全限定名（用于找 `$Stub.asInterface`）/ fully
     *   qualified AIDL interface name (to locate `$Stub.asInterface`)
     * @throws AssertionError binder 或 AIDL 桩反射失败 / reflection on the
     *   binder or AIDL stub failed
     */
    internal fun getService(service: String, type: String): IInterface {
        try {
            val binder = GET_SERVICE_METHOD.invoke(null, service) as IBinder
            val asInterfaceMethod = Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java)
            return asInterfaceMethod.invoke(null, binder) as IInterface
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    /** 懒加载 [WindowManager] 包装 / Lazily creates the [WindowManager] wrapper. */
    fun getWindowManager(): WindowManager {
        if (windowManager == null) {
            windowManager = WindowManager.create()
        }
        return windowManager!!
    }

    // DisplayManager 会被 Controller 线程与视频（主）线程并发使用，故加同步
    @Synchronized
    fun getDisplayManager(): DisplayManager {
        if (displayManager == null) {
            displayManager = DisplayManager.create()
        }
        return displayManager!!
    }

    /** 懒加载 [InputManager] 包装 / Lazily creates the [InputManager] wrapper. */
    fun getInputManager(): InputManager {
        if (inputManager == null) {
            inputManager = InputManager.create()
        }
        return inputManager!!
    }

    /** 懒加载 [PowerManager] 包装 / Lazily creates the [PowerManager] wrapper. */
    fun getPowerManager(): PowerManager {
        if (powerManager == null) {
            powerManager = PowerManager.create()
        }
        return powerManager!!
    }

    /** 懒加载 [StatusBarManager] 包装 / Lazily creates the [StatusBarManager] wrapper. */
    fun getStatusBarManager(): StatusBarManager {
        if (statusBarManager == null) {
            statusBarManager = StatusBarManager.create()
        }
        return statusBarManager!!
    }

    /** 懒加载 [ActivityManager] 包装 / Lazily creates the [ActivityManager] wrapper. */
    fun getActivityManager(): ActivityManager {
        if (activityManager == null) {
            activityManager = ActivityManager.create()
        }
        return activityManager!!
    }

    /**
     * 懒加载真实 [CameraManager] 实例
     *
     * CameraManager 不走 binder 直取，而是用隐藏 `CameraManager(Context)`
     * 构造器配 [FakeContext] 实例化；失败抛 [AssertionError]。
     *
     * Lazily creates a real [CameraManager] instance.
     *
     * CameraManager is not fetched as a raw binder; instead the hidden
     * `CameraManager(Context)` constructor is instantiated with [FakeContext].
     * Failure raises [AssertionError].
     */
    fun getCameraManager(): CameraManager {
        if (cameraManager == null) {
            try {
                val ctor: Constructor<CameraManager> = CameraManager::class.java
                    .getDeclaredConstructor(Context::class.java)
                cameraManager = ctor.newInstance(FakeContext.get())
            } catch (e: Exception) {
                throw AssertionError(e)
            }
        }
        return cameraManager!!
    }
}
