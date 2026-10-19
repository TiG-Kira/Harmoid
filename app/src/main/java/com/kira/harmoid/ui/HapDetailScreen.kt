package com.kira.harmoid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kira.harmoid.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用信息页（HapDetail）—— Miuix + Compose 版。
 *
 * 旧版是 View/XML（activity_hap_detail.xml + LinearLayout 动态塞 TextView），
 * 视觉与 Miuix 设计系统完全脱节。本页对齐 AboutScreen 的结构：
 * 独立 Activity + 玻璃顶栏 + Card 分组 + 底部启动按钮。
 */
data class HapDetailUiInfo(
    val label: String,
    val bundleName: String,
    val moduleName: String,
    val moduleType: String,
    val vendor: String,
    val versionName: String,
    val versionCode: String,
    val compileSdk: String,
    val targetApi: String,
    val minApi: String,
    val virtualMachine: String,
    val size: String,
    val permissions: List<String>,
    val abilities: List<String>,
    val pages: List<String>,
    val nativeLibs: List<String>,   // 已格式化 "name — 12.3 MB"
    val nativeLibsTotal: String,
)

@Composable
fun HapDetailScreen(
    info: HapDetailUiInfo?,
    onBack: () -> Unit,
    onLaunch: () -> Unit,
) {
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val density = LocalDensity.current
    val systemNavBarsHeight = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.dialog_app_info_title),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .then(glassPage.contentModifier)
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = standaloneContentPadding(padding, bottom = systemNavBarsHeight + 26.dp),
            verticalArrangement = Arrangement.Top
        ) {
            if (info == null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.toast_module_not_found, "", ""),
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }
            } else {
                item { HeaderCard(info) }
                item { Spacer(modifier = Modifier.height(12.dp)) }
                item {
                    DetailCard(title = stringResource(R.string.label_module)) {
                        InfoRow(stringResource(R.string.label_module), info.moduleName)
                        InfoRow("Type", info.moduleType)
                        if (info.vendor.isNotBlank()) {
                            InfoRow(stringResource(R.string.label_vendor), info.vendor)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
                item {
                    DetailCard(title = stringResource(R.string.label_version)) {
                        InfoRow(
                            stringResource(R.string.label_version),
                            "${info.versionName} (${info.versionCode})"
                        )
                        if (info.compileSdk.isNotBlank()) {
                            InfoRow("Compile SDK", info.compileSdk)
                        }
                        InfoRow("Target API", info.targetApi)
                        InfoRow("Min API", info.minApi)
                        if (info.virtualMachine.isNotBlank()) {
                            InfoRow("VM", info.virtualMachine)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
                item {
                    DetailCard(title = stringResource(R.string.label_size)) {
                        InfoRow(stringResource(R.string.label_size), info.size)
                    }
                }
                if (info.permissions.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                    item {
                        DetailCard(
                            title = stringResource(R.string.label_permissions) +
                                " (${info.permissions.size})"
                        ) {
                            info.permissions.forEach { BulletRow(it) }
                        }
                    }
                }
                if (info.abilities.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                    item {
                        DetailCard(
                            title = stringResource(R.string.label_abilities) +
                                " (${info.abilities.size})"
                        ) {
                            info.abilities.forEach { BulletRow(it) }
                        }
                    }
                }
                if (info.pages.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                    item {
                        DetailCard(
                            title = stringResource(R.string.label_pages) +
                                " (${info.pages.size})"
                        ) {
                            info.pages.forEach { BulletRow(it) }
                        }
                    }
                }
                if (info.nativeLibs.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                    item {
                        DetailCard(title = "Native Libraries (${info.nativeLibs.size})") {
                            info.nativeLibs.forEach { BulletRow(it) }
                            InfoRow("Total", info.nativeLibsTotal)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
                item {
                    Button(
                        onClick = onLaunch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_launch_test_hap),
                            style = TextStyle(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(info: HapDetailUiInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = info.label,
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
            )
            Text(
                text = info.bundleName,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            )
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary
                )
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            ),
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
        )
    }
}

@Composable
private fun BulletRow(text: String) {
    Text(
        text = "•  $text",
        style = TextStyle(
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface
        )
    )
}
