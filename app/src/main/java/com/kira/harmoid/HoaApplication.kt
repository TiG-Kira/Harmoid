package com.kira.harmoid

import android.webkit.WebView
import com.kira.harmoid.bridge.ActivityTracker
import com.kira.harmoid.device.BuildSpoofer
import com.kira.harmoid.device.DeviceProfile
import com.kira.harmoid.device.DeviceProfileStore
import com.kira.harmoid.logging.LogWriter
import ohos.stage.ability.adapter.StageApplication
import ohos.stage.ability.adapter.StageApplicationDelegate
import java.io.File

class HoaApplication : StageApplication() {

    var initSuccess = false
        private set

    var initError: Throwable? = null
        private set

    // StageApplication eagerly loads the native engine; the launcher process
    // intentionally bypasses it. HAP workers call it inside initArkUIX().
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onCreate() {
        LogWriter.init(getExternalFilesDir(null) ?: filesDir)
        LogWriter.e(TAG, "========== HOA Application onCreate START ==========")
        LogWriter.e(TAG, "Process: ${android.os.Process.myPid()}  Name: ${currentProcessName()}")

        // 未捕获异常先落盘再交给系统：HAP 跑在独立 :hap 进程里，崩溃时 logcat 的
        // crash buffer 经常被后续日志冲掉，而 LogWriter 写的是外部存储上的文件，
        // 进程死了也还在。排查「进 HAP 闪退」这类问题就靠它。
        installCrashLogger()
        ActivityTracker.initialize(this)

        // 机型模拟必须抢在任何会读 Build 的代码之前：
        // ArkUI-X 的 DeviceInfoPlugin 是 Java 类，字段读取发生在 Java 侧，
        // 这里覆盖 static 字段之后，JNI 侧读到的就是模拟值。
        // 每个 :hap 进程都会走一遍 Application.onCreate，所以每条 HAP 都吃得到。
        // 覆盖之前先把真机身份存下来，设置页「跟随宿主设备」才有正确值可回填。
        DeviceProfile.captureHost()
        val deviceProfile = DeviceProfileStore.load(this)
        BuildSpoofer.apply(deviceProfile)
        LogWriter.e(TAG, "Device profile: ${deviceProfile.displayName} (model=${deviceProfile.model})")

        // Only initialize the ArkUI-X runtime (libarkui_android.so, ~79 MB) in
        // HAP worker processes (":hap*").  The main process only hosts
        // MainActivity and does not need the native engine at all.
        // This shaves ~1-2 s off the cold-start time of the launcher activity.
        if (isHapProcess()) {
            LogWriter.e(TAG, "HAP process detected — initializing ArkUI-X runtime")
            initArkUIX()
            if (!initSuccess) {
                RuntimeErrorActivity.abortWorker(this,
                    initError?.message ?: "HarmonyOS runtime initialization failed", initError)
            }
        } else {
            LogWriter.e(TAG, "Main process — skipping ArkUI-X init (not needed for launcher)")
            // Load libb.so (musl ABI bridge) even in the main process.
            // HapInstaller (which runs in the main process) needs the ELF
            // patching JNI that lives in libb.so.
            try {
                System.loadLibrary("b")
                LogWriter.e(TAG, "System.loadLibrary(\"b\") — SUCCESS (main process)")
            } catch (e: UnsatisfiedLinkError) {
                LogWriter.e(TAG, "System.loadLibrary(\"b\") — FAILED (main process)", e)
            }
            initSuccess = true   // main process is always "ready"
        }

        LogWriter.e(TAG, "========== HOA Application onCreate END ==========")
    }

