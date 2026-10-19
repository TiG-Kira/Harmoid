package com.kira.harmoid.device

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 模拟机型档案。
 *
 * HOA 把一套「设备身份」抽象成一个档案：品牌、厂商、机型、产品名、设备代号、
 * 主板、Fingerprint，以及面向 HAP 展示的 HarmonyOS 版本号。
 *
 * 应用点有两处：
 *  1. [BuildSpoofer] 在每个进程启动时反射覆盖 `android.os.Build` 的静态字段，
 *     这样 ArkUI-X 的 `DeviceInfoPlugin`（Java 层直接读 `Build.MODEL` 等字段）
 *     给 HAP 的 `@ohos.deviceInfo` 返回的就是档案里的机型。
 *  2. HDC 的 `param get const.product.*` 同步返回档案值，DevEco Studio 看到的设备
 *     信息与 HAP 内看到的一致。
 */
data class DeviceProfile(
    /** 稳定 id，用于持久化与预设匹配。 */
    val id: String,
    /** 设置页展示名，如「HUAWEI Mate 60 Pro」。 */
    val displayName: String,
    val brand: String,
    val manufacturer: String,
    /** ro.product.model —— deviceInfo.productModel / marketName。 */
    val model: String,
    /** ro.product.name —— deviceInfo.marketName。 */
    val product: String,
    /** ro.product.device —— deviceInfo.productSeries。 */
    val device: String,
    /** ro.product.board —— deviceInfo.hardwareModel。 */
    val board: String,
    /** ro.hardware。 */
    val hardware: String,
    /** ro.build.fingerprint，留空则由其它字段拼一个合理值。 */
    val fingerprint: String = "",
    /** 面向 HAP 报告的 HarmonyOS 版本（仅用于展示/参数查询）。 */
    val osVersion: String = "5.0.0",
    /** 是否为用户自定义档案（预设不可删除/改名）。 */
    val isCustom: Boolean = false,
) {
    /** 是否等价于「不模拟」，即完全跟随宿主设备。 */
    val isSystem: Boolean get() = id == SYSTEM_ID

    /**
     * `const.product.devicetype`。
     *
     * 平板预设（MatePad）应当报 `tablet`，否则按机型做布局分流的 HAP 会拿到
     * 手机布局。没有单独的字段，就按机型/展示名里的 "Pad" 判断。
     */
    val deviceType: String
        get() = if (model.contains("pad", ignoreCase = true) ||
            displayName.contains("pad", ignoreCase = true)
        ) "tablet" else "phone"

    fun effectiveFingerprint(): String =
        fingerprint.ifBlank {
            "$brand/$product/$device:${osVersion}/${model}:user/release-keys"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("brand", brand)
        put("manufacturer", manufacturer)
        put("model", model)
        put("product", product)
        put("device", device)
        put("board", board)
        put("hardware", hardware)
        put("fingerprint", fingerprint)
        put("osVersion", osVersion)
        put("isCustom", isCustom)
    }

    companion object {
        const val SYSTEM_ID = "system"

        fun fromJson(json: JSONObject): DeviceProfile = DeviceProfile(
            id = json.optString("id", SYSTEM_ID),
            displayName = json.optString("displayName", "未命名机型"),
            brand = json.optString("brand", ""),
            manufacturer = json.optString("manufacturer", ""),
            model = json.optString("model", ""),
            product = json.optString("product", ""),
            device = json.optString("device", ""),
            board = json.optString("board", ""),
            hardware = json.optString("hardware", ""),
            fingerprint = json.optString("fingerprint", ""),
            osVersion = json.optString("osVersion", "5.0.0"),
            isCustom = json.optBoolean("isCustom", true),
        )

        /**
         * 真机身份的进程内快照。
         *
         * 必须在 [BuildSpoofer.apply] **之前** 抓一次：覆盖 Build 之后，
         * [system] 读到的就是被改过的值，「恢复跟随宿主」会恢复成上次模拟的
         * 机型而不是真机。
         */
        @Volatile
        private var hostSnapshot: DeviceProfile? = null

        /** 在 Application.onCreate 最早期调用一次，把真机身份记下来。 */
        fun captureHost() {
            if (hostSnapshot != null) return
            hostSnapshot = DeviceProfile(
                id = SYSTEM_ID,
                displayName = "跟随宿主设备",
                brand = android.os.Build.BRAND,
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                product = android.os.Build.PRODUCT,
                device = android.os.Build.DEVICE,
                board = android.os.Build.BOARD,
                hardware = android.os.Build.HARDWARE,
                fingerprint = android.os.Build.FINGERPRINT,
                osVersion = "5.0.0",
            )
        }

        /** 「跟随宿主」：不覆盖任何 Build 字段。 */
        fun system(): DeviceProfile = hostSnapshot ?: DeviceProfile(
            id = SYSTEM_ID,
            displayName = "跟随宿主设备",
            brand = android.os.Build.BRAND,
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            product = android.os.Build.PRODUCT,
            device = android.os.Build.DEVICE,
            board = android.os.Build.BOARD,
            hardware = android.os.Build.HARDWARE,
            osVersion = "5.0.0",
        )

        /**
         * 内置预设。
         *
         * 这些档案只影响「应用看到的设备身份」，与真实硬件无关；机型串用于兼容性
         * 测试（有些 HAP 会按机型开功能开关），不宣称与真机完全一致。
         */
        val PRESETS: List<DeviceProfile> = listOf(
            DeviceProfile(
                id = "huawei_mate_60_pro",
                displayName = "HUAWEI Mate 60 Pro",
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "ALN-AL00",
                product = "ALN-AL00",
                device = "ALN-AL00",
                board = "ALN-AL00",
                hardware = "kirin9000s",
                osVersion = "5.0.0",
            ),
            DeviceProfile(
                id = "huawei_mate_70_pro",
                displayName = "HUAWEI Mate 70 Pro",
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "PLR-AL00",
                product = "PLR-AL00",
                device = "PLR-AL00",
                board = "PLR-AL00",
                hardware = "kirin9020",
                osVersion = "5.0.1",
            ),
            DeviceProfile(
                id = "huawei_pura_70_ultra",
                displayName = "HUAWEI Pura 70 Ultra",
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "HBP-AL00",
                product = "HBP-AL00",
                device = "HBP-AL00",
                board = "HBP-AL00",
                hardware = "kirin9010",
                osVersion = "5.0.0",
            ),
            DeviceProfile(
                id = "huawei_mate_x5",
                displayName = "HUAWEI Mate X5",
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "ALT-AL00",
                product = "ALT-AL00",
                device = "ALT-AL00",
                board = "ALT-AL00",
                hardware = "kirin9000s",
                osVersion = "5.0.0",
            ),
            DeviceProfile(
                id = "huawei_matepad_pro_13",
                displayName = "HUAWEI MatePad Pro 13.2",
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "PCE-W29",
                product = "PCE-W29",
                device = "PCE-W29",
                board = "PCE-W29",
                hardware = "kirin9000s",
                osVersion = "5.0.0",
            ),
            DeviceProfile(
                id = "openharmony_generic",
                displayName = "OpenHarmony 通用设备",
                brand = "OpenHarmony",
                manufacturer = "OpenHarmony",
                model = "ohos-generic",
                product = "ohos-generic",
                device = "ohos-generic",
                board = "ohos-generic",
                hardware = "generic",
                osVersion = "5.0.0",
            ),
        )

        fun findPreset(id: String): DeviceProfile? = PRESETS.firstOrNull { it.id == id }
    }
}

