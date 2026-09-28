package com.azurpilot.ghio.log

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 挑出要打进 zip 的文件；不删源、不写盘，纯函数好测
 *
 * 我们的日志分在两棵目录下（`log/` 与 `debug/`），有意不归拢成一棵：`debug/` 那份的路径
 * 在特权进程侧是硬解析的（见 `RemoteBootTrace`），挪了要连着改两边
 *
 * Picks the files to pack into the export zip; no source deletion, no disk writes — a
 * pure, easily testable selection.
 *
 * The logs intentionally live under two trees (`log/` and `debug/`) rather than being
 * merged into one: the `debug/` path is hard-parsed on the privileged-process side (see
 * `RemoteBootTrace`), so moving it would require synchronized changes in both places.
 */
object LogExportCollector {

    /** 导出 zip 的存放目录名；收集时按它排除，防止 zip 套 zip / The directory name holding export zips; excluded during collection so zips never nest inside zips. */
    const val EXPORT_DIR_NAME = "export"

    /** 会无限长的那几个目录只留近 7 天；其余（app.log、触发日志）本身就有上限，全带 / Endlessly growing directories keep only the last 7 days; the rest (app.log, trigger logs) are already capped and always included. */
    const val ROLLING_KEEP_DAYS = 7L

    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * 按次或按轮堆文件的目录
     *
     * 加新目录时记得往这里补一条，否则一年后的导出包会有上千个文件
     */
    private val ROLLING_MARKERS = listOf("/run/", "/focus/", "/logcat/", "/crash/")

    /**
     * 收集启动器日志（`log/` + `debug/` 全量，滚动目录只留近 [ROLLING_KEEP_DAYS] 天）
     *
     * Collects launcher logs (`log/` + `debug/` in full, rolling directories trimmed to
     * the last [ROLLING_KEEP_DAYS] days).
     *
     * @param roots 待扫描的根目录列表 / the root directories to scan
     * @param now 当前时间戳（毫秒），作滚动截止基准 / the current time in millis, the
     *   baseline for the rolling cutoff
     */
    fun collect(roots: List<File>, now: Long): List<File> =
        roots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile }
            .filter { shouldExport(it, now - ROLLING_KEEP_DAYS * MS_PER_DAY) }
            .toList()

    /** 单文件是否入选：排除上一次导出的 zip，滚动目录按 mtime 截止 / Whether a file is selected: previous export zips are excluded, rolling directories cut off by mtime. */
    private fun shouldExport(file: File, rollingCutoff: Long): Boolean {
        val path = file.invariantSeparatorsPath
        // 上一次导出的 zip 不能再打进这一次，否则每导一次体积翻一倍
        if (path.contains("/$EXPORT_DIR_NAME/")) return false
        if (ROLLING_MARKERS.none { path.contains(it) }) return true
        return file.lastModified() >= rollingCutoff
    }

    /**
     * AzurPilot 日志目录（`rootfs/opt/azurpilot/log`）的近 7 天收集，对应「导出AzurPilot日志」
     *
     * 判定基准与 `LogCleaner` 一致：txt 按文件名 `yyyy-MM-dd` 前缀（对不上前缀的保留——
     * 导出多带一份无伤，漏掉现场才误事）；error/<毫秒时间戳>/ 按时间戳，留着的整目录全收
     *
     * Collects the last 7 days from the AzurPilot log directory (`rootfs/opt/azurpilot/log`),
     * backing the "export AzurPilot logs" action.
     *
     * The criteria mirror `LogCleaner`: txts are dated by the `yyyy-MM-dd` file-name
     * prefix (files without a parsable prefix are kept — carrying one extra file in an
     * export is harmless, losing a scene is not); `error/<millis timestamp>/` directories
     * are dated by their timestamp, and surviving ones are collected whole.
     */
    fun collectAzurPilot(logDir: File, now: Long): List<File> {
        val cutoffMillis = now - ROLLING_KEEP_DAYS * MS_PER_DAY
        val cutoffDay = LocalDate.now().minusDays(ROLLING_KEEP_DAYS)
        val dated = logDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.filter { file ->
                val date = datePrefixOf(file.name) ?: return@filter true
                !date.isBefore(cutoffDay)
            }
            .orEmpty()
        val errors = File(logDir, "error").listFiles()
            ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
            ?.filter { (it.name.toLongOrNull() ?: 0L) >= cutoffMillis }
            ?.flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .orEmpty()
        return dated + errors
    }

    /** 解析文件名的 `yyyy-MM-dd` 日期前缀；对不上返回 null / Parses the `yyyy-MM-dd` date prefix of a file name; returns null when it does not match. */
    private fun datePrefixOf(name: String): LocalDate? = runCatching {
        if (name.length < DATE_PREFIX_LENGTH) return null
        LocalDate.parse(name.substring(0, DATE_PREFIX_LENGTH), DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()

    private const val DATE_PREFIX_LENGTH = 10
}
