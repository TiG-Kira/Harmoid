package app.hackeris.hoa.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * HOA 的主题外壳 —— 与 KiTerminal-UX 的 `KiTerminalTheme` 同一套实现：
 * Miuix 主题 + 可选 Material You 动态取色 + 系统栏图标明暗跟随。
 *
 * 视觉上不做任何「HOA 特调」：颜色、字体、圆角、材质一律由 Miuix 主题决定，
 * 这样两边的控件看起来是同一个系统出来的。
 */
@Composable
fun HoaTheme(
    statusBarColor: Color = Color.Unspecified,
    navigationBarColor: Color = Color.Unspecified,
    manageSystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // 必须在订阅前同步加载落盘值，否则开了动态取色会先闪一帧默认配色。
    ThemePrefs.init(context)
    val materialYou by ThemePrefs.materialYouEnabled.collectAsState()

    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode && manageSystemBars) {
        SideEffect {
            val window = (view.context as Activity).window
            if (statusBarColor != Color.Unspecified) {
                @Suppress("DEPRECATION")
                window.statusBarColor = statusBarColor.toArgb()
            }
            if (navigationBarColor != Color.Unspecified) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = navigationBarColor.toArgb()
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val colorSchemeMode = if (materialYou && android.os.Build.VERSION.SDK_INT >= 31) {
        ColorSchemeMode.MonetSystem
    } else {
        ColorSchemeMode.System
    }

    MiuixTheme(
        // ThemeController 的 colorSchemeMode 是构造参数且只读，切换必须重建实例。
        controller = remember(colorSchemeMode) { ThemeController(colorSchemeMode = colorSchemeMode) },
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    )
}
