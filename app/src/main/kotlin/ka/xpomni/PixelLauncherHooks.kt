@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi", "MissingPermission")

package ka.xpomni

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Insets
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference

private const val SLEEP_ACTION = "ka.xpomni.action.PIXEL_LAUNCHER_SLEEP"
private const val SLEEP_SENDER_PERMISSION = "android.permission.BIND_APPWIDGET"
private const val PIXEL_NO_BADGE_FLAG = 2
private const val TASKBAR_ACTIVITY_CONTEXT = "com.android.launcher3.taskbar.TaskbarActivityContext"

internal fun XpOmniModule.hookPixelLauncherFeatures(classLoader: ClassLoader) {
    runHook("hook Pixel Launcher shortcut badges") { hookPixelShortcutBadges(classLoader) }
    runHook("hook Pixel Launcher bottom search bar") { hookPixelBottomSearchBar(classLoader) }
    runHook("hook Pixel Launcher double tap sleep") { hookPixelDoubleTapSleep(classLoader) }
    runHook("hook Pixel Launcher navbar pill") { hookPixelLauncherNavbarPill(classLoader) }
    runHook("hook Pixel Launcher navbar insets") { hookPixelLauncherNavbarInsets(classLoader) }
}

private fun XpOmniModule.hookPixelShortcutBadges(classLoader: ClassLoader) {
    val bubbleClass = classLoader.loadClass("com.android.launcher3.BubbleTextView")
    val hideBadge = bubbleClass.getDeclaredField("mHideBadge").apply { isAccessible = true }
    hookConstructors(bubbleClass, Hooker { chain ->
        chain.afterProceed { hideBadge.setBoolean(it, true) }
    })
    hookMethods(classLoader.loadClass("com.android.launcher3.icons.BitmapInfo"), Hooker { chain ->
        val args = chain.argsArray()
        args[1] = (args[1] as Int) or PIXEL_NO_BADGE_FLAG
        chain.proceed(args)
    }, "newIcon")
}

private fun XpOmniModule.hookPixelBottomSearchBar(classLoader: ClassLoader) {
    var heightId = 0
    for (name in arrayOf("getDimension", "getDimensionPixelOffset", "getDimensionPixelSize")) {
        val method = Resources::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
        val zero: Any = if (name == "getDimension") 0f else 0
        hook(method).intercept { chain ->
            if (heightId == 0) {
                val resolvedId = (chain.thisObject as Resources)
                    .getIdentifier("qsb_widget_height", "dimen", PIXEL_LAUNCHER)
                if (resolvedId != 0) heightId = resolvedId
            }
            if (heightId != 0 && chain.getArg(0) == heightId) zero else chain.proceed()
        }
    }

    val hotseatClass = classLoader.loadClass("com.android.launcher3.Hotseat")
    val qsbField = hotseatClass.getDeclaredField("mQsb").apply { isAccessible = true }
    val hideSearchBar = Hooker { chain ->
        chain.afterProceed { hideLauncherSearchBar(qsbField.get(it) as View) }
    }
    hookConstructors(hotseatClass, hideSearchBar)
    hookMethods(hotseatClass, hideSearchBar, "setInsets")
}

private fun hideLauncherSearchBar(view: View) {
    view.visibility = View.GONE
    view.alpha = 0f
    view.isEnabled = false
    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    view.layoutParams = view.layoutParams?.apply { height = 0 }
}

