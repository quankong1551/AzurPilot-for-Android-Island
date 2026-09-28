package com.azurpilot.ghio.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次可安装的更新
 * One installable update.
 *
 * @property versionCode 与 BuildConfig.VERSION_CODE 比较用的整型版本号 /
 *   integer version code compared against BuildConfig.VERSION_CODE
 * @property versionName 展示用版本名 / display version name
 * @property apkUrl Release 域名下的 APK 地址，下载时可套镜像前缀 / APK URL
 *   under the release host, prefixable with a mirror at download time
 * @property apkSha256 期望的整文件 SHA-256（64 位十六进制）/ expected
 *   whole-file SHA-256 (64 hex chars)
 * @property apkSize 期望的字节大小，落盘后与实际文件核对 / expected size in
 *   bytes, checked against the file on disk after download
 */
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long,
)

/**
 * 更新流程的 UI 快照 / UI snapshot of the update flow.
 *
 * @property checking 清单检查进行中 / a manifest check is running
 * @property downloading 下载与校验进行中 / download and verification are
 *   running
 * @property available 有比当前包新的更新；null 表示无需更新 / a newer update
 *   exists; null means up to date
 * @property error 最近一次失败的文案，成功路径上清空 / copy of the latest
 *   failure, cleared on the success path
 */
data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val available: AppUpdateInfo? = null,
    val error: String? = null,
)

/**
 * 固定开发通道的 APK 更新器；系统安装确认仍由 Android Package Installer 展示。
 *
 * 检查与下载都走 Release 清单（[ReleaseUrls]）；下载源支持镜像，大小与 SHA-256
 * 在落盘后校验，安装交给系统安装器。
 *
 * Updater for the fixed dev channel's APK; the system Package Installer still
 * owns the install confirmation.
 *
 * Both check and download go through the release manifest ([ReleaseUrls]).
 * Downloads support mirrors; size and SHA-256 are verified after the file
 * lands, and installation is handed to the system installer.
 */
