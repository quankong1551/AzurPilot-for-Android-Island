package com.azurpilot.ghio.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.azurpilot.ghio.MainActivity
import com.azurpilot.ghio.R

/**
 * 2x1 极简快捷开关小组件 / 2x1 Minimal Quick Switch AppWidget (MD3)
 *
 * 紧凑型胶囊设计，显示状态与配置，点击右侧按钮直接一键启停。
 * Compact MD3 pill design displaying state and config, with a quick toggle action.
 */
class AzurPilotQuickWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val state = AzurPilotWidgetUpdater.currentState(context)

                val openAppAction = actionStartActivity<MainActivity>()

                val toggleIntent = Intent(context, AzurPilotWidgetActionReceiver::class.java).apply {
                    action = AzurPilotWidgetActionReceiver.ACTION_TOGGLE_RUNNER
                }
                val toggleAction = actionSendBroadcast(toggleIntent)

                val buttonBackground = when {
                    state.busy -> R.drawable.widget_btn_muted
                    state.runnerAlive -> R.drawable.widget_btn_error
                    else -> R.drawable.widget_btn_primary
                }
                val buttonIcon = when {
                    state.busy -> R.drawable.ic_widget_hourglass
                    state.runnerAlive -> R.drawable.ic_widget_stop
                    else -> R.drawable.ic_widget_play
                }
                val buttonIconTint = when {
                    state.busy -> GlanceTheme.colors.onSurfaceVariant
                    state.runnerAlive -> GlanceTheme.colors.onErrorContainer
                    else -> GlanceTheme.colors.onPrimary
                }

                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ImageProvider(R.drawable.widget_card_bg))
                        .clickable(openAppAction)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        // 左侧：App 图标 + 状态与配置说明
                        Image(
                            provider = ImageProvider(R.mipmap.ic_launcher),
                            contentDescription = null,
                            modifier = GlanceModifier.size(24.dp)
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Column(
                            modifier = GlanceModifier.defaultWeight(),
                            verticalAlignment = Alignment.Vertical.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                                Image(
                                    provider = ImageProvider(state.statusLevel.dotDrawableRes),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(8.dp)
                                )
                                Spacer(modifier = GlanceModifier.width(4.dp))
                                Text(
                                    text = state.statusText,
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                            Text(
                                text = state.config,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(8.dp))

                        // 右侧：圆形快捷启停按钮（19dp 胶囊在 36dp 尺寸下自动收为圆形）
                        Box(
                            modifier = GlanceModifier
                                .size(36.dp)
                                .background(ImageProvider(buttonBackground))
                                .then(if (state.busy) GlanceModifier else GlanceModifier.clickable(toggleAction)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(buttonIcon),
                                contentDescription = null,
                                modifier = GlanceModifier.size(18.dp),
                                colorFilter = ColorFilter.tint(buttonIconTint)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 接收器，连接系统 AppWidget 框架与 Glance 极简视图
 */
class AzurPilotQuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AzurPilotQuickWidget()
}
