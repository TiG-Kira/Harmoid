package app.hackeris.hoa.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hackeris.hoa.R
import app.hackeris.hoa.hap.InstalledHap
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Sort
import top.yukonga.miuix.kmp.icon.glass.Starred
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 应用页：已安装 HAP 的网格 + 安装入口 + 搜索 / 排序。
 *
 * 版式照搬 KiTerminal-UX 总览页：两列网格、8dp 间距、16dp 外边距、
 * 16dp 圆角卡片；顶栏走全局玻璃顶栏槽位，底栏由 [HoaMainScreen] 统一渲染。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    items: List<HapItem>,
    busy: Boolean,
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
    onInstallClick: () -> Unit,
    onLaunch: (InstalledHap) -> Unit,
    onUninstall: (InstalledHap) -> Unit,
    onPin: (HapItem) -> Unit,
    onAppInfo: (InstalledHap) -> Unit,
    navBarBottomPadding: Dp,
    onTopBarContent: (@Composable () -> Unit) -> Unit,
    active: Boolean = true,
) {
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSortDialog by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<HapItem?>(null) }

    val scrollBehavior = MiuixScrollBehavior()
    SideEffect {
        if (active) {
            onTopBarContent {
                GlassTopAppBar(
                    title = stringResource(R.string.tab_apps),
                    backdrop = LocalGlassTopAppBarBackdrop.current,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassIconButton(onClick = {
                                searchExpanded = !searchExpanded
                                if (!searchExpanded) searchQuery = ""
                            }) {
                                Icon(
                                    imageVector = MiuixGlassIcons.Search,
                                    contentDescription = stringResource(R.string.action_search),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            GlassIconButton(onClick = { showSortDialog = true }) {
                                Icon(
                                    imageVector = MiuixGlassIcons.Sort,
                                    contentDescription = stringResource(R.string.action_sort),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            GlassIconButton(onClick = onInstallClick) {
                                Icon(
                                    imageVector = MiuixGlassIcons.Add,
                                    contentDescription = stringResource(R.string.btn_install_hap),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    val query = searchQuery.trim().lowercase()
    val filtered = remember(items, query, sortMode) {
        val base = if (query.isEmpty()) items else items.filter { item ->
            item.label.lowercase().contains(query) ||
                item.hap.bundleName.lowercase().contains(query) ||
                item.hap.moduleName.lowercase().contains(query)
        }
        when (sortMode) {
            SortMode.NAME -> base.sortedBy { it.label.lowercase() }
            SortMode.TIME_DESC -> base.sortedByDescending { it.installedAt }
            SortMode.TIME_ASC -> base.sortedBy { it.installedAt }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(
                top = topBarClearance(padding) + 8.dp,
                bottom = navBarBottomPadding + 16.dp,
                start = 16.dp,
                end = 16.dp,
            )
        ) {
            if (searchExpanded) {
                item(key = "search", span = { GridItemSpan(2) }) {
                    SearchBar(
                        modifier = Modifier.padding(vertical = 4.dp),
                        inputField = {
                            InputField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = { },
                                expanded = searchExpanded,
                                onExpandedChange = {
                                    searchExpanded = it
                                    if (!it) searchQuery = ""
                                },
                                label = stringResource(R.string.hint_search_haps),
                            )
                        },
                        expanded = searchExpanded,
                        onExpandedChange = {
                            searchExpanded = it
                            if (!it) searchQuery = ""
                        },
                    ) { }
                }
            }

            // 安装入口：和卡片同一套材质，宽卡占满两列。
            item(key = "install", span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = onInstallClick, onLongClick = {}),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingIcon(icon = MiuixGlassIcons.Add, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (busy) {
                                    stringResource(R.string.btn_extracting)
                                } else {
                                    stringResource(R.string.btn_install_hap)
                                },
                                style = textStyles.title4,
                            )
                            Text(
                                text = stringResource(R.string.hint_install_supports),
                                style = textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(2) }) {
                    EmptyStateCard(
                        title = if (items.isEmpty()) {
                            stringResource(R.string.hint_no_haps_title)
                        } else {
                            stringResource(R.string.hint_no_search_results_title)
                        },
                        description = if (items.isEmpty()) {
                            stringResource(R.string.hint_no_haps)
                        } else {
                            stringResource(R.string.hint_no_search_results)
                        },
                        icon = MiuixGlassIcons.Starred,
                    )
                }
            } else {
                items(items = filtered, key = { "${it.hap.bundleName}.${it.hap.moduleName}" }) { item ->
                    AppTile(
                        item = item,
                        onClick = { onLaunch(item.hap) },
                        onLongClick = { menuTarget = item },
                    )
                }
            }
        }
    }

    menuTarget?.let { target ->
        WindowDialog(
            show = true,
            onDismissRequest = { menuTarget = null },
            title = target.label,
            summary = "${target.hap.bundleName} · ${target.hap.moduleName}",
            content = {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.btn_app_info),
                        summary = target.hap.mainAbility,
                        onClick = {
                            menuTarget = null
                            onAppInfo(target.hap)
                        },
                        startAction = {
                            SettingIcon(icon = MiuixGlassIcons.Info, contentDescription = null)
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.btn_add_to_home),
                        summary = stringResource(R.string.btn_add_to_home_summary),
                        onClick = {
                            menuTarget = null
                            onPin(target)
                        },
                        startAction = {
                            SettingIcon(icon = MiuixGlassIcons.Starred, contentDescription = null)
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.btn_uninstall),
                        summary = stringResource(
                            R.string.dialog_uninstall_msg,
                            target.hap.bundleName,
                            target.hap.moduleName
                        ),
                        onClick = {
                            menuTarget = null
                            onUninstall(target.hap)
                        },
                        startAction = {
                            SettingIcon(icon = MiuixGlassIcons.Delete, contentDescription = null)
                        },
                    )
                }
            }
        )
    }

    if (showSortDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showSortDialog = false },
            title = stringResource(R.string.action_sort),
            summary = stringResource(R.string.sort_dialog_summary),
            content = {
                Column {
                    SortMode.entries.forEach { mode ->
                        ArrowPreference(
                            title = stringResource(mode.labelRes()),
                            summary = stringResource(mode.summaryRes()),
                            onClick = {
                                onSortChange(mode)
                                showSortDialog = false
                            },
                            startAction = {
                                SettingIcon(icon = MiuixGlassIcons.Sort, contentDescription = null)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showSortDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    item: HapItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = item.icon
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.label,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = item.label.take(1).uppercase(),
                        style = textStyles.title2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = item.label,
                style = textStyles.title4,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "v${item.hap.moduleConfig.versionName} · ${item.hap.moduleConfig.type}",
                style = textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
