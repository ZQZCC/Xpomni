@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Field

internal const val TAG = "Xpomni"
internal const val ANDROID_FRAMEWORK = "android"
internal const val INTENT_RESOLVER = "com.android.intentresolver"
internal const val ANDROID_SYSTEM_INTELLIGENCE = "com.google.android.as"
internal const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"
internal const val SYSTEMUI = "com.android.systemui"

internal fun XpOmniModule.runHook(name: String, block: () -> Unit) {
    runCatching(block).onFailure { log(Log.ERROR, TAG, "$name failed", it) }
}

internal fun XpOmniModule.hookMethods(clazz: Class<*>, hooker: Hooker, vararg names: String) {
    var found = false
    for (method in clazz.declaredMethods) {
        if (method.name !in names) continue
        hook(method).intercept(hooker)
        found = true
    }
    check(found) { "No method ${names.joinToString()} in ${clazz.name}" }
}

internal fun XpOmniModule.hookConstructors(clazz: Class<*>, hooker: Hooker) {
    clazz.declaredConstructors.forEach { hook(it).intercept(hooker) }
}

internal fun Chain.afterProceed(action: (Any?) -> Unit): Any? {
    val result = proceed()
    action(thisObject)
    return result
}

internal fun Chain.argsArray(): Array<Any?> = Array(executable.parameterCount) { getArg(it) }

internal inline fun <T> attempt(fallback: T, block: () -> T): T =
    try { block() } catch (_: Throwable) { fallback }

internal fun Class<*>.findField(name: String): Field {
    var type: Class<*>? = this
    while (type != null) {
        val field = try { type.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
        if (field != null) return field.apply { isAccessible = true }
        type = type.superclass
    }
    throw NoSuchFieldException("$this.$name")
}
