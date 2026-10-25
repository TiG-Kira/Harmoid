package com.kira.harmoid.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kira.harmoid.HoaShortcutActivity
import com.kira.harmoid.ProcessSlotManager
import com.kira.harmoid.hap.HapBundleLoader
import com.kira.harmoid.hap.HapInstaller
import com.kira.harmoid.hap.HapParseException
import com.kira.harmoid.hap.InstalledHap
import com.kira.harmoid.hap.ModuleConfig
import com.kira.harmoid.logging.LogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 应用页需要的一条 HAP 记录（含已解析的展示名与图标）。 */
data class HapItem(
    val hap: InstalledHap,
    val label: String,
    val icon: Bitmap?,
    val installedAt: Long,
)

/** 安装前的确认信息。 */
data class InstallPreview(
    val file: File,
    val config: ModuleConfig,
)

enum class SortMode { NAME, TIME_DESC, TIME_ASC }

/** 排序方式在设置页 / 应用页共用的展示文案。 */
internal fun SortMode.labelRes(): Int = when (this) {
    SortMode.NAME -> com.kira.harmoid.R.string.sort_name
    SortMode.TIME_DESC -> com.kira.harmoid.R.string.sort_time_desc
    SortMode.TIME_ASC -> com.kira.harmoid.R.string.sort_time_asc
}

internal fun SortMode.summaryRes(): Int = when (this) {
    SortMode.NAME -> com.kira.harmoid.R.string.sort_name_summary
    SortMode.TIME_DESC -> com.kira.harmoid.R.string.sort_time_desc_summary
    SortMode.TIME_ASC -> com.kira.harmoid.R.string.sort_time_asc_summary
}

/**
 * 主页的全部状态与动作。
 *
 * 之所以从 Activity 里抽出来：新的 UI 是 Compose 的，页面重组会频繁发生，
 * 安装 / 卸载 / 图标解码这些耗时活儿必须活在 Activity 生命周期之外，
 * 且不能阻塞主线程。
 */
class HoaViewModel(app: Application) : AndroidViewModel(app) {

    private val installer = HapInstaller(app)

    private val _items = MutableStateFlow<List<HapItem>>(emptyList())
    val items: StateFlow<List<HapItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _preview = MutableStateFlow<InstallPreview?>(null)
    val preview: StateFlow<InstallPreview?> = _preview.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private var refreshJob: Job? = null
    private var previewJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    installer.getInstalledHaps().map { hap ->
                        val label = HapBundleLoader.resolveLabel(hap.contentDir, hap.moduleConfig)
                        // HapItem already retains the bitmap across recompositions.
                        // Re-read on refresh so upgrades cannot reuse stale icons.
                        val icon = HapBundleLoader.loadHapIcon(hap.contentDir, hap.moduleConfig)
                        HapItem(
                            hap = hap,
                            label = label,
                            icon = icon,
                            installedAt = hap.contentDir.lastModified(),
                        )
                    }
                }
                _items.value = loaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogWriter.e(TAG, "HAP list refresh failed", e)
                _events.tryEmit("读取应用列表失败：${e.message}")
            }
        }
    }

    /** 用户选完 HAP 文件后：先解出 module.json 做确认，不直接安装。 */
    fun previewFromUri(uri: Uri) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val ownedFiles = mutableListOf<File>()
            _busy.value = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val tmp = File.createTempFile("hap_preview_", ".hap", getApplication<Application>().cacheDir)
                    ownedFiles.add(tmp)
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("Cannot open selected file")
                    val loader = HapBundleLoader()
                    try {
                        InstallPreview(tmp, loader.previewConfig(tmp.path))
                    } catch (e: HapParseException) {
                        if (!loader.isAppPackage(tmp.path)) throw e
                        val hap = File(loader.unwrapSingleHap(tmp.path))
                        ownedFiles.add(hap)
                        InstallPreview(hap, loader.previewConfig(hap.path))
                    }
                }
                _preview.value?.file?.delete()
                _preview.value = parsed
                ownedFiles.remove(parsed.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogWriter.e(TAG, "HAP preview failed", e)
                _events.tryEmit("安装包解析失败：${e.message}")
            } finally {
                ownedFiles.forEach { it.delete() }
                if (previewJob == coroutineContext[Job]) _busy.value = false
            }
        }
    }

    override fun onCleared() {
        dismissPreview()
        super.onCleared()
    }

    fun confirmInstall() {
        val pending = _preview.value ?: return
        _preview.value = null
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    installer.install(pending.file.absolutePath)
                }
                _events.tryEmit("已安装 ${result.bundleName}")
                refresh()
            } catch (e: Exception) {
                LogWriter.e(TAG, "HAP install failed", e)
                _events.tryEmit("安装失败：${e.message}")
            } finally {
                pending.file.delete()
                _busy.value = false
            }
        }
    }

    fun dismissPreview() {
        _preview.value?.file?.delete()
        _preview.value = null
    }

    fun uninstall(hap: InstalledHap) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { installer.uninstall(hap.bundleName) }
                _events.tryEmit("已卸载 ${hap.bundleName}")
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogWriter.e(TAG, "HAP uninstall failed", e)
                _events.tryEmit("卸载失败：${e.message}")
            }
        }
    }

    /** @return 是否成功分配到进程槽。 */
    fun launch(hap: InstalledHap): Boolean {
        val slot = try {
            ProcessSlotManager.launchHap(getApplication(), hap.bundleName, hap.moduleName, hap.mainAbility)
        } catch (e: Exception) {
            LogWriter.e(TAG, "HAP launch failed", e)
            _events.tryEmit("启动失败：${e.message}")
            return false
        }
        if (slot < 0) {
            _events.tryEmit("进程槽已满（最多 ${ProcessSlotManager.MAX_SLOTS} 个），请先关闭一个正在运行的 HAP")
            return false
        }
        return true
    }

    fun pinShortcut(item: HapItem) {
        viewModelScope.launch {
            val hap = item.hap
            val context = getApplication<Application>()
            val shortcutId = "${hap.bundleName}.${hap.moduleName}.${hap.mainAbility}"
            val intent = Intent(context, HoaShortcutActivity::class.java).apply {
                putExtra("BUNDLE_NAME", hap.bundleName)
                putExtra("MODULE_NAME", hap.moduleName)
                putExtra("ABILITY_NAME", hap.mainAbility)
                action = Intent.ACTION_VIEW
            }
            val builder = ShortcutInfo.Builder(context, shortcutId)
                .setShortLabel(item.label)
                .setLongLabel(item.label)
                .setIntent(intent)
            item.icon?.let { builder.setIcon(Icon.createWithBitmap(it)) }

            val manager = context.getSystemService(ShortcutManager::class.java)
            if (manager != null && manager.isRequestPinShortcutSupported) {
                // requestPinShortcut 要求先有动态快捷方式；系统会把数据序列化进
                // 固定确认框，之后立即移除即可保持长按菜单干净。
                manager.addDynamicShortcuts(listOf(builder.build()))
                manager.requestPinShortcut(builder.build(), null)
                manager.removeDynamicShortcuts(listOf(shortcutId))
                _events.tryEmit("已请求添加「${item.label}」到桌面")
            } else {
                _events.tryEmit("当前桌面不支持固定快捷方式")
            }
        }
    }

    companion object {
        private const val TAG = "Harmoid.Main"
    }
}
