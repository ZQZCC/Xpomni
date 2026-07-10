@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.app.ActivityManager
import android.content.IntentFilter
import android.content.pm.ShortcutManager
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable

private const val SHARE_LOW_RAM_HOOK_ID = "share.low_ram"
private const val SHARE_SERVICE_COUNT_HOOK_ID = "share.service_count"
private const val SHARE_TARGETS_HOOK_ID = "share.targets"

internal fun XpOmniModule.hookHideDirectShare(classLoader: ClassLoader) {
    runHook("hook DirectShare low-ram") {
        hookLowRamDeviceStatic()
    }

    runOptionalHook("hook DirectShare service-target-count") {
        hookServiceTargetCountFallback(classLoader)
    }
}

private fun XpOmniModule.hookLowRamDeviceStatic() {
    val isLowRamDeviceStatic =
        ActivityManager::class.java.getDeclaredMethod("isLowRamDeviceStatic")
            .apply { isAccessible = true }

    intercept(isLowRamDeviceStatic, SHARE_LOW_RAM_HOOK_ID) { true }
}

private fun XpOmniModule.hookServiceTargetCountFallback(classLoader: ClassLoader) {
    val chooserListAdapterClass =
        classLoader.loadClass("com.android.intentresolver.ChooserListAdapter")
    val getServiceTargetCount =
        chooserListAdapterClass.getDeclaredMethod("getServiceTargetCount")
            .apply { isAccessible = true }

    intercept(getServiceTargetCount, SHARE_SERVICE_COUNT_HOOK_ID) { 0 }
}

internal fun XpOmniModule.hookShareTargets() {
    val getShareTargets =
        ShortcutManager::class.java.getDeclaredMethod(
            "getShareTargets",
            IntentFilter::class.java,
        ).apply { isAccessible = true }

    intercept(getShareTargets, SHARE_TARGETS_HOOK_ID) { emptyList<Any>() }
}

internal fun XpOmniModule.resolveShareSheetHotReloadHook(
    hookId: String?,
    executable: Executable,
): Hooker? {
    val legacyLowRam =
        executable.declaringClass == ActivityManager::class.java &&
            executable.name == "isLowRamDeviceStatic"
    val legacyServiceCount =
        executable.declaringClass.name == "com.android.intentresolver.ChooserListAdapter" &&
            executable.name == "getServiceTargetCount"
    val legacyTargets =
        executable.declaringClass == ShortcutManager::class.java &&
            executable.name == "getShareTargets"

    return when {
        hookId == SHARE_LOW_RAM_HOOK_ID || legacyLowRam -> Hooker { true }
        hookId == SHARE_SERVICE_COUNT_HOOK_ID || legacyServiceCount -> Hooker { 0 }
        hookId == SHARE_TARGETS_HOOK_ID || legacyTargets -> Hooker { emptyList<Any>() }
        else -> null
    }
}
