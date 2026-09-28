package com.azurpilot.ghio.ui.azurpilot

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * AzurPilot 页的动效词汇表
 *
 * 页面里不再各写 `tween(300)`——**缓动与时长的取值只此一处**，这样全页的「快慢手感」是一套。
 * 取值全部来自 MD3 的 motion token（下方常量即规范里的贝塞尔控制点与时长），只按语义分成三档：
 *
 * - [spatial] 空间动效：位移、尺寸这类「东西在动」。用弹簧而不是缓动曲线，
 *   落点带一点自然的收尾；MD3 里空间的默认时长比效果长，因为位置变化需要被眼睛跟住。
 * - [resize] 尺寸变化与列表落位：不加回弹。尺寸过冲像控件在抽搐，列表落位过冲则显得没对齐。
 * - [effects] 效果动效：颜色、透明度这类「样子在变」。比空间快，用缓动曲线——
 *   淡入淡出没有惯性可循，给它弹簧只会显得拖。
 *
 * Motion vocabulary for the AzurPilot page.
 *
 * Pages never hand-write `tween(300)` — **easings and durations are defined
 * only here**, so the whole page shares one sense of timing. Every value comes
 * from the MD3 motion tokens (the constants below are the spec's bezier
 * control points and durations), grouped into three semantic tiers:
 *
 * - [spatial] spatial motion: things that move — position, size. Springs
 *   instead of easing curves so the landing settles naturally; MD3 gives
 *   spatial motion longer default durations than effects because the eye has
 *   to track position.
 * - [resize] size changes and list placement: no bounce. An overshooting size
 *   looks like a twitching control, and overshooting list placement reads as
 *   misalignment.
 * - [effects] effect motion: things that change appearance — color, alpha.
 *   Faster than spatial, driven by easing curves — a fade has no inertia to
 *   follow, and a spring only makes it drag.
 */
object ApMotion {

    /** MD3 emphasized：标准强调曲线 / MD3 "emphasized": the standard emphasized curve. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** MD3 emphasized-decelerate：进场的减速段 / MD3 "emphasized decelerate": the decelerating half of an entrance. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** MD3 emphasized-accelerate：出场的加速段 / MD3 "emphasized accelerate": the accelerating half of an exit. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** MD3 standard：一般过渡曲线，[effects] 的默认档 / MD3 "standard": the general transition curve and the default easing of [effects]. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** MD3 standard-decelerate / MD3 "standard decelerate". */
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** MD3 standard-accelerate / MD3 "standard accelerate". */
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    // 以下为 MD3 duration token，单位毫秒；数值照抄规范，勿凭手感调整。
    const val Short2 = 100
    const val Short4 = 200
    const val Medium2 = 300
    const val Medium4 = 400
    const val Long2 = 500