class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettingsManager,
) {
    private val _state = MutableStateFlow(AppUpdateState())

    /** 更新流程状态流，UI 直接订阅 / The update-flow state stream, observed by the UI directly. */
    val state = _state.asStateFlow()

    /**
     * 拉取 Release 清单，判断是否有新版本
     *
     * 检查或下载进行中时本次调用直接忽略。清单里没有 `apkUrl` 视为「只发了
     * rootfs，还没有可安装 APK」；字段合法性（Release 域名、SHA-256 形态、
     * 大小为正）经 require 校验，不合法按失败处理。仅当清单版本号大于当前
     * 包时才置 [AppUpdateState.available]。协程跑在注入 scope 的 IO 上。
     *
     * Fetches the release manifest and decides whether a newer version exists.
     *
     * The call is ignored while a check or download is already in flight. A
     * manifest without `apkUrl` means "rootfs published, no installable APK
     * yet". Field validity (release host, SHA-256 shape, positive size) is
     * asserted via require, and any violation counts as a failure.
     * [AppUpdateState.available] is set only when the manifest version code
     * exceeds the running build. The coroutine runs on the injected scope's
     * IO dispatcher.
     */
    fun check() {
        if (_state.value.checking || _state.value.downloading) return
        scope.launch(AppDispatchers.IO) {
            _state.update { it.copy(checking = true, error = null) }
            runCatching {
                val indexUrl = ReleaseUrls.selected(ReleaseUrls.INDEX, mirrorPrefix())
                val body = requestText("$indexUrl?t=${System.currentTimeMillis()}")
                val json = JSONObject(body)
                // Latest 可先只发布 rootfs；正式签名尚未配置时没有可安装的 APK。
                if (!json.has("apkUrl")) return@runCatching null
                AppUpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    apkSha256 = json.getString("apkSha256"),
                    apkSize = json.getLong("apkSize"),
                ).also {
                    require(it.apkUrl.startsWith(ReleaseUrls.BASE))
                    require(it.apkSha256.matches(Regex("[0-9a-f]{64}")))
                    require(it.apkSize > 0)
                }
            }.onSuccess { info ->
                _state.update {
                    it.copy(checking = false, available = info?.takeIf { candidate ->
                        candidate.versionCode > BuildConfig.VERSION_CODE
                    })
                }
            }.onFailure { error ->
                Timber.w(error, "App update check failed")
                _state.update { it.copy(checking = false, error = error.message ?: "检查更新失败") }
            }
        }
    }

    /**
     * 下载可用更新并交给系统安装器
     *
     * 无可用更新或已在下载时直接忽略。缓存目录里按 SHA-256 命名的 APK 通过
     * 大小与哈希复核后直接复用；新下载先写 `.part` 临时文件，大小与 SHA-256
     * 双校验通过才改名落定。安装经 FileProvider + ACTION_VIEW 拉起系统
     * Package Installer，由它展示确认界面。
     *
     * Downloads the available update and hands it to the system installer.
     *
     * Ignored when nothing is available or a download is already running. A
     * cached APK named by its SHA-256 is reused after size and hash
     * re-verification; a fresh download lands in a `.part` temp file first and
     * is renamed into place only after both size and SHA-256 pass.
     * Installation goes through FileProvider + ACTION_VIEW, with the system
     * Package Installer showing the confirmation.
     */
    fun downloadAndInstall() {
        val info = _state.value.available ?: return
        if (_state.value.downloading) return
        scope.launch(AppDispatchers.IO) {
            _state.update { it.copy(downloading = true, error = null) }
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { check(mkdirs() || isDirectory) }
                // 安装器可能在 startActivity 返回后才读取文件。按 SHA 命名并保持内容不变，
                // 避免再次点击更新时覆盖它，导致安装阶段的 APK v2 内容摘要不匹配。
                val target = File(dir, "${info.apkSha256}.apk")
                if (target.length() != info.apkSize || sha256Hex(target) != info.apkSha256) {
                    val partial = File(dir, "${info.apkSha256}.apk.part")
                    partial.delete()
                    try {
                        val downloadUrl = ReleaseUrls.selected(info.apkUrl, mirrorPrefix())
                        ReleaseDownloader.download(downloadUrl, partial)
                        require(partial.length() == info.apkSize) { "APK 大小校验失败" }
                        require(sha256Hex(partial) == info.apkSha256) { "APK 校验失败" }
                        require(partial.renameTo(target)) { "无法保存更新安装包" }
                    } finally {
                        partial.delete()
                    }
                }
                launchInstaller(target)
            }.onSuccess {
                _state.update { it.copy(downloading = false) }
            }.onFailure { error ->
                Timber.w(error, "App update download failed")
                _state.update { it.copy(downloading = false, error = error.message ?: "下载更新失败") }
            }
        }
    }

    /**
     * 关闭当前更新提示并清掉错误 / Dismisses the current update prompt and clears the error.
     */
    fun dismiss() = _state.update { it.copy(available = null, error = null) }

    /** 当前生效的镜像前缀（与 Runtime 下载共用同一个源选择）/ The effective mirror prefix (shares the source selection with the runtime download). */
    private fun mirrorPrefix() =
        ReleaseUrls.mirrorPrefix(settings.githubMirror.value, settings.githubMirrorCustom.value)

    /**
     * 拉取 URL 文本正文；12s 连接 / 读取超时，带 no-cache 头并跟随重定向
     *
     * Fetches the URL body as text: 12 s connect/read timeouts, a no-cache
     * header, redirects followed.
     *
     * @throws IllegalArgumentException 非 2xx 状态时 / on a non-2xx status
     */
    private fun requestText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Cache-Control", "no-cache")
            require(connection.responseCode in 200..299) { "更新检查失败（HTTP ${connection.responseCode}）" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 经 FileProvider 拉起系统安装器 / Launches the system installer via FileProvider.
     */
    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

}
