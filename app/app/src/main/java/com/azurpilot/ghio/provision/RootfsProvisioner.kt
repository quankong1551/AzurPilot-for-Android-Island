package com.azurpilot.ghio.provision

import android.app.Application
import android.os.Build
import android.system.Os
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.update.DownloadAborted
import com.azurpilot.ghio.update.ReleaseDownloader
import com.azurpilot.ghio.update.ReleaseUrls
import com.azurpilot.ghio.update.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import timber.log.Timber
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * 首启 rootfs 部署状态机
 *
 * The first-boot rootfs deployment state machine.
 */
sealed interface ProvisionState {
    /**
     * 刚启动，正在比对内置版本与已装版本
     *
     * Just started; comparing the bundled version against the installed one.
     */
    data object Checking : ProvisionState

    /**
     * 未内置 rootfs.tar.xz（或架构不符），且 Release 清单也没有可部署的 Runtime
     *
     * No bundled rootfs.tar.xz (or arch mismatch), and the release manifest
     * offers no deployable Runtime either.
     */
    data object NotBundled : ProvisionState

    /**
     * 磁盘余量不足（roadmap 硬校验：≥2GB）
     *
     * Not enough free disk (the roadmap's hard check: at least 2 GB).
     */
    data class LowDisk(val freeBytes: Long) : ProvisionState

    /**
     * 解压中；进度按压缩字节读数 / 资产总长（流式解压拿不到的解压后总量不用）
     *
     * Extracting; progress reads compressed bytes / archive size (the
     * uncompressed total is unavailable to streaming extraction and unused).
     */
    data class Extracting(val doneBytes: Long, val totalBytes: Long) : ProvisionState

    /** 下载中；进度按已下字节 / 总长 / Downloading; progress reads bytes done / total size. */
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ProvisionState

    /** Runtime 在位可用 / The Runtime is in place and usable. */
    data object Ready : ProvisionState

    /** 部署失败，[reason] 人可读 / Deployment failed; [reason] is human readable. */
    data class Failed(val reason: String) : ProvisionState
}

/**
 * 运行时更新检查的界面态
 *
 * UI state of the runtime update check.
 */
data class RuntimeUpdateCheck(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val latestVersion: String? = null,
    val error: String? = null,
    /**
     * 最新包与已装包的 rootfs_version 只有上游提交前缀不同、构建输入哈希后段一致——
     * 即基础镜像无变化、差异仅在源码，热更可替代整包重部署
     *
     * The latest and installed rootfs_version differ only in the upstream-commit
     * prefix while the build-input hash suffix matches — the base image is
     * unchanged and the difference is source-only, so a hot update can replace
     * a full redeploy.
     */
    val commitOnly: Boolean = false,
)

