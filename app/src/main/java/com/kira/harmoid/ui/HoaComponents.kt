package com.kira.harmoid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles

/**
 * 顶栏玻璃材质共享 MainScreen 的取景层，各页无需各自持有 Backdrop。
 * 与 KiTerminal-UX 同名同语义，方便两边页面逐行对照。
 */
val LocalGlassTopAppBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 滚动页第一项距离屏幕上沿要留出的空白。
 *
 * 玻璃顶栏自带渐变带遮挡，页面不把内容整体推到栏下，而是让内容从栏底穿过、
 * 只在可滚动内容上加这段 padding：第一屏仍从栏下方开始，往下滚动时内容从
 * 渐变带下面穿过。
 */
val LocalTopBarClearance = compositionLocalOf { 0.dp }

/** [padding] 去掉顶部，供内容要穿过玻璃顶栏的页面使用。 */
@Composable
fun pagePaddingWithoutTop(padding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction),
        end = padding.calculateEndPadding(direction),
        bottom = padding.calculateBottomPadding(),
    )
}

/** 顶栏当前实际遮挡的高度（顶栏收起时它会跟着变小）。 */
@Composable
fun topBarClearance(padding: PaddingValues): Dp = padding.calculateTopPadding()

/** 系统手势条高度：浮动玻璃底栏靠它贴住屏幕安全下沿。 */
@Composable
fun systemNavigationBarHeight(): Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
}

/**
 * 独立页（关于页等不在 Tab 宿主里的页面）滚动内容的 contentPadding：
 * 在 [padding] 之上叠加顶栏避让 [top]，底部/两侧传页面自己的 insets。
 * 与 KiTerminal-UX `standaloneContentPadding` 同语义。
 */
@Composable
fun standaloneContentPadding(
    padding: PaddingValues,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
): PaddingValues = PaddingValues(
    top = top + topBarClearance(padding),
    bottom = bottom,
    start = start,
    end = end,
)

/**
 * 独立页的玻璃顶栏取景层。
 *
 * Tab 宿主之外的页面（关于页等）看不到 MainScreen 的 backdrop，每页自持一层。
 * 取景要加在内容上而不是 Scaffold 上：`contentModifier` 只圈内容，
 * 顶栏留在层外，否则顶栏会采样到包含自己的纹理，折叠重新取景时
 * runtime shader 直接 abort 进程。
 */
class GlassPageBackdrop internal constructor(
    val layer: LayerBackdrop,
) {
    /** What [top.yukonga.miuix.kmp.glass.GlassTopAppBar] samples. */
    val backdrop: Backdrop get() = layer

    /** Marks the page content as what [backdrop] captures. */
    val contentModifier: Modifier = Modifier.layerBackdrop(layer)
}

@Composable
fun rememberGlassPageBackdrop(): GlassPageBackdrop {
    val layer = rememberLayerBackdrop()
    return remember(layer) { GlassPageBackdrop(layer) }
}

/**
 * 设置项的统一数据形状，[SettingsGroupCard] 按它渲染成一整张卡。
 * 与 KiTerminal-UX 的 `SettingItem` 字段一一对应。
 */
data class SettingItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val action: () -> Unit = {},
    val hasSwitch: Boolean = false,
    val switchValue: Boolean = false,
    val onSwitchChange: (Boolean) -> Unit = {},
)

/** 40dp 的圆角底色图标块 —— 设置页 / 列表里的统一图标容器。 */
@Composable
fun SettingIcon(icon: ImageVector, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = rememberVectorPainter(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}

/** 一组设置项合成一张 16dp 圆角卡（Miuix Card + Preference 行）。 */
@Composable
fun SettingsGroupCard(items: List<SettingItem>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column {
            items.forEach { item ->
                if (item.hasSwitch) {
                    SwitchPreference(
                        title = item.title,
                        summary = item.description,
                        checked = item.switchValue,
                        onCheckedChange = item.onSwitchChange,
                        startAction = {
                            SettingIcon(item.icon, contentDescription = item.title)
                        }
                    )
                } else {
                    ArrowPreference(
                        title = item.title,
                        summary = item.description,
                        onClick = item.action,
                        startAction = {
                            SettingIcon(item.icon, contentDescription = item.title)
                        }
                    )
                }
            }
        }
    }
}

/** 页内空状态：一张卡 + 居中说明，避免空白页看起来像「加载失败」。 */
@Composable
fun EmptyStateCard(title: String, description: String, icon: ImageVector) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingIcon(icon, contentDescription = null)
            Text(
                text = title,
                style = textStyles.title3,
            )
            Text(
                text = description,
                style = textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 一行小标题 + 右侧可选操作，用于卡片区之间的分隔。 */
@Composable
fun SectionHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        trailing?.invoke()
    }
}

/** 圆角胶囊小标签（版本号 / 模块类型）。 */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
