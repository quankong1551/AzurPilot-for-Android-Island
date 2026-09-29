# 特权进程 native 桥与 root app_process 启动器的 CMake 构建定义。
#
# libbridge.so 提供 JNI 采集、帧缓冲、预览和输入上行；liblauncher.so 是 root 后端用来
# exec app_process 的小型 C 启动器。默认以尺寸和可裁剪符号为目标优化，而帧热路径单独
# 使用 -O2，避免全局 -Os 降低图像转换吞吐。
#
# CMake build definition for the privileged-process native bridge and root app_process launcher.
# libbridge.so provides JNI capture, frame storage, preview, and input upcalls; liblauncher.so
# is the small C launcher used by the root backend to exec app_process. The defaults favor size
# and removable symbols, while frame hot paths use -O2 separately to avoid sacrificing
# conversion throughput.

cmake_minimum_required(VERSION 3.22.1)

project("bridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 整库默认采用尺寸优先的 release 优化：LTO、死代码裁剪和隐藏符号可压缩 APK；bridge.h 中
# 明确标出的 BRIDGE_API 仍对同进程消费者可见。
#
# Whole-library defaults prioritize release size: LTO, dead-code elimination, and hidden symbols
# reduce APK size; BRIDGE_API symbols explicitly declared in bridge.h remain visible to in-process
# consumers.
add_compile_options(-Os -flto -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden)
add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)

# bridge 的帧转换在每次采集回调中执行，单独升为 -O2，避免全局 -Os 降低吞吐。
# Frame conversion runs on every bridge capture callback, so these sources use -O2 separately to
# avoid reducing throughput under the global -Os policy.
add_library(bridge SHARED
        bridge.cpp
        bridge.h
        bridge_internal.h
        bridge_frame_buffer.h
        bridge_frame_buffer.cpp
        bridge_preview.h
        bridge_preview.cpp
        bridge_capture.h
        bridge_capture.cpp
        bridge_input.h
        bridge_input.cpp
        misc.cpp)
set_source_files_properties(
        bridge.cpp
        bridge_frame_buffer.cpp
        bridge_preview.cpp
        bridge_capture.cpp
        bridge_input.cpp
        PROPERTIES COMPILE_OPTIONS "-O2")
# 仅在诊断构建按需启用逐帧时序日志；默认关闭，避免在采集热路径格式化日志。
# Enable per-frame timing only for diagnostic builds; it is off by default to avoid formatting
# logs on the capture hot path.
option(ENABLE_FRAME_TIMING "Enable per-frame timing logs" OFF)
if (ENABLE_FRAME_TIMING)
    target_compile_definitions(bridge PRIVATE ENABLE_FRAME_TIMING)
endif ()
# launcher 必须生成为 liblauncher.so：AGP 将 lib*.so 安装到 nativeLibraryDir，而该位置
# 是 targetSdk 限制下唯一可稳定 execve 的 APK 私有目录。
#
# launcher must be emitted as liblauncher.so: AGP installs lib*.so into nativeLibraryDir, the only
# APK-private location that remains reliably execve-able under targetSdk restrictions.
add_executable(launcher launcher.c)
set_target_properties(launcher PROPERTIES
        OUTPUT_NAME "liblauncher"
        SUFFIX ".so"
        LINKER_LANGUAGE C)

# bridge 使用 Android 日志、Surface、Bitmap、Media NDK 与 EGL/GLES API；launcher 只需 log。
# bridge uses Android log, Surface, Bitmap, Media NDK, and EGL/GLES APIs; launcher needs only log.
target_link_libraries(bridge log android jnigraphics mediandk EGL GLESv2)
target_link_libraries(launcher log)