/**
 * 机型档案的持久化。
 *
 * 主进程写入，`:hap*` 进程启动时读取 —— 各进程有独立的 SharedPreferences 缓存，
 * 因此这里刻意每次 [load] 都直接读文件（[Context.getSharedPreferences] 本身是
 * 跨进程安全的 MODE_PRIVATE 文件），保证刚改完机型下次启动 HAP 就生效。
 */
object DeviceProfileStore {

    private const val PREFS_NAME = "hoa_device_profile"
    private const val KEY_JSON = "profile_json"

    @Volatile
    private var cached: DeviceProfile? = null

    fun load(context: Context): DeviceProfile {
        cached?.let { return it }
        val app = context.applicationContext
        val raw = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        val profile = if (raw.isNullOrBlank()) {
            DeviceProfile.system()
        } else {
            runCatching { DeviceProfile.fromJson(JSONObject(raw)) }
                .getOrElse { DeviceProfile.system() }
        }
        cached = profile
        return profile
    }

    fun save(context: Context, profile: DeviceProfile) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, profile.toJson().toString())
            .commit()
        cached = profile
        BuildSpoofer.apply(profile)
    }

    /** 测试/调试用：清掉进程内缓存，让下一次 [load] 重新读盘。 */
    fun invalidate() {
        cached = null
    }
}

