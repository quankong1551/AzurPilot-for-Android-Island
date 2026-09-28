package com.azurpilot.ghio.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.azurpilot.ghio.settings.AppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 激进保活系统总控：编排全部保活子系统，并对外提供心跳自检自愈入口
 *
 * 聚合十重保活机制：
 * 1. 后台 24 小时无音量音频 (24/7 background silent audio) -> AudioFlinger 活跃媒体流提升进程优先级
 * 2. 前台 1px 透明浮窗像素 (1px foreground overlay pixel) -> WMS/AMS 判定为 PROCESS_STATE_VISIBLE 状态
 * 3. 伴侣设备服务 (CompanionDeviceService) -> 系统级后台执行与网络豁免
 * 4. 无障碍服务守护 (AccessibilityService daemon) -> 利用 system_server 的无障碍死亡自动重拉机制
 * 5. CPU 防休眠唤醒锁 (WakeLock) -> 阻止系统在息屏时进入深度休眠
 * 6. 定时精准闹钟唤醒 (AlarmManager setExactAndAllowWhileIdle) -> 周期性唤醒 CPU 执行自检自愈
 * 7. 系统定时作业调度 (JobScheduler) -> 15分钟系统级作业持久化守护
 * 8. 系统事件多广播监听 (BroadcastReceiver) -> 静态与动态监听开机、解锁、亮灭屏、电源插拔
 * 9. START_STICKY 粘性服务 (Sticky Service) -> 内存压力释放后系统自动重建服务与进程
 * 10. 双进程互拉守护 (Dual-Process Watchdog) -> 主进程与 :daemon 独立进程互相监听 Binder 死亡并拉起
 *
 * 进程级单例：[init] 写入 [getInstance]，供各接收器 / 服务在无 DI 注入的路径上取回实例。
 * [start] 在 [appScope] 上收集保活开关，切换时全量启用 / 停用所有子系统；运行期间由
 * 30 秒心跳协程、精准闹钟、系统广播与无障碍事件反复触发 [onKeepAlivePing] 自愈。
 *
 * Aggressive keep-alive orchestrator: coordinates every keep-alive subsystem and exposes
 * the heartbeat self-check / self-heal entry point.
 *
 * Ten keep-alive mechanisms:
 * 1. 24/7 background silent audio -> an active media stream in AudioFlinger raises the
 *    process priority
 * 2. 1px transparent foreground overlay pixel -> WMS/AMS classify the process as
 *    PROCESS_STATE_VISIBLE
 * 3. CompanionDeviceService -> system-level background execution and network exemptions
 * 4. Accessibility daemon -> leverages system_server's automatic rebind of accessibility
 *    services
 * 5. CPU partial wake lock -> keeps the system out of deep sleep while the screen is off
 * 6. Exact alarm wake-ups (AlarmManager setExactAndAllowWhileIdle) -> periodic CPU
 *    wake-ups for self-check and self-heal
 * 7. JobScheduler -> a persisted 15-minute system job
 * 8. System-event broadcast listeners -> static and dynamic receivers for boot, unlock,
 *    screen, and power events
 * 9. START_STICKY sticky service -> the system rebuilds it once memory pressure eases
 * 10. Dual-process watchdog -> the main process and the `:daemon` process watch each
 *     other's binder death and restart each other
 *
 * Process-level singleton: [init] writes [getInstance] so receivers and services can
 * reach the instance on paths without DI. [start] collects the keep-alive toggle on
 * [appScope], enabling or disabling all subsystems wholesale on change; while running,
 * the 30-second heartbeat coroutine, exact alarms, system broadcasts, and accessibility
 * events repeatedly drive [onKeepAlivePing].
 */
