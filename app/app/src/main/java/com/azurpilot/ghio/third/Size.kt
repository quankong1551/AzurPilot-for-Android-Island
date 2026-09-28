package com.azurpilot.ghio.third

import android.graphics.Rect

/**
 * 宽 × 高的尺寸值（单位与使用场景一致，本工程内为显示器像素）
 *
 * 承载显示器/编码分辨率的纯值类型，提供旋转、限幅、对齐 8 像素等纯函数变换，
 * 不持有任何系统资源。
 *
 * 从 scrcpy 服务端的 `third/Size.java` 移植（Apache-2.0, Genymobile/scrcpy）。
 *
 * A width-by-height size value (in display pixels in this project).
 *
 * Pure value type carrying display/encoder resolutions with pure transforms
 * (rotate, clamp, align to 8 pixels); holds no system resources.
 *
 * Ported from scrcpy's server `third/Size.java` (Apache-2.0, Genymobile/scrcpy).
 *
 * @property width 宽 / width
 * @property height 高 / height
 */
data class Size(val width: Int, val height: Int) {

    /** 较长边 / The larger of the two dimensions. */
    val max: Int
        get() = maxOf(width, height)

    /** 宽高互换 / Returns the size with width and height swapped. */
    fun rotate(): Size {
        return Size(height, width)
    }

    /**
     * 把较长边限制到 [maxSize]，按比例缩放较短边
     *
     * 用于给编码器设定分辨率上限；[maxSize] 必须是非负的 8 的倍数（编码器对齐
     * 要求），为 0 表示不限制。
     *
     * Clamps the major dimension to [maxSize], scaling the minor one
     * proportionally.
     *
     * Used to cap the encoder resolution; [maxSize] must be a non-negative
     * multiple of 8 (encoder alignment requirement), and 0 means "no limit".
     *
     * @param maxSize 较长边的上限（0 = 不限制）/ cap for the major dimension
     *   (0 = unlimited)
     * @return 未超限时返回自身，否则返回等比缩小的新尺寸 / this when already
     *   within the limit, otherwise a proportionally scaled-down size
     */
    fun limit(maxSize: Int): Size {
        assert(maxSize >= 0) { "Max size may not be negative" }
        assert(maxSize % 8 == 0) { "Max size must be a multiple of 8" }

        if (maxSize == 0) {
            return this
        }

        val portrait = height > width
        val major = if (portrait) height else width
        if (major <= maxSize) {
            return this
        }

        val minor = if (portrait) width else height

        val newMajor = maxSize
        val newMinor = maxSize * minor / major

        val w = if (portrait) newMinor else newMajor
        val h = if (portrait) newMajor else newMinor
        return Size(w, h)
    }

    /**
     * 把两边都取整到 8 的倍数（多数编码器要求），尽量保持长宽比
     *
     * 长边向下取整（不超出原始分辨率），短边就近取整（减小长宽比失真），
     * 取整后短边不得大于长边。
     *
     * Rounds both dimensions to a multiple of 8 (as required by many encoders),
     * preserving the aspect ratio as far as possible.
     *
     * The major dimension rounds down (never exceeding the initial size), the
     * minor one to the nearest multiple (minimizing aspect ratio distortion);
     * after rounding the minor side never exceeds the major one.
     *
     * @return 已对齐的尺寸；本来就是 8 的倍数时返回自身 / the aligned size; this
     *   when already a multiple of 8
     */
    fun round8(): Size {
        if (isMultipleOf8()) {
            return this
        }

        val portrait = height > width
        var major = if (portrait) height else width
        var minor = if (portrait) width else height

        major = major and 7.inv() // 向下取整，不超出原始尺寸
        minor = (minor + 4) and 7.inv() // 就近取整，减小长宽比失真
        if (minor > major) {
            minor = major
        }

        val w = if (portrait) minor else major
        val h = if (portrait) major else minor
        return Size(w, h)
    }

    /** 两边是否都是 8 的倍数 / Whether both dimensions are multiples of 8. */
    fun isMultipleOf8(): Boolean {
        return (width and 7) == 0 && (height and 7) == 0
    }

    /** 转为原点在左上角、覆盖整个尺寸的 [Rect] / Converts to a [Rect] covering the whole size from the top-left origin. */
    fun toRect(): Rect {
        return Rect(0, 0, width, height)
    }

    /** 格式 `WxH`，如 `1920x1080` / Formats as `WxH`, e.g. `1920x1080`. */
    override fun toString(): String {
        return "${width}x$height"
    }
}
