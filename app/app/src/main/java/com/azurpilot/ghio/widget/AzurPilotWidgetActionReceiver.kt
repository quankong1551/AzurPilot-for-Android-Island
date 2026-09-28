package com.azurpilot.ghio.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.azurpilot.ghio.di.AppCoroutineScope
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.service.RunForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import timber.log.Timber

/**
 * 桌面小组件用户点击操作广播接收器 / Broadcast receiver handling user click actions from AppWidgets
 *
 * 核心安全机制：在用户点击小组件的广播接收窗口内第一时间拉起 [RunForegroundService]，
 * 享有 Android 12+ 后台启动前台服务的临时豁免权，彻底避免 ForegroundServiceStartNotAllowedException。
 *
 * Core safety mechanism: Starts [RunForegroundService] immediately inside the user touch
 * broadcast window to utilize the Android 12+ foreground service start exemption.
 */
class AzurPilotWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("AzurPilotWidgetActionReceiver received action=%s", action)

        when (action) {
            ACTION_TOGGLE_RUNNER -> {
                handleToggle(context)
            }
            ACTION_REFRESH -> {
                AzurPilotWidgetUpdater.updateAll(context)
            }
        }
    }

    private fun handleToggle(context: Context) {
        val koin = GlobalContext.getOrNull()
        val runController = runCatching { koin?.get<AzurPilotRunController>() }.getOrNull()
        val prootHost = runCatching { koin?.get<ProotHost>() }.getOrNull()

        val isAlive = runController?.state?.value?.runnerAlive ?: false

        if (isAlive) {
            // 正在运行 -> 停止
            Timber.i("Widget action: Stopping ALAS runner")
            runController.stopRunner()
            AzurPilotWidgetUpdater.updateAll(context)
        } else {
            // 未运行 -> 启动
            Timber.i("Widget action: Starting ALAS runner")
            // 关键：第一时间以用户交互豁免提升为前台服务
            RunForegroundService.start(context)

            val scope = runCatching { koin?.get<CoroutineScope>(named<AppCoroutineScope>()) }.getOrNull()
                ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

            scope.launch {
                // 乐观更新小组件为准备中
                AzurPilotWidgetUpdater.updateAll(context)

                // 确保 Proot 容器拉起并就绪
                prootHost?.ensureStarted()

                if (runController != null) {
                    // 等待薄接口可达（最多等待 30 秒）
                    withTimeoutOrNull(30_000L) {
                        runController.state.first { it.reachable }
                    }
                    runController.startRunner()
                }

                // 启动后稍作延迟更新
                delay(1000L)
                AzurPilotWidgetUpdater.updateAll(context)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_RUNNER = "com.azurpilot.ghio.action.WIDGET_TOGGLE_RUNNER"
        const val ACTION_REFRESH = "com.azurpilot.ghio.action.WIDGET_REFRESH"
    }
}
