package com.aos.spikea

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpikeA"

/**
 * 执行 PRoot 可执行性阶梯和 PhantomProcessKiller 观测的设备端 Spike Activity。
 *
 * UI 仅在主线程创建。耗时的 PRoot 与进程 I/O 在工作线程执行；[busy] 阻止同一 Activity
 * 重叠运行。Activity 重建不会恢复已启动的实验。
 *
 * Runs on-device PRoot executability ladders and PhantomProcessKiller observations.
 *
 * Its UI is created only on the main thread. Lengthy PRoot and process I/O run on worker threads;
 * [busy] prevents overlapping runs in one Activity. An Activity recreation does not resume an
 * experiment that was already started.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var logFile: File
    private lateinit var rootfsDir: File
    private lateinit var tmpDir: File
    private lateinit var nativeLibDir: String
    private val busy = AtomicBoolean(false)

    /**
     * 返回 Intent 请求的实验模式，未提供时使用阶梯模式。
     *
     * 只有 `phantom` 选择 PhantomProcessKiller 观测；其他值均按阶梯模式处理。
     *
     * Returns the experiment mode requested by the Intent, defaulting to ladder mode.
     *
     * Only `phantom` selects the PhantomProcessKiller observation; every other value is treated as
     * ladder mode.
     */
    private fun mode(): String = intent?.getStringExtra("mode") ?: "ladder"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeLibDir = applicationInfo.nativeLibraryDir
        logFile = File(filesDir, "spikea.log")
        rootfsDir = File(filesDir, "rootfs")
        tmpDir = File(filesDir, "tmp")

        val header = TextView(this).apply { textSize = 11f }
        val runButton = Button(this).apply { text = "RUN LADDER AGAIN" }
        val scroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
        }
        scroll.addView(logView)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(runButton)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(scroll, lp)
        }
        setContentView(root)
        header.text = "SpikeA targetSdk=${applicationInfo.targetSdkVersion} mode=${mode()}\n" +
            "nld=$nativeLibDir\nlog=${logFile.absolutePath}"

        runButton.setOnClickListener {
            if (mode() == "phantom") launchPhantom() else launchLadder()
        }
        if (mode() == "phantom") launchPhantom() else launchLadder()
    }

    /**
     * 启动完整阶梯的后台线程。
     *
     * [busy] 将一次运行限制为一个工作线程，避免两个 rootfs 部署或日志写入相互干扰。
     *
     * Starts the worker thread for a complete ladder.
     *
     * [busy] limits a run to one worker thread, preventing two rootfs deployments or log writes from
     * interfering with each other.
     */
    private fun launchLadder() {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                runLadder()
            } catch (t: Throwable) {
                log("FATAL ${t.javaClass.name}: ${t.message}")
            } finally {
                busy.set(false)
            }
        }.start()
    }

    /**
     * 将一行实验信息写入 logcat、持久化日志和屏幕日志。
     *
     * 调用方通常是工作线程；界面更新被切回主线程，以避免跨线程访问 [logView]。
     *
     * Writes one experiment line to logcat, the persistent log, and the on-screen log.
     *
     * Callers normally run on worker threads; the UI update returns to the main thread to avoid
     * cross-thread access to [logView].
     */
    private fun log(line: String) {
        Log.i(TAG, line)
        try {
            FileOutputStream(logFile, true).use {
                it.write((line + "\n").toByteArray())
                it.flush()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "log file write failed", t)
        }
        runOnUiThread {
            logView.append(line + "\n")
            (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * 读取文本文件的首行，并将读取失败编码为诊断文本。
     *
     * 设备事实采集不能因可选的 procfs 或 SELinux 节点不可读而中断整个阶梯。
     *
     * Reads the first line of a text file and encodes read failures as diagnostic text.
     *
     * Device-fact collection must not interrupt the whole ladder when optional procfs or SELinux
     * nodes are unreadable.
     */
    private fun readFirstLine(path: String): String = try {
        File(path).readLines().firstOrNull() ?: "(empty)"
    } catch (t: Throwable) {
        "ERR ${t.javaClass.simpleName}: ${t.message}"
    }

    /**
     * 返回安装后的 nativeLibraryDir 中一个库文件的绝对路径。
     *
     * Returns the absolute path of one library in the installed nativeLibraryDir.
     */
    private fun nld(name: String) = File(nativeLibDir, name).absolutePath

    /**
     * 返回已部署 rootfs 中一个相对路径的绝对路径。
     *
     * Returns the absolute path of one relative path in the deployed rootfs.
     */
    private fun guest(path: String) = File(rootfsDir, path).absolutePath

    /**
     * 保存一次子进程执行的已观测状态。
     *
     * [exit] 在启动失败或超时时为空；[exception] 仅记录启动失败，进程自身的 stderr 保留在
     * [err] 中。
     *
     * Holds the observed state of one child-process execution.
     *
     * [exit] is null for a launch failure or timeout. [exception] records launch failures only;
     * process stderr remains in [err].
     */
    private class ExecResult(
        val exit: Int?,
        val out: String,
        val err: String,
        val exception: String?,
        val ms: Long,
        val timedOut: Boolean,
    )

    /**
     * 返回 PRoot 与 guest 进程共用的基线环境变量。
     *
     * 每次启动均删除继承的 `LD_PRELOAD`，避免宿主注入库改变 PRoot 的加载行为。
     *
     * Returns the baseline environment shared by PRoot and guest processes.
     *
     * Each launch removes inherited `LD_PRELOAD` so host-injected libraries cannot alter PRoot
     * loading behavior.
     */
    private val baseEnv: Map<String, String>
        get() = mapOf(
            "LD_LIBRARY_PATH" to nativeLibDir,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "PROOT_LOADER" to File(nativeLibDir, "libproot-loader.so").absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "HOME" to filesDir.absolutePath,
        )

    /**
     * 在 rootfs 工作目录中执行命令，并同时收集标准输出与标准错误。
     *
     * 两个读取线程持续排空管道，以免大量输出阻塞子进程；超时后强制终止进程并在结果中标记。
     * 调用方必须在非主线程执行此函数。
     *
     * Runs a command in the rootfs working directory while collecting standard output and error.
     *
     * Separate reader threads drain both pipes so large output cannot block the child. A timed-out
     * process is forcibly stopped and marked in the result. Callers must invoke this on a
     * non-main thread.
     */
    private fun exec(id: String, cmd: List<String>, extraEnv: Map<String, String> = emptyMap(), timeoutSec: Long = 30): ExecResult {
        log("[$id] CMD: ${cmd.joinToString(" ")}")
        if (extraEnv.isNotEmpty()) {
            log("[$id] ENV_EXTRA: ${extraEnv.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
        }
        val pb = ProcessBuilder(cmd)
        pb.directory(rootfsDir)
        pb.environment().remove("LD_PRELOAD")
        pb.environment().putAll(baseEnv)
        pb.environment().putAll(extraEnv)
        val start = SystemClock.elapsedRealtime()
        val proc = try {
            pb.start()
        } catch (e: IOException) {
            val ms = SystemClock.elapsedRealtime() - start
            val ex = "${e.javaClass.name}: ${e.message}"
            log("[$id] EXEC-FAILED after ${ms}ms: $ex")
            return ExecResult(null, "", "", ex, ms, false)
        }
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val tOut = Thread { proc.inputStream.bufferedReader().forEachLine { outBuf.append(it).append('\n') } }
        val tErr = Thread { proc.errorStream.bufferedReader().forEachLine { errBuf.append(it).append('\n') } }
        tOut.start()
        tErr.start()
        val finished = try {
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!finished) proc.destroyForcibly()
        tOut.join(2000)
        tErr.join(2000)
        val ms = SystemClock.elapsedRealtime() - start
        return ExecResult(if (finished) proc.exitValue() else null, outBuf.toString(), errBuf.toString(), null, ms, !finished)
    }

    /**
     * 执行一个阶梯步骤，记录其完整结果，并更新汇总表。
     *
     * 将启动失败、超时和非零退出码统一归为失败，便于设备间比较。
     *
     * Executes one ladder step, records its complete result, and updates the summary table.
     *
     * Launch failures, timeouts, and nonzero exit codes are all normalized as failures for
     * cross-device comparison.
     */
    private fun step(id: String, cmd: List<String>, extraEnv: Map<String, String> = emptyMap()): Boolean {
        val r = exec(id, cmd, extraEnv)
        val status = when {
            r.exception != null -> "EXEC-FAILED"
            r.timedOut -> "TIMEOUT"
            r.exit == 0 -> "PASS"
            else -> "FAIL"
        }
        log("[$id] EXIT: ${r.exit ?: "n/a"}  DURATION: ${r.ms}ms  TIMEOUT: ${r.timedOut}")
        r.out.trimEnd().split('\n').forEach { if (it.isNotEmpty()) log("[$id] out| $it") }
        r.err.trimEnd().split('\n').forEach { if (it.isNotEmpty()) log("[$id] err| $it") }
        if (r.exception != null) log("[$id] EXCEPTION: ${r.exception}")
        val overall = if (status == "PASS") "PASS" else "$status(exit=${r.exit ?: "-"})"
        log("[$id] RESULT: $overall")
        results[id] = overall
        return status == "PASS"
    }

    /**
     * 按执行顺序保存阶梯结果，供运行末尾输出稳定的结果表。
     *
     * Preserves ladder results in execution order for a stable table at the end of a run.
     */
    private val results = LinkedHashMap<String, String>()

    /**
     * 启动 Spike C 的 PhantomProcessKiller 观测。
     *
     * 工作线程通过 PRoot 生成多个长期存活的 guest 进程，并定期报告存活数。它在所有退出路径中
     * 清除 [busy]，使正常结束、启动失败和异常后都可重新运行。
     *
     * Starts the Spike C PhantomProcessKiller observation.
     *
     * A worker thread creates multiple long-lived guest processes through PRoot and periodically
     * reports their liveness. It clears [busy] on every exit path so normal completion, launch
     * failure, and exceptions all permit another run.
     */
    private fun launchPhantom() {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                runPhantom()
            } catch (t: Throwable) {
                log("FATAL ${t.javaClass.name}: ${t.message}")
            } finally {
                busy.set(false)
            }
        }.start()
    }

    /**
     * 生成由 PRoot guest 执行的 Phantom 进程生成脚本。
     *
     * guest 将后台 busybox PID 写入 rootfs 的临时文件，使宿主进程可以在无需 `run-as` 的情况下
     * 通过同 UID 的 [Os.kill] 探测其存活状态。
     *
     * Builds the Phantom-process spawning script executed by the PRoot guest.
     *
     * The guest writes background busybox PIDs to a rootfs temporary file, letting the host process
     * probe their liveness through same-UID [Os.kill] without `run-as`.
     */
    private fun guestPhantomScript(n: Int): String = """
        echo "GUEST_START pid=${'$'}${'$'}"
        : > /tmp/phantoms.pids
        i=0
        while [ "${'$'}i" -lt $n ]; do
          /bin/busybox sleep 3600 &
          echo "${'$'}!" >> /tmp/phantoms.pids
          i=${'$'}((i+1))
        done
        echo "GUEST_SPAWNED=${'$'}(/bin/busybox wc -l < /tmp/phantoms.pids)"
        echo "GUEST_PIDS=${'$'}(/bin/busybox cat /tmp/phantoms.pids | /bin/busybox tr '\n' ',')"
        while true; do
          alive=0
          for p in ${'$'}(/bin/busybox cat /tmp/phantoms.pids); do
            /bin/busybox kill -0 "${'$'}p" 2>/dev/null && alive=${'$'}((alive+1))
          done
          echo "GUEST_ALIVE=${'$'}alive/$n ts=${'$'}(/bin/busybox date +%s)"
          /bin/busybox sleep 15
        done
    """.trimIndent()

    /**
     * 返回 guest 写入 Phantom PID 列表的位置。
     *
     * Returns the location where the guest writes its Phantom PID list.
     */
    private fun guestPidFile() = File(rootfsDir, "tmp/phantoms.pids")

    /**
     * 从 app 进程的视角统计仍存活的 guest PID。
     *
     * `kill(pid, 0)` 只探测同 UID 进程，不发送信号；无法读取 PID 文件时返回诊断值而不打断观察。
     *
     * Counts guest PIDs still alive from the app process's point of view.
     *
     * `kill(pid, 0)` probes same-UID processes without sending a signal. An unreadable PID file
     * returns a diagnostic value instead of interrupting the observation.
     */
    private fun countGuestAlive(): String {
        val f = guestPidFile()
        if (!f.exists()) return "n/a"
        val pids = try {
            f.readLines().mapNotNull { it.trim().toIntOrNull() }
        } catch (t: Throwable) {
            return "read-err"
        }
        var alive = 0
        for (p in pids) {
            try {
                Os.kill(p, 0)
                alive++
            } catch (t: Throwable) {
                // ESRCH 表示进程已退出并被回收；其他探测失败同样不能算作存活。
            }
        }
        return "$alive/${pids.size}"
    }

    /**
     * 运行一个 Phantom guest，直到其退出或达到可选持续时间。
     *
     * guest 后台任务的 stdin 会重定向到 `/dev/null`，因此显式绑定宿主 `/dev`；新建 rootfs
     * 本身没有该节点。此函数在工作线程阻塞等待 PRoot 退出。
     *
     * Runs one Phantom guest until it exits or reaches the optional duration.
     *
     * Guest background jobs redirect stdin to `/dev/null`, so the host `/dev` is explicitly bound;
     * a newly provisioned rootfs does not contain that node. This function blocks on a worker thread
     * until PRoot exits.
     */
    private fun runPhantom() {
        val n = intent?.getIntExtra("count", 48) ?: 48
        val durationSec = intent?.getIntExtra("durationSec", 0) ?: 0
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val myPid = android.os.Process.myPid()
        log("")
        log("================================================================================")
        log("=== PHANTOM RUN $stamp  count=$n durationSec=$durationSec appPid=$myPid")
        log("================================================================================")
        provisionRootfs()
        val script = guestPhantomScript(n)
        val cmd = listOf(
            nld("libproot.so"), "-w", "/", "-r", rootfsDir.absolutePath, "-b", "/dev:/dev",
            "/bin/busybox", "sh", "-c", script,
        )
        log("[PHANTOM] CMD: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
        pb.directory(rootfsDir)
        pb.environment().remove("LD_PRELOAD")
        pb.environment().putAll(baseEnv)
        val start = SystemClock.elapsedRealtime()
        val proc = try {
            pb.start()
        } catch (e: IOException) {
            log("[PHANTOM] EXEC-FAILED: ${e.javaClass.name}: ${e.message}")
            return
        }
        log("[PHANTOM] proot started (appPid=$myPid)")
        Thread { proc.inputStream.bufferedReader().forEachLine { log("[GUEST] $it") } }.start()
        Thread { proc.errorStream.bufferedReader().forEachLine { log("[GUEST-ERR] $it") } }.start()

        val stop = AtomicBoolean(false)
        Thread {
            while (!stop.get()) {
                try {
                    Thread.sleep(30_000)
                } catch (t: InterruptedException) {
                    return@Thread
                }
                if (stop.get()) return@Thread
                val up = (SystemClock.elapsedRealtime() - start) / 1000
                log(
                    "[HEARTBEAT] appPid=$myPid uptimeSec=$up prootAlive=${proc.isAlive} " +
                        "guestAlive=${countGuestAlive()}"
                )
            }
        }.start()

        if (durationSec > 0) {
            Thread {
                try {
                    Thread.sleep(durationSec * 1000L)
                } catch (t: InterruptedException) {
                    return@Thread
                }
                log("[PHANTOM] duration ${durationSec}s elapsed -> destroying proot")
                proc.destroyForcibly()
            }.start()
        }

        val exit = try {
            proc.waitFor()
        } catch (t: InterruptedException) {
            -1
        }
        stop.set(true)
        log("[PHANTOM] PROOT_EXIT=$exit after ${(SystemClock.elapsedRealtime() - start) / 1000}s")
    }

    /**
     * 执行完整的 Spike A 阶梯并输出按步骤排序的结果表。
     *
     * 此函数重置上一次的结果，因此一次 Activity 运行只能由一个 [busy] 保护的工作线程进入。
     *
     * Runs the complete Spike A ladder and emits a result table ordered by step.
     *
     * This function clears results from the preceding run, so only one [busy]-guarded worker thread
     * may enter it for an Activity instance.
     */
    private fun runLadder() {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        results.clear()
        log("")
        log("================================================================================")
        log("=== RUN $stamp  targetSdk=${applicationInfo.targetSdkVersion}  sdkInt=${Build.VERSION.SDK_INT}")
        log("================================================================================")
        logDeviceFacts()
        provisionRootfs()
        runSteps()
        log("")
        log("=== RESULT TABLE ===")
        results.forEach { (k, v) -> log("RESULT $k = $v") }
        log("=== LADDER DONE ===")
    }

    private fun logDeviceFacts() {
        log("--- A0 DEVICE FACTS ---")
        log("model=${Build.MODEL} manufacturer=${Build.MANUFACTURER} device=${Build.DEVICE} hardware=${Build.HARDWARE}")
        log("androidRelease=${Build.VERSION.RELEASE} sdkInt=${Build.VERSION.SDK_INT} securityPatch=${Build.VERSION.SECURITY_PATCH}")
        log("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        log("targetSdk=${applicationInfo.targetSdkVersion} minSdk=${applicationInfo.minSdkVersion} versionName=${packageManager.getPackageInfo(packageName, 0).versionName}")
        val flags = applicationInfo.flags
        // 此隐藏常量没有公开 SDK 符号，但需要记录安装器是否提取 native 库。
        val flagExtractNativeLibs = 1 shl 28
        log(
            "applicationFlags=0x${Integer.toHexString(flags)} " +
                "debuggable=${flags and ApplicationInfo.FLAG_DEBUGGABLE != 0} " +
                "extractNativeLibs=${flags and flagExtractNativeLibs != 0} " +
                "largeHeap=${flags and ApplicationInfo.FLAG_LARGE_HEAP != 0}"
        )
        log("nativeLibraryDir=$nativeLibDir")
        log("logFile=${logFile.absolutePath}")
        try {
            log("pageSize=${Os.sysconf(OsConstants._SC_PAGESIZE)}")
        } catch (t: Throwable) {
            log("pageSize=ERR ${t.javaClass.simpleName}: ${t.message}")
        }
        log("processSelinuxContext=${readFirstLine("/proc/self/attr/current")}")
        log("selinuxEnforce=${readFirstLine("/sys/fs/selinux/enforce")} (1=enforcing 0=permissive)")
        log("nld listing (name size canExecute):")
        File(nativeLibDir).listFiles()?.sortedBy { it.name }?.forEach {
            log("  nld| ${it.name} ${it.length()} ${it.canExecute()}")
        }
        val g = exec("A0-getenforce", listOf("/system/bin/getenforce"))
        log("A0-getenforce out=${g.out.trim()} err=${g.err.trim()} exit=${g.exit}")
    }

    private fun provisionRootfs() {
        log("--- PROVISION ROOTFS ---")
        tmpDir.mkdirs()
        for (d in listOf("bin", "usr/bin", "system/bin", "system/lib64", "tmp", "root", "dev", "proc")) {
            File(rootfsDir, d).mkdirs()
        }
        val assetFiles = listOf(
            "rootfs/bin/busybox",
            "rootfs/usr/bin/hello_static",
            "rootfs/usr/bin/hello_dynamic",
            "rootfs/system/lib64/libbusybox_app.so",
            "rootfs/system/lib64/libandroid-selinux.so",
            "rootfs/system/lib64/libpcre2-8.so",
            "rootfs/system/lib64/libtalloc.so",
            "rootfs/system/lib64/libandroid-shmem.so",
        )
        for (a in assetFiles) {
            val dst = File(filesDir, a)
            dst.parentFile?.mkdirs()
            try {
                assets.open(a).use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
                dst.setExecutable(true, false)
                log("provisioned asset $a -> ${dst.absolutePath} size=${dst.length()} canExec=${dst.canExecute()}")
            } catch (t: Throwable) {
                log("provision asset FAILED $a: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        val hostMap = mapOf(
            "/system/bin/linker64" to "system/bin/linker64",
            "/system/lib64/ld-android.so" to "system/lib64/ld-android.so",
            "/system/lib64/libc.so" to "system/lib64/libc.so",
            "/system/lib64/libm.so" to "system/lib64/libm.so",
            "/system/lib64/libdl.so" to "system/lib64/libdl.so",
            "/system/lib64/liblog.so" to "system/lib64/liblog.so",
            "/system/lib64/libc++.so" to "system/lib64/libc++.so",
        )
        for ((src, rel) in hostMap) {
            val s = File(src)
            if (!s.exists()) {
                log("host file missing, skipped: $src")
                continue
            }
            try {
                val dst = File(rootfsDir, rel)
                s.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
                log("provisioned host $src -> $rel size=${s.length()} canExec=${dst.canExecute()}")
            } catch (t: Throwable) {
                log("provision host FAILED $src: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        File(rootfsDir, "usr/bin/hello_static").setExecutable(true, false)
        File(rootfsDir, "usr/bin/hello_dynamic").setExecutable(true, false)
        log("rootfs at ${rootfsDir.absolutePath}")
    }

    private fun runSteps() {
        log("--- LADDER ---")
        step("C1-hostsh", listOf("/system/bin/sh", "-c", "echo HOST_SH_OK; id; uname -m"))
        step(
            "P0-rootfs-modes",
            listOf("/system/bin/sh", "-c", "ls -l ${rootfsDir.absolutePath}/bin ${rootfsDir.absolutePath}/usr/bin ${rootfsDir.absolutePath}/system/bin ${rootfsDir.absolutePath}/system/lib64"),
        )
        step("A1-busybox-exec", listOf(nld("libbusybox.so"), "uname", "-m"))
        step("A1b-busybox-id", listOf(nld("libbusybox.so"), "id"))
        step("A1c-shim-busybox", listOf(nld("libspike_shim.so"), "busybox", nld("libbusybox.so"), "uname", "-m"))
        step("A2-proot-version", listOf(nld("libproot.so"), "--version"))

        val a3ok = step("A3-proot-ptrace", listOf(nld("libproot.so"), "-0", "/system/bin/sh", "-c", "echo PROOT_PTRACE_OK; uname -m"))
        if (!a3ok) {
            step(
                "A3r-proot-ptrace-noseccomp",
                listOf(nld("libproot.so"), "-0", "/system/bin/sh", "-c", "echo PROOT_PTRACE_OK; uname -m"),
                mapOf("PROOT_NO_SECCOMP" to "1"),
            )
        }

        step("P1-direct-static-exec", listOf(guest("usr/bin/hello_static")))
        step(
            "P1b-direct-busybox-exec",
            listOf(guest("bin/busybox"), "uname", "-m"),
            mapOf("LD_LIBRARY_PATH" to guest("system/lib64")),
        )
        step("P1c-direct-dynamic-exec", listOf(guest("usr/bin/hello_dynamic")))

        val a4ok = step("A4-proot-rootfs-busybox", listOf(nld("libproot.so"), "-w", "/", "-r", rootfsDir.absolutePath, "/bin/busybox", "uname", "-m"))
        if (!a4ok) {
            step(
                "A4r-proot-rootfs-busybox-verbose",
                listOf(nld("libproot.so"), "-v", "1", "-w", "/", "-r", rootfsDir.absolutePath, "/bin/busybox", "uname", "-m"),
                mapOf("PROOT_NO_SECCOMP" to "1", "PROOT_VERBOSE" to "1"),
            )
            step(
                "A4c-proot-rootfs-busybox-bind",
                listOf(nld("libproot.so"), "-w", "/", "-r", rootfsDir.absolutePath, "-b", "/system:/system", "/bin/busybox", "uname", "-m"),
                mapOf("PROOT_NO_SECCOMP" to "1"),
            )
        }

        step("A4b-proot-rootfs-static", listOf(nld("libproot.so"), "-w", "/", "-r", rootfsDir.absolutePath, "/usr/bin/hello_static"))
        step("A4e-proot-rootfs-dynamic", listOf(nld("libproot.so"), "-w", "/", "-r", rootfsDir.absolutePath, "/usr/bin/hello_dynamic"))
        step(
            "A5a-nested-exec-static",
            listOf(nld("libproot.so"), "-w", "/", "-r", rootfsDir.absolutePath, "/usr/bin/hello_dynamic", "--exec", "/usr/bin/hello_static"),
        )
        step(
            "A5b-nested-exec-busybox",
            listOf(nld("libproot.so"), "-w", "/", "-r", rootfsDir.absolutePath, "/usr/bin/hello_dynamic", "--exec", "/bin/busybox", "uname", "-m"),
        )
    }
}