    /** 位移、尺寸：弹簧，轻回弹 / Position and size: a spring with a light bounce. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 尺寸变化与列表落位：不要回弹——过冲会让「对齐」这件事显得不确定
     *
     * Size changes and list placement: no bounce — overshoot makes alignment
     * look uncertain.
     */
    fun <T> resize(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 颜色、透明度：缓动曲线，比空间动效快 / Color and alpha: an easing curve, faster than spatial motion. */
    fun <T> effects(
        durationMillis: Int = Short4,
        easing: Easing = Standard,
    ): FiniteAnimationSpec<T> = tween(durationMillis, easing = easing)
}

/**
 * 分区之间是**平级**切换
 *
 * 用淡入淡出（fade-through）而不是滑动：滑动在 MD3 里表示层级推进，标签之间来回切并不发生
 * 层级变化，给它方向会让人以为「配置」在「总览」的下一层。进来的那半程慢、带着色，
 * 出去的那半程快、先加速离开——避免两个分区同时可见时出现可读的叠字。
 *
 * Switching between sections is **peer-level**.
 *
 * Fade-through instead of sliding: a slide signals a hierarchical advance in
 * MD3, and hopping between tabs crosses no hierarchy — giving it direction
 * would suggest "Config" sits one level below "Overview". The incoming half is
 * slow and fades in while the outgoing half accelerates away quickly, so two
 * sections never show readable overlapping text while both are visible.
 *
 * @see sectionExitTransition
 */
fun sectionEnterTransition(): EnterTransition =
    fadeIn(tween(ApMotion.Medium2, easing = ApMotion.EmphasizedDecelerate))

/** [sectionEnterTransition] 的出场半程：短促加速离场 / The outgoing half of [sectionEnterTransition]: a short, accelerating departure. */
fun sectionExitTransition(): ExitTransition =
    fadeOut(tween(ApMotion.Short4, easing = ApMotion.EmphasizedAccelerate))

/**
 * 详情页推进的共享轴 X：位移只走一小段（1/4 屏宽），其余交给淡入淡出，避免整屏横扫
 *
 * The forward shared-axis X for detail pages: the slide covers only a short
 * distance (a quarter of the width) and the rest is left to the fade, avoiding
 * a full-screen sweep.
 */
fun detailEnterTransition(): EnterTransition =
    slideInHorizontally(tween(ApMotion.Medium4, easing = ApMotion.EmphasizedDecelerate)) { it / 4 } +
        fadeIn(tween(ApMotion.Medium2, easing = ApMotion.EmphasizedDecelerate))

/** 详情页推进的出场：小幅反向滑出并淡出 / The exit of a forward detail push: a small counter-slide with a fade. */
fun detailExitTransition(): ExitTransition =
    slideOutHorizontally(tween(ApMotion.Medium2, easing = ApMotion.EmphasizedAccelerate)) { -it / 8 } +
        fadeOut(tween(ApMotion.Short4, easing = ApMotion.EmphasizedAccelerate))

/**
 * 返回（pop）时前一层从左缘 1/8 处滑回，方向与推进相反
 *
 * On pop the previous layer slides back in from an eighth of the width on the
 * left, reversing the push direction.
 */
fun detailPopEnterTransition(): EnterTransition =
    slideInHorizontally(tween(ApMotion.Medium4, easing = ApMotion.EmphasizedDecelerate)) { -it / 8 } +
        fadeIn(tween(ApMotion.Medium2, easing = ApMotion.EmphasizedDecelerate))

/** 返回（pop）时当前层向右 1/4 加速滑出 / On pop the current layer accelerates out to the right by a quarter. */
fun detailPopExitTransition(): ExitTransition =
    slideOutHorizontally(tween(ApMotion.Medium2, easing = ApMotion.EmphasizedAccelerate)) { it / 4 } +
        fadeOut(tween(ApMotion.Short4, easing = ApMotion.EmphasizedAccelerate))

/**
 * 入场递延上限：几十张卡一路排下去会让人等，后面的直接一起进
 *
 * Stagger cap: making dozens of cards queue up one by one just keeps people
 * waiting; past the cap the rest all enter together.
 */
private const val MAX_STAGGER_INDEX = 6

/** 递延步长（毫秒） / The stagger step, in milliseconds. */
private const val STAGGER_STEP_MS = 40L

/** 入场上浮距离 / The distance an entrance rises from. */
private val ENTER_RISE = 20.dp

/**
 * 入场：淡入 + 轻微上浮，按 [index] 依次递延
 *
 * 给一屏里并列的卡片用。**不要用在 LazyColumn 的条目上**——懒列表会回收并重建条目，
 * 滚动回来时会重播一次入场，看起来像内容在抖。
 *
 * Entrance: fade in with a slight upward drift, deferred by [index].
 *
 * For sibling cards on one screen. **Do not use it on LazyColumn items** — the
 * lazy list recycles and rebuilds entries, so scrolling back replays the
 * entrance once more and the content looks like it is jittering.
 */
@Composable
fun Modifier.apEnter(index: Int = 0, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(MAX_STAGGER_INDEX) * STAGGER_STEP_MS)
        shown = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = ApMotion.effects(ApMotion.Medium2, ApMotion.EmphasizedDecelerate),
        label = "apEnterAlpha",
    )
    val density = LocalDensity.current
    val rise by animateFloatAsState(
        targetValue = if (shown) 0f else with(density) { ENTER_RISE.toPx() },
        animationSpec = ApMotion.spatial(),
        label = "apEnterRise",
    )
    return this.graphicsLayer {
        this.alpha = alpha
        translationY = rise
    }
}

/** 内容切换的淡入淡出：加载态 → 内容 → 空态之间不要硬切 / The fade spec for content swaps (loading → content → empty), so states never hard-cut. */
@Composable
fun apCrossFadeSpec(): FiniteAnimationSpec<Float> =
    ApMotion.effects(ApMotion.Medium2, ApMotion.EmphasizedDecelerate)
