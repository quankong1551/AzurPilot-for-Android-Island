# OCR NPU 加速 / OCR NPU acceleration

## 中文

### 调用链

APK 内置原始 ONNX 权重，以及四个识别器的 LiteRT 和 MNN FP32 模型。AP 的 ONNX 会话工厂由
`android_ocr.py` 在 Android 运行时代理，按文件内容 SHA-256 匹配 APK 白名单，向
`127.0.0.1:22302` 发送预处理后的 NCHW 张量。宿主负责推理，AP 保留语言字典、CTC
解码和后处理。接口运行于私有绑定的 `:ocr` 工作进程，与主界面和特权设备桥分离，
不需要 root 或 Shizuku 权限。

LiteRT CompiledModel 通过厂商编译、dispatch 插件调用 NPU，未注册 NNAPI。
高通使用 QNN HTP，联发科使用 NeuroPilot。海思使用独立的 MNN → HiAI 后端：
V320 客户端明确请求 NPU，禁用 MNN 的 CPU 备援。模型和库随 APK 安装，运行时不依赖
Google Play 服务下载或网络连接。每次 PRoot 启动会同步 APK overlay，已有 rootfs
也可随轻量 APK 更新接入。上游热更新后的新权重不会误匹配旧模型，自动保留原推理路径。
宿主为当前厂商创建独立的库目录，其中只放指向安装目录的符号链接，防止另一厂商的
离线编译器抢先接管模型；APK 更新后会刷新链接。
Manifest 还以 `required=false` 请求厂商公开的 RPC、NeuroPilot 和 HiAI 系统库；Android 12+
要求显式声明这些非 NDK 库，缺少库的平台仍能安装并使用 CPU。
这些声明不能让 App 访问未向应用开放的私有驱动库；厂商必须公开对应服务接口。

### 支持范围

| 平台 | 当前实现 | 条件和限制 |
| --- | --- | --- |
| 高通 | LiteRT → QNN HTP | Android 12+、ARM64，内置 v68/v69/v73/v75/v79/v81 Skel 和 Stub；实际以驱动和算子编译成功为准 |
| 联发科 | LiteRT → NeuroPilot | Android 12+、ARM64，内置 8.0.10 / 9.0.3 adapter；系统必须有兼容的 NeuroPilot 服务 |
| 小米手机中的高通 / 联发科芯片 | 使用芯片对应后端 | 按 SoC 厂商识别，与手机品牌无关 |
| 小米玄戒 | CPU 回退 | 已找到官方 XNN 接口头文件，尚未取得匹配的用户态运行库和模型转换器 |
| 海思 / 麒麟 | MNN → HiAI NPU | Android 10+、ARM64，内置官方示例的 NPU 最小库集；需兼容的 HiAI 驱动，全部算子必须被后端接受 |
| 三星 Exynos | CPU 回退 | 已取得公开 AI LiteCore SDK，但四个内置识别器的 AOT 编译均失败；尚未启用此后端 |
| Google Tensor | CPU 回退 | AOT 需要外部 Beta SDK 和芯片专用产物；尚未取得 SDK |
| Android 9 / x86_64 | ONNX CPU | 保留原 App 的安装范围；Android 10–11 可尝试海思后端 |

