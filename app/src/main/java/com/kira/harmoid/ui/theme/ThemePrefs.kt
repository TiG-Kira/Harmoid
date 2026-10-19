package com.kira.harmoid.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观偏好：Material You 动态取色开关。
 *
 * 与 KiTerminal-UX 的 `AppThemePrefs` 行为一致：写入同时更新 SharedPreferences
 * 与 StateFlow，Composable 侧订阅 StateFlow，开关一拨下一次重组立即换色，
 * 不必重启 App。
 */
object ThemePrefs {

    private const val PREFS_NAME = "hoa_ui_prefs"
    private const val KEY_MATERIAL_YOU = "material_you_enabled"

    private val _materialYouEnabled = MutableStateFlow(false)
    val materialYouEnabled: StateFlow<Boolean> = _materialYouEnabled.asStateFlow()

    @Volatile
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        _materialYouEnabled.value = prefs(context).getBoolean(KEY_MATERIAL_YOU, false)
        loaded = true
    }

    @Synchronized
    fun setMaterialYouEnabled(context: Context, enabled: Boolean) {
        _materialYouEnabled.value = enabled
        prefs(context).edit().putBoolean(KEY_MATERIAL_YOU, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
