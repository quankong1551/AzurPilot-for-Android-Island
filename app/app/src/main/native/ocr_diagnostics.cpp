// 通过钉版 LiteRT 公开 C API 检查 JIT 分区，避免把静默 CPU 回退报告为 NPU。
// JNI 按 2.1.0rc1 的 ModelWrapper 布局取出模型；调用方必须持锁，不得与 close 并发。
//
// Checks JIT partitions through the pinned LiteRT public C API so silent CPU fallback is not
// reported as NPU. JNI unwraps the pinned 2.1.0rc1 ModelWrapper; callers must hold the model
// lock and never close it concurrently.

#include <jni.h>
#include <dlfcn.h>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace {
// C API 参数使用原始模型指针，不能直接传入 Kotlin 的 ModelWrapper 指针。
using GetSubgraph = int32_t (*)(void*, size_t, void**);
using GetOpCount = int32_t (*)(void*, size_t*);
using GetOp = int32_t (*)(void*, size_t, void**);
using GetOpCode = int32_t (*)(void*, int32_t*);
constexpr int32_t kCustomOp = 32;
}  // namespace

// SDK 8 初始化会直接调用 queryHwConfigInternal；缺失时必须在加载 adapter 前拒绝。
extern "C" JNIEXPORT jstring JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_mediatekDriverError(JNIEnv* env, jobject) {
    std::string errors;
    for (const char* name : {"libapuwareutils_v2.mtk.so", "libapuwareutils.mtk.so"}) {
        void* library = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (library != nullptr) {
            void* query = dlsym(library, "queryHwConfigInternal");
            if (query != nullptr) {
                // 保持工具库存活，adapter 稍后还要按名称查找同一入口；不调用私有查询函数。
                return nullptr;
            }
            dlclose(library);
            // adapter 一旦打开 v2 工具库就不会转用旧版；不能用旧库符号掩盖该缺失。
            return env->NewStringUTF((std::string("MediaTek driver ") + name +
                    " lacks queryHwConfigInternal").c_str());
        }
        if (!errors.empty()) errors += "; ";
        errors += name;
        errors += ": ";
        const char* error = dlerror();
        errors += error != nullptr ? error : "queryHwConfigInternal unavailable";
    }
    return env->NewStringUTF(("MediaTek APU driver unavailable: " + errors).c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_countCustomOps(
        JNIEnv* env, jobject, jobject model, jstring runtime_version) {
    if (model == nullptr || runtime_version == nullptr) return -1;
    const char* version = env->GetStringUTFChars(runtime_version, nullptr);
    if (version == nullptr) return -1;
    const bool supported = std::strcmp(version, "2.1.0rc1") == 0;
    env->ReleaseStringUTFChars(runtime_version, version);
    if (!supported) return -1;
    jclass model_class = env->GetObjectClass(model);
    jfieldID handle_field = env->GetFieldID(model_class, "handle", "J");
    env->DeleteLocalRef(model_class);
    if (handle_field == nullptr) {
        env->ExceptionClear();
        return -1;
    }
    auto* wrapper = reinterpret_cast<void*>(env->GetLongField(model, handle_field));
    if (wrapper == nullptr) return -1;
    // 上游此版本 ModelWrapper 的首成员是 LiteRtModel；memcpy 避免伪造结构的别名访问。
    // 来源：https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/
    // litert/kotlin/src/main/jni/litert_model_wrapper.h
    void* handle = nullptr;
    std::memcpy(&handle, wrapper, sizeof(handle));
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
