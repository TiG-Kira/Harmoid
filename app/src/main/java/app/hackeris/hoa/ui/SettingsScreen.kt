package app.hackeris.hoa.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.hackeris.hoa.BuildConfig
import app.hackeris.hoa.R
import app.hackeris.hoa.device.DeviceProfile
import app.hackeris.hoa.device.DeviceProfileStore
import app.hackeris.hoa.hdc.DEFAULT_PORT
import app.hackeris.hoa.hdc.HdcService
import app.hackeris.hoa.logging.LogCollector
import app.hackeris.hoa.ui.theme.ThemePrefs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Usb
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置页：原先挤在主页菜单里的所有设置都搬到这里。
 *
 * 版式与 KiTerminal-UX 设置页一致：`SmallTitle` 分组标题 + 16dp 圆角 Card，
 * 卡内是 Miuix 的 `SwitchPreference` / `ArrowPreference` 行。
 */
@Composable
fun SettingsScreen(
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
    navBarBottomPadding: Dp,
    onTopBarContent: (@Composable () -> Unit) -> Unit,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
    }

    val prefs = remember { context.getSharedPreferences("hoa_prefs", Context.MODE_PRIVATE) }
    var hdcRunning by remember { mutableStateOf(isHdcRunning(context)) }
    var hdcPort by remember { mutableStateOf(prefs.getInt(HdcService.KEY_PORT, DEFAULT_PORT)) }
    var profile by remember { mutableStateOf(DeviceProfileStore.load(context)) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showDeviceEditor by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateHasNewVersion by remember { mutableStateOf(false) }

    val materialYou by ThemePrefs.materialYouEnabled.collectAsState()
    LaunchedEffect(Unit) {
        ThemePrefs.init(context)
        hdcRunning = isHdcRunning(context)
    }

    val scrollBehavior = MiuixScrollBehavior()
    SideEffect {
        if (active) {
            onTopBarContent {
                GlassTopAppBar(
                    title = stringResource(R.string.tab_settings),
                    backdrop = LocalGlassTopAppBarBackdrop.current,
                    scrollBehavior = scrollBehavior,
                )
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = navBarBottomPadding),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnackbarHost(state = snackbarHostState)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topBarClearance(padding) + 4.dp,
                bottom = navBarBottomPadding + 16.dp,
            )
        ) {
            item(key = "section_device") {
                SmallTitle(text = stringResource(R.string.settings_section_device))
            }
            item(key = "card_device") {
                SettingsGroupCard(
                    listOf(
                        SettingItem(
                            title = stringResource(R.string.settings_device_model),
                            description = profile.displayName,
                            icon = Icons.Rounded.PhoneAndroid,
                            action = { showDevicePicker = true },
                        ),
                        SettingItem(
                            title = stringResource(R.string.settings_device_custom),
                            description = stringResource(R.string.settings_device_custom_summary),
                            icon = Icons.Rounded.SettingsEthernet,
                            action = { showDeviceEditor = true },
                        ),
                        SettingItem(
                            title = stringResource(R.string.settings_device_restore),
                            description = stringResource(R.string.settings_device_restore_summary),
                            icon = Icons.Rounded.Sync,
                            action = {
                                val system = DeviceProfile.system()
                                DeviceProfileStore.save(context, system)
                                profile = system
                                toast(context.getString(R.string.settings_device_restored))
                            },
                        ),
                    )
                )
            }

            item(key = "section_app") {
                SmallTitle(text = stringResource(R.string.settings_section_app))
            }
            item(key = "card_app") {
                SettingsGroupCard(
                    listOf(
                        SettingItem(
                            title = stringResource(R.string.settings_default_sort),
                            description = stringResource(sortMode.labelRes()),
                            icon = Icons.Rounded.SortByAlpha,
                            action = { showSortDialog = true },
                        ),
                        SettingItem(
                            title = stringResource(R.string.settings_permissions),
                            description = stringResource(R.string.settings_permissions_summary),
                            icon = Icons.Rounded.Security,
                            action = { showPermissionsDialog = true },
                        ),
                    )
                )
            }

            item(key = "section_connect") {
                SmallTitle(text = stringResource(R.string.settings_section_connect))
            }
            item(key = "card_connect") {
                SettingsGroupCard(
                    listOf(
                        SettingItem(
                            title = stringResource(R.string.settings_hdc),
                            description = if (hdcRunning) {
                                stringResource(R.string.settings_hdc_running_fmt, hdcPort)
                            } else {
                                stringResource(R.string.settings_hdc_stopped)
                            },
                            icon = Icons.Rounded.Usb,
                            hasSwitch = true,
                            switchValue = hdcRunning,
                            onSwitchChange = { enabled ->
                                if (enabled) {
                                    startHdcService(context, hdcPort)
                                    hdcRunning = true
                                    toast(context.getString(R.string.toast_hdc_started, hdcPort))
                                } else {
                                    context.stopService(Intent(context, HdcService::class.java))
                                    hdcRunning = false
                                    toast(context.getString(R.string.toast_hdc_stopped))
                                }
                            },
                        ),
                        SettingItem(
                            title = stringResource(R.string.settings_hdc_port),
                            description = hdcPort.toString(),
                            icon = Icons.Rounded.SettingsEthernet,
                            action = { showPortDialog = true },
                        ),
                    )
                )
            }

            item(key = "section_appearance") {
                SmallTitle(text = stringResource(R.string.settings_section_appearance))
            }
            item(key = "card_appearance") {
                SettingsGroupCard(
                    listOf(
                        SettingItem(
                            title = stringResource(R.string.settings_material_you),
                            description = stringResource(R.string.settings_material_you_summary),
                            icon = Icons.Rounded.Palette,
                            hasSwitch = true,
                            switchValue = materialYou,
                            onSwitchChange = { ThemePrefs.setMaterialYouEnabled(context, it) },
                        ),
                    )
                )
            }

            item(key = "section_about") {
                SmallTitle(text = stringResource(R.string.settings_section_about))
            }
            item(key = "card_about") {
                SettingsGroupCard(
                    listOf(
                        SettingItem(
                            title = stringResource(R.string.settings_check_update),
                            description = stringResource(R.string.settings_check_update_summary),
                            icon = Icons.Rounded.AccessTime,
                            action = {
                                app.hackeris.hoa.UpdateChecker.check(context) { result ->
                                    updateMessage = describeUpdate(context, result)
                                    updateHasNewVersion = result is app.hackeris.hoa.UpdateChecker.Result.UpdateAvailable
                                }
                            },
                        ),
                        SettingItem(
                            title = stringResource(R.string.settings_feedback),
                            description = stringResource(R.string.settings_feedback_summary),
                            icon = Icons.Rounded.BugReport,
                            action = { showFeedbackDialog = true },
                        ),
                        SettingItem(
                            title = stringResource(R.string.settings_about),
                            description = stringResource(R.string.settings_version_fmt, BuildConfig.VERSION_NAME),
                            icon = Icons.Rounded.Info,
                            action = { showAboutDialog = true },
                        ),
                    )
                )
            }

            item(key = "footer") {
                Text(
                    text = stringResource(R.string.settings_footer),
                    style = textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                )
            }
        }
    }

    if (showSortDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showSortDialog = false },
            title = stringResource(R.string.settings_default_sort),
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
                            startAction = { SettingIcon(Icons.Rounded.SortByAlpha, null) },
                        )
                    }
                }
            }
        )
    }

    if (showPortDialog) {
        var portText by remember { mutableStateOf(hdcPort.toString()) }
        WindowDialog(
            show = true,
            onDismissRequest = { showPortDialog = false },
            title = stringResource(R.string.dialog_hdc_port_title),
            summary = stringResource(R.string.dialog_hdc_port_msg),
            content = {
                Column {
                    TextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = stringResource(R.string.settings_hdc_port),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showPortDialog = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        TextButton(
                            text = stringResource(R.string.save),
                            onClick = {
                                val port = portText.toIntOrNull()
                                if (port != null && port in 1024..65535) {
                                    hdcPort = port
                                    prefs.edit().putInt(HdcService.KEY_PORT, port).commit()
                                    if (hdcRunning) {
                                        context.stopService(Intent(context, HdcService::class.java))
                                        startHdcService(context, port)
                                    }
                                    showPortDialog = false
                                } else {
                                    toast(context.getString(R.string.toast_hdc_invalid_port))
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        )
    }

    if (showDevicePicker) {
        WindowDialog(
            show = true,
            onDismissRequest = { showDevicePicker = false },
            title = stringResource(R.string.settings_device_model),
            summary = stringResource(R.string.settings_device_model_summary),
            content = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val options = remember { DeviceProfile.PRESETS + DeviceProfile.system() }
                    options.forEach { candidate ->
                        ArrowPreference(
                            title = candidate.displayName,
                            summary = if (candidate.isSystem) {
                                stringResource(R.string.settings_device_follow_host)
                            } else {
                                "${candidate.brand} · ${candidate.model}"
                            },
                            onClick = {
                                DeviceProfileStore.save(context, candidate)
                                profile = candidate
                                showDevicePicker = false
                                toast(context.getString(R.string.settings_device_applied, candidate.displayName))
                            },
                            startAction = { SettingIcon(Icons.Rounded.PhoneAndroid, null) },
                        )
                    }
                }
            }
        )
    }

    if (showDeviceEditor) {
        DeviceEditorDialog(
            initial = profile,
            onDismiss = { showDeviceEditor = false },
            onSave = { edited ->
                DeviceProfileStore.save(context, edited)
                profile = edited
                showDeviceEditor = false
                toast(context.getString(R.string.settings_device_applied, edited.displayName))
            },
        )
    }

    if (showPermissionsDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showPermissionsDialog = false },
            title = stringResource(R.string.dialog_permissions_title),
            summary = stringResource(R.string.dialog_permissions_msg),
            content = {
                Row {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showPermissionsDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    TextButton(
                        text = stringResource(R.string.dialog_permissions_go),
                        onClick = {
                            showPermissionsDialog = false
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        )
    }

    if (showFeedbackDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showFeedbackDialog = false },
            title = stringResource(R.string.dialog_feedback_title),
            content = {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.dialog_feedback_export_logs),
                        summary = stringResource(R.string.dialog_feedback_export_logs_summary),
                        onClick = {
                            showFeedbackDialog = false
                            LogCollector.export(
                                context,
                                onComplete = { path -> LogCollector.share(context, path) },
                                onError = { err -> toast(context.getString(R.string.toast_logs_export_failed, err)) },
                            )
                        },
                        startAction = { SettingIcon(Icons.Rounded.BugReport, null) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.dialog_feedback_github),
                        summary = "github.com/harmony-on-android/HOA/issues",
                        onClick = {
                            showFeedbackDialog = false
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/harmony-on-android/HOA/issues")
                                )
                            )
                        },
                        startAction = { SettingIcon(Icons.Rounded.ChevronRight, null) },
                    )
                }
            }
        )
    }

    updateMessage?.let { message ->
        WindowDialog(
            show = true,
            onDismissRequest = { updateMessage = null },
            title = stringResource(R.string.settings_check_update),
            summary = message,
            content = {
                Row {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { updateMessage = null },
                        modifier = Modifier.weight(1f),
                    )
                    if (updateHasNewVersion) {
                        Spacer(Modifier.width(16.dp))
                        TextButton(
                            text = stringResource(R.string.update_dialog_go),
                            onClick = {
                                updateMessage = null
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/harmony-on-android/HOA/releases")
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        )
    }

    if (showAboutDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showAboutDialog = false },
            title = stringResource(R.string.app_name),
            summary = stringResource(
                R.string.settings_about_summary,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            content = {
                TextButton(
                    text = stringResource(R.string.ok),
                    onClick = { showAboutDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        )
    }
}

@Composable
private fun DeviceEditorDialog(
    initial: DeviceProfile,
    onDismiss: () -> Unit,
    onSave: (DeviceProfile) -> Unit,
) {
    var displayName by remember { mutableStateOf(if (initial.isSystem) "" else initial.displayName) }
    var brand by remember { mutableStateOf(if (initial.isSystem) "" else initial.brand) }
    var manufacturer by remember { mutableStateOf(if (initial.isSystem) "" else initial.manufacturer) }
    var model by remember { mutableStateOf(if (initial.isSystem) "" else initial.model) }
    var product by remember { mutableStateOf(if (initial.isSystem) "" else initial.product) }
    var device by remember { mutableStateOf(if (initial.isSystem) "" else initial.device) }
    var board by remember { mutableStateOf(if (initial.isSystem) "" else initial.board) }
    var hardware by remember { mutableStateOf(if (initial.isSystem) "" else initial.hardware) }
    var osVersion by remember { mutableStateOf(if (initial.isSystem) "5.0.0" else initial.osVersion) }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_device_custom),
        summary = stringResource(R.string.settings_device_editor_summary),
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DeviceField(stringResource(R.string.field_display_name), displayName) { displayName = it }
                DeviceField("brand", brand) { brand = it; if (manufacturer.isBlank()) manufacturer = it }
                DeviceField("manufacturer", manufacturer) { manufacturer = it }
                DeviceField("model", model) { model = it; if (product.isBlank()) product = it }
                DeviceField("product", product) { product = it }
                DeviceField("device", device) { device = it; if (board.isBlank()) board = it }
                DeviceField("board", board) { board = it }
                DeviceField("hardware", hardware) { hardware = it }
                DeviceField(stringResource(R.string.field_os_version), osVersion) { osVersion = it }
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
                    text = stringResource(R.string.save),
                    onClick = {
                        val name = displayName.ifBlank { model }.ifBlank { "自定义机型" }
                        onSave(
                            DeviceProfile(
                                id = "custom_${System.currentTimeMillis()}",
                                displayName = name,
                                brand = brand.ifBlank { "HUAWEI" },
                                manufacturer = manufacturer.ifBlank { brand.ifBlank { "HUAWEI" } },
                                model = model.ifBlank { "custom" },
                                product = product.ifBlank { model.ifBlank { "custom" } },
                                device = device.ifBlank { model.ifBlank { "custom" } },
                                board = board.ifBlank { model.ifBlank { "custom" } },
                                hardware = hardware.ifBlank { "generic" },
                                osVersion = osVersion.ifBlank { "5.0.0" },
                                isCustom = true,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    )
}

@Composable
private fun DeviceField(label: String, value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

/** 把更新检查结果翻译成一句人话；与 UI 无关，方便单测。 */
private fun describeUpdate(
    context: Context,
    result: app.hackeris.hoa.UpdateChecker.Result,
): String = when (result) {
    is app.hackeris.hoa.UpdateChecker.Result.UpdateAvailable ->
        context.getString(R.string.update_dialog_msg, result.date)
    app.hackeris.hoa.UpdateChecker.Result.UpToDate ->
        context.getString(R.string.update_dialog_uptodate)
    is app.hackeris.hoa.UpdateChecker.Result.Error ->
        context.getString(R.string.update_error_fmt, result.message)
}

private fun isHdcRunning(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return manager.getRunningServices(Int.MAX_VALUE).any {
        HdcService::class.java.name == it.service.className
    }
}

private fun startHdcService(context: Context, port: Int) {
    val intent = Intent(context, HdcService::class.java)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
