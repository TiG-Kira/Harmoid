package com.kira.harmoid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

import com.kira.harmoid.compose.NavigationHelper
import com.kira.harmoid.ui.AboutScreen
import com.kira.harmoid.ui.theme.HoaTheme

/**
 * 关于页 —— 独立 Activity（与 KiTerminal-UX 的 `AboutActivity` 对齐）。
 *
 * 早先是在设置页内部用 `showAboutPage` 状态把 [AboutScreen] 盖上去，
 * 但关于页自带一整套边到边 + 呼吸渐变背景 + 玻璃顶栏，塞进设置页的
 * Scaffold 里会和宿主的系统栏 / 滚动行为打架。拆成独立 Activity 之后：
 * 窗口背景、状态栏、返回栈全部由自己管，设置页只负责 `startActivity`。
 */
class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边到边：状态栏 / 导航栏透明，页面自己画背景并决定避让。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContent {
            // 与 MainActivity 同一约定：根 composition 必须提供
            // LocalNavigationEventDispatcherOwner，否则关于页里 Miuix 的
            // NavigationBackHandler（返回手势）会抛 IllegalStateException。
            val navDispatcher = remember { NavigationHelper.createDispatcher() }
            val navDispatcherOwner = remember { NavigationHelper.createOwner(navDispatcher) }
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                HoaTheme {
                    AboutScreen(onBack = { finish() })
                }
            }
        }
    }
}
