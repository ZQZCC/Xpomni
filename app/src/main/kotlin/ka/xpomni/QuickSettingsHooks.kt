@file:android.annotation.SuppressLint("DiscouragedApi")

package ka.xpomni

import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable

private const val QUICK_QS_ROWS = "quick_qs_paginated_grid_num_rows"
private const val QUICK_QS_PORTRAIT_ROWS = 3

@Volatile
private var quickSettingsRowHooksInstalled = false

internal fun XpOmniModule.hookQuickSettingsTileRows() {
    synchronized(XpOmniModule::class.java) {
        if (quickSettingsRowHooksInstalled) return@synchronized

        val getInteger = Resources::class.java.getDeclaredMethod(
            "getInteger",
            Int::class.javaPrimitiveType!!,
        )

        intercept(getInteger) {
            val resources = thisObject as Resources
            val resId = getArg(0) as Int
            val replacement = resources.quickSettingsPortraitRows(resId)

            if (replacement != null) {
                replacement
            } else {
                proceed()
            }
        }

        quickSettingsRowHooksInstalled = true
        log(Log.INFO, TAG, "hooked quick settings tile rows")
    }
}

internal fun XpOmniModule.handleQuickSettingsHotReloadHook(
    executable: Executable,
    chain: Chain,
): Any? =
    with(chain) {
        if (executable.declaringClass != Resources::class.java || executable.name != "getInteger") {
            return@with UnhandledHotReloadHook
        }

        val resources = thisObject as Resources
        val resId = getArg(0) as Int
        resources.quickSettingsPortraitRows(resId) ?: proceed()
    }

private fun Resources.quickSettingsPortraitRows(resId: Int): Int? {
    if (configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return null

    val isSystemUiResource = attempt(false) {
        getResourcePackageName(resId) == SYSTEMUI
    }
    if (!isSystemUiResource) return null

    return when (attempt<String?>(null) { getResourceEntryName(resId) }) {
        QUICK_QS_ROWS -> QUICK_QS_PORTRAIT_ROWS
        else -> null
    }
}
