package com.kira.harmoid.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-local identity of the HAP ability hosted by this process.
 *
 * Every ArkUI-X plugin (`ohos.ace.plugin.*`) is instantiated by
 * `ohos.ace.adapter.capability.plugin.PluginManager` **inside the same
 * process** as the `HoaAbilityActivity{N}` that hosts the HAP, so a
 * process-wide singleton is enough for a plugin to answer the question
 * "which HAP am I serving right now?".
 *
 * HoaAbilityActivity publishes itself on onCreate; the plugins then use
 * the information to route Android-side events (notification taps, media
 * controls, live-update dismissal) back into the ArkTS ability.
 */
object HapSession {

    private const val TAG = "Harmoid.Session"
    private const val LOG_TAG = "Harmoid.Session"

    /**
     * Extra consumed by StageActivity: JSON-encoded OHOS `WantParams`.
     * Must match the private `WANT_PARAMS` constant in
     * `ohos.stage.ability.adapter.StageActivity`.
     */
    const val EXTRA_WANT_PARAMS = "params"

    const val EXTRA_BUNDLE_NAME = "BUNDLE_NAME"
    const val EXTRA_MODULE_NAME = "MODULE_NAME"
    const val EXTRA_ABILITY_NAME = "ABILITY_NAME"
    const val EXTRA_PROCESS_SLOT = "PROCESS_SLOT"

    /** WantParams value-type ids, mirroring ohos.ace.adapter.WantParams. */
    private const val TYPE_BOOLEAN = 1
    private const val TYPE_INT = 5
    private const val TYPE_DOUBLE = 9
    private const val TYPE_STRING = 10

    data class Info(
        val bundleName: String,
        val moduleName: String,
        val abilityName: String,
        val slot: Int,
    ) {
        /** Same shape as StageActivity instanceName: "bundle:module:ability:". */
        val instanceName: String
            get() = "$bundleName:$moduleName:$abilityName:"
    }

    @Volatile
    var current: Info? = null
        private set

    @JvmStatic
    fun attach(bundleName: String, moduleName: String, abilityName: String, slot: Int) {
        current = Info(bundleName, moduleName, abilityName, slot)
        Log.i(TAG, "attached: ${current!!.instanceName} slot=$slot")
    }

    @JvmStatic
    fun detach() {
        current = null
    }

    // ------------------------------------------------------------------
    // WantParams serialisation
    // ------------------------------------------------------------------

    /**
     * Builds the JSON payload understood by `ohos.ace.adapter.WantParams`:
     *
     * ```json
     * {"params":[{"key":"notificationId","type":5,"value":7}]}
     * ```
     */
    @JvmStatic
    fun wantParams(vararg entries: Pair<String, Any?>): String {
        val array = JSONArray()
        for ((key, value) in entries) {
            if (value == null) continue
            val type = when (value) {
                is Boolean -> TYPE_BOOLEAN
                is Int, is Long -> TYPE_INT
                is Float, is Double -> TYPE_DOUBLE
                else -> TYPE_STRING
            }
            array.put(JSONObject().apply {
                put("key", key)
                put("type", type)
                put("value", value)
            })
        }
        return JSONObject().put("params", array).toString()
    }

    // ------------------------------------------------------------------
    // Launch intents
    // ------------------------------------------------------------------

    /**
     * Intent that brings the HAP ability to the foreground and hands the
     * supplied `want` to it.
     *
     * When the ability is already running, `HoaAbilityActivity{N}` is
     * declared `singleTop`, so StageActivity.onNewIntent() fires and the
     * payload is delivered as `UIAbility.onNewWant()`.  When the process
     * is gone, onCreate() delivers it as the initial `want` instead.
     *
     * Falls back to the shortcut relay (which allocates a fresh slot) when
     * this process has no HAP attached — e.g. a notification posted from
     * the main process.
     */
    @JvmStatic
    fun launchIntent(context: Context, params: String): Intent? {
        val info = current
        val pkg = context.packageName
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP

        if (info != null && info.slot >= 0) {
            return Intent().apply {
                setClassName(pkg, "$pkg.HoaAbilityActivity${info.slot}")
                putExtra(EXTRA_BUNDLE_NAME, info.bundleName)
                putExtra(EXTRA_MODULE_NAME, info.moduleName)
                putExtra(EXTRA_ABILITY_NAME, info.abilityName)
                putExtra(EXTRA_PROCESS_SLOT, info.slot)
                putExtra(EXTRA_WANT_PARAMS, params)
                addFlags(flags)
            }
        }

        // No HAP attached to this process: nothing sensible to route to.
        Log.w(TAG, "no HAP session attached — cannot route want")
        return null
    }
}
