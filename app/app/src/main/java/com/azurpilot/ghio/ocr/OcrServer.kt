package com.azurpilot.ghio.ocr

import android.content.Context
import com.azurpilot.ghio.proot.AndroidControlAuth
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * 在 App 进程提供带口令的回环 OCR 张量 API，独立于特权设备桥。
 *
 * 一行 JSON 后跟 little-endian FP32 字节。服务在运行时或用户测试时监听，随 App 进程终止；
 * 四个连接槽和有界报文避免无限线程或内存。推理在工作线程执行，模型锁由 [OcrEngine] 管理。
 *
 * Provides an authenticated loopback OCR tensor API in the app process, separate from the
 * privileged device bridge. JSON lines precede little-endian FP32 bytes. The service starts
 * with the runtime or a user test and ends with the app process. Four connection slots and bounded messages
 * prevent unbounded threads/memory. Workers infer under [OcrEngine]'s model lock.
 */
class OcrServer(private val context: Context) {
    private val engine by lazy { OcrEngine(context) }
    private val clients = Semaphore(4)
    private val inference = Semaphore(1)
    @Volatile private var socket: ServerSocket? = null

    /**
     * 当前监听地址；启动失败时为空。
     *
     * Active listener address, empty when startup failed.
     */
    val address: String get() = if (socket != null) "127.0.0.1:$PORT" else ""

    /** 返回共享引擎的状态，仅在 IO 线程读取。 / Reads shared engine status on an IO worker. */
    fun status(): JsonObject = engine.status()

    /**
     * 从 IO 线程经过实际认证回环接口运行测试，与 AP 使用相同引擎。
     *
     * Tests the shared AP engine through the authenticated loopback API on an IO worker.
     */
    fun test(hash: String): JsonObject {
        start()
        check(address.isNotEmpty()) { "OCR API could not start" }
        Socket().use { client ->
            client.connect(InetSocketAddress("127.0.0.1", PORT), 3_000)
            client.soTimeout = 240_000
            val request = buildJsonObject {
                put("method", "test")
                put("model_sha256", hash)
                put("token", AndroidControlAuth.get(context))
            }
            send(BufferedOutputStream(client.getOutputStream()), request)
            val reply = Json.parseToJsonElement(readLine(BufferedInputStream(client.getInputStream()))
                ?: error("OCR test returned no response")).jsonObject
            check(reply["ok"]?.jsonPrimitive?.boolean == true) {
                reply["error"]?.jsonPrimitive?.content ?: "OCR test failed"
            }
            return reply.getValue("result").jsonObject
        }
    }