/**
 * 首启解压流水线：assets 的 rootfs.tar.xz → 内部存储 files/rootfs
 *
 * - **必须内部 filesDir**：/sdcard 模拟存储不支持符号链接（ubuntu-base 有 740 个），
 *   且 noexec；内部 filesDir 是 Spike A 实证 proot 可用的位置（targetSdk 35）。
 * - **按架构选 Runtime**：rootfs 按设备 ABI（[RuntimeArch]，proot 不做指令翻译）构建发布；
 *   内置包的 `BUILD_MANIFEST.rootfs_arch` 与设备不符时跳过内置改走 Release，
 *   Release 按 `latest.json.runtimes[abi]` 取对应架构的包。
 * - 版本闸门：assets 侧 `rootfs/BUILD_MANIFEST` 与 marker `files/rootfs/.provisioned`
 *   对版本号；不一致（或 python3 sanity 不过）就重解。升级=换新包重解，不做增量。
 * - 安装包未内置 rootfs（轻量 APK）时从 Release 拉 `rootfs-<abi>.tar.xz` 自动部署；
 *   两者共用同一条解压流水线。
 * - 落盘走 `rootfs.tmp` 解完再换名，半途失败不留半拉子正式目录。
 * - busybox tar 解 ubuntu-base 硬链接前向引用必炸（M1-d 坑②），故用纯 Java
 *   commons-compress + tukaani xz 流式解；硬链接物化成副本，符号链接走 [Os.symlink]。
 *
 * First-extraction pipeline: the assets rootfs.tar.xz → internal files/rootfs.
 *
 * - **Internal filesDir is mandatory**: emulated /sdcard storage supports no
 *   symlinks (ubuntu-base ships 740) and is noexec; the internal filesDir is
 *   the Spike-A-proven location where proot works (targetSdk 35).
 * - **Per-ABI Runtime**: the rootfs is built and published per device ABI
 *   ([RuntimeArch]; proot does no instruction translation). When the bundled
 *   package's `BUILD_MANIFEST.rootfs_arch` does not match the device, the
 *   bundled path is skipped in favor of Release, which picks the matching
 *   package from `latest.json.runtimes[abi]`.
 * - Version gate: the assets-side `rootfs/BUILD_MANIFEST` is compared with the
 *   marker `files/rootfs/.provisioned`; on mismatch (or a failed python3
 *   sanity check) the tree is re-extracted. Upgrades replace the whole package
 *   and re-extract — no incremental deltas.
 * - When the APK ships without a bundled rootfs (slim builds), the release's
 *   `rootfs-<abi>.tar.xz` is fetched and deployed automatically; both paths
 *   share the same extraction pipeline.
 * - Extraction lands in `rootfs.tmp` and renames only after success, so a
 *   failed run never leaves a half-deployed directory.
 * - busybox tar explodes on ubuntu-base hard links with forward references
 *   (pitfall M1-d ②), hence pure-Java commons-compress + tukaani xz streaming;
 *   hard links materialize as copies, and symlinks go through [Os.symlink].
 */
