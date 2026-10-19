package com.kira.harmoid.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.kira.harmoid.BuildConfig
import com.kira.harmoid.R
import com.kira.harmoid.UpdateChecker
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页（自 Termux Ultra / KiTerminal-UX 的 HyperCeiler 风格关于页 1:1 移植）：
 * 呼吸渐变背景 + 玻璃顶栏 + 应用信息/设备信息/开发者/检查更新/版本信息/许可卡片。
 *
 * 与 Termux Ultra 版的差异（Harmoid 适配）：
 *  - 更新检查走 Harmoid 自己的 GitHub 仓库（TiG-Kira/Harmoid）；
 *  - 版本信息卡保留「基于 HOA」署名，其余文字全部换成 Harmoid；
 *  - 去掉了 Termux 专有的 beta 通道开关 / APK 内下载 / Workflow CLI 标记；
 *  - 贡献者保留开发者本人（Termux Ultra 的贡献者不属于 Harmoid）。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val systemNavBarsHeight = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateSummary by remember { mutableStateOf<String?>(null) }
    var releaseIsPrerelease by remember { mutableStateOf<Boolean?>(null) }

    val currentVersion = remember { BuildConfig.VERSION_NAME }
    val currentBuild = remember { BuildConfig.VERSION_CODE }

    // 呼吸渐变动画 (FeatureCenterCard 风格)
    val infiniteTransition = rememberInfiniteTransition(label = "breathingGradient")
    val gradientFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradientBreathing"
    )

    // 进来先拉一次 release 元信息，仅用于头部 Beta / 内测标记
    LaunchedEffect(Unit) {
        scope.launch {
            val meta = withContext(Dispatchers.IO) { UpdateChecker.fetchReleaseMeta() }
            releaseIsPrerelease = meta?.isPrerelease
        }
    }

    val darkTheme = isSystemInDarkTheme()

    // 呼吸渐变颜色 (FeatureCenterCard 风格)
    val lightGradient = Brush.verticalGradient(
        colors = listOf(
            lerp(Color(0xFFFFE5EF), Color(0xFFF9DBE5), gradientFraction),
            lerp(Color(0xFFF7BAD1), Color(0xFFBCC1FF), gradientFraction),
            lerp(Color(0xFFA3A5F9), Color(0xFF8EAFFF), gradientFraction)
        )
    )
    val darkGradient = Brush.verticalGradient(
        colors = listOf(
            lerp(Color(0xFF330FE0), Color(0xFF934CBC), gradientFraction),
            lerp(Color(0xFF4C238C), Color(0xFF9E35AA), gradientFraction),
            lerp(Color(0xFF1C28D3), Color(0xFF0033C6), gradientFraction)
        )
    )
    // 滚动状态跟踪
    val listState = rememberLazyListState()
    val headerHeightPx = with(density) { 100.dp.toPx() }
    var scrollFraction by remember { mutableStateOf(0f) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset ?: 0 }
            .collect { offset ->
                val firstIndex = listState.firstVisibleItemIndex
                scrollFraction = if (firstIndex == 0) {
                    (-offset.toFloat() / headerHeightPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
            }
    }

    // 页面遮罩透明度动画 (亮色: surface, 暗色: surface)
    val pageMaskAlphaAnim by animateFloatAsState(
        targetValue = scrollFraction,
        label = "pageMaskAlpha"
    )

    // 头部卡片淡出动画
    val headerAlphaAnim by animateFloatAsState(
        targetValue = 1f * (1f - scrollFraction),
        label = "headerAlpha"
    )

    // 其他卡片: 始终完全不透明 (背景穿透只在 header)
    val cardsAlphaAnim by animateFloatAsState(
        targetValue = 1f,
        label = "cardsAlpha"
    )

    // Dual-track background: API 33+ RuntimeShader animated, older Brush fallback
    val useShaderBg = android.os.Build.VERSION.SDK_INT >= 31
    var bgController by remember { mutableStateOf<AboutBgEffect.ShaderController?>(null) }
    var bgDarkTheme by remember { mutableStateOf(darkTheme) }

    Box(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
    ) {
        if (useShaderBg) {
            AndroidView(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (1f - scrollFraction) * (if (darkTheme) 0.5f else 1f) }.blur(120.dp).zIndex(-1f),
                factory = { ctx ->
                    android.view.View(ctx).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        val ctl = AboutBgEffect.createFor(this, darkTheme)
                        bgController = ctl
                        bgDarkTheme = darkTheme
                        ctl?.start()
                    }
                },
                update = { v ->
                    if (bgDarkTheme != darkTheme) {
                        bgController?.updateParams(AboutBgEffect.getParams(darkTheme))
                        bgDarkTheme = darkTheme
                    }
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (1f - scrollFraction) * (if (darkTheme) 0.5f else 1f) }
                    .background(if (darkTheme) darkGradient else lightGradient)
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                GlassTopAppBar(
                    title = stringResource(R.string.about_preference_title),
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
                state = listState,
                modifier = Modifier
                    .then(glassPage.contentModifier)
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = systemNavBarsHeight + 26.dp),
                verticalArrangement = Arrangement.Top
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = headerAlphaAnim }
                            .padding(top = 60.dp, bottom = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val headerFg = if (darkTheme) Color.White else Color(0xFF333333)
                        val fgInt = AndroidColor.argb(
                            (headerFg.alpha * 255).toInt(),
                            (headerFg.red * 255).toInt(),
                            (headerFg.green * 255).toInt(),
                            (headerFg.blue * 255).toInt()
                        )
                        val appIcon = remember(darkTheme) {
                            val orig = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
                            orig?.let { bm ->
                                val copy = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                                val pixels = IntArray(copy.width * copy.height)
                                copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
                                for (i in pixels.indices) {
                                    val c = pixels[i]
                                    val r = (c shr 16) and 0xFF
                                    val g = (c shr 8) and 0xFF
                                    val b = c and 0xFF
                                    val a = (c shr 24) and 0xFF
                                    val brightness = (r + g + b) / 3f
                                    if (a > 128 && brightness > 180f) {
                                        // 白色像素 → 透明，让渐变背景透出
                                        pixels[i] = 0x00000000
                                    } else if (a > 128) {
                                        // 其他非透明像素 → 染成和文字同色
                                        pixels[i] = fgInt
                                    }
                                }
                                copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
                                copy.asImageBitmap().let { BitmapPainter(it) }
                            }
                        }
                        if (appIcon != null) {
                            Image(
                                painter = appIcon,
                                contentDescription = "Logo",
                                modifier = Modifier.size(100.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.mipmap.ic_launcher),
                                contentDescription = "Logo",
                                modifier = Modifier.size(60.dp),
                                tint = headerFg
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = TextStyle(
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black,
                                color = headerFg
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = currentVersion,
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    color = headerFg
                                )
                            )
                            when (releaseIsPrerelease) {
                                true -> BetaTag()
                                false -> Unit                      // 正式版，无标记
                                null -> InternalBuildTag()          // 元信息还没拉到/仓库私有
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InfoRow(
                                title = stringResource(R.string.device_model),
                                value = android.os.Build.MODEL
                            )
                            InfoRow(
                                title = stringResource(R.string.android_version),
                                value = android.os.Build.VERSION.RELEASE
                            )
                            InfoRow(
                                title = stringResource(R.string.kernel_version),
                                value = android.os.Build.DISPLAY
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.developer_name),
                            summary = stringResource(R.string.about_developer_summary),
                            onClick = {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/TiG-Kira")
                                )
                                context.startActivity(intent)
                            },
                            startAction = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = "https://github.com/TiG-Kira.png",
                                        contentDescription = "Developer Avatar",
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    val summary = when {
                        checkingUpdate -> stringResource(R.string.checking_updates)
                        updateSummary != null -> updateSummary!!
                        else -> stringResource(R.string.about_check_updates_desc)
                    }
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.check_updates),
                            summary = summary,
                            onClick = {
                                if (!checkingUpdate) {
                                    checkingUpdate = true
                                    updateSummary = null
                                    scope.launch {
                                        UpdateChecker.check(context) { result ->
                                            checkingUpdate = false
                                            updateSummary = when (result) {
                                                is UpdateChecker.Result.UpdateAvailable ->
                                                    context.getString(R.string.new_version_available)
                                                is UpdateChecker.Result.UpToDate ->
                                                    context.getString(R.string.up_to_date)
                                                is UpdateChecker.Result.Error ->
                                                    context.getString(R.string.about_check_updates_desc)
                                            }
                                            UpdateChecker.showResult(context, result)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = stringResource(R.string.harmoid_version),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = currentVersion,
                                    style = TextStyle(
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                )
                            }
                            Text(
                                text = stringResource(R.string.about_build_fmt, currentBuild) + "\n" +
                                    stringResource(R.string.about_based_on_hoa, UPSTREAM_HOA_VERSION) + "\n" +
                                    stringResource(R.string.about_runtime_info),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.about_license),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = stringResource(R.string.about_repo_harmoid),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                ),
                                modifier = Modifier.clickable {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://github.com/TiG-Kira/Harmoid")
                                        )
                                    )
                                }
                            )
                            Text(
                                text = stringResource(R.string.about_repo_hoa),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                ),
                                modifier = Modifier.clickable {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://github.com/harmony-on-android/HOA")
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** prebuilts 抽自哪个上游 HOA Release（pack_prebuilts.sh 的来源）。 */
private const val UPSTREAM_HOA_VERSION = "26.7.9.1"

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
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
private fun BetaTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.tag_beta),
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun InternalBuildTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.tag_internal),
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        )
    }
}
