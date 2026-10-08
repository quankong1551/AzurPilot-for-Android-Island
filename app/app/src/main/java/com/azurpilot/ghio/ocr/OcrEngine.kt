package com.azurpilot.ghio.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.Model
import com.google.ai.edge.litert.NpuAcceleratorProvider
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 在 OCR 工作进程串行推理白名单模型；驱动错误和不支持的尺寸回退原始 ONNX CPU。
 *
 * LiteRT 使用厂商插件，海思使用严格的 HiAI 会话，不注册 NNAPI。缓存最多两个模型。
 * 所有公开方法在服务工作线程调用，内部同步保证模型与缓冲区不会并发访问。
 *
 * Serializes allowlisted OCR inference in the OCR worker, falling back to original ONNX CPU
 * for driver failures and unsupported sizes. LiteRT uses vendor plugins and Kirin uses strict
 * HiAI sessions, never registering NNAPI. At most two models are cached. Public methods run on workers;
 * internal synchronization protects sessions and buffers.
 */
class OcrEngine(private val context: Context, disabledModels: Map<String, String> = emptyMap()) : AutoCloseable {
    private val manifest by lazy {
        context.assets.open("ocr/manifest.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }
    private val models by lazy {
        manifest.getValue("models").jsonArray.associate { value ->
            val model = value.jsonObject
            model.getValue("sha256").jsonPrimitive.content to model
        }
    }
    private val runtime by lazy {
        context.assets.open("ocr/runtime.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }
    private val sessions = LinkedHashMap<String, Session>(4, 0.75f, true)
    private val failures = disabledModels.toMutableMap()
    private val activity = mutableMapOf<String, JsonObject>()
    private val provider = BundledProvider(context)
    private var environment: Environment? = null
    private val trace = OcrTrace(context)
    private var mediatekAdapter: String? = null

    /**
     * 返回匹配权重的原始元数据；未知哈希拒绝调用。
     *
     * Returns original metadata or rejects unknown hashes.
     */
    @Synchronized
    fun describe(hash: String): JsonObject = models[hash] ?: error("OCR model is not bundled in this APK")

    /**
     * 返回运行库、会话和回退原因；JIT 分区证据不代替硬件性能分析。
     *
     * Reports libraries, sessions, and fallback reasons; JIT partition evidence does not
     * replace hardware profiling.
     */
    @Synchronized
    fun status(): JsonObject = buildJsonObject {
        put("api_version", 1)
        put("worker_process_isolated", true)
        put("worker_pid", android.os.Process.myPid())
        put("runtime", "litert_and_hiai")
        put("runtime_versions", buildJsonObject {
            put("litert", runtime.getValue("litert"))
            put("qnn", runtime.getValue("qnn"))
            put("neuropilot", runtime.getValue("neuropilot"))
            context.assets.open("ocr/hiai-runtime.json").bufferedReader().use {
                val hiai = Json.parseToJsonElement(it.readText()).jsonObject
                put("mnn_source_commit", hiai.getValue("mnn_source_commit"))
                put("hiai_source_commit", hiai.getValue("hiai_source_commit"))
            }
        })
        put("nnapi", false)
        trace.latest?.let { put("last_stage", it) }
        put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE)
        put("vendor", provider.vendor)
        mediatekAdapter?.let { put("mediatek_adapter_library", it) }
        val bundled = provider.isLibraryReady() || provider.isHiaiReady()
        val recognizers = models.filterValues { "litert" in it }.keys
        val disabled = recognizers.count { it in failures || "*" in failures }
        put("npu_libraries_bundled", bundled)
        // 兼容已有 AP 客户端；此字段只表示库存在，实际运行证据在 sessions 中。
        put("npu_libraries_ready", bundled)
        put("npu_state", when {
            !bundled -> "unavailable"
            disabled == recognizers.size -> "disabled"
            sessions.values.any { it.validated && (it.partitions > 0 || it.hiai != 0L) } -> "verified"
            disabled > 0 -> "partially_disabled"
            else -> "untested"
        })
        put("models", JsonArray(models.keys.map(::JsonPrimitive)))
        put("model_status", buildJsonArray {
            models.forEach { (hash, spec) ->
                val last = activity[hash]
                add(buildJsonObject {
                    put("model_sha256", hash)
                    put("name", spec.getValue("asset").jsonPrimitive.content.substringAfterLast('/'))
                    put("testable", "litert" in spec)
                    put("backend", last?.get("backend") ?: JsonPrimitive("uninitialized"))
                    put("ap_requests", last?.get("ap_requests") ?: JsonPrimitive(0))
                    put("diagnostic_requests", last?.get("diagnostic_requests") ?: JsonPrimitive(0))
                    last?.get("last_ms")?.let { put("last_ms", it) }
                    last?.get("shape")?.let { put("shape", it) }
                    last?.get("cpu_reason")?.let { put("cpu_reason", it) }
                    (failures[hash] ?: failures["*"])?.let { put("error", it) }
                })
            }
        })
        put("sessions", buildJsonArray {
            sessions.forEach { (hash, session) ->
                add(buildJsonObject {
                    put("model_sha256", hash)
                    put("backend", session.backend)
                    put("npu_requested", session.lite != null || session.hiai != 0L)
                    put("npu_dispatch_partitions", session.partitions)
                    put("hiai_npu_only_session_ready", session.hiai != 0L)
                    put("npu_delegation_verified", session.validated &&
                        (session.partitions > 0 || session.hiai != 0L))
                    put("npu_hardware_profile_verified", false)
                })
            }
        })
        put("fallbacks", buildJsonObject { failures.forEach { (key, value) -> put(key, value) } })
    }

    /**
     * 推理 NCHW FP32 张量并返回原 ONNX 输出形状；批次拆成单图，宽度不改写。
     *
     * Runs an NCHW FP32 tensor and returns ONNX-shaped output; batches split into individual
     * images without changing their width.
     */
    @Synchronized
    fun run(hash: String, shape: LongArray, values: FloatArray, source: String = "ap"): Output {
        val previouslyValidated = sessions[hash]?.validated == true
        val started = System.nanoTime()
        try {
            val result = infer(hash, shape, values)
            val previous = activity[hash]
            activity[hash] = buildJsonObject {
                put("backend", result.backend)
                put("last_ms", (System.nanoTime() - started) / 1_000_000.0)
                put("shape", JsonArray(shape.map(::JsonPrimitive)))
                put("ap_requests", (previous?.get("ap_requests")?.jsonPrimitive?.longOrNull ?: 0) +
                    if (source == "ap") 1 else 0)
                put("diagnostic_requests", (previous?.get("diagnostic_requests")?.jsonPrimitive?.longOrNull ?: 0) +
                    if (source != "ap") 1 else 0)
                if (result.backend == "onnx_cpu") {
                    put("cpu_reason", when {
                        hash in failures || "*" in failures -> "npu_failed"
                        "litert" !in describe(hash) -> "detector"
                        shape[2] != 48L || shape[3] != 320L -> "dynamic_shape"
                        else -> "npu_unavailable"
                    })
                }
            }
            return result
        } finally {
            // 测试图片不能替代实际游戏输入的首轮精度校验。
            if (source == "diagnostic" && !previouslyValidated) sessions[hash]?.validated = false
        }
    }

    private fun infer(hash: String, shape: LongArray, values: FloatArray): Output {
        val spec = describe(hash)
        trace.activeModel(hash)
        require(shape.size == 4 && shape[0] in 1..16 && shape[1] == 3L)
        require(shape[2] in 1..2048 && shape[3] in 1..4096)
        require(shape.fold(1L, Long::times) == values.size.toLong())
        require(values.all(Float::isFinite))
        val inputSpec = spec.getValue("inputs").jsonArray.single().jsonObject
        inputSpec.getValue("shape").jsonArray.forEachIndexed { index, dimension ->
            dimension.jsonPrimitive.longOrNull?.let { require(shape[index] == it) }
        }
        val conversion = spec["litert"]?.jsonObject
        if (conversion != null) {
            // 白名单识别器的时间轴步长为 8；先限输出大小，避免 ORT 在拒绝报文前分配大张量。
            val classes = conversion.getValue("output_shape").jsonArray.last().jsonPrimitive.long
            require(shape[0] * ((shape[3] + 7) / 8) * classes <= 64 * 1024 * 1024 / 4) {
                "OCR output exceeds the API limit"
            }
        }
        val session = sessions.getOrPut(hash) {
            while (sessions.size >= 2) {
                val eldest = sessions.entries.iterator().next()
                eldest.value.close()
                sessions.remove(eldest.key)
            }
            Session(spec)
        }
        if (conversion != null && shape[2] == 48L && shape[3] == 320L &&
            (provider.isLibraryReady() || provider.isHiaiReady())
            && hash !in failures && "*" !in failures) {
            try {
                return if (provider.isHiaiReady()) {
                    runHiai(session, spec.getValue("mnn").jsonObject, shape, values)
                } else runLite(session, conversion, shape, values)
            } catch (error: Exception) {
                failures[hash] = error.message?.take(300) ?: error.javaClass.simpleName
                trace.record(hash, "npu_failed", failures[hash])
                session.closeNpu()
                Timber.w(error, "OCR NPU failed; falling back to ONNX CPU")
            } catch (error: LinkageError) {
                failures[hash] = error.message?.take(300) ?: "NPU library linkage failed"
                trace.record(hash, "npu_linkage_failed", failures[hash])
                session.closeNpu()
                Timber.w(error, "OCR NPU libraries unavailable")
            }
        }
        return runCpu(session, shape, values)
    }

    /**
     * 用内置数字图片测量所选识别器，与原 ONNX 比较；不计为 AP 业务调用。
     *
     * 在服务工作线程执行，首次耗时包含初始化，稳定耗时取三次平均。
     * 测试成功只说明数值和接口可用，不能代替游戏识别质量或硬件性能验证。
     *
     * Benchmarks a selected recognizer with a bundled digit image against original ONNX.
     * Runs on a server worker without counting AP business calls. Cold time includes initialization;
     * steady time averages three runs. Success does not prove game accuracy or hardware performance.
     */
    @Synchronized
    fun test(hash: String): JsonObject {
        val previouslyValidated = sessions[hash]?.validated == true
        try {
            return testModel(hash)
        } finally {
            sessions[hash]?.validated = previouslyValidated
        }
    }

    private fun testModel(hash: String): JsonObject {
        require("litert" in describe(hash)) { "Only bundled recognition models can be tested" }
        val bitmap = context.assets.open("ocr/test/sample.png").use(BitmapFactory::decodeStream)
            ?: error("OCR test image is missing")
        val pixels = IntArray(48 * 320)
        try {
            require(bitmap.width == 320 && bitmap.height == 48)
            bitmap.getPixels(pixels, 0, 320, 0, 0, 320, 48)
        } finally {
            bitmap.recycle()
        }
        val values = FloatArray(pixels.size * 3) { index ->
            val shift = (2 - index / pixels.size) * 8
            ((pixels[index % pixels.size] ushr shift) and 255) / 127.5f - 1f
        }
        val shape = longArrayOf(1, 3, 48, 320)
        val coldStart = System.nanoTime()
        var output = run(hash, shape, values, "benchmark")
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0
        val steadyMs = (1..3).map {
            val start = System.nanoTime()
            output = run(hash, shape, values, "benchmark")
            (System.nanoTime() - start) / 1_000_000.0
        }.average()
        val session = sessions.getValue(hash)
        var reference = output
        val cpuMs = (1..3).map {
            val start = System.nanoTime()
            reference = runCpu(session, shape, values)
            (System.nanoTime() - start) / 1_000_000.0
        }.average()
        session.backend = output.backend
        check(output.shape.contentEquals(reference.shape)) { "OCR test output shape differs" }
        val maxError = output.values.indices.maxOf { kotlin.math.abs(output.values[it] - reference.values[it]) }
        val classes = output.shape.last().toInt()
        val equalPredictions = output.values.indices.step(classes).all { offset ->
            (0 until classes).maxBy { output.values[offset + it] } ==
                (0 until classes).maxBy { reference.values[offset + it] }
        }
        check(maxError <= 0.01f && equalPredictions) { "OCR test predictions differ from original ONNX" }
        return buildJsonObject {
            put("model_sha256", hash)
            put("backend", output.backend)
            put("cold_ms", coldMs)
            put("steady_ms", steadyMs)
            put("cpu_ms", cpuMs)
            put("max_abs_error", maxError)
            put("character_predictions_equal", equalPredictions)
            put("npu_hardware_profile_verified", false)
        }
    }

    private fun runLite(session: Session, conversion: JsonObject, shape: LongArray, values: FloatArray): Output {
        val hash = session.spec.getValue("sha256").jsonPrimitive.content
        if (session.lite == null) {
            if (provider.vendor == "mediatek") {
                trace.record(hash, "mediatek_driver_probe")
                OcrNative.mediatekDriverError()?.let { error(it.take(300)) }
                trace.record(hash, "mediatek_adapter_probe")
                mediatekAdapter = OcrNative.mediatekAdapterLibrary()
                trace.record(hash, "mediatek_adapter_selected", mediatekAdapter)
            }
            trace.record(hash, "litert_environment")
            val env = environment ?: Environment.create(provider).also { environment = it }
            trace.record(hash, "litert_model_load")
            val model = Model.load(materialize(conversion).absolutePath).also { session.model = it }
            val version = runtime.getValue("litert").jsonPrimitive.content
            trace.record(hash, "litert_graph_before_compile")
            check(OcrNative.countCustomOps(model, version) == 0) { "OCR conversion contains unexpected custom operators or diagnostics are unavailable" }
            trace.record(hash, "litert_npu_compile")
            session.lite = CompiledModel.create(
                model, CompiledModel.Options(Accelerator.NPU), env,
            )
            trace.record(hash, "litert_graph_after_compile")
            session.partitions = OcrNative.countCustomOps(model, version)
            check(session.partitions > 0) { "LiteRT did not delegate any OCR partition to the NPU" }
            trace.record(hash, "litert_buffers")
            session.inputs = session.lite!!.createInputBuffers()
            session.outputs = session.lite!!.createOutputBuffers()
            check(Accelerator.NPU in env.getAvailableAccelerators()) { "LiteRT reports no NPU accelerator" }
        }
        val expectedShape = conversion.getValue("output_shape").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        val sampleSize = expectedShape.fold(1L, Long::times).toInt()
        val batch = shape[0].toInt()
        require(sampleSize.toLong() * batch <= 64 * 1024 * 1024 / 4) { "OCR output exceeds the API limit" }
        val output = FloatArray(sampleSize * batch)
        val inputSize = 3 * 48 * 320
        val validating = !session.validated
        if (validating) trace.record(hash, "litert_first_inference")
        repeat(batch) { index ->
            val source = values.copyOfRange(index * inputSize, (index + 1) * inputSize)
            val input = if (conversion.getValue("input_layout").jsonPrimitive.content == "nhwc") {
                FloatArray(inputSize) { position -> source[(position % 3) * 48 * 320 + position / 3] }
            } else source
            session.inputs!!.single().writeFloat(input)
            session.lite!!.run(session.inputs!!, session.outputs!!)
            val result = session.outputs!!.single().readFloat()
            require(result.size == sampleSize && result.all(Float::isFinite))
            result.copyInto(output, index * sampleSize)
        }
        expectedShape[0] = shape[0]
        validateConverted(session, expectedShape, output, shape, values)
        if (validating) trace.record(hash, "litert_validated")
        session.backend = "litert_npu_with_cpu_fallback"
        return Output(expectedShape, output, session.backend)
    }

    private fun runHiai(session: Session, conversion: JsonObject, shape: LongArray, values: FloatArray): Output {
        val expectedShape = conversion.getValue("output_shape").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        if (session.hiai == 0L) {
            trace.record(session.spec.getValue("sha256").jsonPrimitive.content, "hiai_model_load")
            session.hiai = OcrHiaiNative.create(materialize(conversion).absolutePath,
                context.applicationInfo.nativeLibraryDir, expectedShape.last().toInt())
            check(session.hiai != 0L) { "HiAI did not create an NPU session" }
        }
        val sampleSize = expectedShape.fold(1L, Long::times).toInt()
        val batch = shape[0].toInt()
        require(sampleSize.toLong() * batch <= 64 * 1024 * 1024 / 4)
        val output = FloatArray(sampleSize * batch)
        val inputSize = 3 * 48 * 320
        repeat(batch) { index ->
            val result = OcrHiaiNative.run(session.hiai,
                values.copyOfRange(index * inputSize, (index + 1) * inputSize))
            require(result.size == sampleSize && result.all(Float::isFinite))
            result.copyInto(output, index * sampleSize)
        }
        expectedShape[0] = shape[0]
        validateConverted(session, expectedShape, output, shape, values)
        session.backend = "hiai_npu"
        return Output(expectedShape, output, session.backend)
    }

    private fun validateConverted(session: Session, expectedShape: LongArray, output: FloatArray,
                                  shape: LongArray, values: FloatArray) {
        // 厂商编译器可能使用较低精度；首个真实请求必须对照原权重，不能只用零张量验收。
        if (!session.validated) {
            trace.record(session.spec.getValue("sha256").jsonPrimitive.content, "onnx_reference_validation")
            val reference = runCpu(session, shape, values)
            require(expectedShape.contentEquals(reference.shape)) { "Converted OCR output shape changed" }
            require(output.indices.all { i -> kotlin.math.abs(output[i] - reference.values[i]) <= 0.01f }) {
                "NPU OCR output differs from the original model"
            }
            val classes = expectedShape.last().toInt()
            require(output.indices.step(classes).all { offset ->
                (0 until classes).maxBy { output[offset + it] } ==
                    (0 until classes).maxBy { reference.values[offset + it] }
            }) { "NPU OCR character predictions differ" }
            session.validated = true
        }
    }

    private fun runCpu(session: Session, shape: LongArray, values: FloatArray): Output {
        val env = OrtEnvironment.getEnvironment()
        val cpu = session.cpu ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(materialize(session.spec).absolutePath, options)
        }.also { session.cpu = it }
        val input = session.spec.getValue("inputs").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape).use { tensor ->
            cpu.run(mapOf(input to tensor)).use { results ->
                val result = results[0] as OnnxTensor
                val info = result.info as TensorInfo
                val buffer = result.floatBuffer
                val output = FloatArray(buffer.remaining())
                buffer.get(output)
                require(output.all(Float::isFinite))
                session.backend = "onnx_cpu"
                return Output(info.shape, output, session.backend)
            }
        }
    }

    private fun materialize(spec: JsonObject): File {
        val hash = spec.getValue("sha256").jsonPrimitive.content
        val directory = File(context.noBackupFilesDir, "ocr-models").apply { mkdirs() }
        val target = File(directory, hash)
        if (!target.isFile) {
            val temporary = File(directory, "$hash.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("ocr/${spec.getValue("asset").jsonPrimitive.content}").use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == hash) { "Bundled OCR model checksum mismatch" }
            check(temporary.renameTo(target)) { "Could not install OCR model" }
        }
        return target
    }

    /**
     * 释放所有会话，调用方须先停止服务。
     *
     * Releases all sessions after the caller stops the service.
     */
    @Synchronized
    override fun close() {
        sessions.values.forEach(Session::close)
        sessions.clear()
        environment?.close()
        environment = null
    }

    /**
     * 保存输出张量及实际请求的后端；线程局部，服务负责编码。
     *
     * Holds output tensors and the requested backend; thread-local, encoded by the server.
     * @property shape 原 ONNX 输出维度。 / Original ONNX output dimensions.
     * @property values 连续 FP32 数据。 / Contiguous FP32 data.
     * @property backend CPU 或带 CPU 回退的 NPU 路径。 / CPU or NPU with CPU fallback.
     */
    data class Output(val shape: LongArray, val values: FloatArray, val backend: String)

    private class Session(val spec: JsonObject) : AutoCloseable {
        var lite: CompiledModel? = null
        var hiai = 0L
        var model: Model? = null
        var cpu: OrtSession? = null
        var inputs: List<com.google.ai.edge.litert.TensorBuffer>? = null
        var outputs: List<com.google.ai.edge.litert.TensorBuffer>? = null
        var validated = false
        var partitions = 0
        var backend = "uninitialized"
        fun closeNpu() {
            if (hiai != 0L) {
                runCatching { OcrHiaiNative.close(hiai) }
                hiai = 0L
            }
            inputs?.forEach { runCatching { it.close() } }
            outputs?.forEach { runCatching { it.close() } }
            lite?.let { runCatching { it.close() } }
            model?.let { runCatching { it.close() } }
            inputs = null
            outputs = null
            lite = null
            model = null
            validated = false
            partitions = 0
        }
        override fun close() {
            closeNpu()
            cpu?.close()
            cpu = null
        }
    }

    private class BundledProvider(private val context: Context) : NpuAcceleratorProvider {
        val vendor: String
            get() {
                // 部分升级到 Android 12 的设备没有填 SoC 字段，仍需使用其硬件标识。
                val identifiers = buildList {
                    add(Build.HARDWARE.lowercase())
                    if (Build.VERSION.SDK_INT >= 31) {
                        add(Build.SOC_MANUFACTURER.lowercase())
                        add(Build.SOC_MODEL.lowercase())
                    }
                }
                return when {
                    identifiers.any {
                        it.contains("qualcomm") || it == "qti" || it.contains("qcom") ||
                            Regex("(?:sm|sdm|msm)\\d{3,5}.*").matches(it)
                    } -> "qualcomm"
                    identifiers.any { it.contains("mediatek") || Regex("mt\\d{4}.*").matches(it) } -> "mediatek"
                    identifiers.any { it.contains("kirin") || it.contains("hisi") } -> "hiai"
                    identifiers.any { it.contains("xring") || it.contains("xiaomi") } -> "xring_unavailable"
                    else -> "unsupported"
                }
            }
        override fun isDeviceSupported() = Build.VERSION.SDK_INT >= 31 && vendor in setOf("qualcomm", "mediatek")
        override fun isLibraryReady(): Boolean {
            if (!isDeviceSupported()) return false
            val suffix = if (vendor == "qualcomm") "Qualcomm" else "MediaTek"
            return listOf("libLiteRtCompilerPlugin_$suffix.so", "libLiteRtDispatch_$suffix.so").all {
                File(context.applicationInfo.nativeLibraryDir, it).isFile
            }
        }
        override suspend fun downloadLibrary() = Unit
        fun isHiaiReady(): Boolean = vendor == "hiai" && Build.VERSION.SDK_INT >= 29 &&
            listOf("libocrhiai.so", "libMNN.so", "libMNN_Backend_HiAI.so", "libhiai.so",
                "libhiai_ir.so", "libhiai_ir_build.so", "libhiai_model_compatible.so",
                "libhiai_enhance.so").all { File(context.applicationInfo.nativeLibraryDir, it).isFile }
        override fun getLibraryDir(): String {
            val source = File(context.applicationInfo.nativeLibraryDir)
            val directory = File(context.noBackupFilesDir, "ocr-npu/$vendor").apply { mkdirs() }
            val names = if (vendor == "qualcomm") {
                listOf("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so",
                    "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so") +
                    listOf(68, 69, 73, 75, 79, 81).flatMap { version ->
                        listOf("libQnnHtpV${version}Skel.so", "libQnnHtpV${version}Stub.so")
                    }
            } else {
                listOf("libLiteRtCompilerPlugin_MediaTek.so", "libLiteRtDispatch_MediaTek.so",
                    "libneuronusdk_adapter.mtk.so", "libneuronusdk_adapter.9.mtk.so")
            }
            // 只暴露本厂商插件，避免另一厂商的离线编译器抢先接管模型；链接仍指向安装目录。
            names.forEach { name ->
                val target = File(source, name).toPath()
                check(Files.isRegularFile(target)) { "Missing NPU runtime library: $name" }
                val link = File(directory, name).toPath()
                if (!Files.isSymbolicLink(link) || Files.readSymbolicLink(link) != target) {
                    Files.deleteIfExists(link)
                    Files.createSymbolicLink(link, target)
                }
            }
            return directory.absolutePath
        }
    }
}