class KeepAliveManager(
    private val context: Context,
    private val appSettings: AppSettingsGateway,
    private val appScope: CoroutineScope,
) {

    private val audioPlayer = KeepAliveAudioPlayer()
    private val pixelOverlay = KeepAlivePixelOverlay(context)
    private val wakeLock = KeepAliveWakeLock(context)
    private val alarmScheduler = KeepAliveAlarmScheduler(context)
    private val jobScheduler = KeepAliveJobScheduler(context)

    /** 静音音频是否在播 / Whether the silent audio is playing. */
    val isAudioPlaying: StateFlow<Boolean> = audioPlayer.isPlaying

    /** 1px 浮窗是否已挂载 / Whether the 1px overlay is attached. */
    val isPixelOverlayAttached: StateFlow<Boolean> = pixelOverlay.isAttached

    /** WakeLock 是否持有 / Whether the wake lock is held. */
    val isWakeLockHeld: StateFlow<Boolean> = wakeLock.isHeld

    private val _isAccessibilityConnected = MutableStateFlow(false)

    /** 无障碍守护是否已连接系统 / Whether the accessibility daemon is connected to the system. */
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private var heartbeatJob: Job? = null
    private var lastEventTime = 0L

    // 动态注册的屏幕亮灭与解锁广播接收器；这些动作无法静态注册，只能在运行期注册
    private var dynamicReceiver: BroadcastReceiver? = null

    companion object {
        @Volatile
        private var instance: KeepAliveManager? = null

        /**
         * 进程级单例取用；管理器构造前为 null
         *
         * Returns the process-level singleton; null before the manager is constructed.
         */
        fun getInstance(): KeepAliveManager? = instance
    }

    init {
        // 写入进程级单例：接收器与服务没有 DI 注入路径，只能经 getInstance 取回
        instance = this
    }

    /**
     * 启动总控：在 [appScope] 上监听保活开关，切换时全量启用 / 停用所有子系统
     *
     * Starts the orchestrator: observes the keep-alive toggle on [appScope] and
     * enables / disables all subsystems wholesale on change.
     */
    fun start() {
        appScope.launch {
            appSettings.keepAliveEnabled
                .collect { enabled ->
                    Timber.d("KeepAliveManager: keepAliveEnabled changed to $enabled")
                    if (enabled) {
                        enableAll()
                    } else {
                        disableAll()
                    }
                }
        }
    }

    /**
     * 全量启用所有子系统；各子系统自身幂等，重复调用安全
     *
     * Enables every subsystem; each is idempotent, so repeat calls are safe.
     */
    private fun enableAll() {
        audioPlayer.start()

        pixelOverlay.attach()

        wakeLock.acquire()

        KeepAliveStickyService.start(context)

        KeepAliveLocalService.start(context)
        KeepAliveDaemonService.start(context)

        jobScheduler.schedule()

        scheduleNextAlarm()

        registerDynamicReceiver()

        startHeartbeat()

        Timber.i("KeepAliveManager: All keep-alive subsystems ENABLED successfully")
    }

    private fun disableAll() {
        stopHeartbeat()
        unregisterDynamicReceiver()
        alarmScheduler.cancel()
        jobScheduler.cancel()
        KeepAliveDaemonService.stop(context)
        KeepAliveLocalService.stop(context)
        KeepAliveStickyService.stop(context)
        wakeLock.release()
        audioPlayer.stop()
        pixelOverlay.detach()
        Timber.i("KeepAliveManager: All keep-alive subsystems DISABLED")
    }

    /**
     * 启动 30 秒周期心跳协程（[Dispatchers.Default]）：仅在保活开关开启时 ping
     *
     * Starts the 30-second heartbeat coroutine ([Dispatchers.Default]); pings only
     * while the keep-alive toggle is on.
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = appScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(30_000L) // 每 30 秒自检一次
                if (appSettings.keepAliveEnabled.value) {
                    onKeepAlivePing()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 动态注册屏幕亮灭与解锁广播：这些动作在隐式广播豁免清单里，manifest 静态注册收不到
     *
     * Dynamically registers screen on / off and unlock broadcasts; these actions are on
     * the implicit-broadcast exemption list, so static manifest registration never
     * receives them.
     */
    private fun registerDynamicReceiver() {
        if (dynamicReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    Timber.d("KeepAliveManager: Dynamic broadcast action=${intent?.action}")
                    onKeepAlivePing()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(receiver, filter)
            dynamicReceiver = receiver
            Timber.d("KeepAliveManager: Dynamic screen broadcast receiver registered")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveManager: Failed to register dynamic broadcast receiver")
        }
    }

    private fun unregisterDynamicReceiver() {
        dynamicReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            dynamicReceiver = null
            Timber.d("KeepAliveManager: Dynamic screen broadcast receiver unregistered")
        }
    }

    /**
     * 排期下一轮精准闹钟；保活开关关闭时不排期（关闭路径由 disableAll 统一 cancel）
     *
     * Schedules the next exact alarm; skipped while the keep-alive toggle is off
     * (the off path cancels via disableAll).
     *
     * @param delayMs 距触发的延迟 / delay before firing; defaults to
     *   [KeepAliveAlarmScheduler.DEFAULT_INTERVAL_MS]
     */
    fun scheduleNextAlarm(delayMs: Long = KeepAliveAlarmScheduler.DEFAULT_INTERVAL_MS) {
        if (!appSettings.keepAliveEnabled.value) return
        alarmScheduler.scheduleNext(delayMs)
    }

    /**
     * 全面保活自检与自愈入口：由伴侣设备事件、无障碍事件、精准闹钟、系统广播与
     * 30 秒心跳触发
     *
     * 幂等：各子系统先检查后补挂，重复 ping 无副作用；保活开关关闭时直接返回
     *
     * Full keep-alive self-check and self-heal entry point, triggered by companion
     * events, accessibility events, exact alarms, system broadcasts, and the
     * 30-second heartbeat.
     *
     * Idempotent: each subsystem is checked then repaired, so repeated pings are
     * harmless; returns immediately when the keep-alive toggle is off.
     */
    fun onKeepAlivePing() {
        if (!appSettings.keepAliveEnabled.value) return

        audioPlayer.ensurePlaying()

        if (!pixelOverlay.isAttached.value && pixelOverlay.canDrawOverlays()) {
            pixelOverlay.attach()
        }

        if (!wakeLock.isHeld.value) {
            wakeLock.acquire()
        }

        KeepAliveStickyService.start(context)
        KeepAliveLocalService.start(context)
        KeepAliveDaemonService.start(context)
    }

    /**
     * 无障碍守护已连上系统的回调；连接状态置位并触发一次全面自检
     *
     * Callback for the accessibility daemon connecting to the system; sets the
     * connected state and triggers a full self-check.
     */
    fun onAccessibilityConnected() {
        _isAccessibilityConnected.value = true
        Timber.d("KeepAliveManager: Accessibility service connected")
        onKeepAlivePing()
    }

    /**
     * 无障碍守护断开连接的回调；仅置位状态，重拉依赖 system_server 的无障碍自动重连
     *
     * Callback for the accessibility daemon disconnecting; only updates the state,
     * relying on system_server's automatic accessibility reconnect for revival.
     */
    fun onAccessibilityDisconnected() {
        _isAccessibilityConnected.value = false
        Timber.d("KeepAliveManager: Accessibility service disconnected")
    }

    /**
     * 无障碍事件节流入口：30 秒窗口内的多次事件只触发一次全面自检
     *
     * Throttled accessibility-event entry: collapses bursts within a 30-second
     * window into a single full self-check.
     */
    fun onAccessibilityEvent() {
        val now = System.currentTimeMillis()
        if (now - lastEventTime > 30_000L) {
            lastEventTime = now
            onKeepAlivePing()
        }
    }
}
