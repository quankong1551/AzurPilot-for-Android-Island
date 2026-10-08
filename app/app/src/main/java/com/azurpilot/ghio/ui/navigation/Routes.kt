package com.azurpilot.ghio.ui.navigation

import android.net.Uri

/**
 * 二级页面路由
 *
 * 主 tab（Hangar/Screen/AzurPilot/Settings）由 AppRoot 的 HorizontalPager 承载，
 * 不进 NavHost；NavHost 只承载推入式子页面，主 tab 路由仅作空占位。
 * 二级页盖在 Scaffold 之上自成一层，背景与状态栏 inset 由各页自己负责
 *
 * Second-level page routes.
 *
 * The main tabs (Hangar/Screen/AzurPilot/Settings) are carried by AppRoot's
 * HorizontalPager and stay out of the NavHost; the NavHost hosts only pushed
 * sub-pages, with the main-tab routes kept as empty placeholders. Sub-pages stack
 * above the Scaffold as their own layer, so each page draws its own background
 * and consumes its own status-bar insets.
 */
object Routes {
    const val HANGAR = "hangar"
    const val SCREEN = "screen"
    const val AZURPILOT = "azurpilot"
    const val SETTINGS = "settings"

    /**
     * 主 tab 路由集合，用来判断当前是否停在主界面
     *
     * Main-tab route set; tells whether the current destination is a main page.
     */
    val mainTabs: Set<String> = setOf(HANGAR, SCREEN, AZURPILOT, SETTINGS)

    /**
     * 设置主页的分类入口：主页只列分类，内容各自推入二级页
     *
     * Category entries of the settings hub: the hub only lists categories; the
     * content lives in one pushed second-level page each.
     */
    const val SETTINGS_DISPLAY = "settings/display"
    const val SETTINGS_VIRTUAL_DISPLAY = "settings/virtual_display"
    const val SETTINGS_LOGS = "settings/logs"
    const val SETTINGS_KEEP_ALIVE = "settings/keep_alive"
    const val SETTINGS_ADVANCED = "settings/advanced"
    const val SETTINGS_WIDGET = "settings/widget"
    const val SETTINGS_RUNTIME = "settings/runtime"
    /** OCR 后端状态和本机测试页。 / OCR backend status and local tests. */
    const val SETTINGS_OCR = "settings/ocr"
    const val SETTINGS_ABOUT = "settings/about"

    /** 机型报告预览与提交页。 / Device report preview and submission page. */
    const val SETTINGS_DEVICE_REPORT = "settings/device_report"

    /**
     * 开源组件与许可证列表二级页
     *
     * Open-source components and licenses list sub-page.
     */
    const val SETTINGS_LICENSES = "settings/licenses"

    /**
     * 单个开源组件协议原文详情页；id 是组件唯一标识
     *
     * Detail page for one open-source component's full license text; id is the component identifier.
     */
    const val SETTINGS_LICENSE_DETAIL = "settings/licenses/{id}"
    const val SETTINGS_LICENSE_DETAIL_ARG = "id"
    fun licenseDetail(id: String) = "settings/licenses/${Uri.encode(id)}"

    /**
     * 启动器日志（`log/` 目录递归：app.log 系列 / session.log / crash）
     *
     * Launcher logs (recursive `log/` walk: app.log family / session.log / crash).
     */
    const val APP_LOG = "app_log"

    /**
     * 某一份启动器日志的正文；file 是相对 `log/` 目录的路径（含 `/`，必须 URL 编码）
     *
     * Body of one launcher log; file is a path relative to the `log/` directory
     * (contains `/`, must be URL-encoded).
     */
    const val APP_LOG_DETAIL = "app_log_detail/{file}"
    const val APP_LOG_DETAIL_ARG = "file"
    fun appLogDetail(path: String) = "app_log_detail/${Uri.encode(path)}"

    /**
     * AzurPilot 日志（错误现场 + 按天日志两区）
     *
     * AzurPilot logs (two sections: error scenes + daily logs).
     */
    const val AZURPILOT_LOG = "azurpilot_log"

    /**
     * 某一份 AzurPilot 按天日志的正文；file 是 txt 文件名
     *
     * Body of one daily AzurPilot log; file is the txt file name.
     */
    const val AZURPILOT_LOG_DETAIL = "azurpilot_log_detail/{file}"
    const val AZURPILOT_LOG_DETAIL_ARG = "file"
    fun azurPilotLogDetail(fileName: String) = "azurpilot_log_detail/${Uri.encode(fileName)}"

    /**
     * 一个 AzurPilot 错误现场（error/<毫秒时间戳>/）：log.txt + 截图
     *
     * One AzurPilot error scene (error/<millisecond timestamp>/): log.txt +
     * screenshots.
     */
    const val AZURPILOT_ERROR_DETAIL = "azurpilot_error_detail/{dir}"
    const val AZURPILOT_ERROR_DETAIL_ARG = "dir"
    fun azurPilotErrorDetail(dirName: String) = "azurpilot_error_detail/${Uri.encode(dirName)}"
}
