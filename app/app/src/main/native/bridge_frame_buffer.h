// libbridge 三重 BGR 帧缓冲的公开内部契约。
//
// 采集回调发布最新帧；消费者必须将 GetLockedPixels() 与 UnlockPixels() 成对调用，且
// 不得跨越采集器重配置持有 FrameInfo。
//
// Public internal contract for libbridge's triple-buffered BGR frames.
// Capture callbacks publish the newest frame; consumers must pair GetLockedPixels() with
// UnlockPixels() and must not retain FrameInfo across capture reconfiguration.

#ifndef BRIDGE_FRAME_BUFFER_H
#define BRIDGE_FRAME_BUFFER_H

#include "bridge_internal.h"

#include <android/hardware_buffer.h>
#include <media/NdkImage.h>
#include <string>

// 表示一个采集槽是否能被写入；读取引用另由原子计数保护。
// States whether a capture slot can be written; reader references are protected by a separate
// atomic count.
typedef enum {
    FRAME_STATE_FREE = 0,
    FRAME_STATE_WRITING = 2
} FrameBufferState;

// 三个槽允许读取者保留上一帧，同时采集准备下一帧。
// Three slots let a reader retain one frame while capture prepares the next.
#define FRAME_BUFFER_COUNT 3

// 保存一张紧凑 BGR888 帧及其分配元数据。
// Stores one packed BGR888 frame and its allocation metadata.
typedef struct {
    uint8_t *bgr_data;
    size_t bgr_size;
    int64_t frame_count;
    int width;
    int height;
    int index;
} FrameBuffer;

// 为指定显示像素尺寸分配帧槽。
// Allocates frame slots for the specified display-pixel dimensions.
void InitFrameBuffers(int width, int height);

// 等待活动读取/写入结束后释放所有槽位。
// Frees every slot after active readers and writers leave.
void ReleaseFrameBuffers();

// 将一张 RGBA AImage 转换并发布为最新 BGR 帧。
// Converts one RGBA AImage and publishes it as the newest BGR frame.
bool WriteImageToFrame(AImage *image);

// 返回最近一次图像平面校验的诊断结果。
// Returns diagnostics from the most recent image-plane validation.
std::string GetFrameReadDiagnostics();

// 将当前 BGR 帧复制为 ARGB_8888 Java Bitmap；无帧时返回 null。
// Copies the current BGR frame into an ARGB_8888 Java Bitmap; returns null when no frame exists.
jobject CreateFrameBufferBitmap(JNIEnv *env);

// 返回成功发布的单调递增帧数。
// Returns the monotonic count of successfully published frames.
int64_t GetFrameCount();

#endif // BRIDGE_FRAME_BUFFER_H
