// libbridge.so 的公共 ABI 头：帧缓冲交接（FrameInfo + Get/UnlockPixels）与
// 输入分发入口（MethodType / MethodParam / DispatchInputMessage）。
// 本库整体运行在特权进程内（由 liblauncher.so 经 app_process 拉起的
// :root_service 进程）：FrameInfo 供同进程内的原生消费者锁定最新帧，
// DispatchInputMessage 把触摸 / 按键 / 启动请求上行给 Kotlin 侧的 DriverClass。
// JNI 静态方法面在 bridge.cpp 注册，对应 Kotlin 侧
// app/app/src/main/java/com/azurpilot/ghio/bridge/（NativeBridgeLib.kt 声明
// external 方法，DriverClass.kt 声明上行回调目标）。
//
// Public ABI header of libbridge.so: the frame-buffer handoff (FrameInfo with
// GetLockedPixels / UnlockPixels) and the input dispatch entry (MethodType /
// MethodParam / DispatchInputMessage).
// The library runs inside the privileged process (the :root_service process
// spawned via app_process by liblauncher.so): FrameInfo lets in-process native
// consumers lock the latest frame, while DispatchInputMessage forwards
// touch / key / start-app requests up to DriverClass on the Kotlin side. The
// JNI static-method surface is registered in bridge.cpp and mirrors the Kotlin
// package app/app/src/main/java/com/azurpilot/ghio/bridge/ (NativeBridgeLib.kt
// declares the external methods; DriverClass.kt declares the upcall targets).

#ifndef NATIVE_LIB_H
#define NATIVE_LIB_H

#include <jni.h>

#include <cstddef>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// 导出符号标记：整库按 -fvisibility=hidden 编译（见 CMakeLists.txt），仅此
// C ABI 对进程内消费者显式可见。
//
// Exported-symbol marker: the whole library is built with
// -fvisibility=hidden (see CMakeLists.txt); only this C ABI is explicitly
// visible to in-process consumers.
#define BRIDGE_API __attribute__((visibility("default")))

// 一次锁定持有的帧快照：紧凑 BGR888 排布（无行填充，stride 恒为 width * 3）。
// data 指向共享帧缓冲，frame_ref 是 UnlockPixels 所需的不透明句柄。
//
// A locked frame snapshot: tightly packed BGR888 (no row padding; the stride
// is always width * 3). data points into the shared frame buffer, and
// frame_ref is the opaque handle that must be handed back to UnlockPixels.

struct FrameInfo {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t length;
    void *data;
    void *frame_ref;
};

// 输入分发协议的操作码。数值是与调用侧约定的取值，只增不改；STOP_GAME /
// INPUT 声明于此仅为 ABI 完整，当前 DispatchInputMessage 不处理（落入
// default 按忽略处理，返回 0）。
//
// Opcodes of the input-dispatch protocol. The numeric values are fixed with
// the calling side and must never be renumbered. STOP_GAME / INPUT stay
// declared for ABI completeness, but DispatchInputMessage does not handle them
// today (they fall through to default and are ignored with a 0 return).
enum MethodType {
    START_GAME = 1,
    STOP_GAME = 2,
    INPUT = 4,
    TOUCH_DOWN = 6,
    TOUCH_MOVE = 7,
    TOUCH_UP = 8,
    KEY_DOWN = 9,
    KEY_UP = 10
};

struct Position {
    int x;
    int y;
};

// 各操作码的参数体。package_name 可为 "包名/Activity" 形式；force_stop 非 0
// 表示启动前先强停；STOP_GAME / INPUT 的参数体目前没有分发处理，仅为 ABI
// 完整而保留。
//
// Argument bodies keyed by opcode. package_name may take the
// "package/Activity" form; force_stop non-zero means force-stop before
// starting. The STOP_GAME / INPUT bodies have no dispatch handling today and
// are kept for ABI completeness.

struct StartGameArgs {
    const char *package_name;
    int force_stop;
};

struct StopGameArgs {
    const char *client_type;
};

struct InputArgs {
    const char *text;
};

struct TouchArgs {
    Position p;
    int contact;
};

struct KeyArgs {
    int key_code;
};

union ArgUnion {
    StartGameArgs start_game;
    StopGameArgs stop_game;
    InputArgs input;
    TouchArgs touch;
    KeyArgs key;
};

struct MethodParam {
    int display_id;
    MethodType method;
    ArgUnion args;
};

BRIDGE_API FrameInfo GetLockedPixels(void);
BRIDGE_API int UnlockPixels(FrameInfo info);
BRIDGE_API int DispatchInputMessage(MethodParam param);

#ifdef __cplusplus
}

bool CheckJNIException(JNIEnv *env, const char *context);

#endif

#endif // NATIVE_LIB_H
