@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.app.ActivityManager
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutManager
import android.widget.BaseAdapter
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable

private const val SHARE_LOW_RAM_HOOK_ID = "share.low_ram"
private const val SHARE_SERVICE_COUNT_HOOK_ID = "share.service_count"
private const val SHARE_TARGETS_HOOK_ID = "share.targets"
private const val SHARE_KITOOL_UNSTACK_HOOK_ID = "share.kitool_unstack"
private const val KITOOL_PACKAGE = "ka.kitool"

internal fun XpOmniModule.hookHideDirectShare(classLoader: ClassLoader) {
    runHook("hook DirectShare low-ram") {
        hookLowRamDeviceStatic()
    }

    runOptionalHook("hook DirectShare service-target-count") {
        hookServiceTargetCountFallback(classLoader)
    }

    runHook("unstack ka.kitool share activities") {
        hookKitoolShareActivities()
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

private fun XpOmniModule.hookKitoolShareActivities() {
    val notifyDataSetChanged =
        BaseAdapter::class.java.getDeclaredMethod("notifyDataSetChanged")
            .apply { isAccessible = true }

    intercept(notifyDataSetChanged, SHARE_KITOOL_UNSTACK_HOOK_ID) {
        unstackKitoolShareActivities()
    }
}

private fun Chain.unstackKitoolShareActivities(): Any? {
    attempt(Unit) {
        thisObject?.unstackKitoolShareActivities()
    }
    return proceed()
}

@Suppress("UNCHECKED_CAST")
private fun Any.unstackKitoolShareActivities() {
    val targets = readField("mSortedList") as? ArrayList<Any?> ?: return
    for (index in targets.lastIndex downTo 0) {
        val members = targets[index]?.shareGroupMembersOrNull() ?: continue
        if (members.firstOrNull()?.resolvedPackageName() != KITOOL_PACKAGE) continue
        targets.removeAt(index)
        targets.addAll(index, members)
    }
}

private fun Any.shareGroupMembersOrNull(): List<*>? {
    val method =
        methodOrNull("getAllDisplayTargets", 0)
            ?: methodOrNull("getTargets", 0)
            ?: return null
    return attempt<List<*>?>(null) {
        (method.invoke(this) as? List<*>)?.takeIf { it.size > 1 }
    }
}

private fun Any.resolvedPackageName(): String? {
    val method = methodOrNull("getResolveInfo", 0) ?: return null
    val resolveInfo = attempt<ResolveInfo?>(null) { method.invoke(this) as? ResolveInfo }
    return resolveInfo?.activityInfo?.packageName
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
    val legacyKitoolUnstack =
        executable.declaringClass == BaseAdapter::class.java &&
            executable.name == "notifyDataSetChanged"

    return when {
        hookId == SHARE_LOW_RAM_HOOK_ID || legacyLowRam -> Hooker { true }
        hookId == SHARE_SERVICE_COUNT_HOOK_ID || legacyServiceCount -> Hooker { 0 }
        hookId == SHARE_TARGETS_HOOK_ID || legacyTargets -> Hooker { emptyList<Any>() }
        hookId == SHARE_KITOOL_UNSTACK_HOOK_ID || legacyKitoolUnstack ->
            Hooker { chain -> chain.unstackKitoolShareActivities() }
        else -> null
    }
}
