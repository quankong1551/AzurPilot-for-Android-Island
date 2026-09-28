package com.azurpilot.ghio.settings

import com.aliothmoon.preferences.PrefKey
import com.aliothmoon.preferences.PrefSchema

/** Shizuku 官方包名，manifest 的 `<queries>` 里声明的就是它 / The official Shizuku package name, the one declared in the manifest's `<queries>`. */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/**
 * app 级设置，与 `UserConfiguration` 分开存
 *
 * `UserConfiguration` 是运行配置的聚合根，走 schemaVersion 信封 + 版本不符即重置；
 * 提权后端这类设置不该跟着运行配置一起被重置，所以另起一个 Preferences DataStore
 *
 * 字段一律声明成 String：`@PrefSchema` 生成的 key 按字段类型选 preferencesKey，
 * 枚举与布尔都以文本落盘，改默认值不会让老数据变成非法值（见 [AppSettingsManager] 的解析）
 *
 * key 生成规则：KSP 把字段名按 camelToSnakeCase 落成裸 key（如
 * `githubMirror` → `github_mirror`）。改字段名 = 改裸 key = 丢老数据；
 * DataStore 迁移必须用 snake_case 裸字符串，不能引用本类的属性名
 *
 * App-level settings, stored apart from `UserConfiguration`.
 *
 * `UserConfiguration` is the aggregate root of run configurations and uses the
 * schemaVersion envelope with a reset on version mismatch; settings such as
 * the privileged backend must not be reset along with run configurations, so
 * they get their own Preferences DataStore.
 *
 * Every field is declared as String: the `@PrefSchema`-generated accessors pick
 * the preferencesKey by field type, and enums and booleans are both stored as
 * text, so changing a default value never turns old data into an invalid value
 * (see the parsing in [AppSettingsManager]).
 *
 * Key naming rule: KSP maps each field name to its raw key via
 * camelToSnakeCase (for example `githubMirror` → `github_mirror`). Renaming a
 * field changes the raw key and loses old data; DataStore migrations must use
 * the snake_case raw strings, never this class's property names.
 */
@PrefSchema
data class AppSettings(
    /** 虚拟屏请求刷新率；0 跟随物理屏，仅 Android 14+ 生效 / Requested refresh rate of the virtual display; 0 follows the physical display, effective on Android 14+ only. */
    @PrefKey(default = "0")
    val virtualDisplayRefreshRate: String = "0",

    /** [com.azurpilot.ghio.domain.RemoteBackend] 的 name / The name of a [com.azurpilot.ghio.domain.RemoteBackend]. */
    @PrefKey(default = "SHIZUKU")
    val startupBackend: String = "SHIZUKU",

    /** 用户在引导弹窗上点过「不再提醒」 / The user tapped "don't remind again" on the onboarding dialog. */
    @PrefKey(default = "false")
    val skipShizukuCheck: String = "false",

    /** Shizuku 管理器的包名；有 ROM 内置了自己的分发，允许指到别处 / The Shizuku manager's package name; some ROMs ship their own distribution, so it may point elsewhere. */
    @PrefKey(default = SHIZUKU_PACKAGE)
    val shizukuLaunchPackage: String = SHIZUKU_PACKAGE,

    /** [com.azurpilot.ghio.domain.RunMode] 的 name / The name of a [com.azurpilot.ghio.domain.RunMode]. */
    @PrefKey(default = "BACKGROUND")
    val runMode: String = "BACKGROUND",

    /** [com.azurpilot.ghio.domain.OverlayControlMode] 的 name；仅前台模式生效 / The name of a [com.azurpilot.ghio.domain.OverlayControlMode]; effective in foreground mode only. */
    @PrefKey(default = "FLOAT_BALL")
    val overlayControlMode: String = "FLOAT_BALL",

    /** 后台模式运行期是否自动盖上屏保；默认关，盖住整块屏幕这种事要用户先点头 / Whether the screensaver auto-covers the display while a background run is active; off by default — covering the whole screen needs the user's explicit consent first. */
    @PrefKey(default = "false")
    val screenSaverEnabled: String = "false",

    /** 冷启动自动清理过期日志（AzurPilot 7 天前日志、过期 session.log 截尾）；默认开 / Auto-clean stale logs on cold start (AzurPilot logs older than 7 days, truncated stale session.log); on by default. */
    @PrefKey(default = "true")
    val autoCleanLogs: String = "true",

    /** Release 下载源：direct / 内置镜像前缀（见 [com.azurpilot.ghio.update.ReleaseUrls.MIRRORS]）/ custom / Release download source: direct / a built-in mirror prefix (see [com.azurpilot.ghio.update.ReleaseUrls.MIRRORS]) / custom. */
    @PrefKey(default = "direct")
    val githubMirror: String = "direct",

    /** 自定义镜像前缀（ghproxy 形态：前缀 + 完整 GitHub URL）；仅 githubMirror=custom 时生效 / The custom mirror prefix (ghproxy shape: prefix + full GitHub URL); effective only when githubMirror=custom. */
    @PrefKey(default = "")
    val githubMirrorCustom: String = "",

    /**
     * 激进后台保活系统 / Persistent aggressive keep-alive system
     *
     * 包含后台无音量音频 + 前台 1px 浮窗像素 + 伴侣设备服务 + 无障碍守护
     *
     * Silent audio + 1px overlay pixel + CompanionDeviceService + accessibility daemon
     */
    @PrefKey(default = "false")
    val keepAliveEnabled: String = "false",

    /**
     * 应用锁屏保护 / Global app lock screen protection
     *
     * 依赖系统锁（PIN/指纹/面部）；未设置系统锁时不要求验证
     *
     * Depends on the device screen lock (PIN / biometrics); no verification is
     * demanded when no device lock is set.
     */
    @PrefKey(default = "true")
    val appLockEnabled: String = "true",
)
