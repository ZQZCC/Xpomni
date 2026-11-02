@file:android.annotation.SuppressLint("DiscouragedApi")

package ka.xpomni

import android.content.res.Configuration
import android.content.res.Resources
import io.github.libxposed.api.XposedInterface.Chain

private const val QUICK_QS_ROWS = "quick_qs_paginated_grid_num_rows"
private const val QUICK_QS_PORTRAIT_ROWS = 3
@Volatile
private var quickSettingsRowsId = 0

internal fun XpOmniModule.hookQuickSettingsTileRows() {
    val getInteger = Resources::class.java.getDeclaredMethod(
        "getInteger",
        Int::class.javaPrimitiveType!!,
    )

    hook(getInteger).intercept(::handleQuickSettingsRows)
}

private fun handleQuickSettingsRows(chain: Chain): Any? =
    with(chain) {
        val resources = thisObject as Resources
        val resId = getArg(0) as Int
        resources.quickSettingsPortraitRows(resId) ?: proceed()
    }

private fun Resources.quickSettingsPortraitRows(resId: Int): Int? {
    if (quickSettingsRowsId != 0 && resId != quickSettingsRowsId) return null
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