    /**
     * 幂等启动；失败记录日志，让 AP 保留原 CPU 路径。
     *
     * Starts idempotently; failures keep AP's original CPU path.
     */
    @Synchronized
    fun start() {
        if (socket != null) return
        val listener = ServerSocket()
        try {
            engine.status()
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
            socket = listener
            thread(isDaemon = true, name = "ocr-accept") {
                try {
                    while (!listener.isClosed) {
                        val client = try { listener.accept() } catch (_: Exception) { break }
                        if (!clients.tryAcquire()) {
                            client.close()
                            continue
                        }
                        thread(isDaemon = true, name = "ocr-client") {
                            try {
                                serve(client)
                            } catch (error: LinkageError) {
                                // 驱动链接错误不能让宿主进程退出；连接关闭后 Python 会回退原模型。
                                Timber.w(error, "OCR runtime linkage failed")
                            } finally {
                                client.close()
                                clients.release()
                            }
                        }
                    }
                } finally {
                    listener.close()
                    synchronized(this@OcrServer) {
                        if (socket === listener) socket = null
                    }
                }
            }
            Timber.i("OCR API listening on %s", address)
        } catch (error: Exception) {
            listener.close()
            Timber.w(error, "OCR API unavailable; AP will use original CPU inference")
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 90_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val expectedToken = AndroidControlAuth.get(context).toByteArray()
            try {
                while (true) {
                    val line = readLine(input) ?: return
                    val request = Json.parseToJsonElement(line).jsonObject
                    val token = request["token"]?.jsonPrimitive?.content.orEmpty().toByteArray()
                    require(MessageDigest.isEqual(expectedToken, token)) { "Unauthorized OCR request" }
                    when (request.getValue("method").jsonPrimitive.content) {
                        "status" -> send(output, buildJsonObject { put("ok", true); put("status", engine.status()) })
                        "test" -> withInferenceBuffers {
                            val result = engine.test(request.getValue("model_sha256").jsonPrimitive.content)
                            send(output, buildJsonObject { put("ok", true); put("result", result) })
                        }
                        "describe" -> send(output, buildJsonObject {
                            put("ok", true)
                            put("model", engine.describe(request.getValue("model_sha256").jsonPrimitive.content))
                        })
                        "run" -> withInferenceBuffers {
                            val hash = request.getValue("model_sha256").jsonPrimitive.content
                            val spec = engine.describe(hash)
                            val shape = request.getValue("shape").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
                            require(shape.size == 4 && shape.all { it in 1..4096 })
                            val count = shape.fold(1L, Long::times)
                            require(count in 1..MAX_PAYLOAD / 4L)
                            val length = request.getValue("length").jsonPrimitive.int
                            require(length.toLong() == count * 4L)
                            val name = spec.getValue("inputs").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content
                            require(request.getValue("input_name").jsonPrimitive.content == name)
                            val payload = ByteArray(length)
                            var offset = 0
                            while (offset < length) {
                                val read = input.read(payload, offset, length - offset)
                                check(read > 0) { "Truncated OCR request" }
                                offset += read
                            }
                            val floats = FloatArray(count.toInt())
                            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                            val source = request["source"]?.jsonPrimitive?.content ?: "ap"
                            require(source in setOf("ap", "diagnostic"))
                            val result = engine.run(hash, shape, floats, source)
                            require(result.values.size <= MAX_PAYLOAD / 4)
                            val replyBytes = ByteArray(result.values.size * 4)
                            ByteBuffer.wrap(replyBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(result.values)
                            val outputName = spec.getValue("outputs").jsonArray.single().jsonObject.getValue("name")
                            send(output, buildJsonObject {
                                put("ok", true)
                                put("backend", result.backend)
                                put("length", replyBytes.size)
                                put("outputs", buildJsonArray { add(buildJsonObject {
                                    put("name", outputName)
                                    put("shape", JsonArray(result.shape.map(::JsonPrimitive)))
                                }) })
                            }, replyBytes)
                        }
                        else -> error("Unknown OCR method")
                    }
                }
            } catch (error: Exception) {
                runCatching {
                    send(output, buildJsonObject {
                        put("ok", false)
                        put("error", error.message?.take(300) ?: "OCR request failed")
                    })
                }
                Timber.d("OCR connection closed: %s", error.javaClass.simpleName)
            }
        }
    }

    private inline fun withInferenceBuffers(action: () -> Unit) {
        // 模型锁只保护推理；收发缓冲也必须串行，否则等待的客户端会各占两份大输入。
        inference.acquire()
        try {
            action()
        } finally {
            inference.release()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16 * 1024) {
            val next = input.read()
            if (next == -1) {
                check(bytes.size() == 0) { "Truncated OCR header" }
                return null
            }
            if (next == 10) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
        }
        error("OCR request header exceeds 16 KiB")
    }

    private fun send(output: BufferedOutputStream, header: JsonObject, bytes: ByteArray = byteArrayOf()) {
        output.write(header.toString().toByteArray())
        output.write(10)
        output.write(bytes)
        output.flush()
    }

    private companion object {
        const val PORT = 22302
        const val MAX_PAYLOAD = 64 * 1024 * 1024
    }
}
