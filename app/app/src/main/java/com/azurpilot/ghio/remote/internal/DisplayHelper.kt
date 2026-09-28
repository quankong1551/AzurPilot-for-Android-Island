package com.azurpilot.ghio.remote.internal

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build

/**
 * 采集端 [ImageReader] 工厂
 *
 * Android 10+ 用带 usage 的重载（CPU 直读 + GPU 采样），老系统没有该重载只能退基础版。
 *
 * Factory for the capture-side [ImageReader].
 *
 * On Android 10+ the usage-carrying overload is used (direct CPU reads plus GPU sampling);
 * older releases lack that overload and fall back to the basic form.
 */
object DisplayHelper {

    /** maxImages 上限；3 张兼顾采集-消费流水延迟与生产端不被反压卡住 / maxImages; 3 balances pipeline latency against backpressure stalling the producer */
    private const val MAX_IMAGES = 3

    /**
     * 按目标尺寸创建 RGBA_8888 的 [ImageReader]
     *
     * Creates an RGBA_8888 [ImageReader] at the requested size.
     *
     * @param width 帧宽（px）/ frame width (px)
     * @param height 帧高（px）/ frame height (px)
     */
    fun newInstanceImagerReader(width: Int, height: Int): ImageReader {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(
                width, height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES,
                HardwareBuffer.USAGE_CPU_READ_OFTEN
                        or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
        } else {
            ImageReader.newInstance(
                width, height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )
        }
    }
}
