package com.azurpilot.ghio.ocr

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import com.azurpilot.ghio.BuildConfig
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 在首次原生初始化和验证前同步记录阶段，原生信号退出后仍可导出最后位置。
 *
 * 由 OCR 工作线程调用；两个文件合计最多约 512 KiB。记录不含 API 口令或输入张量。
 *
 * Persists stages before native initialization and validation so signal exits leave evidence.
 * Called on OCR workers; two files total roughly 512 KiB. Never records tokens or tensors.
 */
internal class OcrTrace(context: Context) {
    private val directory = context.getExternalFilesDir("debug")?.let { File(it, "ocr") }
    private val manager = context.getSystemService(ActivityManager::class.java)
    private var activeHash = ""

    /**
     * 将当前模型写入系统退出摘要，不在张量热路径同步写文件。
     *
     * Records the active model in system exit summaries without synchronous hot-path file writes.
     */
    fun activeModel(hash: String) {
        if (Build.VERSION.SDK_INT >= 30 && activeHash != hash) {
            runCatching {
                manager.setProcessStateSummary(hash.toByteArray(Charsets.UTF_8))
                activeHash = hash
            }
        }
    }

    /** 最近一条阶段，供诊断报告复制。 / Latest stage for the copyable diagnostic report. */
    var latest: JsonObject? = null
        private set

    /** 同步落盘；日志失败不阻止推理。 / Flushes to disk without failing inference on log errors. */
    fun record(hash: String, stage: String, message: String? = null) {
        val entry = buildJsonObject {
            put("timestamp_ms", System.currentTimeMillis())
            put("pid", Process.myPid())
            put("app_version", BuildConfig.VERSION_NAME)
            put("model_sha256", hash)
            put("stage", stage)
            message?.let { put("message", it.take(300)) }
        }
        latest = entry
        Timber.i("OCR stage %s model=%s", stage, hash.take(12))
        runCatching {
            val dir = directory ?: return
            check(dir.isDirectory || dir.mkdirs())
            val file = File(dir, "stages.jsonl")
            if (file.length() >= 256 * 1024) {
                val previous = File(dir, "stages.previous.jsonl")
                check(!previous.exists() || previous.delete())
                check(file.renameTo(previous))
            }
            FileOutputStream(file, true).use {
                it.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }.onFailure { Timber.w(it, "OCR stage logging failed") }
    }
}
