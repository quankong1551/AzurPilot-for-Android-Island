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
 * 激进保活系统总控 / Aggressive keep-alive orchestrator
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

    val isAudioPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val isPixelOverlayAttached: StateFlow<Boolean> = pixelOverlay.isAttached
    val isWakeLockHeld: StateFlow<Boolean> = wakeLock.isHeld

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private var heartbeatJob: Job? = null
    private var lastEventTime = 0L

    // 动态注册的屏幕亮灭与解锁广播接收器
    private var dynamicReceiver: BroadcastReceiver? = null

    companion object {
        @Volatile
        private var instance: KeepAliveManager? = null

        fun getInstance(): KeepAliveManager? = instance
    }

    init {
        instance = this
    }

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

    private fun enableAll() {
        // 1. 启动 24 小时后台静音音频播放
        audioPlayer.start()

        // 2. 挂载前台 1px 透明悬浮窗像素
        pixelOverlay.attach()

        // 3. 持有 CPU 防休眠 WakeLock
        wakeLock.acquire()

        // 4. 启动 START_STICKY 粘性守护服务
        KeepAliveStickyService.start(context)

        // 5. 启动双进程守护 (主进程与 :daemon 互相监听)
        KeepAliveLocalService.start(context)
        KeepAliveDaemonService.start(context)

        // 6. 注册系统 JobScheduler 周期性作业
        jobScheduler.schedule()

        // 7. 调度精准防休眠闹钟
        scheduleNextAlarm()

        // 8. 动态注册屏幕亮灭广播
        registerDynamicReceiver()

        // 9. 启动心跳守护协程
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

    fun scheduleNextAlarm(delayMs: Long = KeepAliveAlarmScheduler.DEFAULT_INTERVAL_MS) {
        if (!appSettings.keepAliveEnabled.value) return
        alarmScheduler.scheduleNext(delayMs)
    }

    /** 伴侣设备事件、无障碍事件、闹钟或广播触发的全面保活自检与自愈 */
    fun onKeepAlivePing() {
        if (!appSettings.keepAliveEnabled.value) return

        // 检查静音音频
        audioPlayer.ensurePlaying()

        // 检查 1px 浮窗
        if (!pixelOverlay.isAttached.value && pixelOverlay.canDrawOverlays()) {
            pixelOverlay.attach()
        }

        // 检查 WakeLock
        if (!wakeLock.isHeld.value) {
            wakeLock.acquire()
        }

        // 确保粘性服务与双进程处于启动状态
        KeepAliveStickyService.start(context)
        KeepAliveLocalService.start(context)
        KeepAliveDaemonService.start(context)
    }

    fun onAccessibilityConnected() {
        _isAccessibilityConnected.value = true
        Timber.d("KeepAliveManager: Accessibility service connected")
        onKeepAlivePing()
    }

    fun onAccessibilityDisconnected() {
        _isAccessibilityConnected.value = false
        Timber.d("KeepAliveManager: Accessibility service disconnected")
    }

    fun onAccessibilityEvent() {
        val now = System.currentTimeMillis()
        if (now - lastEventTime > 30_000L) {
            lastEventTime = now
            onKeepAlivePing()
        }
    }
}
