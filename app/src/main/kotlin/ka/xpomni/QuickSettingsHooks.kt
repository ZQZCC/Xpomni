@file:android.annotation.SuppressLint("DiscouragedApi")

package ka.xpomni

import android.content.res.Configuration
import android.content.res.Resources
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable

private const val QUICK_QS_ROWS = "quick_qs_paginated_grid_num_rows"
private const val QUICK_QS_PORTRAIT_ROWS = 3
private const val QUICK_SETTINGS_ROWS_HOOK_ID = "quick_settings.rows"

@Volatile
private var quickSettingsRowHooksInstalled = false

@Volatile
private var quickSettingsRowsId = 0

internal fun XpOmniModule.hookQuickSettingsTileRows() {
    synchronized(XpOmniModule::class.java) {
        if (quickSettingsRowHooksInstalled) return@synchronized

        val getInteger = Resources::class.java.getDeclaredMethod(
            "getInteger",
            Int::class.javaPrimitiveType!!,
        )

        intercept(getInteger, QUICK_SETTINGS_ROWS_HOOK_ID) {
            handleQuickSettingsRows(this)
        }

        quickSettingsRowHooksInstalled = true
    }
}

internal fun XpOmniModule.resolveQuickSettingsHotReloadHook(
    hookId: String?,
    executable: Executable,
): Hooker? {
    val legacyMatch =
        executable.declaringClass == Resources::class.java && executable.name == "getInteger"
    if (hookId != QUICK_SETTINGS_ROWS_HOOK_ID && !legacyMatch) return null

    return Hooker { chain -> handleQuickSettingsRows(chain) }
}

private fun handleQuickSettingsRows(chain: Chain): Any? =
    with(chain) {
        val resources = thisObject as Resources
        val resId = getArg(0) as Int
        resources.quickSettingsPortraitRows(resId) ?: proceed()
    }

private fun Resources.quickSettingsPortraitRows(resId: Int): Int? {
    if (configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return null

    var targetId = quickSettingsRowsId
    if (targetId == 0) {
        targetId = attempt(0) {
            getIdentifier(QUICK_QS_ROWS, "integer", SYSTEMUI)
        }
        if (targetId != 0) quickSettingsRowsId = targetId
    }

    return if (resId == targetId) QUICK_QS_PORTRAIT_ROWS else null
}
