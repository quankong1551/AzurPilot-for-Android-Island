package com.azurpilot.ghio.ui

import android.graphics.Outline
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.splashscreen.SplashScreenViewProvider
import com.azurpilot.ghio.R

/**
 * 启动画面退出阶段的探头动画调度
 *
 * 职责按系统版本分治：
 * - API 31+：`windowSplashScreenAnimatedIcon` 的 AVD 已随启动画面自动播放完毕，
 *   退出保持系统默认节奏，立即移除即可
 * - API < 31：compat 启动画面只渲染 AVD 的静态基态（人物居中），从不自动播放；
 *   在退出时叠一份同尺寸的 AVD 实例到系统图标位上补播"探头→回中"，再整体淡出。
 *   帧位与静态图标完全重合，衔接无跳变
 *
 * Dispatches the peek animation during the splash screen exit.
 *
 * Responsibilities per platform:
 * - API 31+: the icon AVD already played while the splash screen was showing;
 *   exit keeps the system default rhythm by removing immediately
 * - API < 31: the compat splash only renders the AVD's static base state
 *   (character centered) and never auto-plays; overlay an equal-sized AVD
 *   instance on the system icon slot to replay "peek out and spring back",
 *   then fade the whole splash view out. Frame 0 coincides with the static
 *   icon pixel-for-pixel, so the handoff has no visible jump
 */
object SplashExitAnimator {

    /** 与 splash_icon_avd.xml 的 android:duration 对齐 / Matches the AVD duration. */
    private const val AVD_DURATION_MS = 700L
    private const val FADE_DURATION_MS = 160L

    fun run(provider: SplashScreenViewProvider) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            provider.remove()
            return
        }

        val splash = provider.view
        val icon = provider.iconView
        // 尚未布局完成拿不到图标位几何：直接放行，宁可少一次动画也不挡启动
        if (icon.width == 0 || icon.height == 0) {
            provider.remove()
            return
        }
        val drawable = icon.context.getDrawable(R.drawable.splash_icon_avd)
        if (drawable !is AnimatedVectorDrawable) {
            provider.remove()
            return
        }

        // 图标画的是满幅 432 场景，compat 的图标位按 fitCenter 缩放；舞台复制同一几何与圆形
        // 裁切，保证与静态帧一致。AVD 需要独立实例，mutate 分离共享态避免影响其他引用
        val stageDrawable = drawable.mutate() as AnimatedVectorDrawable
        val stage = ImageView(splash.context).apply {
            setImageDrawable(stageDrawable)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        val params = FrameLayout.LayoutParams(icon.width, icon.height).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = if (icon.parent === splash) icon.left else (splash.width - icon.width) / 2
            topMargin = if (icon.parent === splash) icon.top else (splash.height - icon.height) / 2
        }
        // provider.view 声明为 View，实际是容器；叠舞台前收窄成 ViewGroup
        val container = splash as? ViewGroup
        if (container == null) {
            provider.remove()
            return
        }
        container.addView(stage, params)
        icon.visibility = View.INVISIBLE
        stageDrawable.start()

        // AVD 播完先静止一拍再淡出：回中构图与静态图标相同，淡出即自然交接到应用内容
        stage.postDelayed({
            splash.animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .withEndAction { provider.remove() }
                .start()
        }, AVD_DURATION_MS)
    }
}
