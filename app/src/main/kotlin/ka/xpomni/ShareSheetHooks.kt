@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.app.ActivityManager
import android.content.IntentFilter
import android.content.pm.ShortcutManager
import android.util.Log

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

    log(Log.INFO, TAG, "hooked DirectShare low-ram")
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

    log(Log.INFO, TAG, "hooked DirectShare service-target-count")
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

    log(Log.INFO, TAG, "hooked DirectShare share-targets")
}
