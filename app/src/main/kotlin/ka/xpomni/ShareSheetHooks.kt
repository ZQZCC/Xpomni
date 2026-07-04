@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.app.ActivityManager
import android.content.IntentFilter
import android.content.pm.ShortcutManager
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable

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

    intercept(isLowRamDeviceStatic) {
        true
    }

}

private fun XpOmniModule.hookServiceTargetCountFallback(classLoader: ClassLoader) {
    val chooserListAdapterClass =
        classLoader.loadClass("com.android.intentresolver.ChooserListAdapter")
    val getServiceTargetCount =
        chooserListAdapterClass.getDeclaredMethod("getServiceTargetCount")
            .apply { isAccessible = true }

    intercept(getServiceTargetCount) {
        0
    }

}

internal fun XpOmniModule.hookShareTargets() {
    val getShareTargets =
        ShortcutManager::class.java.getDeclaredMethod(
            "getShareTargets",
            IntentFilter::class.java,
        ).apply { isAccessible = true }

    intercept(getShareTargets) {
        emptyList<Any>()
    }

}

internal fun XpOmniModule.handleShareSheetHotReloadHook(
    executable: Executable,
    chain: Chain,
): Any? =
    with(chain) {
        when {
            executable.declaringClass == ActivityManager::class.java &&
                executable.name == "isLowRamDeviceStatic" -> true

            executable.declaringClass.name == "com.android.intentresolver.ChooserListAdapter" &&
                executable.name == "getServiceTargetCount" -> 0

            executable.declaringClass == ShortcutManager::class.java &&
                executable.name == "getShareTargets" -> emptyList<Any>()

            else -> UnhandledHotReloadHook
        }
    }
