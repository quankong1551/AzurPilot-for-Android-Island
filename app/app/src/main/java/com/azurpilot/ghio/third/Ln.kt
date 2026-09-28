package com.azurpilot.ghio.third

import android.util.Log

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * 同时写入 Android logger（logcat 可见）与进程标准输出/错误（终端直接可见）的日志门面
 *
 * 特权进程（app_process 拉起，无 Koin/Timber）内的专用日志通道：app 侧按仓库惯例走
 * Timber，特权进程里只能用它。每条日志双路输出：`Log.v/d/i/w/e` 进 logcat（TAG 固定
 * [TAG]），同时带 [PREFIX] 前缀打到 stdout（v/i/d）或 stderr（w/e）。
 *
 * 从 scrcpy 服务端的 `third/Ln.java` 移植（Apache-2.0, Genymobile/scrcpy）。
 *
 * Logging facade that writes to both the Android logger (visible in
 * "adb logcat") and the process's stdout/stderr (visible in the terminal
 * directly).
 *
 * Dedicated to the privileged process (spawned via app_process, without Koin or
 * Timber): the app side logs through Timber per repo convention, the privileged
 * process must use this. Every entry is written twice: through `Log.v/d/i/w/e`
 * into logcat (with the fixed [TAG]) and prefixed with [PREFIX] to stdout
 * (v/i/d) or stderr (w/e).
 *
 * Ported from scrcpy's server `third/Ln.java` (Apache-2.0, Genymobile/scrcpy).
 */
object Ln {

    private const val TAG = "AzurPilot"
    private const val PREFIX = "[MC] "

    // 进程生命周期内复用同一组流，避免每次打日志都新建 PrintStream
    private val CONSOLE_OUT = PrintStream(FileOutputStream(FileDescriptor.out))
    private val CONSOLE_ERR = PrintStream(FileOutputStream(FileDescriptor.err))

    /**
     * 枚举日志级别，从细（VERBOSE）到粗（ERROR）
     *
     * [threshold] 只放行严重程度不低于它的级别。
     *
     * Enumerates log levels from finest (VERBOSE) to coarsest (ERROR).
     *
     * The threshold only lets through entries whose severity is at or above it.
     */
    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    private var threshold = Level.DEBUG

    /**
     * 把 System.out/System.err 整体替换为空流，后续一切写标准流的输出都被丢弃
     *
     * 框架/第三方代码可能直接往标准流里写内容，在终端里产生无法归类的噪音；
     * scrcpy 上游同样"聊胜于无"地屏蔽。应在进程启动早期调用。
     *
     * Replaces System.out/System.err with null streams, discarding everything
     * written to the standard streams afterwards.
     *
     * Framework and third-party code may write straight to the standard streams
     * and pollute the terminal with unattributable noise; upstream scrcpy does
     * the same as a "better than nothing" measure. Call early in the process
     * startup.
     */
    fun disableSystemStreams() {
        val nullStream = PrintStream(NullOutputStream())
        System.setOut(nullStream)
        System.setErr(nullStream)
    }

    /**
     * 设置日志级别阈值
     *
     * 须在启动任何新线程之前调用：[threshold] 是普通变量（无同步），晚于读线程
     * 写入时新线程可能永远看不到新值。
     *
     * Sets the log level threshold.
     *
     * Must be called before starting any new thread: [threshold] is a plain
     * (unsynchronized) variable, so a thread started before the write may never
     * observe the new value.
     *
     * @param level 新阈值 / the new threshold
     */
    fun initLogLevel(level: Level) {
        threshold = level
    }

    /**
     * 查询某级别当前是否会被输出 / Returns whether the given level currently passes the threshold.
     */
    fun isEnabled(level: Level): Boolean {
        return level.ordinal >= threshold.ordinal
    }

    /** 记录 VERBOSE 级日志（双路：logcat + stdout）/ Logs a VERBOSE entry (logcat and stdout). */
    fun v(message: String) {
        if (isEnabled(Level.VERBOSE)) {
            Log.v(TAG, message)
            CONSOLE_OUT.print(PREFIX + "VERBOSE: " + message + '\n')
        }
    }

    /** 记录 DEBUG 级日志（双路：logcat + stdout）/ Logs a DEBUG entry (logcat and stdout). */
    fun d(message: String) {
        if (isEnabled(Level.DEBUG)) {
            Log.d(TAG, message)
            CONSOLE_OUT.print(PREFIX + "DEBUG: " + message + '\n')
        }
    }

    /** 记录 INFO 级日志（双路：logcat + stdout）/ Logs an INFO entry (logcat and stdout). */
    fun i(message: String) {
        if (isEnabled(Level.INFO)) {
            Log.i(TAG, message)
            CONSOLE_OUT.print(PREFIX + "INFO: " + message + '\n')
        }
    }

    /**
     * 记录 WARN 级日志（双路：logcat + stderr，异常栈随 [throwable] 走 stderr）
     *
     * Logs a WARN entry (logcat and stderr; the stack trace of [throwable] goes
     * to stderr too).
     */
    fun w(message: String, throwable: Throwable?) {
        if (isEnabled(Level.WARN)) {
            Log.w(TAG, message, throwable)
            CONSOLE_ERR.print(PREFIX + "WARN: " + message + '\n')
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR)
            }
        }
    }

    /** 记录 WARN 级日志（无异常）/ Logs a WARN entry without a throwable. */
    fun w(message: String) {
        w(message, null)
    }

    /**
     * 记录 ERROR 级日志（双路：logcat + stderr，异常栈随 [throwable] 走 stderr）
     *
     * Logs an ERROR entry (logcat and stderr; the stack trace of [throwable]
     * goes to stderr too).
     */
    fun e(message: String, throwable: Throwable?) {
        if (isEnabled(Level.ERROR)) {
            Log.e(TAG, message, throwable)
            CONSOLE_ERR.print(PREFIX + "ERROR: " + message + '\n')
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR)
            }
        }
    }

    /** 记录 ERROR 级日志（无异常）/ Logs an ERROR entry without a throwable. */
    fun e(message: String) {
        e(message, null)
    }

    /** 吞掉一切写入的黑洞流，供 [disableSystemStreams] 挂到 System.out/err / Black-hole stream swallowing all writes, attached to System.out/err by [disableSystemStreams]. */
    private class NullOutputStream : OutputStream() {
        override fun write(b: ByteArray) {
            // ignore
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            // ignore
        }

        override fun write(b: Int) {
            // ignore
        }
    }
}