private fun XpOmniModule.hookPixelDoubleTapSleep(classLoader: ClassLoader) {
    val listenerClass = classLoader.loadClass("com.android.launcher3.touch.WorkspaceTouchListener")
    val detectorField = listenerClass.getDeclaredField("mGestureDetector").apply { isAccessible = true }
    var lastListener = WeakReference<Any>(null)
    hookMethods(listenerClass, Hooker { chain ->
        val listener = chain.thisObject!!
        if (lastListener.get() !== listener) {
            val context = (chain.getArg(0) as View).context.applicationContext
            (detectorField.get(listener) as GestureDetector).setOnDoubleTapListener(
                object : GestureDetector.OnDoubleTapListener {
                    override fun onSingleTapConfirmed(event: MotionEvent) = false
                    override fun onDoubleTapEvent(event: MotionEvent) = false
                    override fun onDoubleTap(event: MotionEvent): Boolean {
                        attempt(Unit) {
                            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        }
                        context.sendBroadcast(
                            Intent(SLEEP_ACTION).setPackage(ANDROID_FRAMEWORK)
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        )
                        return true
                    }
                },
            )
            lastListener = WeakReference(listener)
        }
        chain.proceed()
    }, "onTouch")
}

internal fun XpOmniModule.hookLauncherSleepReceiver(classLoader: ClassLoader) {
    val pwm = classLoader.loadClass("com.android.server.policy.PhoneWindowManager")
    val init = pwm.getDeclaredMethod(
        "init", Context::class.java,
        classLoader.loadClass("com.android.server.policy.WindowManagerPolicy\$WindowManagerFuncs"),
    )
    val goToSleep = PowerManager::class.java.getDeclaredMethod("goToSleep", Long::class.javaPrimitiveType)
        .apply { isAccessible = true }
    hook(init).intercept { chain ->
        chain.proceed().also {
            val context = chain.getArg(0) as Context
            val power = context.getSystemService(PowerManager::class.java)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != SLEEP_ACTION) return
                    runCatching { goToSleep.invoke(power, SystemClock.uptimeMillis()) }
                        .onFailure { log(Log.ERROR, TAG, "launcher sleep receiver failed", it) }
                }
            }, IntentFilter(SLEEP_ACTION), SLEEP_SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
        }
    }
}

private fun XpOmniModule.hookPixelLauncherNavbarPill(classLoader: ClassLoader) {
    val taskbarClass = classLoader.loadClass(TASKBAR_ACTIVITY_CONTEXT)
    val controllers = taskbarClass.findField("mControllers")
    val handleController = controllers.type.findField("stashedHandleViewController")
    val width = handleController.type.findField("mStashedHandleWidth")
    hookMethods(taskbarClass, Hooker { chain ->
        chain.afterProceed { context ->
            width.setInt(handleController.get(controllers.get(context)), 0)
        }
    }, "init")
}

private fun XpOmniModule.hookPixelLauncherNavbarInsets(classLoader: ClassLoader) {
    val paramsClass = WindowManager.LayoutParams::class.java
    val layoutField = classLoader.loadClass(TASKBAR_ACTIVITY_CONTEXT)
        .getDeclaredField("mWindowLayoutParams").apply { isAccessible = true }
    val rotationField = paramsClass.getDeclaredField("paramsForRotation").apply { isAccessible = true }
    val providedField = paramsClass.getDeclaredField("providedInsets").apply { isAccessible = true }
    val providerClass = classLoader.loadClass("android.view.InsetsFrameProvider")
    val getType = providerClass.getDeclaredMethod("getType")
    val setSize = providerClass.getDeclaredMethod("setInsetsSize", Insets::class.java)
    fun clearInsets(params: Any) {
        val providers = providedField.get(params) as Array<*>? ?: return
        for (provider in providers) {
            if (getType.invoke(provider) == WindowInsets.Type.navigationBars())
                setSize.invoke(provider, Insets.NONE)
        }
    }
    hookMethods(classLoader.loadClass(TASKBAR_ACTIVITY_CONTEXT), Hooker { chain ->
        val params = layoutField.get(chain.thisObject) as WindowManager.LayoutParams?
        if (params != null) {
            clearInsets(params)
            (rotationField.get(params) as Array<*>?)?.forEach { if (it != null) clearInsets(it) }
        }
        chain.proceed()
    }, "notifyUpdateLayoutParams")
}