/**
 * 把 [DeviceProfile] 应用 `android.os.Build` 的静态字段。
 *
 * 为什么可以这么做：ArkUI-X 的 `DeviceInfoPlugin` 是 **Java** 类，字段读取发生在
 * Java 侧（`return Build.MODEL;`），JNI 只是调用它的方法。ART 上静态字段被反射
 * 改写后，JNI `GetStaticObjectField` 读到的就是新值，于是 `@ohos.deviceInfo`
 * 返回的机型跟着变。
 *
 * 实现优先用 `sun.misc.Unsafe`（不受 final 语义限制），失败再退回普通反射。
 * 两条路都失败时只记日志，不影响启动 —— 模拟机型是增强能力，不该拖垮运行。
 */
object BuildSpoofer {

    private const val TAG = "Harmoid.DeviceProfile"

    @Volatile
    private var appliedId: String? = null

    fun apply(profile: DeviceProfile) {
        if (profile.isSystem) return
        if (appliedId == profile.id) return

        // 先触发一次类初始化，避免把「初始化前」的字段覆盖又被 Build 的
        // static 初始化块写回宿主值。
        @Suppress("UNUSED_EXPRESSION")
        android.os.Build.MODEL

        var ok = 0
        ok += setStatic("BRAND", profile.brand)
        ok += setStatic("MANUFACTURER", profile.manufacturer)
        ok += setStatic("MODEL", profile.model)
        ok += setStatic("PRODUCT", profile.product)
        ok += setStatic("DEVICE", profile.device)
        ok += setStatic("BOARD", profile.board)
        ok += setStatic("HARDWARE", profile.hardware)
        ok += setStatic("FINGERPRINT", profile.effectiveFingerprint())

        appliedId = profile.id
        android.util.Log.i(TAG, "Build spoofed → ${profile.displayName} ($ok/8 fields)")
    }

    private fun setStatic(field: String, value: String): Int {
        if (value.isBlank()) return 0
        return try {
            unsafeSet(field, value)
        } catch (t: Throwable) {
            try {
                reflectiveSet(field, value)
            } catch (t2: Throwable) {
                android.util.Log.w(TAG, "Cannot spoof Build.$field: ${t2.message}")
                0
            }
        }
    }

    private fun unsafeSet(field: String, value: String): Int {
        val unsafe = UnsafeHolder.instance ?: throw IllegalStateException("Unsafe unavailable")
        val f = android.os.Build::class.java.getDeclaredField(field)
        f.isAccessible = true
        val base = unsafe.staticFieldBase(f)
        val offset = unsafe.staticFieldOffset(f)
        unsafe.putObject(base, offset, value)
        return 1
    }

    private fun reflectiveSet(field: String, value: String): Int {
        val f = android.os.Build::class.java.getDeclaredField(field)
        f.isAccessible = true
        f.set(null, value)
        return 1
    }

    /** 延迟拿到 Unsafe 单例；拿不到就返回 null，走反射兜底。 */
    private object UnsafeHolder {
        val instance: sun.misc.Unsafe? by lazy {
            runCatching {
                val clazz = Class.forName("sun.misc.Unsafe")
                val f = clazz.getDeclaredField("theUnsafe")
                f.isAccessible = true
                f.get(null) as sun.misc.Unsafe
            }.getOrNull()
        }
    }
}