    private fun initArkUIX() {
        // Android 10+ requires setDataDirectorySuffix for WebView multi-process.
        // Without this, the second HAP process that creates a WebView gets a
        // white screen because the WebView rendering engine data directory is
        // locked by the first process.  Each process gets its own suffix so
        // they can coexist.  Colon (:) in process names is replaced with '_'.
        val suffix = currentProcessName().replace(':', '_')
        try {
            WebView.setDataDirectorySuffix(suffix)
            LogWriter.i(TAG, "WebView.setDataDirectorySuffix(\"$suffix\") — OK")
        } catch (e: Exception) {
            LogWriter.e(TAG, "WebView.setDataDirectorySuffix(\"$suffix\") — FAILED", e)
        }

        // Load musl ABI bridge (libb.so) FIRST — it must be in the linker
        // namespace before any HAP .so files load, so musl symbols resolve
        // through libb.so instead of bionic libc.so.
        try {
            System.loadLibrary("b")
            LogWriter.e(TAG, "System.loadLibrary(\"b\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"b\") — FAILED", e)
            initError = e
            return
        }

        // Try explicit native library load first for better error reporting
        try {
            System.loadLibrary("arkui_android")
            LogWriter.e(TAG, "System.loadLibrary(\"arkui_android\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"arkui_android\") — FAILED", e)
            LogWriter.e(TAG, "  nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
            val libFile = java.io.File(applicationInfo.nativeLibraryDir, "libarkui_android.so")
            LogWriter.e(TAG, "  libarkui_android.so exists: ${libFile.exists()}, size: ${if (libFile.exists()) libFile.length() else 0}")
            initError = e
            return
        }

        // Pre-load HDS (Huawei Design System) stub so its NAPI module
        // "hds.hdsBaseComponent" is registered before any HAP tries to
        // import "@hms:hds.hdsBaseComponent".
        try {
            System.loadLibrary("hms_hds")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_hds\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_hds\") — FAILED", e)
        }

        // Pre-load HMS Security stubs so NAPI modules "security.deviceCertificate",
        // "core.AAID", and "security.safetyDetect" are registered before any HAP
        // tries to import them.
        try {
            System.loadLibrary("hms_security")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_security\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_security\") — FAILED", e)
        }

