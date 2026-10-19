package com.kira.harmoid

import android.app.ActivityManager
import android.os.Bundle
import android.util.Log
import com.kira.harmoid.bridge.HapSession
import com.kira.harmoid.bridge.HarmoidMediaBridge
import ohos.stage.ability.adapter.StageActivity
import org.json.JSONObject
import java.io.File

open class HoaAbilityActivity : StageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val bundleName = intent.getStringExtra("BUNDLE_NAME") ?: "app.hackeris.harmonyexample"
        val moduleName = intent.getStringExtra("MODULE_NAME") ?: "entry"
        val abilityName = intent.getStringExtra("ABILITY_NAME") ?: "EntryAbility"
        val slot = intent.getIntExtra("PROCESS_SLOT", -1)

        if (slot >= 0) {
            val contentDir = java.io.File(filesDir, "hap/$bundleName.$moduleName").absolutePath
            ProcessSlotManager.claimSlot(this, slot, contentDir)
            Log.e(TAG, "Process slot $slot claimed, PID=${android.os.Process.myPid()}, contentDir=$contentDir")
        }

        // Publish this process' HAP identity for the ArkUI-X plugins and the
        // Harmoid bridges (notification routing / media session).
        HapSession.attach(bundleName, moduleName, abilityName, slot)

        val instanceName = "$bundleName:$moduleName:$abilityName:"
        Log.e(TAG, "========== HoaAbilityActivity onCreate START ==========")
        Log.e(TAG, "bundleName=$bundleName, moduleName=$moduleName, abilityName=$abilityName")
        Log.e(TAG, "instanceName=$instanceName, slot=$slot")

        // Check if StageApplication init succeeded
        val app = applicationContext as? HoaApplication
        if (app != null && !app.initSuccess) {
            Log.e(TAG, "StageApplication init FAILED — ArkUI rendering will not work")
            Log.e(TAG, "  Error was: ${app.initError?.message}")
            // 运行时没起来时继续走 super.onCreate() 只会在 native 里炸成
            // SEGFAULT（Java catch 拦不住）。这里给出可见的错误并退栈。
            //
            // 但 super.onCreate() 必须被调用过一次：Android 15+ 把 mCalled 的
            // 设置放进了 Activity 基类 onCreate，直接 return 会触发
            // SuperNotCalledException 次生崩溃（对话框还没显示进程就死了，
            // 26.10.19.2 AVD 实测）。基类 onCreate 是空实现风险低，仍然兜异常。
            try {
                super.onCreate(savedInstanceState)
                Log.e(TAG, "super.onCreate() called in guard path (mCalled satisfied)")
            } catch (e: Throwable) {
                Log.e(TAG, "super.onCreate() in guard path failed", e)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_init_failed_title)
                .setMessage(getString(R.string.dialog_init_failed_msg, app.initError?.message ?: "unknown"))
                .setPositiveButton(R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        // Verify module exists before handing off to ArkUI-X runtime.
        // If the module is not found, the runtime creates a null stage and crashes
        // with StackOverflow in WindowViewSurface.onHoverEvent.
        if (!moduleExists(bundleName, moduleName)) {
            Log.e(TAG, "Module not found: bundleName=$bundleName, moduleName=$moduleName")
            Log.e(TAG, "  Checked: filesDir/hap/$bundleName.$moduleName/")
            // 同守卫路径：先满足 Activity 契约再退栈，避免 SuperNotCalledException。
            try {
                super.onCreate(savedInstanceState)
            } catch (e: Throwable) {
                Log.e(TAG, "super.onCreate() in module-missing path failed", e)
            }
            android.widget.Toast.makeText(this, getString(R.string.toast_module_not_found, bundleName, moduleName), android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            setInstanceName(instanceName)
            Log.e(TAG, "setInstanceName() OK")
        } catch (e: Exception) {
            Log.e(TAG, "setInstanceName() FAILED", e)
        }

        // Preload all .so files from the HAP libs directory so that
        // DT_NEEDED dependencies within the HAP resolve correctly.
        // Without this, libentry.so → libhelper.so fails because the
        // linker does not search the HAP extraction directory by default.
        preloadNativeDeps(bundleName, moduleName)

        // 先让 decor 边到边，super.onCreate() 创建 SurfaceView 时窗口尺寸就是全屏；
        // 否则首帧会按"扣掉状态栏"的高度建表面，之后再改回来要闪一下。
        // 这段只是窗口属性调整，任何 OEM ROM 上的意外都不该阻断 HAP 启动，
        // 因此整体兜异常：失败了就退回系统默认行为（顶栏留白由 ArkUI 自己算）。
        if (isEdgeToEdgeEnabled()) {
            try {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            } catch (e: Throwable) {
                Log.e(TAG, "setDecorFitsSystemWindows(false) failed — 保持系统默认行为", e)
                disableEdgeToEdgeNextLaunch(e)
            }
        } else {
            Log.w(TAG, "edge-to-edge 已禁用 — 跳过 setDecorFitsSystemWindows(false)")
        }

        try {
            super.onCreate(savedInstanceState)
            Log.e(TAG, "super.onCreate() completed — ArkUI rendering surface should be created")
            Log.e(TAG, "instanceId=${getInstanceId()}, instanceName=${getInstanceName()}")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "FATAL: Native library link error during Activity onCreate", e)
            disableEdgeToEdgeNextLaunch(e)
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Activity onCreate failed", e)
            disableEdgeToEdgeNextLaunch(e)
        }

        Log.e(TAG, "========== HoaAbilityActivity onCreate END ==========")
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Apply HAP metadata (title + icon) AFTER super.onCreate() so that
        // any title/task-description set by StageActivity is overwritten.
        configureEdgeToEdgeForHap()
        val bundleName = intent.getStringExtra("BUNDLE_NAME") ?: "app.hackeris.harmonyexample"
        val moduleName = intent.getStringExtra("MODULE_NAME") ?: "entry"
        val abilityName = intent.getStringExtra("ABILITY_NAME") ?: "EntryAbility"
        applyHapTaskDescription(bundleName, moduleName, abilityName)
    }

    override fun onResume() {
        Log.e(TAG, "onResume — UIAbility.onForeground() should fire")
        super.onResume()
    }

    override fun onPause() {
        Log.e(TAG, "onPause — UIAbility.onBackground() should fire")
        super.onPause()
    }

    // ================================================================
    // setRequestedOrientation — MIUI Activity-restart workaround
    // ================================================================
    //
    // Background:
    //   OHOS HAPs call setMainWindowOrientation(portrait/landscape/...)
    //   expecting a lightweight, non-destructive window-level change.
    //   The call chain is:
    //
    //     ArkTS: window.setMainWindowOrientation(portrait)
    //       → NAPI: JsWindow::OnSetPreferredOrientation
    //       → C++:  VirtualRSWindow::SetRequestedOrientation
    //       → JNI:  SubWindowManagerJni::RequestOrientation
    //       → Java: SubWindowManager.requestOrientation()
    //               → mRootActivity.setRequestedOrientation(...)
    //
    //   On standard Android, configChanges="orientation|screenSize" in the
    //   manifest means the Activity is NOT restarted — onConfigurationChanged
    //   fires instead.  On MIUI (Xiaomi), however, setRequestedOrientation()
    //   bypasses configChanges entirely: the OS destroys the Activity and
    //   cold-starts a new process.  isChangingConfigurations is false during
    //   this destroy, so it is indistinguishable from a "real" finish().
    //
    //   The process restart drops the entire ArkUI navigation stack and the
    //   user sees the HAP home page instead of the current page.  Example:
    //   BackdropBlurStyleTabBar in ui-examples — renders briefly then white
    //   screen (before this fix) or snaps back to home (with onDestroy fix
    //   alone).
    //
    // Workaround:
    //   We compare the device's current orientation (from Configuration) with
    //   the target orientation encoded in the requestedOrientation flag:
    //
    //     - Match (same orientation):  safe — forward to the real
    //       setRequestedOrientation().  Calling it with the already-active
    //       value is a no-op at the OS level and won't trigger a restart.
    //
    //     - Mismatch (real switch):  blocked — the MIUI restart would nuke
    //       the ArkUI runtime.  Log a warning so the limitation is visible.
    //
    //   Most HAP calls are of the "match" variety (e.g. a portrait HAP
    //   locking itself to portrait on an already-portrait device), so this
    //   handles the common case correctly.
    //
    // Long-term fix:
    //   SubWindowManager.java in ArkUI-X should avoid Activity-level
    //   setRequestedOrientation() and use a lower-level API (e.g.
    //   WindowManager / Display rotation) that doesn't trigger an Activity
    //   lifecycle transition on MIUI.
    // ================================================================
    override fun setRequestedOrientation(requestedOrientation: Int) {
        val config = resources.configuration
        val currentOrientation = config.orientation
        val targetOrientation = when (requestedOrientation) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT ->
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ->
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            else -> currentOrientation  // UNSPECIFIED, BEHIND, etc. — don't change
        }

        if (targetOrientation == currentOrientation) {
            Log.e(TAG, "setRequestedOrientation($requestedOrientation) — already in target orientation, forwarding")
            super.setRequestedOrientation(requestedOrientation)
        } else {
            Log.e(TAG, "setRequestedOrientation($requestedOrientation) — BLOCKED: " +
                "current=$currentOrientation target=$targetOrientation. " +
                "Would trigger MIUI Activity restart and lose ArkUI navigation state.")
        }
    }

    // ================================================================
    // onDestroy — only kill process on "real" destroy, not config changes
    // ================================================================
    //
    // Why we kill the process on destroy:
    //   Each HAP runs in an isolated process (:hap0–:hap9).  The ArkUI-X
    //   StageApplication / ResourceManager is process-global and
    //   AddResource() fails if called a second time with the same path.
    //   Killing the process guarantees a clean slate for the next launch.
    //
    // Why we DON'T kill on config changes:
    //   When isChangingConfigurations == true, the OS is destroying the
    //   Activity temporarily (e.g. physical device rotation) and will
    //   recreate it immediately in the same process.  Killing here would
    //   leave the user staring at a dead window.
    //
    // NOTE: setRequestedOrientation() on MIUI triggers a destroy with
    //       isChangingConfigurations == FALSE (see setRequestedOrientation
    //       override above).  That path is blocked before it reaches
    //       onDestroy, so this check alone was not sufficient — the
    //       setRequestedOrientation workaround is also required.
    // ================================================================
    override fun onDestroy() {
        Log.e(TAG, "onDestroy — UIAbility.onDestroy() should fire, isChangingConfigurations=$isChangingConfigurations")
        val slot = intent.getIntExtra("PROCESS_SLOT", -1)
        if (slot >= 0) {
            ProcessSlotManager.releaseSlot(this, slot)
            Log.e(TAG, "Process slot $slot released")
        }
        HarmoidMediaBridge.release()
        HapSession.detach()
        super.onDestroy()
        if (isChangingConfigurations) {
            Log.e(TAG, "onDestroy — skipping killProcess (config change, activity will be recreated)")
            return
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ================================================================
    // 状态栏避让：宿主不再替 HAP 让位，改成把真实 insets 交给运行时
    // ================================================================
    //
    // 旧实现把 `android.R.id.content` 按状态栏高度 padding 掉，看起来"避让了"，
    // 实际上把 HAP 能拿到的避让区抹平了：
    //
    //   ArkUI-X 的 WindowViewCommon 在它自己的根视图上监听 insets，再用
    //   `processAvoidAreas()` 把 insets 换算成 avoid area：
    //     top avoid = intersection(窗口矩形, (0, 0, screenW, insets.top))
    //   而窗口矩形来自 getLocationOnScreen()。宿主给 content 加了 top padding 之后，
    //   WindowView 的 y 起点正好等于 insets.top，二者求交得到空矩形 ——
    //   HAP 拿到的顶边避让区恒为 0，`window.getWindowAvoidArea()` /
    //   `expandSafeArea` 全部失效，只能看到一条被宿主压掉的、高度不对的可视区。
    //
    // 现在的做法：
    //   1. `setDecorFitsSystemWindows(false)`，让 insets 一路传到 WindowView 根视图；
    //   2. content 不加 padding、不消费 insets（监听器原样返回），窗口矩形保持
    //      (0, 0, w, h)；
    //   3. 系统栏透明、图标明暗按 HAP 背景亮度决定。
    //   这样 top avoid area 就等于真实状态栏高度，HAP 自己做让位（ArkUI 默认
    //   就会给页面加 safe area，只有显式 expandSafeArea 才铺到栏下）。
    private fun configureEdgeToEdgeForHap() {
        // 自愈开关：一旦上次启动在 edge-to-edge 路径上炸过，就写持久化标记，
        // 后续启动整体退回上游旧行为（不碰 decor fitting、不调本方法）。
        // 既保证 app 不至于一直闪退，也能反证崩溃是不是这段改造引起的。
        if (!isEdgeToEdgeEnabled()) {
            Log.w(TAG, "edge-to-edge 已被上次启动的异常禁用 — 退回系统默认窗口行为")
            return
        }
        // 整段兜异常：窗口属性调整失败时退回系统默认（DecorView 自己按 insets padding），
        // HAP 照样能起来，只是避让区回到"被宿主压掉"的旧行为。
        // 不能让一段样式调整把整个 HAP 启动搞崩。
        try {
            applyEdgeToEdgeForHap()
        } catch (e: Throwable) {
            Log.e(TAG, "configureEdgeToEdgeForHap failed — 退回系统默认窗口行为", e)
            disableEdgeToEdgeNextLaunch(e)
        }
    }

    private fun isEdgeToEdgeEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_EDGE_TO_EDGE, true)

    /** 启动路径炸过 → 禁用 edge-to-edge，下次启动走旧行为。 */
    private fun disableEdgeToEdgeNextLaunch(e: Throwable) {
        Log.e(TAG, "disableEdgeToEdgeNextLaunch: ${e.javaClass.simpleName}: ${e.message}")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EDGE_TO_EDGE, false)
            .apply()
    }

    private fun applyEdgeToEdgeForHap() {
        val window = this.window
        // 关键的一步：decor 不再消费 insets。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // 系统栏透明，内容穿过状态栏 / 导航栏区域。
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isNavigationBarContrastEnforced = false
        }

        // 状态栏图标明暗跟随 HAP 自己的窗口背景色，避免白底白字 / 黑底黑字。
        val ta = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        val bgDrawable = ta.getDrawable(0)
        ta.recycle()
        val lightIcons = if (bgDrawable is android.graphics.drawable.ColorDrawable) {
            val bgColor = bgDrawable.color
            val luminance = 0.299 * android.graphics.Color.red(bgColor) +
                0.587 * android.graphics.Color.green(bgColor) +
                0.114 * android.graphics.Color.blue(bgColor)
            luminance > 128
        } else {
            // 拿不到纯色背景时跟随系统深浅色，和宿主保持一致的观感。
            val nightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            nightMode != android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 清掉宿主之前可能留下的 padding，并保证不消费 insets：
        // 监听器原样返回 insets，后面的 View 与 WindowView 根视图都能读到真值。
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        content.setPadding(0, 0, 0, 0)
        content.fitsSystemWindows = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets -> insets }
    }

    override fun onBackPressed() {
        Log.e(TAG, "onBackPressed")
        super.onBackPressed()
    }

    // ============================================================
    // Apply the HAP's app name and icon to the Android Activity,
    // so the task switcher / recents screen shows the HAP identity
    // rather than the host app's "Harmoid" label and launcher icon.
    // ============================================================
    //
    // Limitations (by design — kept simple intentionally):
    //
    //   1. Title: String resources referenced by module.json (e.g.
    //      "$string:app_name") are stored in the binary resources.index.
    //      Without a parser for that format, the actual display string
    //      is not accessible.  We fall back to using the bundleName,
    //      which is always available and human-recognisable for most
    //      real-world HAPs (e.g. "top.wangchenyan.wanharmony" →
    //      "wanharmony" or "top.wangchenyan.wanharmony").
    //
    //   2. Icon:  Resource references like "$media:icon" are resolved
    //      by scanning resources/base/media/ for {name}.{ext}.  Only
    //      the "base" density bucket is checked.  HAPs that place the
    //      icon exclusively in density-specific directories (e.g.
    //      resources/xxxhdpi/media/) will not be matched.  SVG icons
    //      are skipped because Android cannot decode them natively.
    //
    //   3. The icon loaded here is used for the task-switcher thumbnail
    //      only.  It does NOT change the launcher icon (the host APK
    //      icon remains) and does NOT affect the splash / start-window
    //      shown during cold start.
    //
    private fun applyHapTaskDescription(
        bundleName: String, moduleName: String, abilityName: String
    ) {
        val fullModuleName = "$bundleName.$moduleName"
        val moduleJsonFile = File(filesDir, "hap/$fullModuleName/module.json")
        if (!moduleJsonFile.exists()) {
            setTitle(bundleName)
            return
        }

        var displayName = bundleName

        try {
            val json = JSONObject(moduleJsonFile.readText())

            // Prefer the app.label from module.json.
            val appObj = json.optJSONObject("app")
            val rawLabel = appObj?.optString("label", "") ?: ""
            if (rawLabel.isNotBlank()) {
                if (rawLabel.startsWith("\$string:")) {
                    val key = rawLabel.removePrefix("\$string:")
                    val moduleDir = File(filesDir, "hap/$fullModuleName")
                    val resolved = com.kira.harmoid.hap.HapBundleLoader.parseStringFromIndex(moduleDir, key)
                    if (resolved != null) displayName = resolved
                } else {
                    displayName = rawLabel
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyHapTaskDescription failed", e)
        }

        val bitmap = com.kira.harmoid.hap.HapBundleLoader.loadHapIcon(
            File(filesDir, "hap/$fullModuleName")
        )

        setTitle(displayName)
        @Suppress("DEPRECATION")
        if (bitmap != null) {
            setTaskDescription(ActivityManager.TaskDescription(displayName, bitmap))
        } else {
            setTaskDescription(ActivityManager.TaskDescription(displayName))
        }
        Log.e(TAG, "applyHapTaskDescription: title=$displayName icon=${bitmap != null}")
    }

    // ============================================================
    // Preload all .so files from the HAP libs directory.
    //
    // HAP .so files that DT_NEEDED other .so files in the same HAP
    // (e.g. libentry.so → libhelper.so) can't resolve those deps
    // because the linker does not search the extraction directory.
    //
    // By loading all .so files via System.load() before ArkUI-X
    // dlopen's the main module, every dependency is already in
    // memory and DT_NEEDED resolution succeeds by refcount bump.
    //
    // Files whose own deps are not yet satisfied will fail (caught);
    // they will be loaded later by ArkUI-X once their deps are met.
    // ============================================================
    private fun preloadNativeDeps(bundleName: String, moduleName: String) {
        val fullModuleName = "$bundleName.$moduleName"
        val libsDir = File(filesDir, "hap/$fullModuleName/libs")
        if (!libsDir.isDirectory) {
            Log.e(TAG, "preloadNativeDeps: libs dir not found at $libsDir")
            return
        }

        val nativeAbi = android.os.Build.SUPPORTED_ABIS[0]
        val soFiles = libsDir.walkTopDown()
            .filter { it.isFile && it.extension == "so" && it.parentFile?.name == nativeAbi }
            .toList()

        if (soFiles.isEmpty()) {
            Log.e(TAG, "preloadNativeDeps: no .so files found")
            return
        }

        // Patch every .so's RUNPATH to "$ORIGIN" as a best-effort fix.
        // HAPs built without RUNPATH (CMAKE_SKIP_RPATH TRUE) have none;
        // topological dependency loading in nativeLoad handles those.
        for (soFile in soFiles) {
            val patched = com.kira.harmoid.hap.ElfPatcher.patchRunpath(soFile.absolutePath)
            if (patched) {
                Log.e(TAG, "preloadNativeDeps: RUNPATH→\$ORIGIN ${soFile.name}")
            }
        }

        // With RUNPATH=$ORIGIN the linker can walk DT_NEEDED chains
        // transitively, so topological order no longer matters.
        // Still do multi-pass for robustness in case some .so files
        // have non-trivial dependency shapes.
        Log.e(TAG, "preloadNativeDeps: found ${soFiles.size} .so file(s)")
        var remaining = soFiles.toMutableList()
        var pass = 0
        while (remaining.isNotEmpty()) {
            pass++
            var progress = false
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val soFile = iter.next()
                val ret = com.kira.harmoid.hap.ElfPatcher.nativeLoad(soFile.absolutePath)
                if (ret == 0) {
                    iter.remove()
                    progress = true
                    Log.e(TAG, "preloadNativeDeps: loaded ${soFile.name} (pass $pass)")
                    // Patch GOT entries to redirect sigaction to libb.so bridge
                    val n = com.kira.harmoid.hap.ElfPatcher.patchGot(soFile.absolutePath)
                    if (n > 0) Log.e(TAG, "preloadNativeDeps: patched $n sigaction GOT entries in ${soFile.name}")
                } else {
                    Log.w(TAG, "preloadNativeDeps: FAILED ${soFile.name} (nativeLoad returned $ret)")
                }
            }
            if (!progress) break
        }

        if (remaining.isNotEmpty()) {
            Log.w(TAG, "preloadNativeDeps: ${remaining.size} .so(s) still unresolved after $pass passes: ${remaining.map { it.name }}")
        }
    }

    private fun moduleExists(bundleName: String, moduleName: String): Boolean {
        // Check app data dir: filesDir/hap/$bundleName.$moduleName/
        val fullName = "$bundleName.$moduleName"
        val dynamicDir = java.io.File(filesDir, "hap/$fullName")
        if (dynamicDir.isDirectory && dynamicDir.listFiles()?.isNotEmpty() == true) return true

        return false
    }


    companion object {
        private const val TAG = "Harmoid.Ability"

        /** edge-to-edge 自愈开关的存储位置（与设置页的 hoa_ui_prefs 分开，互不影响）。 */
        private const val PREFS_NAME = "hoa_hap_window"
        private const val KEY_EDGE_TO_EDGE = "edge_to_edge_enabled"
    }
}
