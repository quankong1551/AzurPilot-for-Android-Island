package com.azurpilot.ghio.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.service.HostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * 桌面小组件状态更新与分发管理器 / AppWidget state updater and dispatcher
 *
 * 负责提取后台环境与调度器的最新运行状态，缓存快照并分发刷新至所有注册的桌面小组件。
 * Extracts the latest runtime status from background services, caches snapshot and updates widgets.
 */
object AzurPilotWidgetUpdater {

    private const val PREFS_NAME = "azurpilot_widget_cache"
    private const val KEY_RUNNER_ALIVE = "runner_alive"
    private const val KEY_BUSY = "busy"
    private const val KEY_CONFIG = "config"
    private const val KEY_STATUS_TEXT = "status_text"
    private const val KEY_STATUS_LEVEL = "status_level"
    private const val KEY_CURRENT_TASK = "current_task"
    private const val KEY_PID = "pid"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var cachedState: AzurPilotWidgetState? = null

    fun currentState(context: Context): AzurPilotWidgetState {
        cachedState?.let { return it }
        // 从 SharedPreferences 恢复缓存状态
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val state = AzurPilotWidgetState(
            runnerAlive = sp.getBoolean(KEY_RUNNER_ALIVE, false),
            busy = sp.getBoolean(KEY_BUSY, false),
            config = sp.getString(KEY_CONFIG, "ap") ?: "ap",
            statusText = sp.getString(KEY_STATUS_TEXT, "") ?: "",
            statusLevel = runCatching {
                WidgetStatusLevel.valueOf(sp.getString(KEY_STATUS_LEVEL, WidgetStatusLevel.INACTIVE.name)!!)
            }.getOrDefault(WidgetStatusLevel.INACTIVE),
            currentTask = sp.getString(KEY_CURRENT_TASK, null),
            pid = if (sp.contains(KEY_PID)) sp.getInt(KEY_PID, 0) else null,
        )
        cachedState = state
        return state
    }

    fun updateAll(context: Context) {
        val state = computeLatestState(context)
        saveState(context, state)
        scope.launch {
            runCatching {
                AzurPilotControlWidget().updateAll(context)
            }.onFailure { Timber.w(it, "Failed to update AzurPilotControlWidget") }
            runCatching {
                AzurPilotQuickWidget().updateAll(context)
            }.onFailure { Timber.w(it, "Failed to update AzurPilotQuickWidget") }
        }
    }

    private fun computeLatestState(context: Context): AzurPilotWidgetState {
        val koin = GlobalContext.getOrNull() ?: return currentState(context)
        val hostState = runCatching { koin.get<HostState>() }.getOrNull()
        val prootHost = runCatching { koin.get<ProotHost>() }.getOrNull()
        val runController = runCatching { koin.get<AzurPilotRunController>() }.getOrNull()
        val repository = runCatching { koin.get<AzurPilotRepository>() }.getOrNull()

        return AzurPilotWidgetState.resolve(
            context = context,
            host = hostState?.snapshot?.value,
            prootPhase = prootHost?.state?.value?.phase,
            prootSessionActive = prootHost?.state?.value?.sessionActive ?: false,
            prootDetail = prootHost?.state?.value?.detail ?: "",
            run = runController?.state?.value,
            overview = repository?.overview?.value,
            instances = repository?.instances?.value,
        )
    }

    private fun saveState(context: Context, state: AzurPilotWidgetState) {
        cachedState = state
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNER_ALIVE, state.runnerAlive)
            .putBoolean(KEY_BUSY, state.busy)
            .putString(KEY_CONFIG, state.config)
            .putString(KEY_STATUS_TEXT, state.statusText)
            .putString(KEY_STATUS_LEVEL, state.statusLevel.name)
            .putString(KEY_CURRENT_TASK, state.currentTask)
            .apply {
                if (state.pid != null) putInt(KEY_PID, state.pid) else remove(KEY_PID)
            }
            .apply()
    }
}
