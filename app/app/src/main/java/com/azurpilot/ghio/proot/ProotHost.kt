package com.azurpilot.ghio.proot

import android.app.Application
import android.os.Build
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.constant.AppPaths
import com.azurpilot.ghio.provision.RuntimeArch
import com.azurpilot.ghio.provision.RootfsProvisioner
import com.azurpilot.ghio.service.RunForegroundService
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.update.ReleaseUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * proot 会话宿主：以 App 进程为父，拉起 rootfs 内的 wrapper.py（WebUI 由 wrapper 监管）
 *
 * 链路（roadmap 阶段三第 3 条）：
 * 自愈清锁 → 播种实例配置 → 拉起 WebUI
 * → ProcessBuilder 拉起 proot 长跑会话 → 崩溃/退出带退避重拉
 *
 * 生命周期约定：
 * - **stdin 管道必须保持敞开**：wrapper 挂 stdin 监控线程，App 进程一死管道 EOF，
 *   wrapper 杀 runner/gui 进程组后自尽（防孤儿主链路；stop() 也是先关 stdin）
 * - 重拉走 supervisor 协程，退避 3s 翻倍至 60s
 * - FGS 保活：会话活跃期间 RunForegroundService 钉住 app 进程（其退出判据已并入本会话状态）
 *
 * proot session host: spawns the rootfs's wrapper.py with the app process as
 * parent (the wrapper supervises the WebUI).
 *
 * Chain (roadmap phase 3, item 3): self-heal stale locks → seed instance
 * config → bring up the WebUI → ProcessBuilder starts the long-running proot
 * session → respawn with backoff on crash or exit.
 *
 * Lifecycle contract:
 * - **The stdin pipe must stay open**: the wrapper runs a stdin watcher; when
 *   the app process dies the pipe hits EOF, the wrapper kills the runner/gui
 *   process groups and exits (no orphaned chain; stop() also closes stdin
 *   first).
 * - Restarts run on a supervisor coroutine with 3 s backoff doubling to 60 s.
 * - FGS keep-alive: while a session is active, RunForegroundService pins the
 *   app process (its exit criteria fold into this session's state).
 */
class ProotHost(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: AppSettingsManager,
    private val provisioner: RootfsProvisioner,
) {

    private val _state = MutableStateFlow(ProotSnapshot())

    /** 会话状态；UI 与 [RunForegroundService] 的保活判据都读它 / The session state; read by the UI and [RunForegroundService]'s keep-alive criterion. */
    val state: StateFlow<ProotSnapshot> = _state.asStateFlow()

    private val startMutex = Mutex()
    private var session: Process? = null
    private var supervisorJob: kotlinx.coroutines.Job? = null

    /** 「要求会话运行」的意图位；stop() 撤掉后 supervisor 不再重拉 / The "session wanted" intent flag; once stop() clears it the supervisor respawns no more. */
    @Volatile
    private var wantRunning = false

    /** 是否处于「要求运行」状态 / Whether a running session has been requested. */
    val startRequested: Boolean get() = wantRunning

    private val rootfsDir: File get() = File(app.filesDir, "rootfs")
    private val installDir: File get() = File(rootfsDir, "opt/azurpilot")
    private val prootTmpDir: File get() = File(app.filesDir, "proot-tmp")
    private val sessionLog: File get() = File(AppPaths.LOG_DIR, "proot/session.log")
    private val nativeLibDir: String get() = app.applicationInfo.nativeLibraryDir

    /**
     * 请求启动会话；幂等，已在跑/在起直接返回
     *
     * 失败后可重复调（手动重试同一入口）。IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun ensureStarted() {
        wantRunning = true
        scope.launch(AppDispatchers.IO) { startLocked() }
    }

    /**
     * 停会话：关 stdin 让 wrapper 自尽，超时兜底 destroyForcibly
     *
     * Stops the session: stdin closes so the wrapper kills itself, with a
     * destroyForcibly fallback past the grace period. IO 调度器上执行
     * / Runs on the IO dispatcher.
     */
    fun stop() {
        wantRunning = false
        scope.launch(AppDispatchers.IO) {
            startMutex.withLock {
                val proc = session ?: return@withLock
                Timber.i("proot session: stopping")
                runCatching { proc.outputStream.close() }
                withTimeoutOrNull(STOP_GRACE_MS) { runInterruptible { proc.waitFor() } }
                if (proc.isAlive) {
                    Timber.w("proot session: still alive after stdin close, destroyForcibly")
                    proc.destroyForcibly()
                }
                session = null
                _state.update { it.copy(phase = ProotPhase.IDLE, detail = "") }
            }
        }
    }

    /**
     * 重启会话：先请运行时优雅停掉运行中的实例（AP 现有的 update/suspend 协议），
     * 再走完整停起链；会话本就没跑时等价于启动。
     *
     * 实例不会在重启后自动恢复：恢复清单 reloadalas 会在下次 startLocked 的
     * cleanupStale 里被清掉，这是「重启」而非「热更」的语义差别。
     *
     * Restarts the session: asks the runtime to gracefully stop any running
     * instances first (the existing AP update/suspend protocol), then runs the
     * full stop/start chain; a no-op when the session is down (acts as start).
     *
     * Instances do not auto-resume after the restart: the reloadalas recovery
     * manifest is wiped by cleanupStale on the next startLocked — that is the
     * semantic difference from a hot update. IO 调度器上执行 / Runs on the IO
     * dispatcher.
     */
    fun restart() {
        wantRunning = true
        scope.launch(AppDispatchers.IO) {
            suspendGuestInstances()
            stopAndAwait()
            ensureStarted()
        }
    }

    /**
     * 协调式停会话：挂起直到进程完全退出。
     *
     * 供 [com.azurpilot.ghio.provision.RuntimeAutoUpdater] 在 rootfs 切换前调用：
     * 必须保证进程不会被 supervisor 重拉、且完全退出后再动文件系统。
     *
     * Coordinated session stop: suspends until the process is fully dead.
     *
     * Called by [com.azurpilot.ghio.provision.RuntimeAutoUpdater] before a
     * rootfs swap: ensures the supervisor will not respawn and the process is
     * entirely gone before the filesystem is touched. IO 调度器上执行
     * / Runs on the IO dispatcher.
     */
    suspend fun stopAndAwait() {
        wantRunning = false
        supervisorJob?.cancel()
        withContext(AppDispatchers.IO) {
            startMutex.withLock {
                val proc = session ?: return@withLock
                Timber.i("proot session: stopping (auto-update)")
                runCatching { proc.outputStream.close() }
                withTimeoutOrNull(STOP_GRACE_MS) { runInterruptible { proc.waitFor() } }
                if (proc.isAlive) {
                    Timber.w("proot session: still alive after stdin close, destroyForcibly")
                    proc.destroyForcibly()
                }
                session = null
                _state.update { it.copy(phase = ProotPhase.IDLE, detail = "") }
            }
        }
    }

    /**
     * 启动链：自愈清锁、播种实例配置、拉起会话并等待服务就绪。
     *
     * 本函数自行取得 [startMutex]；调用方不得预先持锁。会话已存活时重复调用直接短路。运行在 IO
     * 调度器。
     *
     * The start chain: self-heal cleanup, seed instance configuration, spawn the session, and wait
     * for services.
     *
     * This function acquires [startMutex] itself; callers must not hold it first. Repeat calls
     * short-circuit while the session is alive. Runs on the IO dispatcher.
     */
    private suspend fun startLocked() = startMutex.withLock {
        if (session?.isAlive == true) return@withLock
        if (!installDir.exists()) {
            File(rootfsDir, "opt/azurpilot.previous").takeIf { it.isDirectory }
                ?.renameTo(installDir)
        }
        if (!sanityCheck()) return@withLock

        setState(ProotPhase.PREPARING, "清理残留")
        cleanupStale()
        writeResolvConf()
        ensureGuestUserEntry()

        setState(ProotPhase.PREPARING, "播种实例配置")
        runGuest(
            listOf(".venv/bin/python", "seed_azurpilot.py"),
            SHORT_EXEC_MS,
        )?.let { r ->
            if (r.exit != 0) Timber.w("seed_azurpilot exit=%s out=%s", r.exit, r.output.take(300))
        }

        setState(ProotPhase.STARTING, "拉起 proot 会话")
        // LAN 开关与镜像设置都经 baseEnv 注入会话环境：先等一拍读盘完成，
        // 否则设置刚改完立刻重启时拿到的还是旧值
        awaitSettingsLoaded()
        syncHostOverlay()
        writeRemoteAccessConfig()
        val proc = runCatching { spawnSession() }.getOrElse {
            fail("exec proot: ${it.message}")
            return@withLock
        }
        session = proc
        RunForegroundService.start(app)
        supervise(proc)

        if (awaitServices(SERVICES_UP_MS)) {
            setState(ProotPhase.RUNNING)
            Timber.i("proot session up: AzurPilot ready on %d", WEBUI_PORT)
            // 会话健康 = 新 rootfs 体检通过：统一在此回收旧版备份。手动整包更新链
            // （applyUpdate）不像自动更新链那样自带体检后清理，不做这步的话
            // rootfs.previous（约 2.4GB）会永久滞留且没有任何 UI 能回收
            provisioner.cleanupPrevious()
        } else {
            setState(ProotPhase.STARTING, "等待 AzurPilot 服务就绪")
            Timber.w("AzurPilot WebUI not ready within %dms", SERVICES_UP_MS)
        }
    }

    /**
     * 启动前置体检：ABI 受支持、Python 在位、proot 双库齐
     *
     * Pre-start sanity check: a supported ABI, Python in place, both proot
     * libraries present. 不过关时置 FAILED 并返回 false / Sets FAILED and
     * returns false when any item fails.
     */
    private fun sanityCheck(): Boolean {
        val primaryAbi = RuntimeArch.deviceAbi()
        if (primaryAbi == null) {
            fail(
                "设备架构不受支持（${Build.SUPPORTED_ABIS.firstOrNull()}）；" +
                    "proot 不做指令翻译，需要 ${RuntimeArch.SUPPORTED.joinToString(" 或 ")}",
            )
            return false
        }
        val python = File(installDir, ".venv/bin/python")
        if (!python.exists() && !Files.isSymbolicLink(python.toPath())) {
            fail("rootfs 未部署（AzurPilot Python 缺失）")
            return false
        }
        if (!File(nativeLibDir, "libproot.so").exists()) {
            fail("libproot.so 缺失（当前 ABI 不支持？）")
            return false
        }
        if (!File(nativeLibDir, "libproot-loader.so").exists()) {
            fail("libproot-loader.so 缺失")
            return false
        }
        return true
    }

    /**
     * 拉起长跑会话；调用方持有返回的 Process（stdin 保持敞开，见类头约定）
     *
     * Spawns the long-running session; the caller owns the returned Process
     * (its stdin stays open — see the class doc's contract).
     */
    private fun spawnSession(): Process {
        prootTmpDir.mkdirs()
        sessionLog.parentFile?.mkdirs()
        val cmd = listOf(
            File(nativeLibDir, "libproot.so").absolutePath,
            "-w", GUEST_INSTALL_ROOT,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
            ".venv/bin/python", "android_host.py",
        )
        Timber.i("proot session spawn: %s", cmd.joinToString(" "))
        val proc = ProcessBuilder(cmd)
            .directory(installDir)
            .apply { environment().remove("LD_PRELOAD"); environment().putAll(baseEnv()) }
            .start()
        drainTo(proc.inputStream, "proot-out")
        drainTo(proc.errorStream, "proot-err")
        return proc
    }

    /**
     * proot 进程环境：与 Spike A 实证的同一套（nld 即 LD_LIBRARY_PATH）
     *
     * The proot process environment: the same set Spike A validated
     * ("nld" being LD_LIBRARY_PATH).
     */
    private fun baseEnv(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to nativeLibDir,
        "PROOT_LOADER" to File(nativeLibDir, "libproot-loader.so").absolutePath,
        "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
        "TMPDIR" to prootTmpDir.absolutePath,
        "HOME" to app.filesDir.absolutePath,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG" to "C.UTF-8",
        // rootfs 未装 tzdata：用 POSIX 形式 CST-8（UTC+8 无 DST），不依赖 zoneinfo 文件；
        // 不设则全环境 UTC，AzurPilot 日志/调度时间比设备慢 8 小时
        "TZ" to "CST-8",
        "AZURPILOT_ROOT" to GUEST_INSTALL_ROOT,
        "AZURPILOT_ANDROID" to "1",
        "AZURPILOT_ANDROID_TOKEN" to AndroidControlAuth.get(app),
        // 局域网控制开关：android_host.py 读此位决定 gui.py 绑 0.0.0.0 还是 127.0.0.1。
        // 公网监听下上游自动生成 WebUI 访问口令；/android 薄接口的回环校验不受影响
        "AZURPILOT_ANDROID_LAN" to if (settings.lanControlEnabled.value) "1" else "0",
        // 热更预构建前端的下载基址：ghproxy 形态镜像前缀 + Release 基址，运行时按
        // frontend-<commit>.tar.xz 探测/下载；镜像设置变更后下次会话生效
        "AZURPILOT_ANDROID_DIST_BASE" to
            ReleaseUrls.mirrorPrefix(settings.githubMirror.value, settings.githubMirrorCustom.value) +
            ReleaseUrls.BASE,
    )

    /**
     * stdout/stderr 汇进 session 日志（带行级时间戳太贵，纯追加即可）
     *
     * Drains stdout/stderr into the session log (per-line timestamps cost too
     * much; plain appending suffices).
     */
    private fun drainTo(stream: java.io.InputStream, tag: String) {
        Thread {
            runCatching {
                sessionLog.parentFile?.mkdirs()
                java.io.FileOutputStream(sessionLog, true).bufferedWriter().use { w ->
                    stream.bufferedReader().forEachLine {
                        w.append("[$tag] ").append(it)
                        w.newLine()
                        // 行量小（wrapper 生命周期事件），逐行 flush 保证现场随时可查
                        w.flush()
                    }
                }
            }.onFailure { Timber.d("drain %s closed: %s", tag, it.message) }
        }.apply { isDaemon = true; name = "proot-drain-$tag" }.start()
    }

    /**
     * 崩溃/退出重拉：退避 3s 翻倍至 60s；wantRunning 撤了就不拉。
     * 熔断：会话连续秒退（<QUICK_DEATH_MS）MAX_RAPID_DEATHS 次即放弃——典型诱因是
     * 端口被同机旧装 App 的残留会话占用，此时 awaitServices 会被占位者喂成假 RUNNING，
     * 不退熔断就是 3s 一轮的无限崩溃循环（21:16 真机事故）
     *
     * Respawns after a crash or exit: backoff starts at 3 s and doubles to
     * 60 s; once wantRunning is cleared nothing respawns.
     *
     * Circuit breaker: after MAX_RAPID_DEATHS consecutive quick deaths
     * (<QUICK_DEATH_MS) the supervisor gives up — the classic cause is the port
     * held by a stale session of an older install of the app on the same
     * device, where awaitServices gets fed a fake RUNNING by the squatter.
     * Without the breaker that is an endless 3 s crash loop (a real-device
     * incident at 21:16). IO 调度器上执行 / Runs on the IO dispatcher.
     */
    private fun supervise(first: Process) {
        supervisorJob?.cancel()
        supervisorJob = scope.launch(AppDispatchers.IO) {
            var proc = first
            var backoff = RESTART_BACKOFF_INIT_MS
            var rapidDeaths = 0
            var spawnedAt = System.currentTimeMillis()
            while (true) {
                val code = runCatching { runInterruptible { proc.waitFor() } }.getOrDefault(-1)
                val livedMs = System.currentTimeMillis() - spawnedAt
                Timber.w("proot session exited code=%s lived=%dms", code, livedMs)
                session = null
                if (!wantRunning) break
                rapidDeaths = if (livedMs < QUICK_DEATH_MS) rapidDeaths + 1 else 0
                if (rapidDeaths >= MAX_RAPID_DEATHS) {
                    fail("会话连续 $MAX_RAPID_DEATHS 次秒退（端口被占用？），已停止重拉")
                    break
                }
                setState(ProotPhase.STARTING, "会话退出($code)，${backoff / 1000}s 后重拉")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RESTART_BACKOFF_MAX_MS)
                if (!wantRunning) break
                cleanupStale()
                val next = runCatching { spawnSession() }
                    .onFailure { Timber.w(it, "proot respawn failed") }
                    .getOrNull() ?: continue
                session = next
                proc = next
                spawnedAt = System.currentTimeMillis()
                if (awaitServices(SERVICES_UP_MS)) {
                    backoff = RESTART_BACKOFF_INIT_MS
                    setState(ProotPhase.RUNNING)
                    Timber.i("proot session respawned, wrapper ready")
                    // 与 startLocked 同理：会话健康即回收旧版 rootfs 备份
                    provisioner.cleanupPrevious()
                }
            }
            Timber.i("proot supervisor exited")
        }
    }

    /**
     * 轮询直到 `/android/status`（薄接口，带控制口令）与 `/healthz` 双双可达（1s 一拍）
     *
     * RUNNING 的语义必须是「服务真的能答」：gui.py 进程活着但 uvicorn 还在 import 的几秒里，
     * 两个端口都是 connection refused，此时报 RUNNING 会让界面拿着一个连不上的地址去发请求
     *
     * Polls until both `/android/status` (the thin API, with the control
     * token) and `/healthz` answer (1 s cadence).
     *
     * RUNNING must mean "the services truly answer": in the seconds where
     * gui.py is alive but uvicorn is still importing, both ports refuse
     * connections — reporting RUNNING then hands the UI an address it cannot
     * connect to.
     */
    internal suspend fun awaitServices(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // 无 config 参数时运行时按「首个存活实例或 alas」解析实例名，调度器不在跑而
            // 默认实例是 ap 时会回 400，被误判成服务未就绪（90s 超时、phase 卡 STARTING，
            // 自动更新的健康检查也会因此永远失败回滚）；显式带上播种的默认实例名。
            if (httpOk("http://127.0.0.1:$WEBUI_PORT/android/status?config=${AzurPilotRunState.DEFAULT_CONFIG}") &&
                httpOk("http://127.0.0.1:$WEBUI_PORT/healthz")
            ) {
                return true
            }
            delay(1_000)
        }
        return false
    }

    /** 带口令的探活 GET；任何异常都按不可达处理 / A token-carrying probe GET; any exception counts as unreachable. */
    private fun httpOk(url: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("X-AzurPilot-Android-Token", AndroidControlAuth.get(app))
        conn.connectTimeout = 800
        conn.readTimeout = 800
        conn.inputStream.use { it.readBytes() }
        conn.responseCode == 200
    }.getOrDefault(false)

    /**
     * 重启前请运行时优雅停掉全部运行实例：POST `/android/update/suspend`（AP 现有
     * 协议，整包/热更挂起实例用的同一条），实例有序收尾并落恢复清单。会话不在跑、
     * 运行时已僵死或接口不可达都不阻塞重启——杀会话本身兜底一切。
     *
     * Asks the runtime to gracefully stop every running instance before the
     * restart: POST `/android/update/suspend` (the existing AP protocol also
     * used by full/hot updates to suspend instances), letting them wind down in
     * order and drop the recovery manifest. A down session, a wedged runtime or
     * an unreachable API never blocks the restart — killing the session is the
     * fallback that always works.
     */
    private suspend fun suspendGuestInstances() {
        if (session?.isAlive != true) return
        runCatching {
            val conn = URL("http://127.0.0.1:$WEBUI_PORT/android/update/suspend").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-AzurPilot-Android-Token", AndroidControlAuth.get(app))
            conn.connectTimeout = SUSPEND_CONNECT_TIMEOUT_MS
            conn.readTimeout = SUSPEND_READ_TIMEOUT_MS
            conn.inputStream.use { it.readBytes() }
            Timber.i("proot restart: guest instances suspended")
        }.onFailure { Timber.w(it, "proot restart: suspend instances failed, continuing") }
    }

    /**
     * 等设置读盘落位（最多一拍）；等不到就用当前值继续——LAN 开关与镜像地址
     * 宁可拿到本次默认值也不把启动链卡死。
     *
     * Awaits the settings disk load (one beat at most); proceeds with whatever
     * is loaded otherwise — better a stale LAN toggle or mirror address than a
     * wedged start chain.
     */
    private suspend fun awaitSettingsLoaded() {
        withTimeoutOrNull(SETTINGS_LOADED_WAIT_MS) {
            settings.loaded.first { it }
        }
    }

    /**
     * 用 APK 内置资产覆盖 rootfs 里的 android_host.py（内容一致则跳过）。
     *
     * 该文件是 app↔运行时的边界契约（拉起参数、stdin EOF 契约、绑定地址开关），
     * 以 APK 资产为准：热更只 git 重置上游源码、不更新这个 untracked 文件，
     * 不随 APK 同步的话小包更新的用户永远拿不到新 overlay。
     *
     * 覆盖失败（如老包无此资产）只记日志，沿用 rootfs 既有副本。
     *
     * Overwrites the rootfs's android_host.py with the APK-bundled asset
     * (skipped when the content already matches).
     *
     * The file is the app↔runtime boundary contract (spawn arguments, the stdin
     * EOF contract, the bind-address switch) and the APK asset wins: hot
     * updates only git-reset the upstream source and never touch this
     * untracked file, so without syncing from the APK, slim-update users would
     * never receive a new overlay. A failed overwrite (an older APK without the
     * asset, say) only logs and keeps the rootfs's existing copy.
     */
    private fun syncHostOverlay() {
        runCatching {
            val target = File(installDir, HOST_OVERLAY_NAME)
            val tmp = File(installDir, "$HOST_OVERLAY_NAME.tmp")
            app.assets.open("overlays/$HOST_OVERLAY_NAME").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (target.isFile && target.readBytes().contentEquals(tmp.readBytes())) {
                tmp.delete()
            } else {
                target.delete()
                if (!tmp.renameTo(target)) error("rename to $target failed")
                Timber.i("android_host.py overlay synced from APK asset")
            }
        }.onFailure { Timber.w(it, "sync android_host.py overlay failed") }
    }

    /**
     * 把远程访问开关同步进 rootfs 的 deploy.yaml（App 开关是唯一事实源）。
     *
     * 上游的 RemoteAccess 段（localshare SSH 中转 + P2P 信令）由 rootfs 出厂预填，
     * 这里只做行级改写：翻转 `EnableRemoteAccess`；开启时再补三件事——
     * WebUI 口令为空则生成 32 位强随机（远程入口没有口令等于公开控制权）、
     * `SSHServer`/`SSHExecutable` 缺失或为 null 时落出厂默认。运行时进程此刻
     * 尚未拉起，无并发写者；失败仅记日志（沿用盘上现状，不阻塞启动链）。
     *
     * Syncs the remote-access toggle into the rootfs's deploy.yaml (the App
     * toggle is the single source of truth).
     *
     * The upstream RemoteAccess section (localshare SSH relay + P2P signaling)
     * ships pre-filled in the rootfs, so only line-level edits are needed:
     * flip `EnableRemoteAccess`, and when on, additionally — generate a 32-char
     * random WebUI password if empty (a passwordless remote entry hands control
     * to anyone with the URL) and fill factory defaults for `SSHServer` /
     * `SSHExecutable` when missing or null. The runtime process is not up yet,
     * so there is no concurrent writer; failures only log and keep the on-disk
     * state rather than wedging the start chain.
     */
    private fun writeRemoteAccessConfig() {
        val enabled = settings.remoteAccessEnabled.value
        runCatching {
            val file = File(installDir, DEPLOY_YAML_PATH)
            if (!file.isFile) return
            val tmp = File(installDir, "$DEPLOY_YAML_PATH.tmp")
            tmp.parentFile?.mkdirs()
            tmp.writeText(renderRemoteAccessConfig(file.readText(), enabled))
            file.delete()
            if (!tmp.renameTo(file)) error("rename to $file failed")
            Timber.i("deploy.yaml remote access synced: %s", if (enabled) "on" else "off")
        }.onFailure { Timber.w(it, "sync remote access config failed") }
    }

    /**
     * deploy.yaml 的远程访问行级改写：见 [writeRemoteAccessConfig]
     *
     * Line-level remote-access rewrite of deploy.yaml; see
     * [writeRemoteAccessConfig].
     */
    private fun renderRemoteAccessConfig(text: String, enabled: Boolean): String {
        var lines = text.lines().toMutableList()
        lines = setYamlKey(lines, "EnableRemoteAccess", enabled.toString())
        if (enabled) {
            if (yamlValue(lines, "Password").isNullOrEmpty()) {
                lines = setYamlKey(lines, "Password", generateWebUiPassword())
            }
            if (yamlValue(lines, "SSHServer").isNullOrEmpty()) {
                lines = setYamlKey(lines, "SSHServer", DEFAULT_SSH_SERVER)
            }
            if (yamlValue(lines, "SSHExecutable").isNullOrEmpty()) {
                lines = setYamlKey(lines, "SSHExecutable", DEFAULT_SSH_EXECUTABLE)
            }
            if (yamlValue(lines, "RemoteAccessMode").isNullOrEmpty()) {
                lines = setYamlKey(lines, "RemoteAccessMode", "auto")
            }
        }
        return lines.joinToString("\n")
    }

    /** 取某键的标量值（首个匹配行，剥行内注释；null/空归一为 null）/ Reads a key's scalar (first match, inline comment stripped; null and empty normalize to null). */
    private fun yamlValue(lines: List<String>, key: String): String? {
        val regex = Regex("^\\s*${Regex.escape(key)}:\\s*(.*)$")
        for (line in lines) {
            val m = regex.find(line) ?: continue
            val value = m.groupValues[1].substringBefore(" #").trim()
            return value.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) || it == "\"\"" || it == "''" }
        }
        return null
    }

    /**
     * 行级 set-or-append：改首个 `Key:` 行的值；键不存在则插进所在段尾，
     * 段也没有时整体追加。缩进沿用原行（新行用四格，段用两格——与出厂模板一致）
     *
     * Line-level set-or-append: rewrites the first `Key:` line; appends into the
     * key's section (or a new section at EOF) when absent. Rewritten lines keep
     * their original indent; new lines use four spaces (two for sections) to
     * match the factory template.
     */
    private fun setYamlKey(lines: MutableList<String>, key: String, value: String): MutableList<String> {
        val keyRegex = Regex("^(\\s*)${Regex.escape(key)}:.*$")
        val keyIndex = lines.indexOfFirst { keyRegex.matches(it) }
        if (keyIndex >= 0) {
            lines[keyIndex] = "${lines[keyIndex].takeWhile { it == ' ' }}$key: $value"
            return lines
        }
        // 键不存在：定位所属段（RemoteAccess 键归 RemoteAccess 段，其余归 Webui 段）
        val section = if (key == "EnableRemoteAccess" || key == "SSHServer" ||
            key == "SSHExecutable" || key == "RemoteAccessMode"
        ) {
            "RemoteAccess"
        } else {
            "Webui"
        }
        val sectionHeader = Regex("^\\s{2}${Regex.escape(section)}:\\s*$")
        val sectionIndex = lines.indexOfFirst { sectionHeader.matches(it) }
        if (sectionIndex < 0) {
            lines.addAll(listOf("", "  $section:", "    $key: $value"))
            return lines
        }
        var insertAt = lines.size
        for (i in sectionIndex + 1 until lines.size) {
            val line = lines[i]
            val isSectionHeader = line.length >= 2 && line[0] == ' ' && line[1] != ' ' && line.trimEnd().endsWith(":")
            if (isSectionHeader) {
                insertAt = i
                break
            }
        }
        lines.add(insertAt, "    $key: $value")
        return lines
    }

    /** 32 位强随机 WebUI 口令，字符集与上游 generate_webui_password 一致 / A 32-char strong WebUI password; the alphabet matches upstream's generate_webui_password. */
    private fun generateWebUiPassword(): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val random = java.security.SecureRandom()
        return buildString(32) { repeat(32) { append(alphabet[random.nextInt(alphabet.size)]) } }
    }

    /**
     * 一次性 proot 执行的结果
     *
     * The result of a one-shot proot execution.
     *
     * @property exit 退出码；超时被杀时为 null / the exit code; null when killed
     *   on timeout
     * @property output 合并后的全部输出 / the merged full output
     * @property timedOut 是否超时被强杀 / whether it was killed on timeout
     */
    data class ExecResult(
        val exit: Int?,
        val output: String,
        val timedOut: Boolean,
    )

    /**
     * 带默认环境的 [runGuestRaw]（seed/assets_fix 用）
     *
     * [runGuestRaw] with the default environment (used by seed/assets_fix);
     * failures log and return null instead of throwing.
     */
    private suspend fun runGuest(
        guestCmd: List<String>,
        timeoutMs: Long,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult? = runCatching { runGuestRaw(guestCmd, timeoutMs, extraEnv) }
        .onFailure { Timber.w(it, "guest exec failed: %s", guestCmd.joinToString(" ")) }
        .getOrNull()

    /**
     * 一次性 proot 执行：合并 stderr，限时强杀；输出整体回收（更新脚本的 verdict 在里面）
     *
     * A one-shot proot execution: stderr merged, hard-killed past the limit;
     * output is collected whole (the update script's verdict lives inside). IO
     * 调度器上执行 / Runs on the IO dispatcher.
     */
    private suspend fun runGuestRaw(
        guestCmd: List<String>,
        timeoutMs: Long,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult = withContext(AppDispatchers.IO) {
        prootTmpDir.mkdirs()
        val cmd = listOf(
            File(nativeLibDir, "libproot.so").absolutePath,
            "-w", GUEST_INSTALL_ROOT,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
        ) + guestCmd
        val proc = ProcessBuilder(cmd)
            .directory(installDir)
            .redirectErrorStream(true)
            .apply { environment().remove("LD_PRELOAD"); environment().putAll(baseEnv()); environment().putAll(extraEnv) }
            .start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        }.apply { isDaemon = true; name = "proot-exec-reader" }
        reader.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) proc.destroyForcibly()
        reader.join(2_000)
        ExecResult(if (finished) proc.exitValue() else null, out.toString(), !finished)
    }

    /**
     * 自愈清锁：proot 临时目录整体重来 + git 锁 + reloadalas（会话不在跑时才可调）
     *
     * Self-heal cleanup: rebuild the proot temp dir from scratch, plus git
     * locks and reloadalas (callable only while no session runs). IO 调度器上执行
     * / Runs on the IO dispatcher.
     */
    private suspend fun cleanupStale() {
        runCatching {
            prootTmpDir.deleteRecursively()
            prootTmpDir.mkdirs()
        }.onFailure { Timber.w(it, "cleanup proot-tmp failed") }
        runCatching { File(installDir, "config/reloadalas").delete() }
        runCatching {
            val gitDir = File(installDir, ".git")
            if (gitDir.isDirectory) {
                gitDir.walkTopDown().filter { it.isFile && it.name.endsWith(".lock") }
                    .forEach { it.delete() }
            }
        }.onFailure { Timber.w(it, "cleanup git locks failed") }
        truncateSessionLogIfStale()
    }

    /**
     * session.log 截尾：mtime 超 7 天且体积超上限时只留最后 [SESSION_LOG_KEEP_BYTES]
     *
     * 放在这里做是因为 startLocked 每次启动必经、且早于 spawnSession——此刻没有
     * drain 线程在写，无竞争；截断会刷新 mtime，崩溃重拉循环里不会再重复截
     *
     * Truncates session.log: when its mtime is over 7 days old and its size
     * exceeds the cap, only the last [SESSION_LOG_KEEP_BYTES] survive.
     *
     * It lives here because startLocked passes through on every start, before
     * spawnSession — no drain thread is writing yet, so there is no race; the
     * truncation refreshes the mtime, so a crash-respawn loop never truncates
     * twice. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    private suspend fun truncateSessionLogIfStale() {
        // 设置读盘是异步的：最多等一拍，等不到就本次跳过（下轮启动再判），不卡启动链
        val loaded = withTimeoutOrNull(SETTINGS_LOADED_WAIT_MS) {
            settings.loaded.first { it }
            true
        } ?: false
        if (!loaded || !settings.autoCleanLogs.value) return

        val file = sessionLog
        val length = file.length()
        if (!file.isFile || length <= SESSION_LOG_KEEP_BYTES) return
        if (System.currentTimeMillis() - file.lastModified() < SESSION_LOG_STALE_MS) return

        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val tail = ByteArray(SESSION_LOG_KEEP_BYTES.toInt())
                raf.seek(length - tail.size)
                raf.readFully(tail)
                // 切口多半落在半行/半个 UTF-8 字符上：从第一个换行之后开始留
                val firstNewline = tail.indexOf('\n'.code.toByte())
                val body = if (firstNewline in 0 until tail.size - 1) {
                    tail.copyOfRange(firstNewline + 1, tail.size)
                } else {
                    tail
                }
                val marker = buildString {
                    append("[host] ")
                    synchronized(sessionLogLock) { append(phaseTs.format(java.util.Date())) }
                    append(" TRUNCATED 过期 session.log，仅保留尾部 ")
                    append(SESSION_LOG_KEEP_BYTES / 1024 / 1024).append("MB\n")
                }.toByteArray()
                raf.setLength(0)
                raf.write(marker)
                raf.write(body)
            }
            Timber.w("session.log 过期且超 %dMB，已截尾（原 %dKB）", SESSION_LOG_KEEP_BYTES / 1024 / 1024, length / 1024)
        }.onFailure { Timber.w(it, "session.log 截尾失败") }
    }

    /**
     * 写死 DNS：烘焙包里的 /etc/resolv.conf 是指向 /run/systemd 的悬空软链，
     * 设备上解析必挂（热更新需要网络）。写普通文件， mainland 默认 AliDNS
     *
     * Hardwrites DNS: in the baked image /etc/resolv.conf is a dangling symlink
     * to /run/systemd and resolution always fails on device (the hot update
     * needs the network). A regular file is written; mainland defaults to
     * AliDNS.
     */
    private fun writeResolvConf() {
        runCatching {
            val f = File(rootfsDir, "etc/resolv.conf")
            if (Files.isSymbolicLink(f.toPath()) || f.exists()) f.delete()
            f.writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
        }.onFailure { Timber.w(it, "write resolv.conf failed") }
    }

    /**
     * 确保 guest 的 /etc/passwd 里有 App uid 的条目。
     *
     * proot 不做 uid 映射：guest 内进程的 uid 就是宿主 App uid（如 10244），而出厂
     * rootfs 的 /etc/passwd 只有无机条目。OpenSSH 启动时无条件 getpwuid(getuid())，
     * 查不到即 fatal「No user exists for uid N」——远程访问的 SSH 反向隧道因此在
     * 注册前退出。补一行 android 条目（HOME 指向 rootfs 内可写的 /tmp）后 ssh 与
     * 上游的 ~ 展开都恢复正常；幂等，uid 变了（重装 App）会补新条目。
     *
     * Ensures the guest /etc/passwd carries an entry for the App's uid.
     *
     * proot does no uid mapping: guest processes run with the host App's uid
     * (e.g. 10244), which the factory rootfs's /etc/passwd knows nothing about.
     * OpenSSH unconditionally calls getpwuid(getuid()) at startup and fatals
     * with "No user exists for uid N" when it misses — the remote-access SSH
     * reverse tunnel died before registering because of this. Appending an
     * android entry (HOME pointing at the writable in-rootfs /tmp) fixes both
     * ssh and upstream's ~ expansion; idempotent, and a new entry is appended
     * when the uid changes (an app reinstall).
     */
    private fun ensureGuestUserEntry() {
        runCatching {
            val uid = android.os.Process.myUid()
            val file = File(rootfsDir, "etc/passwd")
            val existing = if (file.isFile) file.readText() else ""
            val entryRegex = Regex("(?m)^[^:]*:x?:$uid:")
            if (entryRegex.containsMatchIn(existing)) return
            // 顺带把旧 uid 的 android 条目清掉，避免重装后条目无限堆积
            val cleaned = existing.replace(Regex("(?m)^android:x:\\d+:.*\\n?"), "")
            file.parentFile?.mkdirs()
            file.writeText(cleaned + "android:x:$uid:$uid:android:/tmp:/bin/false\n")
            Timber.i("guest /etc/passwd: appended android entry for uid %d", uid)
        }.onFailure { Timber.w(it, "ensure guest user entry failed") }
    }

    /** 落盘时间戳的锁：SimpleDateFormat 非线程安全，所有 [host] 行经它串行化 / Guards the on-disk timestamps: SimpleDateFormat is not thread-safe, so every [host] line serializes through it. */
    private val sessionLogLock = Any()

    /** 日志行时间戳格式；仅限 [sessionLogLock] 内使用 / The log line timestamp format; used only under [sessionLogLock]. */
    private val phaseTs = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    /** 推进状态机并同步落一行阶段日志 / Advances the state machine and appends one phase log line. */
    private fun setState(phase: ProotPhase, detail: String = "") {
        _state.update { it.copy(phase = phase, detail = detail) }
        logPhase(phase.name, detail)
    }

    /**
     * 阶段迁移落盘 session.log：release 版 Timber 只落 W+，准备链 2~5 分钟全程静默
     * 曾致「App 假死」误判——session.log 本就是生命周期时间线，[host] 行与 [proot-out] 交错即全貌
     *
     * Persists phase transitions into session.log: release Timber keeps only W+,
     * leaving the 2-5 minute preparation chain totally silent, which once caused
     * a false "app hung" verdict — session.log is the lifecycle timeline itself,
     * and interleaved [host] / [proot-out] lines tell the whole story.
     */
    private fun logPhase(tag: String, detail: String) {
        runCatching {
            sessionLog.parentFile?.mkdirs()
            val line = buildString {
                append("[host] ")
                synchronized(sessionLogLock) { append(phaseTs.format(java.util.Date())) }
                append(' ').append(tag)
                if (detail.isNotEmpty()) append(' ').append(detail)
                append('\n')
            }
            java.io.FileOutputStream(sessionLog, true).use { it.write(line.toByteArray()) }
        }
    }

    /** 置 FAILED 态： Timber 与 session.log 双落 / Enters the FAILED state, logged to both Timber and session.log. */
    private fun fail(reason: String) {
        Timber.e("ProotHost failed: %s", reason)
        _state.update { it.copy(phase = ProotPhase.FAILED, detail = reason) }
        logPhase("FAILED", reason)
    }

    companion object {
        /**
         * 网关端口（deploy.yaml WebuiPort）：WS 网关、Android 薄接口与应用内原生界面都打它
         *
         * The gateway port (deploy.yaml WebuiPort): the WS gateway, the Android
         * thin API and the in-app native UI all hit it.
         */
        const val WEBUI_PORT = 25548

        /** rootfs 内的安装根，proot `-w` 的 guest 工作目录 / The install root inside the rootfs, proot's guest `-w` directory. */
        private const val GUEST_INSTALL_ROOT = "/opt/azurpilot"

        /** app↔运行时边界 overlay 的文件名（APK 资产与 rootfs 内路径同名）/ The boundary-overlay file name (same in the APK assets and inside the rootfs). */
        private const val HOST_OVERLAY_NAME = "android_host.py"

        /** rootfs 内上游部署配置的相对路径（相对安装根）/ The upstream deploy config's path relative to the install root. */
        private const val DEPLOY_YAML_PATH = "config/deploy.yaml"

        /** 远程访问 SSH 中转的出厂默认（上游 localshare 公共服务）/ The factory-default SSH relay (upstream's localshare public service). */
        private const val DEFAULT_SSH_SERVER = "remote.nanoda.work:1022"

        /** rootfs 内系统 ssh 的路径（CI 安装的 openssh-client）/ The in-rootfs system ssh path (openssh-client installed by CI). */
        private const val DEFAULT_SSH_EXECUTABLE = "/usr/bin/ssh"

        /** 重启前 suspend 实例的连接超时 / The connect timeout for the pre-restart instance suspend. */
        private const val SUSPEND_CONNECT_TIMEOUT_MS = 2_000

        /** 重启前 suspend 实例的读超时：实例有序收尾可能要数秒 / The read timeout for the pre-restart instance suspend: an orderly wind-down can take seconds. */
        private const val SUSPEND_READ_TIMEOUT_MS = 15_000

        /** 等服务就绪的上限：冷启 import + 首次播种余量大 / The ceiling for awaiting services: generous for cold-start imports and a first seed. */
        private const val SERVICES_UP_MS = 90_000L

        /** 一次性 guest 命令的执行上限（播种等） / The execution ceiling for one-shot guest commands (seeding and the like). */
        private const val SHORT_EXEC_MS = 60_000L

        /** 关 stdin 后等 wrapper 自尽的宽限 / The grace period for the wrapper to exit after stdin closes. */
        private const val STOP_GRACE_MS = 8_000L

        /** 重拉退避：3s 起步翻倍至 60s 封顶 / Respawn backoff: starts at 3 s, doubles, caps at 60 s. */
        private const val RESTART_BACKOFF_INIT_MS = 3_000L
        private const val RESTART_BACKOFF_MAX_MS = 60_000L

        /** 存活短于此时长算「秒退」，计入熔断 / Living shorter than this counts as a quick death for the circuit breaker. */
        private const val QUICK_DEATH_MS = 10_000L

        /** 连续秒退达到此次数即熔断放弃 / The circuit breaker trips after this many consecutive quick deaths. */
        private const val MAX_RAPID_DEATHS = 5

        /** session.log 截尾：保留尾部 2MB；mtime 超 7 天才算过期 / session.log truncation: keep the last 2 MB; expired only past an mtime of 7 days. */
        private const val SESSION_LOG_KEEP_BYTES = 2L * 1024 * 1024
        private const val SESSION_LOG_STALE_MS = 7L * 24 * 60 * 60 * 1000

        /** 等设置读盘的一拍上限，等不到本轮跳过截尾 / The beat to wait for settings to load; the truncation skips this round when it misses. */
        private const val SETTINGS_LOADED_WAIT_MS = 2_000L
    }
}