不能仅凭厂商或 SoC 名称承诺 NPU 可用。LiteRT 官方的厂商与芯片范围参见
[NPU 文档](https://developers.google.com/edge/litert/next/npu)、
[高通](https://developers.google.com/edge/litert/next/qualcomm) 和
[联发科](https://developers.google.com/edge/litert/next/mediatek)。海思库来自
[华为 HiAI 官方示例](https://gitee.com/huawei-hiai-foundation/HiAIDemo)，玄戒已公开
[XNN 配置接口](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/dijun-v-oss/platform/O1/npu/mnne/include/xnn/c_api/XnnConfig.h)。
本实现只对完整打包的后端尝试推理。

固定形状识别请求 `[N,3,48,320]` 可尝试 NPU，`N` 拆成单图且不改变输入宽度。
其他宽度、动态尺寸检测器、未内置模型和显式 NCNN 后端不做形状改写。前两者使用宿主
ONNX CPU，后两者保留 AP 原推理路径。模型缓存最多两个，单宿主串行推理。

### 回退与状态

宿主首先确认原 LiteRT 模型没有 custom 操作，再通过公开 C 模型 API 检查 JIT 后的
主图是否生成 custom dispatch 分区。没有分区时拒绝静默 CPU 替代。首个真实请求会
与原 ONNX CPU 输出对照：输出尺寸相同、最大绝对误差不超过 0.01、每个时间步的字符
分类一致。失败则关闭 NPU 会话，该模型在本次 OCR 工作进程内使用 CPU。

加载 MTK adapter 前，先尝试打开系统 `libapuwareutils_v2.mtk.so` 或
`libapuwareutils.mtk.so`，确认其导出 `queryHwConfigInternal`。8.0.10 adapter 的
初始化会直接调用该入口，缺失时会跳转空地址。Manifest 把两库声明为可选公开依赖；
无法打开或入口缺失时报告原因并使用 ONNX CPU。若 v2 库能打开但缺少入口，则直接拒绝，
因为 adapter 在这种情况下不会改试旧版库。此检查只排除已知初始化故障，不保证驱动兼容。

LiteRT 2.1.0rc1 的 Kotlin `Model.handle` 指向 JNI `ModelWrapper`，不能直接传给
C 模型 API。诊断桥按该钉版布局读取首成员 `LiteRtModel`，仅接受 `2.1.0rc1`；构建也会
拒绝未经重新验证的升级。包装对象布局依据
[上游钉版源码](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/kotlin/src/main/jni/litert_model_wrapper.h)。
`test_ocr_jni.py` 在 Linux JVM 编译生产 JNI，执行七项模型句柄检查和三项 MTK
驱动入口检查。模拟库验证拒绝缺失入口，且探测不会调用私有函数；它不验证 Android 驱动。

首次初始化和校验阶段同步保存到启动器日志的 `debug/ocr/stages.jsonl`，复制 OCR 报告
也会包含 `last_stage`。导出启动器日志时额外收集 Android 11+ 的进程退出原因；
Android 12+ 如仍保留原生 tombstone，则附带 `debug/process-exits/trace_*.pb`。
系统可能已清除堆栈，空缺不能据此排除原生崩溃。原生信号仍不能被 Kotlin 异常捕获，
因此所有推理库放在 `:ocr` 进程。工作进程退出后，主进程记录当前模型并重新绑定，
该模型改用 CPU；无法确定模型时暂禁全部 NPU。禁用记录持续到 APK 版本升级，避免
重启再次触发同一崩溃。AP 和 App 测试使用同一回环 API，连接中断后允许重试一次。

海思必须成功创建 HiAI 会话、后端为 `MNN_FORWARD_USER_0`，并返回 V320 就绪状态。
该后端明确使用 `AiModelDescription_DeviceType_NPU`，请求成功后报告 `hiai_npu`；
`hiai_npu_only_session_ready` 记录软件层面的就绪证据。任何错误均回退原 ONNX，
不会使用另一套转换模型的 CPU 实现冒充 NPU。

厂商可能只接受部分算子，所以 `litert_npu_with_cpu_fallback` 允许其余算子由 CPU 执行。
`npu_dispatch_partitions` 与 `npu_delegation_verified` 提供软件层面的分区和成功运行证据，
不代表所有算子在 NPU 执行，也不代表更快。`npu_hardware_profile_verified` 保持 `false`，
直到实现硬件性能分析；发布前仍需真机测量识别质量、初始化时间、稳定推理延迟和内存。

在继承宿主环境变量的 AP Python 子进程中运行：

```bash
cd /opt/azurpilot
.venv/bin/python -m android_ocr
```

命令输出 SoC、厂商、运行库是否就绪、模型会话、分区证据及回退原因，不打印口令。
输入输出原始张量为 little-endian FP32，JSON 行头之后按 `length` 读取二进制数据。
`describe` 和 `run` 要求 `model_sha256`，所有请求要求 `AZURPILOT_ANDROID_TOKEN`；
`status` 仅返回状态。服务只绑定回环地址，头部限 16 KiB，数据限 64 MiB，连接最多四个。
客户端每次请求释放连接，网络中断重试一次；服务无法访问时惰性创建原 CPU 会话。

### App 内测试

打开 **设置 → OCR 加速**，可查看芯片、加速库是否就绪，以及每个内置模型最近一次的
实际后端、AP 业务调用次数、测试调用次数和 CPU 回退原因。进入页面不会创建推理会话；
模型尚未运行时显示「尚未运行」。退出页面后停止自动刷新。

点击 **运行测试**，选择的识别器通过带口令的真实回环 API 推理 `ocr/test/sample.png`。
测试图片为本项目生成的 320×48 白底数字「12345」。首次耗时包含模型初始化和数值校验；
稳定耗时及原始 ONNX CPU 对照耗时分别取三次平均。结果比较形状、最大绝对误差和
各时间步字符分类；CPU 成功不会被标记为 NPU 成功。测试不会代替后续游戏输入的首次校验。

AP 已运行时，同一个按钮还通过 PRoot 启动使用正常环境变量的 AP Python 子进程，调用
`module.ocr.al_ocr._create_ocr`，验证原有 RapidOCR 预处理、宿主推理和 CTC 解码。
模型版本不一致、会话工厂未接入或静默回退原 Python 推理都会报告失败。示例文字是否
正确识别单独显示；接线成功不代替游戏画面的识别质量测试。该测试显式选择所选 ONNX
识别器，不修改实例配置。实际任务选用 NCNN 或未知版本权重时保留上游原推理方式。
AP 未启动时仍可测试内置模型，页面会提示启动 AP 后重新测试接线。

**复制测试报告** 包含芯片、运行库版本、实际后端、调用计数、耗时、数值对照和 AP
识别结果，不包含认证口令。诊断调用不增加 AP 业务计数；业务计数只代表此 OCR 工作进程
收到的宿主请求，未匹配的上游权重和 NCNN 请求不会进入宿主。

### 构建与验证

2026-10-08 的软件验收已通过 Kotlin 编译、Debug/Release 打包、R8 保留检查、12 项
协议测试、12 项打包质量门测试和文案一致性检查。Linux 侧还从固定 AP 源码提取并原样
执行 `RecOnlyOCR`、`OcrSettings` 和识别器工厂，配合真实 RapidOCR 3.9.0 与 ONNX CPU
宿主协议替身，四个识别模型均正确解码测试图片的「12345」。这项隔离依赖的验证未执行
完整 PRoot 运行时或 Android 厂商驱动；完整手机调用由 App 内测试入口验证。

LiteRT 核心与 JIT 插件统一锁定在
[v2.1.0rc1](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0rc1)。这是预发布版本，
选用原因是其公开 JIT 包同时包含高通与联发科插件。升级核心须同步重建或更新两套插件，
不能混用 2.2 核心与旧插件。QNN 锁定 2.40.0。下载器验证来源 SHA-256，白名单提取，
不执行下载包的脚本；SDK 许可文本随 APK 打包。

海思 SDK 固定到 HiAIDemo 提交 `220e2070f1fad9005d6ed06d33520e559cdac7ec`，
MNN 源码固定到 `024a946b0b8fcf87c8a418229fadd4cd7858ffba`。下载器提取五个 ARM64
NPU 客户端库、匹配的头文件与许可文本。MNN 在 NDK 构建中编译，仅开启 HiAI 插件。
该 SDK 未导出且 ARM64 编译代码未引用的四个旧 ABI 分发表条目被删除；真实引用的
缺失符号仍由链接器拒绝。来源、修改与库哈希记录在 `hiai-runtime.json`。

```bash
python app/scripts/fetch_ocr_runtime.py
python app/scripts/test_verify_ocr_assets.py
python app/scripts/test_ocr_jni.py  # Linux，需要 JDK 和 C++ 编译器
python rootfs/tests/test_android_ocr.py  # 需要 numpy
cd app
./gradlew compileDebugKotlin -x verifyBundledAzurPilotRuntime
./gradlew assembleDebug -Pazurpilot.slimApk=true
python scripts/verify_ocr_assets.py app/build/outputs/apk/debug/*.apk
```

模型权重与转换产物入库，普通 APK 构建不需要转换器。在 Linux / Python 3.12 中重新转换：

```bash
uv venv /var/tmp/alas-ocr-convert --python 3.12
uv pip install --python /var/tmp/alas-ocr-convert/bin/python -r app/scripts/ocr-models-requirements.txt
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_ocr_models.py
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_ocr_models.py --verify-only
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_hiai_models.py
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_hiai_models.py --verify-only
```

四个识别器的空白和固定随机张量 CPU 对照均通过：LiteRT 最大绝对误差约 `4.6e-5`，
MNN 约 `0.0058`，每个时间步的字符分类一致。MNN 使用 FP32 高精度配置，但算子融合
会产生不同舍入误差，采用与宿主首次请求相同的 `0.01` 容差。
这些输入不足以代表游戏截图全集。当前开发环境没有连接的 Android 设备，
不能把转换测试或 APK 构建成功视为真机 NPU 性能验证。

### 其他厂商的接入调查（2026-10-08）

三星官方 AI LiteCore 1.2.0 下载地址可访问，包内提供 Exynos 9955 / 9965 的编译库。
使用独立 Linux 环境中的 `ai-edge-litert==2.2.0` 对四个现有 LiteRT 识别模型做 E9965
AOT 探测。先加载同版 LiteRT 核心和 SDK 的 `libgraphgen.so`，解决插件未链接其所需
符号的问题；随后四个模型均进入厂商编译阶段，但没有生成可用产物。英文和中文
AL OCR 模型明确报告 `Unsupported shape type UNDEFINED`，两个 PP-OCR 模型返回
编译错误。该结果只说明这组公开插件、SDK 和模型尚不兼容，不能外推为设备不支持 NPU。
SDK 的许可 PDF 使用 NASCA DRM，未取得可读取的许可内容；SDK 库未加入 APK。
三星 SDK 的使用环境和支持芯片见[官方 LiteRT 接入说明](https://developers.google.com/edge/litert/next/samsung)。

Google Tensor 的官方 `ai-edge-litert-sdk-google-tensor==2.2.0` 安装器没有公开的默认
SDK 下载地址，要求 `GOOGLE_TENSOR_SDK_BETA` 本地文件或 `GOOGLE_TENSOR_ML_SDK_URL`。
仅安装 Python 前端与编译插件不能生成目标 NPU 模型，因此仍需要厂商提供的 Beta SDK。
玄戒的 XNN 头文件同样不足以替代运行库和转换器。

## English

### Call path

The APK bundles original ONNX weights and LiteRT and MNN FP32 models for four recognizers.
`android_ocr.py` proxies AP's ONNX session factory only in the Android runtime, matches
file SHA-256 hashes against the APK allowlist, and sends preprocessed NCHW tensors to
`127.0.0.1:22302`. The app performs inference; AP retains dictionaries, CTC decoding, and
postprocessing. A privately bound `:ocr` worker hosts the endpoint, separate from the UI
and privileged device bridge. It requires neither root nor Shizuku permissions.

LiteRT CompiledModel invokes vendor compiler/dispatch plugins without registering NNAPI:
QNN HTP for Qualcomm and NeuroPilot for MediaTek. A separate MNN → HiAI backend uses the
V320 client to request the NPU explicitly and disables MNN CPU backup. Models and libraries install with the
APK and require neither Play services downloads nor a network connection during inference.
Each PRoot start refreshes overlays from APK assets, including existing rootfs installations.
New upstream weights retain their original path when their hashes no longer match.
The host exposes only the current vendor's plugins through a directory of symlinks to the
installed native libraries, preventing another vendor's offline compiler from taking the
model first. APK updates refresh those links.
The manifest requests public vendor RPC, NeuroPilot, and HiAI system libraries with `required=false`.
Android 12+ requires explicit declarations for such non-NDK libraries; devices without them
can still install and use CPU.
These declarations do not grant access to private driver libraries; the vendor must expose
the corresponding service interfaces to apps.

### Coverage

| Platform | Implementation | Conditions and limits |
| --- | --- | --- |
| Qualcomm | LiteRT → QNN HTP | Android 12+, ARM64; bundled v68/v69/v73/v75/v79/v81 Skel and Stub libraries; driver and operator compilation must succeed |
| MediaTek | LiteRT → NeuroPilot | Android 12+, ARM64; bundled 8.0.10 / 9.0.3 adapters; compatible system NeuroPilot services required |
| Xiaomi phones with Qualcomm / MediaTek SoCs | Corresponding SoC backend | Uses the SoC vendor rather than phone brand |
| Xiaomi Xring | CPU fallback | Official XNN interface headers found; matching user-space runtime and model converter not obtained |
| HiSilicon / Kirin | MNN → HiAI NPU | Android 10+, ARM64; official demo's minimal NPU libraries bundled; compatible HiAI drivers required, and the backend must accept all operators |
| Samsung Exynos | CPU fallback | Public AI LiteCore SDK obtained, but AOT compilation failed for all four bundled recognizers; backend not enabled |
| Google Tensor | CPU fallback | AOT requires an external beta SDK and chip-specific artifacts; SDK not obtained |
| Android 9 / x86_64 | ONNX CPU | Preserves the installation range; Android 10–11 may use the HiAI backend |

Vendor or SoC names alone do not establish NPU availability. Consult the official
[NPU overview](https://developers.google.com/edge/litert/next/npu),
[Qualcomm](https://developers.google.com/edge/litert/next/qualcomm), and
[MediaTek](https://developers.google.com/edge/litert/next/mediatek) coverage. This implementation
attempts only complete bundled backends. HiAI clients come from the
[official Huawei demo](https://gitee.com/huawei-hiai-foundation/HiAIDemo). Xiaomi has published
[XNN configuration headers](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/dijun-v-oss/platform/O1/npu/mnne/include/xnn/c_api/XnnConfig.h).

Recognizer requests shaped `[N,3,48,320]` may use NPU; batches split into single images
without changing width. Other widths and the dynamic detector use host ONNX CPU. Unbundled
models and explicit NCNN configurations retain AP's original path. The host serializes
inference and caches at most two models.

### Fallback and diagnostics

The host checks that the original LiteRT model has no custom operators, then uses the
public C model API to check for custom dispatch partitions in the JIT-transformed main graph.
No partitions means rejecting silent CPU substitution. The first real request is compared
against original ONNX CPU output: identical dimensions, absolute error at most 0.01, and
identical character predictions at every timestep. Failure closes the NPU session and keeps that
model on CPU for the current OCR worker process.

Before loading the MTK adapter, the host opens the system `libapuwareutils_v2.mtk.so` or
`libapuwareutils.mtk.so` and checks for `queryHwConfigInternal`. The 8.0.10 adapter calls
this entry directly during initialization; a missing entry causes a null-address jump.
The manifest declares both libraries as optional public dependencies. Unavailable libraries
or entries produce a reported ONNX CPU fallback. An open v2 library without the entry is
rejected immediately, because the adapter does not try the legacy library in that case.
This check excludes the known initialization fault, without proving driver compatibility.

Kotlin `Model.handle` in LiteRT 2.1.0rc1 points to a JNI `ModelWrapper`, which cannot be
passed directly to C model APIs. The diagnostics bridge reads its first `LiteRtModel` member
under the pinned layout and accepts only `2.1.0rc1`; builds also reject unvalidated upgrades.
The layout follows the
[pinned upstream source](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/kotlin/src/main/jni/litert_model_wrapper.h).
`test_ocr_jni.py` compiles production JNI on a Linux JVM for seven model-handle checks and
three MTK driver-entry checks. Mock libraries verify missing-entry rejection and that the
probe never calls the private function. These checks do not validate Android vendor drivers.

Initialization and first validation stages are flushed to launcher logs at
`debug/ocr/stages.jsonl`; copied OCR reports include `last_stage`. Launcher exports also
collect process exit reasons on Android 11+. If Android 12+ still retains a native tombstone,
the export includes `debug/process-exits/trace_*.pb`. Missing traces do not rule out native
crashes because the system may have discarded them. Kotlin cannot catch native signals,
so all inference libraries run in `:ocr`. After worker death, the main process records the
active model and rebinds with CPU recovery for that model, or all NPU models if unknown.
Gates persist until an APK version upgrade to prevent repeating the crash after restart.
AP and in-app tests use the same loopback API and retry a broken connection once.

HiAI must create a ready session using `MNN_FORWARD_USER_0` and report the V320 ready state.
Its client explicitly requests `AiModelDescription_DeviceType_NPU`. Successful requests report
`hiai_npu`; `hiai_npu_only_session_ready` records software readiness. Errors fall back to original
ONNX instead of using converted-model CPU execution as NPU evidence.

`litert_npu_with_cpu_fallback` permits remaining operators to execute on CPU.
`npu_dispatch_partitions` and `npu_delegation_verified` provide software evidence of partitioning
and successful execution. They prove neither full NPU execution nor improved speed.
`npu_hardware_profile_verified` remains `false` until hardware profiling is implemented.
Release testing must measure OCR quality, initialization, steady inference latency, and memory.

Run `.venv/bin/python -m android_ocr` from `/opt/azurpilot` in an AP child process that inherits
host environment variables. It reports SoC, vendor, library readiness, sessions, partition
evidence, and fallback reasons without exposing the token.

Requests contain one JSON line followed by `length` little-endian FP32 bytes. `describe`
and `run` require `model_sha256`; every request requires `AZURPILOT_ANDROID_TOKEN`. `status`
returns diagnostics only. The loopback-only service limits headers to 16 KiB, payloads to
64 MiB, and connections to four. Clients release each connection, retry network interruption
once, and lazily create the original CPU session if the service cannot be used.

### In-app tests

Open **Settings → OCR acceleration** to inspect the chip, library readiness, and each
bundled model's last backend, AP task count, test count, and CPU fallback reason. Opening
the page does not initialize inference sessions; unused models display "Not run yet".
Automatic refresh stops when the page is no longer visible.

**Run test** sends the selected recognizer through the authenticated loopback API using
`ocr/test/sample.png`, a project-generated 320×48 white image containing "12345". First-run
time includes initialization and validation; steady and original ONNX CPU times each average
three runs. The test compares shapes, maximum absolute error, and per-timestep character
classes. A successful CPU test does not claim NPU use, and test inputs do not replace the
first subsequent game-input validation.

When AP is running, the same button also launches an AP Python child via PRoot with the
normal injected environment and calls `module.ocr.al_ocr._create_ocr`. This checks existing
RapidOCR preprocessing, host inference, and CTC decoding. Changed weights, an unpatched
session factory, or silent original-Python fallback fail the integration test. Sample text
accuracy is shown separately; integration success does not prove game-image accuracy.
This explicitly selects the chosen ONNX recognizer without changing instance settings.
Actual tasks using NCNN or unknown weight versions retain upstream inference. With AP
stopped, bundled-model tests remain available and the page asks users to start AP and retest.

**Copy test report** includes the chip, runtime versions, actual backends, request counts,
timings, numerical comparisons, and AP recognition results, without authentication tokens.
Diagnostic calls do not increase AP task counts. Business counts cover host requests in
this OCR worker process; unmatched upstream weights and NCNN calls never enter the host.

### Build and verification

Software acceptance on 2026-10-08 passed Kotlin compilation, debug/release assembly, R8
keep checks, 12 protocol tests, 12 packaging-gate tests, and string consistency checks.
A Linux smoke check also extracted and executed the unchanged `RecOnlyOCR`, `OcrSettings`,
and recognizer factory from the pinned AP source, with real RapidOCR 3.9.0 and an ONNX CPU
substitute for the host protocol. All four recognizers decoded "12345" correctly. This
dependency-isolated check did not run the full PRoot runtime or Android vendor drivers;
the in-app test covers the complete device integration.

Core LiteRT and JIT plugins are pinned together to
[v2.1.0rc1](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0rc1), a prerelease whose
public JIT archive contains both Qualcomm and MediaTek plugins. Core upgrades must update
or rebuild both plugins; mixing a 2.2 core with older plugins is unsupported. QNN is pinned
to 2.40.0. The downloader verifies source hashes, extracts allowlisted files without executing
archive scripts, and bundles SDK license texts.

HiAI clients are pinned to HiAIDemo commit `220e2070f1fad9005d6ed06d33520e559cdac7ec`;
MNN sources use `024a946b0b8fcf87c8a418229fadd4cd7858ffba`. The downloader extracts five
ARM64 NPU clients, matching headers, and licenses. NDK builds MNN with its HiAI plugin.
Four unused legacy ABI dispatch entries absent from this SDK are removed for ARM64;
the linker still rejects missing symbols referenced by compiled code. `hiai-runtime.json`
records source provenance, modifications, and library hashes.

Run `fetch_ocr_runtime.py`, packaging tests, the NumPy-dependent protocol tests, the preferred
Kotlin compile check, slim debug assembly, and `verify_ocr_assets.py` as shown above.
Weights and converted models are committed, so ordinary builds need no converter.
For regeneration, use Linux / Python 3.12 and the pinned `ocr-models-requirements.txt` environment
shown in the Chinese section, then run both preparation scripts and their `--verify-only` checks.

All four recognizers passed CPU comparison on blank and seeded random tensors. Maximum
absolute error was about `4.6e-5` for LiteRT and `0.0058` for MNN, with identical tested
character predictions at every timestep. MNN uses FP32 high precision; fused operators have
different rounding, so comparisons use the host's first-request tolerance of `0.01`. Those inputs
do not represent a full game screenshot corpus. No Android device is connected in the current
development environment; conversion checks and APK assembly do not establish real NPU speed.

### Other vendor investigation (2026-10-08)

Samsung's public AI LiteCore 1.2.0 archive provides compiler libraries for Exynos 9955 / 9965.
An isolated Linux environment with `ai-edge-litert==2.2.0` attempted E9965 AOT compilation
of all four existing LiteRT recognizers. Preloading the matching LiteRT core and the SDK's
`libgraphgen.so` resolved missing plugin dependencies. All models then reached vendor
compilation, but none produced usable artifacts. Both AL OCR models reported
`Unsupported shape type UNDEFINED`; the two PP-OCR models returned compilation errors.
This establishes incompatibility of the tested plugin/SDK/model combination, not lack of
NPU hardware support. The SDK license PDF uses NASCA DRM and its contents could not be read;
SDK libraries have not been added to the APK. Consult the
[official LiteRT Samsung guide](https://developers.google.com/edge/litert/next/samsung)
for development requirements and supported chips.

The official `ai-edge-litert-sdk-google-tensor==2.2.0` installer has no default public SDK
download URL. It requires a local `GOOGLE_TENSOR_SDK_BETA` archive or a
`GOOGLE_TENSOR_ML_SDK_URL`. The Python frontend and compiler plugin alone cannot produce
target NPU models, so the beta SDK is still required. Likewise, Xring's XNN headers do not
replace its runtime libraries and model converter.
