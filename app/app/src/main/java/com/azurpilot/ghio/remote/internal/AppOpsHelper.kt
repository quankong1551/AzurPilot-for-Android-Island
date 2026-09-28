package com.azurpilot.ghio.remote.internal

import android.app.AppOpsManager
import com.azurpilot.ghio.third.Ln


/**
 * 目标包 PLAY_AUDIO AppOps 的读写与复位（静音 / 恢复）
 *
 * 跑在特权进程内，shell/root 身份。优先走 IAppOpsService binder，失败降级 `appops` 命令行；
 * 返回值一律以回读到的实际 mode 为准——写成功不代表生效（ROM 差异），必须校验。
 *
 * Reads, writes, and resets the target package's PLAY_AUDIO AppOps (mute / restore).
 *
 * Runs inside the privileged process with the shell/root identity. Prefers the IAppOpsService
 * binder and degrades to the `appops` command line on failure; return values always come from
 * reading the effective mode back — a successful write does not imply it took effect (ROM
 * differences), so verification is mandatory.
 */
object AppOpsHelper {
    private const val TAG = "AppOpsHelper"

    // OP_PLAY_AUDIO 不在 SDK 里，只能硬编码隐藏 op id
    private const val OP_PLAY_AUDIO = 28

    /**
     * 读取当前生效 mode；读取失败返回 -1
     *
     * Reads the currently effective mode; -1 on read failure.
     */
    fun checkPlayAudioMode(packageName: String, uid: Int): Int = runCatching {
        RemoteUtils.appOpsService.checkOperation(OP_PLAY_AUDIO, uid, packageName)
    }.getOrElse {
        Ln.w("$TAG: checkOperation failed for $packageName: ${it.message}")
        -1
    }

    /**
     * 设置 PLAY_AUDIO mode 并校验实际生效
     *
     * binder 写失败降级 `appops set` 命令行；无论走哪条路，返回值都来自回读。
     *
     * Sets the PLAY_AUDIO mode and verifies it took effect.
     *
     * A failed binder write degrades to the `appops set` command; either way the return value
     * comes from reading the mode back.
     */
    fun setPlayAudioMode(packageName: String, uid: Int, mode: Int): Boolean {
        val viaBinder = runCatching {
            RemoteUtils.appOpsService.setMode(OP_PLAY_AUDIO, uid, packageName, mode)
        }.onFailure {
            Ln.w("$TAG: setMode via binder failed for $packageName: ${it.message}")
        }.isSuccess

        if (!viaBinder) {
            val arg = if (mode == AppOpsManager.MODE_ALLOWED) "allow" else "ignore"
            RemoteUtils.shellExec("appops set $packageName PLAY_AUDIO $arg")
        }
        return checkPlayAudioMode(packageName, uid) == mode
    }

    /**
     * 将该包 AppOps 恢复为系统默认（`appops reset <package>`）。
     * 成功条件：PLAY_AUDIO 不再是 IGNORED；读不到 mode 时以 shell exitCode 为准。
     *
     * Restores the package's AppOps to system defaults (`appops reset <package>`).
     * Success criterion: PLAY_AUDIO is no longer IGNORED; when the mode cannot be read, the
     * shell exit code decides.
     */
    fun resetPackage(packageName: String): Boolean {
        val exitCode = RemoteUtils.shellExec("appops reset $packageName")
        val uid = RemoteUtils.getAppUid(packageName)
        if (uid < 0) {
            Ln.w("$TAG: reset $packageName exit=$exitCode, cannot verify mode (no uid)")
            return exitCode == 0
        }
        val mode = checkPlayAudioMode(packageName, uid)
        if (mode < 0) {
            Ln.w("$TAG: reset $packageName exit=$exitCode, checkOperation failed")
            return exitCode == 0
        }
        val restored = isPlayAudioRestored(mode)
        if (restored) {
            Ln.i("$TAG: reset $packageName ok (mode=$mode, exit=$exitCode)")
        } else {
            Ln.w("$TAG: reset $packageName still muted (mode=$mode, exit=$exitCode)")
        }
        return restored
    }

    /** 复位成功判据：mode 可读且不再是 IGNORED / Reset is successful when the mode is readable and no longer IGNORED */
    internal fun isPlayAudioRestored(mode: Int): Boolean =
        mode >= 0 && mode != AppOpsManager.MODE_IGNORED
}
