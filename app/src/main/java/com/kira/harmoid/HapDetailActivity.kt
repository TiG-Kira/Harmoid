package com.kira.harmoid

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.kira.harmoid.compose.NavigationHelper
import com.kira.harmoid.hap.HapBundleLoader
import com.kira.harmoid.ui.HapDetailScreen
import com.kira.harmoid.ui.HapDetailUiInfo
import com.kira.harmoid.ui.theme.HoaTheme
import java.io.File

/**
 * 应用信息页 —— 独立 Activity，Miuix + Compose 实现（[HapDetailScreen]）。
 *
 * 旧版是 View/XML + LinearLayout 动态塞 TextView，视觉与设计系统脱节；
 * 现在与 [AboutActivity] 同一结构：边到边 + NavigationHelper dispatcher +
 * HoaTheme，页面数据在 Activity 侧加载，Compose 只管渲染。
 */
class HapDetailActivity : ComponentActivity() {

    private lateinit var bundleName: String
    private lateinit var moduleName: String
    private lateinit var mainAbility: String
    private lateinit var contentDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边到边：状态栏 / 导航栏透明，页面自己画背景并决定避让。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        bundleName = intent.getStringExtra("BUNDLE_NAME") ?: ""
        moduleName = intent.getStringExtra("MODULE_NAME") ?: ""
        mainAbility = intent.getStringExtra("ABILITY_NAME") ?: ""

        if (bundleName.isEmpty() || moduleName.isEmpty()) {
            finish()
            return
        }

        val fullName = "$bundleName.$moduleName"
        contentDir = File(filesDir, "hap/$fullName")
        if (!contentDir.isDirectory) {
            Toast.makeText(this, getString(R.string.toast_module_not_found, bundleName, moduleName), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.e(TAG, "Detail page: $bundleName/$moduleName")

        val info = loadInfo()

        setContent {
            // 与 MainActivity 同一约定：根 composition 必须提供
            // LocalNavigationEventDispatcherOwner，否则 Miuix 的
            // NavigationBackHandler（返回手势）会抛 IllegalStateException。
            val navDispatcher = remember { NavigationHelper.createDispatcher() }
            val navDispatcherOwner = remember { NavigationHelper.createOwner(navDispatcher) }
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                HoaTheme {
                    HapDetailScreen(
                        info = info,
                        onBack = { finish() },
                        onLaunch = { launchHap() },
                    )
                }
            }
        }
    }

    private fun loadInfo(): HapDetailUiInfo? {
        val config = try {
            HapBundleLoader().parseModuleJson(File(contentDir, "module.json").readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load module.json", e)
            null
        }
        val label = if (config != null) HapBundleLoader.resolveLabel(contentDir, config) else bundleName

        val libs = scanNativeLibs()
        val libsTotal = libs.fold(0L) { acc, (_, size) -> acc + size }

        return HapDetailUiInfo(
            label = label,
            bundleName = bundleName,
            moduleName = config?.name ?: moduleName,
            moduleType = config?.type ?: "",
            vendor = config?.vendor ?: "",
            versionName = config?.versionName ?: "",
            versionCode = config?.versionCode?.toString() ?: "",
            compileSdk = config?.compileSdkVersion ?: "",
            targetApi = config?.targetApiVersion?.toString() ?: "",
            minApi = config?.minApiVersion?.toString() ?: "",
            virtualMachine = config?.virtualMachine ?: "",
            size = formatBytes(dirSize(contentDir)),
            permissions = config?.requestPermissions ?: emptyList(),
            abilities = config?.abilities?.map { a ->
                buildString {
                    append(a.name)
                    append("  [").append(a.type).append("]")
                    if (a.launchType != "singleton") append("  launch=").append(a.launchType)
                    if (a.exported) append("  exported")
                }
            } ?: emptyList(),
            pages = config?.pages ?: emptyList(),
            nativeLibs = libs.map { (name, size) -> "$name  —  ${formatBytes(size)}" },
            nativeLibsTotal = formatBytes(libsTotal),
        )
    }

    private fun scanNativeLibs(): List<Pair<String, Long>> {
        val libsDir = File(contentDir, "libs/arm64-v8a")
        if (!libsDir.isDirectory) return emptyList()
        return libsDir.listFiles()
            ?.filter { it.name.endsWith(".so") }
            ?.sortedBy { it.name }
            ?.map { it.name to it.length() }
            ?: emptyList()
    }

    private fun launchHap() {
        val slot = ProcessSlotManager.allocateSlot(this, contentDir.absolutePath)
        if (slot < 0) {
            Toast.makeText(this, getString(R.string.toast_slots_full_fmt, ProcessSlotManager.MAX_SLOTS), Toast.LENGTH_LONG).show()
            return
        }
        Log.e(TAG, "Launching HAP slot=$slot bundle=$bundleName/$moduleName/$mainAbility")
        val intent = Intent().apply {
            setClassName(packageName, "${packageName}.HoaAbilityActivity$slot")
            putExtra("BUNDLE_NAME", bundleName)
            putExtra("MODULE_NAME", moduleName)
            putExtra("ABILITY_NAME", mainAbility)
            putExtra("PROCESS_SLOT", slot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) dirSize(f) else f.length()
        }
        return size
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "Harmoid.Detail"
    }
}
