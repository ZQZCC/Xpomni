@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal const val TAG = "Xpomni"
internal const val ANDROID_FRAMEWORK = "android"
internal const val INTENT_RESOLVER = "com.android.intentresolver"
internal const val ANDROID_SYSTEM_INTELLIGENCE = "com.google.android.as"
internal const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"
internal const val LAUNCHER3 = "com.android.launcher3"
internal const val SYSTEMUI = "com.android.systemui"
internal const val OPLUS_APPPLATFORM = "com.oplus.appplatform"
internal const val OPLUS_SCREENSHOT = "com.oplus.screenshot"
internal const val FLYME_SYSTEMUIEX = "com.flyme.systemuiex"
internal const val MIUI_SCREENSHOT = "com.miui.screenshot"

internal val SHARE_SHEET_PACKAGE =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        INTENT_RESOLVER
    } else {
        ANDROID_FRAMEWORK
    }

private val fieldCache = ConcurrentHashMap<String, Field>()
private val methodCache = ConcurrentHashMap<String, Method>()

internal object UnhandledHotReloadHook

internal fun XpOmniModule.runHook(
    name: String,
    block: () -> Unit,
) {
    runCatching(block).onFailure { error ->
        log(Log.ERROR, TAG, "$name failed", error)
    }
}

internal fun XpOmniModule.runOptionalHook(
    name: String,
    block: () -> Unit,
) {
    runCatching(block).onFailure { error ->
        if (error !is ClassNotFoundException) {
            log(Log.ERROR, TAG, "$name failed", error)
        }
    }
}

internal fun XpOmniModule.hookMethods(
    clazz: Class<*>,
    vararg names: String,
    block: Chain.() -> Any?,
) {
    for (method in clazz.declaredMethods) {
        if (!method.name.matchesAny(names)) continue
        intercept(method, block)
    }
}

internal fun XpOmniModule.hookConstructors(
    clazz: Class<*>,
    block: Chain.() -> Any?,
) {
    clazz.declaredConstructors.forEach { constructor ->
        intercept(constructor, block)
    }
}

internal fun XpOmniModule.intercept(
    executable: Executable,
    block: Chain.() -> Any?,
) {
    hook(executable)
        .setId(executable.hookId())
        .intercept(Hooker { chain -> chain.block() })
}

internal fun XpOmniModule.replaceHooksForHotReload(handles: List<HookHandle>) {
    var replaced = 0
    var removed = 0
    for (handle in handles) {
        val executable = runCatching { handle.executable }
            .getOrElse { error ->
                runCatching { handle.unhook() }
                removed++
                log(Log.ERROR, TAG, "hot reload hook missing executable", error)
                continue
            }

        val replacement = Hooker { chain ->
            runCatching {
                dispatchHotReloadHook(executable, chain)
            }.getOrElse { error ->
                log(Log.ERROR, TAG, "hot reloaded hook failed: ${executable.hookId()}", error)
                chain.proceed()
            }
        }

        runCatching {
            handle.replaceHook(replacement)
            replaced++
        }.onFailure { error ->
            runCatching { handle.unhook() }
            removed++
            log(Log.ERROR, TAG, "hot reload hook replacement failed: ${executable.hookId()}", error)
        }
    }
    log(Log.INFO, TAG, "hot reload replaced $replaced hooks, removed $removed hooks")
}

private fun XpOmniModule.dispatchHotReloadHook(
    executable: Executable,
    chain: Chain,
): Any? {
    handleBiometricHotReloadHook(executable, chain).handled { return it }
    handleGitHubHotReloadHook(executable, chain).handled { return it }
    handleFludHotReloadHook(executable, chain).handled { return it }
    handleShareSheetHotReloadHook(executable, chain).handled { return it }
    handleScreenshotHotReloadHook(executable, chain).handled { return it }
    handlePixelLauncherHotReloadHook(executable, chain).handled { return it }
    log(Log.WARN, TAG, "hot reload hook missing dispatcher: ${executable.hookId()}")
    return chain.proceed()
}

private inline fun Any?.handled(block: (Any?) -> Unit) {
    if (this !== UnhandledHotReloadHook) block(this)
}

private fun Executable.hookId(): String =
    declaringClass.name +
        "#" +
        name +
        "(" +
        parameterTypes.joinToString(",") { it.name } +
        ")"

internal fun Chain.afterProceed(action: (Any?) -> Unit): Any? {
    val result = proceed()
    action(thisObject)
    return result
}

internal fun Chain.argsArray(): Array<Any?> = args.toTypedArray()

internal inline fun <T> attempt(
    fallback: T,
    block: () -> T,
): T =
    try {
        block()
    } catch (_: Throwable) {
        fallback
    }

internal fun Any.invokeMethod(
    name: String,
    vararg args: Any?,
): Boolean =
    methodOrNull(name, args.size)?.let { method ->
        attempt(false) {
            method.invoke(this, *args)
            true
        }
    } ?: false

internal fun Any.writeField(
    name: String,
    value: Any?,
): Boolean =
    fieldOrNull(name)?.let { field ->
        attempt(false) {
            field.set(this, value)
            true
        }
    } ?: false

internal fun Any.readField(name: String): Any? =
    fieldOrNull(name)?.let { field -> attempt(null) { field.get(this) } }

private fun Any.fieldOrNull(name: String): Field? {
    val key = "${javaClass.name}#$name"
    return fieldCache[key] ?: lookupField(name)?.also { fieldCache[key] = it }
}

private fun Any.lookupField(name: String): Field? {
    var type: Class<*>? = javaClass
    while (type != null) {
        try {
            val field = type.getDeclaredField(name)
            field.isAccessible = true
            return field
        } catch (_: Throwable) {
            type = type.superclass
        }
    }
    return null
}

internal fun Any.methodOrNull(
    name: String,
    parameterCount: Int,
): Method? {
    val key = "${javaClass.name}#$name/$parameterCount"
    return methodCache[key] ?: lookupMethod(name, parameterCount)?.also { methodCache[key] = it }
}

private fun Any.lookupMethod(
    name: String,
    parameterCount: Int,
): Method? {
    var type: Class<*>? = javaClass
    while (type != null) {
        for (method in type.declaredMethods) {
            if (method.name == name && method.parameterCount == parameterCount) {
                method.isAccessible = true
                return method
            }
        }
        type = type.superclass
    }
    return null
}

internal fun String.matchesAny(names: Array<out String>): Boolean {
    for (name in names) {
        if (this == name) return true
    }
    return false
}
