@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.app.ActivityManager
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutManager
import android.widget.BaseAdapter

internal fun XpOmniModule.hookHideDirectShare(classLoader: ClassLoader) {
    runHook("direct share low-ram") {
        hook(ActivityManager::class.java.getDeclaredMethod("isLowRamDeviceStatic")).intercept { true }
    }
    runHook("direct share service count") {
        hook(classLoader.loadClass("com.android.intentresolver.ChooserListAdapter")
            .getDeclaredMethod("getServiceTargetCount")).intercept { 0 }
    }
    runHook("unstack ka.kitool share activities") {
        val adapterClass = classLoader.loadClass("com.android.intentresolver.ChooserListAdapter")
        val sortedList = adapterClass.findField("mSortedList")
        val groupClass = classLoader.loadClass("com.android.intentresolver.chooser.MultiDisplayResolveInfo")
        val getMembers = groupClass.getDeclaredMethod("getAllDisplayTargets")
        val getResolveInfo = classLoader.loadClass("com.android.intentresolver.chooser.DisplayResolveInfo")
            .getDeclaredMethod("getResolveInfo")
        hook(BaseAdapter::class.java.getDeclaredMethod("notifyDataSetChanged")).intercept { chain ->
            if (adapterClass.isInstance(chain.thisObject)) {
                @Suppress("UNCHECKED_CAST")
                val targets = sortedList.get(chain.thisObject) as MutableList<Any>
                for (index in targets.lastIndex downTo 0) {
                    val target = targets[index]
                    if (!groupClass.isInstance(target)) continue
                    val members = getMembers.invoke(target) as List<*>
                    if (members.size < 2) continue
                    val info = getResolveInfo.invoke(members[0]) as ResolveInfo
                    if (info.activityInfo.packageName != "ka.kitool") continue
                    targets.removeAt(index)
                    @Suppress("UNCHECKED_CAST")
                    targets.addAll(index, members as List<Any>)
                }
            }
            chain.proceed()
        }
    }
}

internal fun XpOmniModule.hookShareTargets() {
    val method = ShortcutManager::class.java.getDeclaredMethod("getShareTargets", IntentFilter::class.java)
    hook(method).intercept { emptyList<Any>() }
}
