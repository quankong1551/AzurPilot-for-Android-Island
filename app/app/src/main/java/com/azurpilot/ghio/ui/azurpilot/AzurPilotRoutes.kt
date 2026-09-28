package com.azurpilot.ghio.ui.azurpilot

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.azurpilot.ghio.R

/**
 * AzurPilot 页内的一级分区
 *
 * 用**标签行**而不是第二条底部导航栏：AzurPilot 本身已经是外层的一个底部目的地，
 * 再叠一条底部栏就分不清哪条管哪个层级了。分区之间是平级的视图切换，正是标签的语义。
 *
 * The top-level sections inside the AzurPilot page.
 *
 * A **tab row** instead of a second bottom navigation bar: AzurPilot is already
 * a bottom destination of the outer app, and stacking another bottom bar would
 * blur which bar owns which hierarchy level. Section switches are peer-level
 * view changes — exactly the semantics of tabs.
 *
 * @param route Nav 路由 / the navigation route
 * @param labelRes 标签文案资源 / the tab-label string resource
 * @param icon 标签图标 / the tab icon
 */
enum class AzurPilotSection(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    /** 总览 / Overview. */
    Overview("ap/overview", R.string.ap_section_overview, Icons.Filled.Dashboard),

    /** 配置 / Config. */
    Config("ap/config", R.string.ap_section_config, Icons.Filled.Widgets),

    /** 日志 / Logs. */
    Logs("ap/logs", R.string.ap_section_logs, Icons.AutoMirrored.Filled.Article),

    /** 统计 / Statistics. */
    Statistics("ap/stats", R.string.ap_section_stats, Icons.Filled.BarChart),

    /** 设置 / Settings. */
    Settings("ap/settings", R.string.ap_section_settings, Icons.Filled.Tune),
    ;

    companion object {
        /** 按路由反查分区；不是分区路由时返回 null / Resolves the section for a route; null when the route is not a section. */
        fun ofRoute(route: String?): AzurPilotSection? =
            entries.firstOrNull { it.route == route }

        /**
         * 判断是详情页（推进来的）还是分区（平级的标签）——两者的转场语义完全不同
         *
         * Whether [route] is a detail page (pushed onto the stack) or a section
         * (a peer tab) — the two use completely different transition semantics.
         */
        fun isDetailRoute(route: String?): Boolean = route != null && route in DETAILS

        /**
         * 详情页归属哪个分区，用来决定标签行停在哪儿
         *
         * Which section a detail route belongs to, so the tab row knows where
         * to rest.
         */
        fun parentOfRoute(route: String?): AzurPilotSection = when {
            route == null -> Overview
            route.startsWith(TASK_PREFIX) -> Config
            route in DETAIL_SETTINGS -> Settings
            else -> ofRoute(route) ?: Overview
        }

        /** 任务配置页的路由参数名：任务名 / The route argument name of the task-config page: the task name. */
        const val TASK_ARG = "task"

        /** 任务配置页路由模板 / The route pattern of the task-config page. */
        const val TASK = "ap/task/{$TASK_ARG}"

        /** 拼出某个任务的详情路由 / Builds the detail route for one task. */
        fun task(name: String) = "ap/task/$name"

        private const val TASK_PREFIX = "ap/task/"

        /** 实例管理页路由 / Route of the instance-management page. */
        const val INSTANCES = "ap/instances"

        /** 公告页路由 / Route of the announcement page. */
        const val ANNOUNCEMENT = "ap/announcement"

        /** 猫粮页路由 / Route of the Meowfficer page. */
        const val MEOWFFICER = "ap/meowfficer"

        /** 运行时更新页路由 / Route of the runtime-updater page. */
        const val UPDATER = "ap/updater"

        /** 部署设置页路由 / Route of the deploy-settings page. */
        const val DEPLOY = "ap/deploy"

        /** 全部设置类详情路由 / Every settings-side detail route. */
        val DETAIL_SETTINGS = setOf(INSTANCES, ANNOUNCEMENT, MEOWFFICER, UPDATER, DEPLOY)

        /** 全部详情路由（任务配置 + 设置类） / Every detail route (task config plus the settings ones). */
        val DETAILS = DETAIL_SETTINGS + setOf(TASK)
    }
}