class RootfsProvisioner(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: AppSettingsManager,
) {

    private val _state = MutableStateFlow<ProvisionState>(ProvisionState.Checking)
    val state: StateFlow<ProvisionState> = _state.asStateFlow()
    private val _updateCheck = MutableStateFlow(RuntimeUpdateCheck())
    val updateCheck: StateFlow<RuntimeUpdateCheck> = _updateCheck.asStateFlow()

    /**
     * 只查询 Latest，不下载或替换运行时。
     *
     * Queries Latest only; never downloads or replaces the runtime. IO 调度器上执行
     * / Runs on the IO dispatcher.
     */
    fun checkForUpdates() {
        if (_updateCheck.value.checking) return
        _updateCheck.value = RuntimeUpdateCheck(checking = true)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val abi = RuntimeArch.deviceAbi() ?: throw IOException("设备架构不受支持")
                parseRuntime(fetchIndex(), abi)?.version
            }.onSuccess { version ->
                // rootfs_version = <上游提交前12位>-<构建输入哈希前10位>：整串不等但
                // 后段一致时，基础镜像没变、差异仅在源码，整包重部署可由热更替代
                val commitOnly = version != null && installedVersion()?.let { installed ->
                    version != installed && inputsHash(version) == inputsHash(installed)
                } == true
                _updateCheck.value = RuntimeUpdateCheck(
                    checked = true, latestVersion = version, commitOnly = commitOnly,
                )
            }.onFailure { error ->
                _updateCheck.value = RuntimeUpdateCheck(checked = true, error = error.message ?: "检查失败")
            }
        }
    }

    /** rootfs_version 的构建输入段（末段哈希）；热更不改它，整包更新才会变 / The build-input segment (trailing hash) of rootfs_version; hot updates never touch it, full redeploys do. */
    private fun inputsHash(version: String): String = version.substringAfterLast('-')

    /**
     * 当前生效的镜像前缀；「换源」判断以 (镜像, 自定义前缀) 二元组整体比较
     *
     * The mirror prefix in effect; "source switched" compares the
     * (mirror, custom prefix) pair as a whole.
     */
    internal fun mirrorPrefix() = ReleaseUrls.mirrorPrefix(settings.githubMirror.value, settings.githubMirrorCustom.value)

    /** 前缀与 [prefix] 不同即视为换了源 / True when the prefix differs from [prefix] — the source switched. */
    internal fun sourceSwitched(prefix: String) = mirrorPrefix() != prefix

    /**
     * Latest 清单；镜像前缀与缓存绕过集中在这里。
     *
     * Fetches the Latest manifest; mirror prefix and cache busting live here. IO
     * 调度器上执行 / Runs on the IO dispatcher.
     *
     * @throws IOException 非 200 响应 / on a non-200 response
     */
    internal fun fetchIndex(): JSONObject {
        val indexUrl = ReleaseUrls.selected(ReleaseUrls.INDEX, mirrorPrefix())
        val connection = URL("$indexUrl?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Cache-Control", "no-cache")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Release 清单里的一条 Runtime 记录
     *
     * One Runtime entry in the release manifest.
     */
    internal data class ReleaseRuntime(val version: String, val url: String, val sha256: String, val size: Long)

    /**
     * 按 ABI 解析 Release 清单。新式清单是 `runtimes: { <abi>: {version,url,sha256,size} }`；
     * 旧式扁平字段（rootfsVersion 等）描述的一直是 arm64 包，仅对 arm64 生效。
     * 该架构没有可用 Runtime 时返回 null。
     *
     * Resolves the release manifest per ABI. The modern manifest is
     * `runtimes: { <abi>: {version,url,sha256,size} }`; the legacy flat fields
     * (rootfsVersion etc.) have always described the arm64 package and apply to
     * arm64 only.
     *
     * @return 该架构的 Runtime 记录；不可用为 null / the Runtime record for the
     *   ABI, or null when none is available
     * @throws IllegalArgumentException 清单字段缺失或非法 / when a manifest
     *   field is missing or invalid
     */
    internal fun parseRuntime(info: JSONObject, abi: String): ReleaseRuntime? {
        info.optJSONObject("runtimes")?.let { runtimes ->
            val entry = runtimes.optJSONObject(abi) ?: return null
            val version = entry.optString("version")
            val url = entry.optString("url")
            val sha = entry.optString("sha256")
            val size = entry.optLong("size", 0)
            require(version.isNotBlank()) { "运行时版本缺失" }
            require(url.startsWith(ReleaseUrls.BASE)) { "运行时下载地址无效" }
            require(sha.matches(SHA256)) { "运行时校验值无效" }
            require(size > 0) { "运行时大小无效" }
            return ReleaseRuntime(version, url, sha, size)
        }
        if (abi != RuntimeArch.ARM64 || !info.has("rootfsVersion")) return null
        val version = info.getString("rootfsVersion")
        require(version.isNotBlank()) { "运行时版本缺失" }
        val url = info.getString("rootfsUrl")
        require(url.startsWith(ReleaseUrls.BASE)) { "运行时下载地址无效" }
        val sha = info.getString("rootfsSha256")
        require(sha.matches(SHA256)) { "运行时校验值无效" }
        val size = info.getLong("rootfsSize")
        require(size > 0) { "运行时大小无效" }
        return ReleaseRuntime(version, url, sha, size)
    }

    /**
     * 用户确认后尝试下载并部署更新；AppRoot 在结果出来前不会启动 proot。
     *
     * 运行中请求会忽略。失败会记录更新检查错误，最终仍进入 [ProvisionState.Ready]，其含义是已有
     * Runtime 继续作为当前部署，不表示本次更新成功。该函数在 IO 调度器运行。
     *
     * Attempts to download and deploy an update after user confirmation; AppRoot does not start
     * proot before the outcome is known.
     *
     * Requests during an active run are ignored. Failure records an update-check error and finally
     * enters [ProvisionState.Ready], which means the existing Runtime remains the current deployment,
     * not that this update succeeded. Runs on the IO dispatcher.
     */
    fun applyUpdate() {
        if (!running.compareAndSet(false, true)) return
        _state.value = ProvisionState.Checking
        scope.launch(Dispatchers.IO) {
            try {
                installFromRelease()
                _updateCheck.value = RuntimeUpdateCheck(checked = true, latestVersion = installedVersion())
            } catch (error: Exception) {
                Timber.w(error, "rootfs update failed")
                _updateCheck.value = _updateCheck.value.copy(error = error.message ?: "更新失败")
            } finally {
                tmpDir.deleteRecursively()
                _state.value = ProvisionState.Ready
                running.set(false)
            }
        }
    }

    /** 部署/更新流水线的互斥位 / The mutex flag around the provision/update pipeline. */
    private val running = AtomicBoolean(false)

    private val rootDir: File get() = File(app.filesDir, "rootfs")
    private val tmpDir: File get() = File(app.filesDir, "rootfs.tmp")

    /** 已装版本标记文件 / The installed-version marker file. */
    private val markerFile: File get() = File(rootDir, MARKER_NAME)

    /**
     * 启动首次部署流水线；幂等，在跑时忽略
     *
     * Starts the first-deploy pipeline; idempotent, ignored while running.
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            scope.launch { run() }
        }
    }

    /** 失败/低磁盘/版本过期后手动重跑 / Manual rerun after a failure, low disk or a stale version. */
    fun retry() = start()

    /**
     * 已装版本（marker 内容），设置页/诊断展示用
     *
     * The installed version (marker content), for the settings page and
     * diagnostics.
     */
    fun installedVersion(): String? =
        runCatching { markerFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

    /**
     * 首启流水线主体：恢复上次中断的部署、按需解压、最后查更新
     *
     * The first-boot pipeline body: restore an interrupted deployment, extract
     * when needed, then check for updates. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    private suspend fun run() = withContext(Dispatchers.IO) {
        _state.value = ProvisionState.Checking
        try {
            val previous = File(app.filesDir, "rootfs.previous")
            if (!rootDir.exists() && previous.exists()) {
                check(previous.renameTo(rootDir)) { "cannot restore previous rootfs" }
            }
            if (!isInstalled()) {
                val deviceAbi = RuntimeArch.deviceAbi()
                val bundled = deviceAbi?.let { bundledRuntime() }?.takeIf { it.arch == deviceAbi }
                if (bundled != null) {
                    checkDisk()
                    extract({ app.assets.open(ASSET_ARCHIVE) }, app.assets.openFd(ASSET_ARCHIVE).use { it.length }, bundled.version)
                } else if (!installFromRelease()) {
                    // 轻量包没内置 Runtime（或内置的是别的架构），Release 也没有本架构可用的：
                    // 只能等下一次发布或换完整版 APK。
                    Timber.w("no usable runtime: bundled arch=%s device=%s", bundled?.arch, deviceAbi)
                    _state.value = ProvisionState.NotBundled
                    return@withContext
                }
            }
            // Runtime 已在位时只查询并提示；用户确认前不替换。
            checkForUpdates()
            _state.value = ProvisionState.Ready
        } catch (e: Exception) {
            Timber.e(e, "rootfs provision failed")
            tmpDir.deleteRecursively()
            _state.value = ProvisionState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }

    /**
     * 已装 rootfs 是否可用：架构匹配、marker 与清单版本一致、python 在位
     *
     * Whether the installed rootfs is usable: arch matches, the marker matches
     * the manifest version, python is present.
     */
    private fun isInstalled(): Boolean {
        val deviceAbi = RuntimeArch.deviceAbi() ?: return false
        val manifest = readManifest { it } ?: return false
        val version = parseVersion(manifest) ?: return false
        // 旧包没写 rootfs_arch，视为 arm64
        if ((parseArch(manifest) ?: RuntimeArch.ARM64) != deviceAbi) return false
        return installedVersion() == version &&
                File(rootDir, PYTHON_REL).let {
                    it.isFile || java.nio.file.Files.isSymbolicLink(it.toPath())
                }
    }

    /**
     * 内置包的版本与架构
     *
     * Version and arch of the bundled package.
     */
    private data class BundledRuntime(val version: String, val arch: String)

    /** 内置包版本与架构；资产缺任一件都视为未内置。旧包没写 rootfs_arch，视为 arm64 */
    private fun bundledRuntime(): BundledRuntime? {
        val manifest = readManifest(fromAssets = true) { it } ?: return null
        val version = parseVersion(manifest) ?: return null
        app.assets.open(ASSET_ARCHIVE).use { }
        return BundledRuntime(version, parseArch(manifest) ?: RuntimeArch.ARM64)
    }

    /**
     * 读 BUILD_MANIFEST 文本：[fromAssets]=true 读 assets 侧，否则读已解包目录。
     * 任何 IO 异常都以 null 收场（版本闸门视为不过）。
     *
     * Reads the BUILD_MANIFEST text: [fromAssets]=true reads the assets side,
     * otherwise the unpacked directory. Any IO failure ends in null (the
     * version gate counts as failed).
     */
    private inline fun <T> readManifest(fromAssets: Boolean = false, block: (String) -> T): T? = runCatching {
        val text = if (fromAssets) {
            app.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() }
        } else {
            File(rootDir, MANIFEST_REL).readText()
        }
        block(text)
    }.getOrNull()

    /** 抓 BUILD_MANIFEST 里的 rootfs_version / Pulls rootfs_version out of BUILD_MANIFEST text. */
    private fun parseVersion(manifestJson: String): String? =
        VERSION_KEY.find(manifestJson)?.groupValues?.get(1)

    /** 抓 BUILD_MANIFEST 里的 rootfs_arch / Pulls rootfs_arch out of BUILD_MANIFEST text. */
    private fun parseArch(manifestJson: String): String? =
        ARCH_KEY.find(manifestJson)?.groupValues?.get(1)

    /**
     * 解压前的磁盘余量硬校验（≥2GB）
     *
     * The hard free-space check before extraction (at least 2 GB).
     *
     * @throws IOException 余量不足 / when free space is insufficient
     */
    internal fun checkDisk() {
        val free = app.filesDir.let { it.mkdirs(); it.usableSpace }
        if (free < MIN_FREE_BYTES) throw IOException("磁盘空间不足：剩余 ${free / 1_000_000} MB，需要至少 2 GB")
    }

    /**
     * 从 Release 下载并部署 Runtime。
     *
     * 清单里还没有 Runtime 字段（旧版 latest.json 只发布 APK）时返回 false；版本已与已装
     * 一致时也返回 false。设备架构不受支持、清单里没有该架构的包时抛异常，调用方把
     * 消息带进 Failed/错误态。
     *
     * Downloads and deploys the Runtime from Release.
     *
     * Returns false when the manifest has no Runtime fields yet (old latest.json
     * shipped only the APK) and when the version already matches what is
     * installed. Throws for an unsupported device arch or a manifest without
     * this arch's package — callers funnel the message into Failed/error states.
     *
     * @return 是否真的部署了新 Runtime / whether a new Runtime was actually deployed
     * @throws IOException 架构不受支持、无可用包或校验失败 / on an unsupported
     *   arch, no available package, or a failed verification
     */
    private suspend fun installFromRelease(): Boolean {
        val info = fetchIndex()
        if (!info.has("rootfsVersion") && !info.has("runtimes")) return false
        val abi = RuntimeArch.deviceAbi()
            ?: throw IOException("设备架构不受支持：${Build.SUPPORTED_ABIS.firstOrNull()}，需要 ${RuntimeArch.SUPPORTED.joinToString()}")
        val runtime = parseRuntime(info, abi)
            ?: throw IOException("发布渠道暂无 $abi 的 Runtime")
        if (runtime.version == installedVersion()) return false
        checkDisk()
        val archive = File(app.filesDir, "rootfs-update.tar.xz")
        try {
            // 下载途中换源就从头再来：不同镜像的断点续传对不上号，删掉重下没有额外风险。
            while (true) {
                val prefix = mirrorPrefix()
                try {
                    downloadArchive(runtime, prefix, archive)
                    break
                } catch (changed: DownloadAborted) {
                    if (!sourceSwitched(prefix)) throw IOException("下载被中止", changed)
                    Timber.i("runtime download source changed, restarting")
                    archive.delete()
                    _state.value = ProvisionState.Downloading(0, runtime.size)
                }
            }
            check(sha256Hex(archive) == runtime.sha256) { "rootfs 下载校验失败" }
            extract({ archive.inputStream() }, runtime.size, runtime.version)
            Timber.i("rootfs installed from release: ${runtime.version}")
        } finally { archive.delete() }
        return true
    }

    /**
     * 单连接下载，进度直推状态机；SHA-256 由调用方在完成后统一校验
     *
     * Single-connection download with progress pushed straight into the state
     * machine; the caller verifies SHA-256 after completion. IO 调度器上执行
     * / Runs on the IO dispatcher.
     *
     * @throws DownloadAborted 下载途中换了源 / when the mirror source switches
     *   mid-download
     */
    internal suspend fun downloadArchive(
        runtime: ReleaseRuntime,
        prefix: String,
        target: File,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ) {
        val downloadUrl = ReleaseUrls.selected(runtime.url, prefix)
        ReleaseDownloader.download(
            url = downloadUrl,
            target = target,
            shouldAbort = { sourceSwitched(prefix) },
            totalBytes = runtime.size,
        ) { done, total ->
            if (onProgress != null) {
                onProgress(done, total)
            } else if (total > 0) {
                _state.value = ProvisionState.Downloading(done, total)
            }
        }
    }

    /**
     * 流式解包到 `rootfs.tmp`，验证后替换正式目录。
     *
     * 步骤：解 tar、补硬链接前向引用、校验版本和 Python、写 marker、在切换前尽力复制用户实例
     * 配置与日志、将旧目录改名为 `rootfs.previous`、再将 tmp 换名。配置和日志迁移不是单独原子的：
     * 复制失败会在切换前终止部署并保留旧 Runtime；最终 tmp 换名失败时会尝试恢复旧目录。
     *
     * Streams the archive into `rootfs.tmp` and replaces the live directory after validation.
     *
     * Steps: untar, backfill forward hard links, verify version and Python, write the marker,
     * best-effort copy user instance configuration and logs before cutover, rename the old directory
     * to `rootfs.previous`, then rename tmp in. Configuration and log migration is not separately
     * atomic: a copy failure stops deployment before cutover and retains the old Runtime; a final
     * tmp-rename failure attempts to restore the old directory.
     *
     * @param openArchive 打开包流的工厂（可重复调用）/ factory opening the archive
     *   stream (repeatable)
     * @param total 包总长，进度分母 / the archive size, the progress denominator
     * @param expectedVersion 解包完成后必须一致的版本 / the version the extracted
     *   manifest must match
     * @throws IOException 解包、路径或换名失败 / on extraction, path or rename failures
     * @throws IllegalStateException 版本或 python 校验不过 / when the version or
     *   python check fails
     */
    private fun extract(openArchive: () -> InputStream, total: Long, expectedVersion: String) {
        extractToStaging(openArchive, total, expectedVersion)
        commitStaging()
    }

    /**
     * 解包到暂存目录 `rootfs.tmp`，校验清单与 Python，迁移用户配置与日志。
     *
     * 完成后 `rootfs.tmp` 已就绪，调用方可在任意时刻调 [commitStaging] 做原子换名，
     * 或调 [rollbackStaging] 放弃。
     *
     * Extracts the archive into the staging directory `rootfs.tmp`, verifies the
     * manifest and Python, and migrates user configuration and logs.
     *
     * When this returns, `rootfs.tmp` is ready; the caller may call
     * [commitStaging] at any point for the atomic cutover, or [rollbackStaging]
     * to give up.
     */
    internal fun extractToStaging(
        openArchive: () -> InputStream,
        total: Long,
        expectedVersion: String,
        onProgress: ((read: Long, total: Long) -> Unit)? = null,
    ) {
        tmpDir.deleteRecursively()
        check(tmpDir.mkdirs()) { "cannot create $tmpDir" }

        val counting = CountingInputStream(openArchive().buffered(BUFFER_SIZE)) { read ->
            if (onProgress != null) {
                onProgress(read, total)
            } else {
                _state.value = ProvisionState.Extracting(read, total)
            }
        }
        val extracted = mutableMapOf<String, File>()
        val deferredLinks = mutableListOf<Pair<String, String>>()

        TarArchiveInputStream(XZInputStream(counting, -1)).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                extractEntry(tar, entry, extracted, deferredLinks)
            }
        }

        // 硬链接前向引用兜底：解完全量后目标必然在（否则包本身坏）
        for ((name, linkName) in deferredLinks) {
            val src = File(tmpDir, linkName)
            check(src.isFile) { "hard link target missing: $linkName (for $name)" }
            src.copyTo(File(tmpDir, name), overwrite = true)
        }

        val extractedVersion = parseVersion(File(tmpDir, MANIFEST_REL).readText())
        check(extractedVersion == expectedVersion) { "rootfs 清单版本不符" }
        val python = File(tmpDir, PYTHON_REL)
        check(python.isFile || java.nio.file.Files.isSymbolicLink(python.toPath())) { "$PYTHON_REL missing" }
        File(tmpDir, MARKER_NAME).writeText(expectedVersion)

        // APK 升级或整包更新重铺 rootfs 时保留用户实例与日志。
        syncUserDataToStaging()
    }

    /**
     * 将当前运行时的用户配置、reloadalas 恢复清单与日志同步到暂存目录。
     *
     * 既在解压完成后初次迁移，也在 [commitStaging] 切换前再次同步（确保 suspend 刚写入的
     * `reloadalas` 能被带到新 rootfs）。
     *
     * Syncs user configs, the reloadalas recovery manifest, and logs to staging.
     */
    internal fun syncUserDataToStaging() {
        val oldPilot = File(rootDir, "opt/azurpilot")
        val newPilot = File(tmpDir, "opt/azurpilot")
        if (!oldPilot.exists() || !newPilot.exists()) return
        oldPilot.resolve("config").listFiles()
            ?.filter { it.isFile && it.extension == "json" &&
                !it.name.startsWith("template") && !it.name.startsWith("deploy") }
            ?.forEach { file -> file.copyTo(newPilot.resolve("config/${file.name}"), overwrite = true) }
        // reloadalas 恢复清单没有扩展名——单独迁移，保证整包更新后调度器自动复活
        oldPilot.resolve("config/reloadalas").takeIf { it.isFile }
            ?.copyTo(newPilot.resolve("config/reloadalas"), overwrite = true)
        oldPilot.resolve("log").takeIf { it.isDirectory }
            ?.copyRecursively(newPilot.resolve("log"), overwrite = true)
    }

    /**
     * 提交暂存目录：原子换名 `rootfs` → `rootfs.previous`，`rootfs.tmp` → `rootfs`。
     *
     * 调用方保证 proot 已停止。换名前会再次同步用户配置与 reloadalas。
     *
     * Commits the staging directory: atomic renames `rootfs` → `rootfs.previous`,
     * then `rootfs.tmp` → `rootfs`. The caller guarantees proot is stopped.
     *
     * @throws IOException 换名失败 / on a rename failure
     */
    internal fun commitStaging() {
        syncUserDataToStaging()
        val previous = File(app.filesDir, "rootfs.previous")
        previous.deleteRecursively()
        val hadRoot = rootDir.exists()
        if (hadRoot) check(rootDir.renameTo(previous)) { "cannot preserve previous rootfs" }
        if (!tmpDir.renameTo(rootDir)) {
            if (hadRoot) previous.renameTo(rootDir)
            throw IOException("rename $tmpDir -> $rootDir failed")
        }
    }

    /**
     * 回滚暂存：删除损坏的 `rootfs`（如有），将 `rootfs.previous` 换回 `rootfs`。
     *
     * 供 [RuntimeAutoUpdater] 健康检查失败时恢复旧版。无 previous 时静默跳过。
     *
     * Rolls the staging back: deletes the broken `rootfs` (if present) and
     * renames `rootfs.previous` back to `rootfs`.
     *
     * Used by [RuntimeAutoUpdater] when the health check fails. Silently skips
     * when no previous exists.
     */
    internal fun rollbackStaging() {
        val previous = File(app.filesDir, "rootfs.previous")
        if (!previous.exists()) return
        rootDir.deleteRecursively()
        check(previous.renameTo(rootDir)) { "rollback rename failed" }
    }

    /**
     * 删除旧版备份 `rootfs.previous`。
     *
     * 供 [RuntimeAutoUpdater] 健康检查通过后清理。
     *
     * Deletes the old-version backup `rootfs.previous`.
     *
     * Used by [RuntimeAutoUpdater] after the health check passes.
     */
    internal fun cleanupPrevious() {
        File(app.filesDir, "rootfs.previous").deleteRecursively()
    }

    /**
     * 解一个 tar 条目；四类条目（目录/软链/硬链/文件）各走各路
     *
     * Extracts one tar entry; the four kinds (directory/symlink/hard link/file)
     * each take their own path. 安全面：拒绝逃出 root 的路径与穿越软链的父目录
     * / Security: paths escaping the root and parents traversing symlinks are
     * rejected.
     *
     * @throws IOException 路径非法、穿越软链或替换软链 / on an illegal path, a
     *   symlink traversal or a symlink replacement
     */
    private fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        extracted: MutableMap<String, File>,
        deferredLinks: MutableList<Pair<String, String>>,
    ) {
        val name = entry.name.removePrefix("./").trimEnd('/')
        if (name.isEmpty()) return
        val root = tmpDir.absoluteFile.toPath().normalize()
        val path = root.resolve(name).normalize()
        // 绝对 guest symlink 在 Android 宿主视角可能悬空；使用词法路径检查，并拒绝穿过已解出的链接。
        if (!path.startsWith(root) || path == root) {
            throw IOException("illegal entry path: $name")
        }
        val target = path.toFile()
        var parent = path.parent
        while (parent != null && parent != root) {
            if (java.nio.file.Files.isSymbolicLink(parent)) {
                throw IOException("entry traverses symlink: $name")
            }
            parent = parent.parent
        }
        if (!entry.isSymbolicLink && java.nio.file.Files.isSymbolicLink(path)) {
            throw IOException("entry replaces symlink: $name")
        }

        when {
            entry.isDirectory -> target.mkdirs()

            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                target.delete()
                Os.symlink(entry.linkName, target.absolutePath)
            }

            entry.isLink -> {
                // 硬链接物化成副本：目标已解出直接拷，前向引用登记后补
                val linkName = entry.linkName.removePrefix("./")
                val src = extracted[linkName]
                if (src != null && src.isFile) {
                    target.parentFile?.mkdirs()
                    src.copyTo(target, overwrite = true)
                } else {
                    deferredLinks += name to linkName
                }
            }

            entry.isFile -> {
                target.parentFile?.mkdirs()
                target.outputStream().buffered(BUFFER_SIZE).use { out ->
                    tar.copyTo(out, BUFFER_SIZE)
                }
                // 可执行位必须保：proot/python/busybox 全靠它（Spike A：缺 +x 报 Permission denied 极易误判）
                if (entry.mode and 0b001_001_001 != 0) target.setExecutable(true, false)
                extracted[name] = target
            }
        }
    }

    /**
     * 每 256KB 才推一次状态，StateFlow 合流前刷太勤纯属白跑重组
     *
     * Pushes progress only every 256 KB — emitting any faster just burns
     * recompositions before the StateFlow conflation settles.
     */
    private class CountingInputStream(
        input: InputStream,
        private val onProgress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var read = 0L
        private var sinceEmit = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            read += n
            sinceEmit += n
            if (sinceEmit >= 256 * 1024) {
                sinceEmit = 0
                onProgress(read)
            }
        }
    }

    private companion object {
        /** APK 内置包路径 / The bundled in-APK archive path. */
        const val ASSET_ARCHIVE = "rootfs/rootfs.tar.xz"

        /** APK 内置清单路径 / The bundled in-APK manifest path. */
        const val ASSET_MANIFEST = "rootfs/BUILD_MANIFEST"

        /** 已解目录内清单的相对路径 / The manifest's path inside the unpacked tree. */
        const val MANIFEST_REL = "opt/azurpilot/BUILD_MANIFEST"

        /** python 可达性探针路径 / The path used to probe python's presence. */
        const val PYTHON_REL = "opt/azurpilot/.venv/bin/python"

        /** 已装版本标记文件名 / The installed-version marker file name. */
        const val MARKER_NAME = ".provisioned"

        /** 磁盘余量硬校验阈值：2GB / The hard free-space check threshold: 2 GB. */
        const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        /** 解压缓冲：256KB 在吞吐与内存间取平 / The extraction buffer: 256 KB balances throughput and memory. */
        const val BUFFER_SIZE = 256 * 1024

        /** 抓 BUILD_MANIFEST 字段的正则 / Regexes pulling the BUILD_MANIFEST fields. */
        val VERSION_KEY = Regex(""""rootfs_version"\s*:\s*"([^"]+)"""")
        val ARCH_KEY = Regex(""""rootfs_arch"\s*:\s*"([^"]+)"""")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
