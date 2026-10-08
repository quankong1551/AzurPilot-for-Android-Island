package com.azurpilot.ghio.ocr

import com.google.ai.edge.litert.Model

/**
 * 读取钉版 LiteRT 的公开 C 模型 API，确认 JIT 确实生成了厂商 dispatch 分区。
 *
 * 仅接收仍存活的模型对象，调用期间由 OcrEngine 的锁保护。JniHandle.handle 指向
 * ModelWrapper，不是 LiteRtModel。JNI 只接受 2.1.0rc1 并取其首成员；升级时须核对布局。
 *
 * Reads pinned LiteRT's public C model API to check that JIT generated vendor dispatch
 * partitions. Accepts live models only under OcrEngine's lock. JniHandle.handle points to
 * ModelWrapper, not LiteRtModel. JNI accepts only 2.1.0rc1 and reads its first member;
 * recheck this layout and the R8 keep rule when upgrading.
 */
internal object OcrNative {
    init { System.loadLibrary("ocrdiagnostics") }

    /**
     * 主图的 custom 操作数量，API 不可用或版本不匹配时为 -1。
     *
     * Main-graph custom op count, or -1 if unavailable or the version mismatches.
     */
    external fun countCustomOps(model: Model, runtimeVersion: String): Int

    /**
     * 在加载 MTK adapter 前检查其必需的系统入口，缺失时返回原因。
     *
     * Checks the required system entry point before loading MTK adapters; returns missing-driver details.
     */
    external fun mediatekDriverError(): String?
}
