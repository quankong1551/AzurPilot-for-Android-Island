package com.azurpilot.ghio

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AppThemeState
import com.azurpilot.ghio.ui.AppRoot
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * App 唯一的壳 Activity：承载 Compose UI，并处理窗口层面的平台适配
 *
 * 职责：
 * - splash 屏保持到设置异步加载完成才放行首帧
 * - 挖孔屏 edge-to-edge 适配（见 onCreate 内的 cutout 模式说明）
 * - 挂机 / 工具运行期间保持屏幕常亮：STARTED 期间跟随运行状态加 / 清
 *   FLAG_KEEP_SCREEN_ON；退到后台或用户手动息屏时不阻止锁屏
 * - 明暗切换时同步状态栏 / 导航栏样式，并把最终结果播给 [AppThemeState]，
 *   供悬浮窗这类拿不到 Configuration 的独立窗口读取
 *
 * The app's sole shell Activity: hosts the Compose UI and handles
 * window-level platform adaptations.
 *
 * Responsibilities:
 * - hold the splash screen until settings finish loading asynchronously
 * - display-cutout edge-to-edge adaptation (see the cutout-mode note in
 *   onCreate)
 * - keep the screen on while an automation session or tool runs: while
 *   STARTED, FLAG_KEEP_SCREEN_ON follows the run state; backgrounding or a
 *   manual screen-off still allows lock
 * - on dark-theme changes, refresh the status / navigation bar styles and
 *   publish the outcome to [AppThemeState] for standalone windows such as
 *   overlays, which cannot read Configuration themselves
 */
class MainActivity : AppCompatActivity() {

    private val appSettings: AppSettingsManager by inject()
    private val runController: AzurPilotRunController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !appSettings.loaded.value }
        super.onCreate(savedInstanceState)

        // 挖孔屏：edge-to-edge 下允许内容画进孔区两侧，**避让**由 Compose 的 displayCutout
        // insets 做（默认模式在横屏会把整窗从孔洞处挤开，出现一条黑边，虚拟屏页面尤其难看）
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 挂机/工具运行期间保持屏幕唤醒（App 退到后台或用户手动息屏时仍允许锁屏）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runController.state.collect { s ->
                    if (s.runnerAlive || s.toolAlive) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            AppRoot(
                onDarkThemeChanged = { dark ->
                    applyEdgeToEdge(dark)
                    // 悬浮窗是独立窗口，拿不到这里的 Configuration，只能读播出来的结果
                    AppThemeState.publish(dark)
                },
            )
        }
    }

    private fun applyEdgeToEdge(darkMode: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Q+ 不关掉对比度强制，系统会在透明导航栏下面垫一层半透明 scrim
            window.isNavigationBarContrastEnforced = false
        }
    }
}
