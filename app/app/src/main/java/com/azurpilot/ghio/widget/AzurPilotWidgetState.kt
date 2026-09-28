package com.azurpilot.ghio.widget

import android.content.Context
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotInstance
import com.azurpilot.ghio.proot.AzurPilotOverview
import com.azurpilot.ghio.proot.AzurPilotRunState
import com.azurpilot.ghio.proot.AzurPilotTaskState
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.service.HostSnapshot

/**
 * 小组件视觉状态级别 / Status level for AppWidget visual cues
 */
enum class WidgetStatusLevel {
    OK,
    INACTIVE,
    WARNING,
    ERROR;

    val dotDrawableRes: Int
        get() = when (this) {
            OK -> R.drawable.ic_widget_dot_green
            INACTIVE -> R.drawable.ic_widget_dot_gray
            WARNING -> R.drawable.ic_widget_dot_yellow
            ERROR -> R.drawable.ic_widget_dot_red
        }
}

/**
 * AzurPilot 桌面小组件状态快照 / Snapshot of AzurPilot state displayed on AppWidget
 */
data class AzurPilotWidgetState(
    val runnerAlive: Boolean = false,
    val busy: Boolean = false,
    val config: String = "ap",
    val statusText: String = "",
    val statusLevel: WidgetStatusLevel = WidgetStatusLevel.INACTIVE,
    val currentTask: String? = null,
    val pid: Int? = null,
) {
    companion object {
        fun resolve(
            context: Context,
            host: HostSnapshot?,
            prootPhase: ProotPhase?,
            prootSessionActive: Boolean,
            prootDetail: String,
            run: AzurPilotRunState?,
            overview: AzurPilotOverview?,
            instances: List<AzurPilotInstance>?,
        ): AzurPilotWidgetState {
            val runnerAlive = run?.runnerAlive == true
            val busy = run?.busy == true
            val config = run?.selectedConfig?.ifEmpty { "ap" } ?: "ap"
            val pid = run?.pid

            // 计算当前运行任务
            val activeTask = instances?.firstOrNull()?.currentTask
                ?: overview?.tasks?.firstOrNull { it.state == AzurPilotTaskState.Running }?.name
                ?: run?.logTail?.lastOrNull()?.takeIf { runnerAlive }

            // 计算状态文本与等级
            val level: WidgetStatusLevel
            val statusText: String

            when {
                run != null && !run.reachable && prootPhase == ProotPhase.FAILED -> {
                    level = WidgetStatusLevel.ERROR
                    statusText = context.getString(R.string.widget_status_error)
                }
                run != null && !run.reachable && prootSessionActive -> {
                    level = WidgetStatusLevel.WARNING
                    statusText = context.getString(R.string.widget_status_preparing)
                }
                run != null && run.runnerAlive -> {
                    level = WidgetStatusLevel.OK
                    statusText = if (pid != null && pid > 0) {
                        context.getString(R.string.widget_status_running, pid)
                    } else {
                        context.getString(R.string.widget_status_running_no_pid)
                    }
                }
                busy -> {
                    level = WidgetStatusLevel.WARNING
                    statusText = context.getString(R.string.widget_action_busy)
                }
                else -> {
                    level = WidgetStatusLevel.INACTIVE
                    statusText = context.getString(R.string.widget_status_idle)
                }
            }

            return AzurPilotWidgetState(
                runnerAlive = runnerAlive,
                busy = busy,
                config = config,
                statusText = statusText,
                statusLevel = level,
                currentTask = activeTask,
                pid = pid,
            )
        }
    }
}
