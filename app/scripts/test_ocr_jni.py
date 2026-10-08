"""在 Linux JVM 验证生产 JNI 桥的模型句柄和 MTK 驱动入口探测。

需要 JDK 和 C++ 编译器；C API fixture 校验收到的模型身份并模拟分区数量。
不需要 Android 或 NPU，无法替代真机驱动验证。

Checks production JNI model handles and MTK driver-entry probing on a Linux JVM.
Requires a JDK and C++ compiler. A C API fixture checks model identity and simulates
partition counts. Requires neither Android nor NPU and does not validate vendor drivers.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "app/app/src/main/native/ocr_diagnostics.cpp"

C_API = r"""
#include <jni.h>
#include <cstddef>
#include <cstdint>
struct Model { int custom; } model;
// 按钉版 ModelWrapper 首成员布局构造不同地址，避免错误指针也通过检查。
struct Wrapper { void* model; unsigned char buffer[32]; } wrapper;
extern "C" JNIEXPORT jlong JNICALL Java_test_Fixture_wrap(JNIEnv*, jclass, jint custom) {
    model.custom = custom;
    wrapper.model = custom < 0 ? nullptr : &model;
    return reinterpret_cast<jlong>(&wrapper);
}
extern "C" int32_t LiteRtGetModelSubgraph(void* handle, size_t index, void** subgraph) {
    if (handle != &model || index != 0) return 1;
    *subgraph = &model;
    return 0;
}
extern "C" int32_t LiteRtGetNumSubgraphOps(void* subgraph, size_t* count) {
    if (subgraph != &model) return 1;
    *count = model.custom + 3;
    return 0;
}
extern "C" int32_t LiteRtGetSubgraphOp(void* subgraph, size_t index, void** op) {
    if (subgraph != &model) return 1;
    *op = reinterpret_cast<void*>(index + 1);
    return 0;
}
extern "C" int32_t LiteRtGetOpCode(void* op, int32_t* code) {
    *code = reinterpret_cast<size_t>(op) <= static_cast<size_t>(model.custom) ? 32 : 18;
    return 0;
}
"""

MODEL = """
package com.google.ai.edge.litert;
class JniHandle {
    private final long handle;
    JniHandle(long value) { handle = value; }
}
public class Model extends JniHandle {
    public Model(long value) { super(value); }
}
"""

BRIDGE = """
package com.azurpilot.ghio.ocr;
import com.google.ai.edge.litert.Model;
public class OcrNative {
    static { System.loadLibrary("ocrdiagnostics"); }
    public native int countCustomOps(Model model, String runtimeVersion);
    public native String mediatekDriverError();
    public native String mediatekAdapterLibrary();
}
"""

FIXTURE = """
package test;
import com.azurpilot.ghio.ocr.OcrNative;
import com.google.ai.edge.litert.Model;
public class Fixture {
    static { System.loadLibrary("LiteRt"); }
    public static native long wrap(int custom);
    static void expect(int expected, int actual) {
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        OcrNative bridge = new OcrNative();
        if (args.length > 0 && args[0].startsWith("adapter_")) {
            String actual;
            try {
                actual = bridge.mediatekAdapterLibrary();
            } catch (IllegalStateException error) {
                actual = "error:" + error.getMessage();
            }
            if (!actual.equals(args[1]))
                throw new AssertionError("Expected " + args[1] + ", got " + actual);
            System.out.println("OCR JNI: " + args[0] + " passed");
            return;
        }
        if (args.length > 0) {
            boolean available = bridge.mediatekDriverError() == null;
            if (available != args[0].equals("driver_present"))
                throw new AssertionError("Unexpected MTK driver probe: " + available);
            System.out.println("OCR JNI: " + args[0] + " passed");
            return;
        }
        expect(0, bridge.countCustomOps(new Model(wrap(0)), "2.1.0rc1"));
        expect(2, bridge.countCustomOps(new Model(wrap(2)), "2.1.0rc1"));
        expect(-1, bridge.countCustomOps(new Model(wrap(-1)), "2.1.0rc1"));
        expect(-1, bridge.countCustomOps(new Model(0), "2.1.0rc1"));
        // 未验证的 ABI 版本必须在读取未知指针前拒绝。
        expect(-1, bridge.countCustomOps(new Model(1), "2.2.0"));
        expect(-1, bridge.countCustomOps(null, "2.1.0rc1"));
        expect(-1, bridge.countCustomOps(new Model(1), null));
        if (bridge.mediatekDriverError() == null)
            throw new AssertionError("Missing MTK driver must be rejected before SDK loading");
        System.out.println("OCR JNI: 7 regression checks passed");
        System.out.println("OCR JNI: driver_absent passed");
    }
}
"""


def main():
    """编译真实 JNI 验证句柄及驱动探测。 / Compiles real JNI to check handles and driver probes."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=NATIVE)
    args = parser.parse_args()
    javac = Path(shutil.which("javac") or "").resolve()
    compiler = shutil.which("c++")
    if not javac.is_file() or not compiler or os.name != "posix":
        parser.error("Run on Linux with JDK and a C++ compiler installed")
    jdk = javac.parent.parent
    with tempfile.TemporaryDirectory(prefix="ocr-jni-") as temp:
        directory = Path(temp)
        api = directory / "api.cpp"
        api.write_text(C_API)
        flags = [compiler, "-std=c++17", "-shared", "-fPIC", "-Wall", "-Wextra", "-O2",
                 f"-I{jdk / 'include'}", f"-I{jdk / 'include/linux'}"]
        subprocess.run(flags + [str(api), "-o", str(directory / "libLiteRt.so")], check=True)
        subprocess.run(flags + [str(args.source), "-ldl", "-o",
                               str(directory / "libocrdiagnostics.so")], check=True)
        sources = []
        for path, text in [("com/google/ai/edge/litert/Model.java", MODEL),
                           ("com/azurpilot/ghio/ocr/OcrNative.java", BRIDGE),
                           ("test/Fixture.java", FIXTURE)]:
            source = directory / path
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(text)
            sources.append(str(source))
        subprocess.run([str(javac), "-d", str(directory), *sources], check=True)
        env = os.environ | {"LD_LIBRARY_PATH": str(directory)}
        java = [str(jdk / "bin/java"), f"-Djava.library.path={directory}",
                "-cp", str(directory), "test.Fixture"]
        subprocess.run(java, env=env, check=True)
        # 探测只能检查入口，不能调用未公开的硬件配置 ABI。
        api.write_text('#include <cstdlib>\nextern "C" void* queryHwConfigInternal(int*) { std::abort(); }\n')
        utility = directory / "libapuwareutils_v2.mtk.so"
        subprocess.run(flags + [str(api), "-o", str(utility)], check=True)
        subprocess.run(java + ["driver_present"], env=env, check=True)
        # SDK 不会绕过已打开但缺少入口的 v2 库，旧库有入口也必须拒绝。
        shutil.copyfile(utility, directory / "libapuwareutils.mtk.so")
        api.write_text('extern "C" int wrong_driver_api() { return 0; }\n')
        subprocess.run(flags + [str(api), "-o", str(utility)], check=True)
        subprocess.run(java + ["driver_wrong_api"], env=env, check=True)
        subprocess.run(java + ["adapter_absent", "error:MediaTek Neuron adapter unavailable"],
                       env=env, check=True)
        # 用完整 SDK 接口模拟正常内置库；旧 MGVI 故意缺少命名接口，复现真机崩溃条件。
        symbols = ["NeuronModel_setName", "NeuronModel_create", "NeuronModel_free",
                   "NeuronModel_addOperand", "NeuronModel_addOperation", "NeuronModel_setOperandValue",
                   "NeuronModel_identifyInputsAndOutputs", "NeuronModel_finish",
                   "NeuronModel_restoreFromCompiledNetwork", "NeuronCompilation_create",
                   "NeuronCompilation_createWithOptions", "NeuronCompilation_finish", "NeuronCompilation_free",
                   "NeuronCompilation_storeCompiledNetwork", "NeuronCompilation_getCompiledNetworkSize",
                   "NeuronExecution_create", "NeuronExecution_setInputFromMemory",
                   "NeuronExecution_setOutputFromMemory", "NeuronExecution_compute", "NeuronExecution_free",
                   "NeuronMemory_createFromFd", "NeuronMemory_free", "Neuron_getVersion"]
        api.write_text("\n".join(f'extern "C" int {name}() {{ return 0; }}' for name in symbols))
        sdk = directory / "libneuronusdk_adapter.mtk.so"
        subprocess.run(flags + [str(api), "-o", str(sdk)], check=True)
        subprocess.run(java + ["adapter_bundled_v8", sdk.name], env=env, check=True)
        legacy = directory / "libneuron_adapter_mgvi.so"
        api.write_text('extern "C" int NeuronModel_create() { return 0; }\n')
        subprocess.run(flags + [str(api), "-o", str(legacy)], check=True)
        subprocess.run(java + ["adapter_legacy_shadow", f"error:MediaTek adapter {legacy.name} lacks NeuronModel_setName"],
                       env=env, check=True)
        legacy.unlink()
        sdk9 = directory / "libneuronusdk_adapter.9.mtk.so"
        shutil.copyfile(sdk, sdk9)
        api.write_text('#include <cstdint>\nextern "C" int NeuronService_getNeuroPilotMagicNumber(int32_t* magic) { *magic = 300; return 0; }\n')
        subprocess.run(flags + [str(api), "-o", str(directory / "libneuron_sys_util.mtk.so")], check=True)
        subprocess.run(java + ["adapter_bundled_v9", sdk9.name], env=env, check=True)


if __name__ == "__main__":
    main()
