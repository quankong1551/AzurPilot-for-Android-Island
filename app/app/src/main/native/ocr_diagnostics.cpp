// 通过钉版 LiteRT 公开 C API 检查 JIT 分区，避免把静默 CPU 回退报告为 NPU。
// JNI 只读取存活 Model 的句柄；调用方必须持有模型锁，不得与 close 并发。
//
// Checks JIT partitions through the pinned LiteRT public C API so silent CPU fallback is not
// reported as NPU. JNI reads a live Model handle; callers must hold its lock and never close
// the model concurrently.

#include <jni.h>
#include <dlfcn.h>
#include <cstddef>
#include <cstdint>

namespace {
// 钉版 API 使用不透明指针、size_t 索引和 int32_t 状态/操作码；不读取私有原生结构。
using GetSubgraph = int32_t (*)(void*, size_t, void**);
using GetOpCount = int32_t (*)(void*, size_t*);
using GetOp = int32_t (*)(void*, size_t, void**);
using GetOpCode = int32_t (*)(void*, int32_t*);
constexpr int32_t kCustomOp = 32;
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_countCustomOps(JNIEnv* env, jobject, jobject model) {
    jclass model_class = env->GetObjectClass(model);
    jfieldID handle_field = env->GetFieldID(model_class, "handle", "J");
    env->DeleteLocalRef(model_class);
    if (handle_field == nullptr) {
        env->ExceptionClear();
        return -1;
    }
    auto* handle = reinterpret_cast<void*>(env->GetLongField(model, handle_field));
    if (handle == nullptr) return -1;
    void* library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return -1;
    auto subgraph_api = reinterpret_cast<GetSubgraph>(dlsym(library, "LiteRtGetModelSubgraph"));
    auto count_api = reinterpret_cast<GetOpCount>(dlsym(library, "LiteRtGetNumSubgraphOps"));
    auto op_api = reinterpret_cast<GetOp>(dlsym(library, "LiteRtGetSubgraphOp"));
    auto code_api = reinterpret_cast<GetOpCode>(dlsym(library, "LiteRtGetOpCode"));
    void* subgraph = nullptr;
    size_t count = 0;
    int custom = 0;
    if (!subgraph_api || !count_api || !op_api || !code_api ||
        subgraph_api(handle, 0, &subgraph) != 0 || count_api(subgraph, &count) != 0 || count > 100000) {
        custom = -1;
    } else {
        for (size_t index = 0; index < count; ++index) {
            void* op = nullptr;
            int32_t code = 0;
            if (op_api(subgraph, index, &op) != 0 || code_api(op, &code) != 0) {
                custom = -1;
                break;
            }
            if (code == kCustomOp) ++custom;
        }
    }
    dlclose(library);
    return custom;
}
