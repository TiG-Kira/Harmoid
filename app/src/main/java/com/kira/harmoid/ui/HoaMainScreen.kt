package com.kira.harmoid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kira.harmoid.R
import com.kira.harmoid.hap.InstalledHap
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationBarDefaults
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassTopAppBar

/**
 * 主页骨架 —— 结构照搬 KiTerminal-UX 的 `MainScreen`：
 *
 *  - 只有一个全局 [GlassTopAppBar]，内容由当前页通过 `onTopBarContent` 槽位写入，
 *    切页动画期间顶栏不会闪空。
 *  - 底部是浮动玻璃 [GlassNavigationBar]，底部留白由系统手势条高度动态决定。
 *  - 页面内容全屏铺开、穿过顶栏渐变带，靠各自的 `contentPadding` 让出第一屏。
 */
@Composable
fun HoaMainScreen(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    items: List<HapItem>,
    busy: Boolean,
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
    onInstallClick: () -> Unit,
    onLaunch: (InstalledHap) -> Unit,
    onUninstall: (InstalledHap) -> Unit,
    onPin: (HapItem) -> Unit,
    onAppInfo: (InstalledHap) -> Unit,
) {
    // TopAppBarState 按选中页重建：吸顶状态存在 state 里，切页后自然复位。
    val topAppBarState = remember(selectedTab) { TopAppBarState(0f, 0f, 0f) }
    // MiuixScrollBehavior 默认参数每次重组都会新建实例，会把吸顶动画打断；
    // 这里显式 remember 稳定住这三个参数。
    val canScrollAlways: () -> Boolean = remember { val f: () -> Boolean = { true }; f }
    val snapSpec = remember { spring<Float>(stiffness = 2500f) }
    val flingSpec: DecayAnimationSpec<Float> = rememberSplineBasedDecay()
    val scrollBehavior = MiuixScrollBehavior(
        state = topAppBarState,
        canScroll = canScrollAlways,
        snapAnimationSpec = snapSpec,
        flingAnimationSpec = flingSpec,
    )

    // 浮动玻璃底栏的取景层：底栏透过它折射页面内容。
    val glassNavBackdrop = rememberLayerBackdrop()
    val topBarContent = remember {
        mutableStateOf<@Composable () -> Unit>({
            GlassTopAppBar(
                title = stringResource(R.string.tab_apps),
                backdrop = glassNavBackdrop,
                scrollBehavior = scrollBehavior,
            )
        })
    }

    val systemNavBarsHeight = systemNavigationBarHeight()
    val navTotalHeight = GlassNavigationBarDefaults.Height + systemNavBarsHeight

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides glassNavBackdrop) {
                topBarContent.value()
            }
        },
        bottomBar = {
            // AGSL 着色器要 API 33+；低版本回退 Miuix 经典底栏，保证 Android 11/12 可用。
            if (android.os.Build.VERSION.SDK_INT < 33) {
                NavigationBar {
                    NavigationBarItem(
                        icon = Icons.Rounded.Apps,
                        label = stringResource(R.string.tab_apps),
                        selected = selectedTab == 0,
                        onClick = { onTabChange(0) },
                    )
                    NavigationBarItem(
                        icon = Icons.Rounded.Settings,
                        label = stringResource(R.string.tab_settings),
                        selected = selectedTab == 1,
                        onClick = { onTabChange(1) },
                    )
                }
            } else {
                GlassNavigationBar(
                    items = listOf(
                        GlassNavigationItem(
                            icon = Icons.Rounded.Apps,
                            label = stringResource(R.string.tab_apps),
                        ),
                        GlassNavigationItem(
                            icon = Icons.Rounded.Settings,
                            label = stringResource(R.string.tab_settings),
                        ),
                    ),
                    selectedIndex = selectedTab,
                    onSelect = onTabChange,
                    backdrop = glassNavBackdrop,
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, bottom = systemNavBarsHeight),
                )
            }
        }
    ) { padding ->
        // 内容不被顶栏 / 底栏推开：玻璃顶栏自带渐变带，底栏浮在内容之上。
        val topBarClearance = padding.calculateTopPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(glassNavBackdrop)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                label = "HoaTabTransition",
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith
                        fadeOut(animationSpec = tween(150))
                }
            ) { tab ->
                CompositionLocalProvider(LocalTopBarClearance provides topBarClearance) {
                    when (tab) {
                        0 -> AppsScreen(
                            items = items,
                            busy = busy,
                            sortMode = sortMode,
                            onSortChange = onSortChange,
                            onInstallClick = onInstallClick,
                            onLaunch = onLaunch,
                            onUninstall = onUninstall,
                            onPin = onPin,
                            onAppInfo = onAppInfo,
                            navBarBottomPadding = navTotalHeight,
                            onTopBarContent = { topBarContent.value = it },
                            active = tab == selectedTab,
                        )

                        else -> SettingsScreen(
                            sortMode = sortMode,
                            onSortChange = onSortChange,
                            navBarBottomPadding = navTotalHeight,
                            onTopBarContent = { topBarContent.value = it },
                            active = tab == selectedTab,
                        )
                    }
                }
            }
        }
    }
}
