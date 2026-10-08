package com.azurpilot.ghio.ocr

import com.google.ai.edge.litert.Model

/**
 * 读取钉版 LiteRT 的公开 C 模型 API，确认 JIT 确实生成了厂商 dispatch 分区。
 *
 * 仅接收仍存活的模型对象，调用期间由 OcrEngine 的锁保护。JNI 读取 JniHandle 的
 * handle 字段；对应 R8 保留规则及 LiteRT 版本必须一起维护。
 *
 * Reads pinned LiteRT's public C model API to check that JIT generated vendor dispatch
 * partitions. Accepts live models only under OcrEngine's lock. JNI reads JniHandle.handle;
 * maintain its R8 keep rule together with the LiteRT version.
 */
internal object OcrNative {
    init { System.loadLibrary("ocrdiagnostics") }

    /**
     * 主图的 custom 操作数量，API 不可用时为 -1。
     *
     * Main-graph custom op count, or -1 if unavailable.
     */
    external fun countCustomOps(model: Model): Int
}