        // Pre-load HMS Push stubs so NAPI modules "core.push.pushService"
        // and "core.push.pushCommon" are registered before any HAP
        // tries to import them.
        try {
            System.loadLibrary("hms_push")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_push\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_push\") — FAILED", e)
        }

        // Pre-load HMS Account stub so NAPI module "core.account.extendservice"
        // is registered before any HAP tries to import it.
        try {
            System.loadLibrary("hms_account")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_account\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_account\") — FAILED", e)
        }

        // Pre-load HMS IAP + Payment stubs so NAPI modules "core.iap"
        // and "core.payment.paymentService" are registered before any HAP
        // tries to import them.
        try {
            System.loadLibrary("hms_iap")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_iap\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_iap\") — FAILED", e)
        }

        // Pre-load @kit.ShareKit stub so NAPI module "collaboration.systemShare"
        // is registered before any HAP tries to import it.
        try {
            System.loadLibrary("hms_share")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_share\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_share\") — FAILED", e)
        }

        // ArkUI Component bridge — registers NAPI module "ohos.arkui.component"
        // so HAPs can import framework global ES values (BottomTabBarStyle etc.)
        // from @kit.ArkUI.  Must be loaded before HAP code references them.
        try {
            System.loadLibrary("arkui_component")
            LogWriter.e(TAG, "System.loadLibrary(\"arkui_component\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"arkui_component\") — FAILED", e)
        }

        // ——— HOA: redirect storage (Preferences/RDB/KV Store) to per-HAP dir ———
        // By default ArkUI-X sets filesDir to the app-level path so all HAPs
        // share files/preference/, files/database/, etc.  We override with the
        // HAP module directory so each HAP gets its own isolated data sandbox.
        try {
            redirectStorageToHapModule()
            super.onCreate()
            LogWriter.e(TAG, "StageApplication.onCreate() completed successfully")
        } catch (e: LinkageError) {
            LogWriter.e(TAG, "FATAL: LinkageError during StageApplication.onCreate()", e)
            initError = e
            return
        } catch (e: Exception) {
            LogWriter.e(TAG, "FATAL: StageApplication.onCreate() failed", e)
            initError = e
            return
        }

        // Enable OHOS HAP mode AFTER super.onCreate() because the JNI methods
        // (including nativeSetOhosHapMode) are registered lazily by
        // AppModeConfig.nativeInitAppMode() → StageJniRegistry::Register().
        try {
            StageApplication.setOhosHapMode(true)
            initSuccess = true
            LogWriter.e(TAG, "setOhosHapMode(true) OK")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "setOhosHapMode not available in current .so — patches inactive", e)
            initError = e
        }
    }

    private fun isHapProcess(): Boolean {
        val name = currentProcessName()
        return extractSlot(name) in 0 until ProcessSlotManager.MAX_SLOTS
    }

    /**
     * 把未捕获异常的完整堆栈写进 LogWriter，再链回系统原来的处理器。
     *
     * 为什么要自己接一层：`Thread.getDefaultUncaughtExceptionHandler()` 默认实现
     * 只往 logcat 的 crash buffer 写，而 :hap 进程崩溃后 ArkUI-X 还会继续刷日志，
     * 很容易把关键堆栈冲掉。这里先同步落盘（外部存储，进程消亡不影响），
     * 再调原处理器，保证系统该有的「应用已停止」行为和进程终止都不变。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            LogWriter.e(
                TAG,
                "!!!!!!!! UNCAUGHT EXCEPTION in thread '${thread.name}' " +
                    "(process=${currentProcessName()}) !!!!!!!!",
                throwable,
            )
            // 链回系统原来的处理器（默认是 RuntimeInit 的 KillApplicationHandler），
            // 保证「应用已停止」对话框和进程终止行为不变。
            // 原处理器为 null 时自己收尾，否则进程会挂住不退出。
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    /**
     * Redirect ArkUI-X storage (Preferences, RDB, KV Store) from the shared
     * app-level filesDir to the per-HAP module directory.
     *
     * Without this, all HAPs share files/preference/, files/database/, etc.
     * and a store named "settings" in two different HAPs would conflict.
     *
     * Called BEFORE [super.onCreate] so that createStagePath() picks up
     * the HAP-specific data directory.
     */
    private fun redirectStorageToHapModule() {
        val procName = currentProcessName()
        val slot = extractSlot(procName)
        if (slot < 0) {
            LogWriter.w(TAG, "redirectStorageToHapModule: cannot parse slot from '$procName'")
            return
        }

        val contentDir = ProcessSlotManager.getContentDir(this, slot)
        checkNotNull(contentDir) { "Missing HAP identity for process slot $slot; launch from the app list" }
        val root = File(filesDir, "hap").canonicalFile
        val module = File(contentDir).canonicalFile
        check(module.parentFile == root && File(module, "module.json").isFile) {
            "HAP module is missing or outside its installation directory: $module"
        }
        ProcessSlotManager.claimSlot(this, slot, contentDir)

        // Ensure storage subdirectories exist under the HAP module dir
        for (sub in arrayOf("temp", "files", "preference", "database", "cache")) {
            val dir = File(contentDir, sub)
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create HAP storage directory: $dir" }
        }

        StageApplicationDelegate.sModuleDataDir = contentDir
        LogWriter.e(TAG, "redirectStorageToHapModule: data dir → $contentDir")
    }

    /**
     * Extracts the slot number from a HAP process name like
     * "com.kira.harmoid:hap3" → 3.  Returns -1 on failure.
     */
    private fun extractSlot(procName: String): Int {
        val prefix = "$packageName:hap"
        if (!procName.startsWith(prefix)) return -1
        return procName.removePrefix(prefix).toIntOrNull() ?: -1
    }

    private fun currentProcessName(): String {
        return android.app.Application.getProcessName()
    }

    companion object {
        private const val TAG = "Harmoid.App"
    }
}
