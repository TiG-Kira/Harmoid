package com.kira.harmoid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.kira.harmoid.compose.NavigationHelper
import com.kira.harmoid.hap.ModuleConfig
import com.kira.harmoid.logging.LogWriter
import com.kira.harmoid.ui.HoaMainScreen
import com.kira.harmoid.ui.HoaViewModel
import com.kira.harmoid.ui.InstallPreview
import com.kira.harmoid.ui.SortMode
import com.kira.harmoid.ui.theme.HoaTheme
import com.kira.harmoid.ui.theme.ThemePrefs
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 主页：应用页 + 设置页两个 Tab 的 Compose 外壳。
 *
 * Activity 只负责「壳」的职责 —— 窗口边到边、Intent 路由、文件选择、广播接收；
 * 列表状态与安装动作都在 [HoaViewModel] 里。
 */
class MainActivity : ComponentActivity() {

    private var pendingUri: Uri? = null
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 安装完成后再刷新一次列表，覆盖 HDC / 外部进程写入的情况。
            currentRefresh?.invoke()
        }
    }

    private var currentRefresh: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePrefs.init(this)
        // 边到边：状态栏 / 导航栏透明，内容自己决定怎么避让。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        LogWriter.e(TAG, "========== HOA MainActivity START ==========")

        pendingUri = extractHapUri(intent)

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            installReceiver,
            IntentFilter("com.kira.harmoid.HAP_INSTALLED"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            // KiTerminal-UX 约定：根 composition 必须提供 LocalNavigationEventDispatcherOwner，
            // 否则 Miuix WindowDialog（首次启动免责声明、安装预览等）内部的
            // NavigationBackHandler 会抛 IllegalStateException 直接闪退。
            val navDispatcher = remember { NavigationHelper.createDispatcher() }
            val navDispatcherOwner = remember { NavigationHelper.createOwner(navDispatcher) }
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                HoaTheme {
                    HoaRoot(
                        initialUri = pendingUri,
                        onRefreshHandlerReady = { currentRefresh = it },
                    )
                }
            }
        }
        if (intent.hasExtra("BUNDLE_NAME")) handleShortcutOrOpen(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutOrOpen(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(installReceiver)
        } catch (_: Exception) {
        }
        currentRefresh = null
        currentOpenUri = null
    }

    /** 快捷方式启动（MIUI 可能把 Intent 重定向到 MainActivity）。 */
    private fun handleShortcutOrOpen(intent: Intent) {
        val bundle = intent.getStringExtra("BUNDLE_NAME")
        if (bundle != null) {
            intent.removeExtra("BUNDLE_NAME") // Do not relaunch on host Activity recreation.
            val module = intent.getStringExtra("MODULE_NAME") ?: "entry"
            val ability = intent.getStringExtra("ABILITY_NAME") ?: "EntryAbility"
            try {
                if (ProcessSlotManager.launchHap(this, bundle, module, ability, intent.getStringExtra("params")) < 0) {
                    android.widget.Toast.makeText(this, getString(R.string.dialog_no_slots_msg,
                        ProcessSlotManager.MAX_SLOTS), android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                LogWriter.e(TAG, "Shortcut launch failed", e)
                android.widget.Toast.makeText(this, getString(R.string.toast_error_fmt, e.message),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        extractHapUri(intent)?.let { uri ->
            // Activity 已在前台时，直接交给 ViewModel 解析。
            currentOpenUri?.invoke(uri)
        }
    }

    private var currentOpenUri: ((Uri) -> Unit)? = null

    /** Compose 侧注册「外部打开 HAP」的处理函数。 */
    fun setOpenUriHandler(handler: (Uri) -> Unit) {
        currentOpenUri = handler
    }

    private fun extractHapUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "Harmoid.Main"
    }
}

@Composable
private fun HoaRoot(
    initialUri: Uri?,
    onRefreshHandlerReady: ((() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val vm: HoaViewModel = viewModel()
    val items by vm.items.collectAsState()
    val busy by vm.busy.collectAsState()
    val preview by vm.preview.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val prefs = remember { context.getSharedPreferences("hoa_prefs", Context.MODE_PRIVATE) }
    var sortMode by remember {
        mutableStateOf(
            runCatching { SortMode.valueOf(prefs.getString(KEY_SORT, SortMode.TIME_ASC.name)!!) }
                .getOrDefault(SortMode.TIME_ASC)
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        onRefreshHandlerReady { vm.refresh() }
        vm.refresh()
    }

    // 一次性提示（安装结果 / 槽位已满等）
    LaunchedEffect(Unit) {
        vm.events.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.previewFromUri(uri)
    }

    LaunchedEffect(Unit) {
        // 从文件管理器 / 分享进来的 HAP
        val main = context as? MainActivity
        main?.let { activity ->
            activity.setOpenUriHandler { uri -> vm.previewFromUri(uri) }
        }
        initialUri?.let { vm.previewFromUri(it) }
    }

    // 首次启动免责声明
    var showDisclaimer by remember {
        mutableStateOf(!prefs.getBoolean(KEY_FIRST_LAUNCH, false))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HoaMainScreen(
            selectedTab = selectedTab,
            onTabChange = { selectedTab = it },
            items = items,
            busy = busy,
            sortMode = sortMode,
            onSortChange = { mode ->
                sortMode = mode
                prefs.edit().putString(KEY_SORT, mode.name).apply()
            },
            onInstallClick = { picker.launch(arrayOf("*/*")) },
            onLaunch = { hap -> vm.launch(hap) },
            onUninstall = { hap -> vm.uninstall(hap) },
            onPin = { item -> vm.pinShortcut(item) },
            onAppInfo = { hap ->
                context.startActivity(
                    Intent(context, HapDetailActivity::class.java).apply {
                        putExtra("BUNDLE_NAME", hap.bundleName)
                        putExtra("MODULE_NAME", hap.moduleName)
                        putExtra("ABILITY_NAME", hap.mainAbility)
                    }
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 96.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(state = snackbarHostState)
        }
    }

    preview?.let { pending ->
        InstallPreviewDialog(
            preview = pending,
            onConfirm = { vm.confirmInstall() },
            onDismiss = { vm.dismissPreview() },
        )
    }

    if (showDisclaimer) {
        WindowDialog(
            show = true,
            onDismissRequest = { },
            title = stringResource(R.string.first_launch_title),
            summary = stringResource(R.string.first_launch_msg),
            content = {
                TextButton(
                    text = stringResource(R.string.first_launch_btn),
                    onClick = {
                        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
                        showDisclaimer = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        )
    }
}

@Composable
private fun InstallPreviewDialog(
    preview: InstallPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val config: ModuleConfig = preview.config
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_install_preview_title),
        summary = config.bundleName,
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                PreviewRow(stringResource(R.string.label_bundle_name), config.bundleName.ifEmpty { "—" })
                PreviewRow(stringResource(R.string.label_module), "${config.name} (${config.type})")
                PreviewRow(
                    stringResource(R.string.label_version),
                    "${config.versionName} (${config.versionCode})"
                )
                PreviewRow(
                    stringResource(R.string.label_sdk),
                    "target=${config.targetApiVersion}  min=${config.minApiVersion}"
                )
                if (config.requestPermissions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.label_permissions)} (${config.requestPermissions.size})",
                        style = textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    config.requestPermissions.forEach { permission ->
                        Text(
                            text = "• $permission",
                            style = textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (config.abilities.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.label_abilities)} (${config.abilities.size})",
                        style = textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    config.abilities.forEach { ability ->
                        Text(
                            text = "• ${ability.name} (${ability.type})",
                            style = textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.btn_install),
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    )
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private const val KEY_SORT = "main_sort_mode"
private const val KEY_FIRST_LAUNCH = "first_launch_done"
