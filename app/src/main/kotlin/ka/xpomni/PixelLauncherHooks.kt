@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi", "MissingPermission")

package ka.xpomni

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Insets
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

private const val DOUBLE_TAP_TIMEOUT = 400L
private const val TAP_DISTANCE_THRESHOLD = 50f
private const val QSB_WIDGET_HEIGHT = "qsb_widget_height"
private const val TASKBAR_ACTIVITY_CONTEXT = "com.android.launcher3.taskbar.TaskbarActivityContext"
private const val SLEEP_ACTION = "ka.xpomni.action.PIXEL_LAUNCHER_SLEEP"
private const val SLEEP_TOKEN_EXTRA = "ka.xpomni.extra.SLEEP_TOKEN"
private const val SLEEP_TOKEN = "xpomni_pixel_launcher_sleep"

private var firstTapTime = 0L
private var firstTapX = 0f
private var firstTapY = 0f
private var isFirstTapRunning = false
private var isFirstTapComplete = false
private var pixelLauncherContext: Context? = null

@Volatile
private var sleepReceiverRegistered = false

@Volatile
private var sleepReceiverContext: Context? = null

@Volatile
private var launcherSleepReceiver: BroadcastReceiver? = null

@Volatile
private var pixelResourceHooksInstalled = false

@Volatile
private var qsbWidgetHeightId = 0

@Volatile
private var goToSleepMethod: Method? = null

private val nonQsbWidgetHeightIds = ConcurrentHashMap.newKeySet<Int>()
private val sentFromUidMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
    BroadcastReceiver::class.java
        .getDeclaredMethod("getSentFromUid")
        .apply { isAccessible = true }
}

internal fun XpOmniModule.hookPixelLauncherFeatures(classLoader: ClassLoader) {
    runOptionalHook("hook Pixel Launcher shortcut badges") {
        hookPixelShortcutBadges(classLoader)
    }
    runOptionalHook("hook Pixel Launcher bottom search bar") {
        hookPixelBottomSearchBar(classLoader)
        hookPixelSearchBarResources()
    }
    runOptionalHook("hook Pixel Launcher double tap sleep") {
        hookPixelDoubleTapSleep(classLoader)
    }
    runOptionalHook("hook Pixel Launcher navbar pill") {
        hookPixelLauncherNavbarPill(classLoader)
    }
    runOptionalHook("hook Pixel Launcher navbar insets") {
        hookPixelLauncherNavbarInsets(classLoader)
    }
}

private fun XpOmniModule.hookPixelShortcutBadges(classLoader: ClassLoader) {
    val bubbleTextViewClass = classLoader.loadClass("com.android.launcher3.BubbleTextView")

    hookConstructors(bubbleTextViewClass) {
        afterProceed { view ->
            view?.writeField("mHideBadge", true)
        }
    }

    hookMethods(bubbleTextViewClass, "setHideBadge") {
        afterProceed { view ->
            view?.writeField("mHideBadge", true)
        }
    }

    runOptionalHook("hook Pixel Launcher bitmap badges") {
        val bitmapInfoClass = classLoader.loadClass("com.android.launcher3.icons.BitmapInfo")

        hookMethods(bitmapInfoClass, "applyFlags") {
            val result = proceed()
            getArg(1)?.clearLauncherDrawableBadge()
            result
        }

        hookMethods(bitmapInfoClass, "newIcon") {
            proceed().also { icon ->
                icon?.clearLauncherDrawableBadge()
            }
        }
    }

}

private fun XpOmniModule.hookPixelBottomSearchBar(classLoader: ClassLoader) {
    val hotseatClass = classLoader.loadClass("com.android.launcher3.Hotseat")

    hookConstructors(hotseatClass) {
        afterProceed { hotseat ->
            hotseat?.hideLauncherSearchBar()
        }
    }

    hookMethods(hotseatClass, "setInsets") {
        afterProceed { hotseat ->
            hotseat?.hideLauncherSearchBar()
        }
    }

}

private fun XpOmniModule.hookPixelSearchBarResources() {
    synchronized(XpOmniModule::class.java) {
        if (pixelResourceHooksInstalled) return@synchronized
        hookResourceDimension("getDimension", 0f)
        hookResourceDimension("getDimensionPixelOffset", 0)
        hookResourceDimension("getDimensionPixelSize", 0)
        pixelResourceHooksInstalled = true
    }
}

private fun XpOmniModule.hookResourceDimension(
    methodName: String,
    replacement: Any,
) {
    val method = Resources::class.java.getDeclaredMethod(
        methodName,
        Int::class.javaPrimitiveType!!,
    )

    intercept(method) {
        val resources = thisObject as Resources
        val resId = getArg(0) as Int
        if (resources.isLauncherQsbHeight(resId)) {
            replacement
        } else {
            proceed()
        }
    }
}

private fun XpOmniModule.hookPixelDoubleTapSleep(classLoader: ClassLoader) {
    val workspaceTouchListenerClass =
        classLoader.loadClass("com.android.launcher3.touch.WorkspaceTouchListener")

    hookMethods(workspaceTouchListenerClass, "onTouch") {
        val result = proceed()
        val event = getArg(1) as MotionEvent
        val context = thisObject?.launcherTouchContext() ?: return@hookMethods result
        handleDoubleTapToSleep(context, event)
        result
    }

}

private fun XpOmniModule.hookPixelLauncherNavbarPill(classLoader: ClassLoader) {
    val taskbarActivityContextClass = classLoader.loadClass(TASKBAR_ACTIVITY_CONTEXT)

    hookMethods(taskbarActivityContextClass, "init") {
        afterProceed { taskbarContext ->
            taskbarContext?.hideLauncherNavbarPill()
        }
    }

}

private fun XpOmniModule.hookPixelLauncherNavbarInsets(classLoader: ClassLoader) {
    val taskbarActivityContextClass = classLoader.loadClass(TASKBAR_ACTIVITY_CONTEXT)

    hookMethods(taskbarActivityContextClass, "notifyUpdateLayoutParams") {
        thisObject?.hideLauncherNavbarInsets()
        proceed()
    }

}

internal fun XpOmniModule.hookLauncherSleepReceiver(classLoader: ClassLoader) {
    val phoneWindowManagerClass =
        classLoader.loadClass("com.android.server.policy.PhoneWindowManager")

    hookMethods(phoneWindowManagerClass, "init") {
        proceed().also {
            (args.firstOrNull { it is Context } as? Context)
                ?.let(::registerLauncherSleepReceiver)
        }
    }
}

private fun XpOmniModule.registerLauncherSleepReceiver(context: Context) {
    if (sleepReceiverRegistered) return

    synchronized(XpOmniModule::class.java) {
        if (sleepReceiverRegistered) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent?) {
                if (intent?.action != SLEEP_ACTION ||
                    intent.getStringExtra(SLEEP_TOKEN_EXTRA) != SLEEP_TOKEN ||
                    !isTrustedLauncherSleepSender(receiverContext)
                ) {
                    return
                }

                runCatching {
                    receiverContext.goToSleepNow()
                }.onFailure { error ->
                    log(Log.ERROR, TAG, "launcher sleep receiver failed", error)
                }
            }
        }
        val filter = IntentFilter(SLEEP_ACTION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        sleepReceiverContext = context
        launcherSleepReceiver = receiver
        sleepReceiverRegistered = true
    }
}

internal fun pixelLauncherHotReloadState(): Context? =
    if (launcherSleepReceiver != null) sleepReceiverContext else null

internal fun XpOmniModule.releasePixelLauncherHotReloadState(): Boolean {
    val receiver = launcherSleepReceiver ?: return true
    val context = sleepReceiverContext ?: return true.also {
        launcherSleepReceiver = null
        sleepReceiverRegistered = false
    }

    return runCatching {
        context.unregisterReceiver(receiver)
    }.onFailure { error ->
        log(Log.WARN, TAG, "launcher sleep receiver unregister failed", error)
    }.isSuccess.also { released ->
        if (released) {
            launcherSleepReceiver = null
            sleepReceiverContext = null
            sleepReceiverRegistered = false
        }
    }
}

internal fun XpOmniModule.restorePixelLauncherHotReloadState(state: Any?) {
    val context = state as? Context ?: return
    registerLauncherSleepReceiver(context)
}

internal fun XpOmniModule.handlePixelLauncherHotReloadHook(
    executable: Executable,
    chain: Chain,
): Any? =
    with(chain) {
        when {
            executable.declaringClass.name == "com.android.launcher3.BubbleTextView" &&
                executable.name == "setHideBadge" -> {
                afterProceed { view ->
                    view?.writeField("mHideBadge", true)
                }
            }

            executable.declaringClass.name == "com.android.launcher3.BubbleTextView" -> {
                afterProceed { view ->
                    view?.writeField("mHideBadge", true)
                }
            }

            executable.declaringClass.name == "com.android.launcher3.icons.BitmapInfo" &&
                executable.name == "applyFlags" -> {
                val result = proceed()
                getArg(1)?.clearLauncherDrawableBadge()
                result
            }

            executable.declaringClass.name == "com.android.launcher3.icons.BitmapInfo" &&
                executable.name == "newIcon" -> {
                proceed().also { icon ->
                    icon?.clearLauncherDrawableBadge()
                }
            }

            executable.declaringClass.name == "com.android.launcher3.Hotseat" &&
                executable.name == "setInsets" -> {
                afterProceed { hotseat ->
                    hotseat?.hideLauncherSearchBar()
                }
            }

            executable.declaringClass.name == "com.android.launcher3.Hotseat" -> {
                afterProceed { hotseat ->
                    hotseat?.hideLauncherSearchBar()
                }
            }

            executable.declaringClass == Resources::class.java &&
                executable.name == "getDimension" -> {
                val resources = thisObject as Resources
                val resId = getArg(0) as Int
                if (resources.isLauncherQsbHeight(resId)) 0f else proceed()
            }

            executable.declaringClass == Resources::class.java &&
                (executable.name == "getDimensionPixelOffset" ||
                    executable.name == "getDimensionPixelSize") -> {
                val resources = thisObject as Resources
                val resId = getArg(0) as Int
                if (resources.isLauncherQsbHeight(resId)) 0 else proceed()
            }

            executable.declaringClass.name == "com.android.launcher3.touch.WorkspaceTouchListener" &&
                executable.name == "onTouch" -> {
                val result = proceed()
                val event = getArg(1) as MotionEvent
                val context = thisObject?.launcherTouchContext() ?: return@with result
                handleDoubleTapToSleep(context, event)
                result
            }

            executable.declaringClass.name == TASKBAR_ACTIVITY_CONTEXT &&
                executable.name == "init" -> {
                afterProceed { taskbarContext ->
                    taskbarContext?.hideLauncherNavbarPill()
                }
            }

            executable.declaringClass.name == TASKBAR_ACTIVITY_CONTEXT &&
                executable.name == "notifyUpdateLayoutParams" -> {
                thisObject?.hideLauncherNavbarInsets()
                proceed()
            }

            executable.declaringClass.name == "com.android.server.policy.PhoneWindowManager" &&
                executable.name == "init" -> {
                proceed().also {
                    (args.firstOrNull { it is Context } as? Context)
                        ?.let(::registerLauncherSleepReceiver)
                }
            }

            else -> UnhandledHotReloadHook
        }
    }

private fun BroadcastReceiver.isTrustedLauncherSleepSender(context: Context): Boolean {
    val senderUid = attempt(-1) {
        sentFromUidMethod.invoke(this) as Int
    }

    if (senderUid < 0) return true

    return context.packageManager
        .getPackagesForUid(senderUid)
        .orEmpty()
        .any { it.isPixelLauncherPackage() }
}

private fun XpOmniModule.handleDoubleTapToSleep(
    context: Context,
    event: MotionEvent,
) {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val currentTime = SystemClock.uptimeMillis()
            if (currentTime - firstTapTime > DOUBLE_TAP_TIMEOUT) {
                resetDoubleTapState()
            }

            if (!isFirstTapRunning) {
                firstTapTime = currentTime
                firstTapX = event.x
                firstTapY = event.y
                isFirstTapRunning = true
            } else if (isFirstTapComplete) {
                if (event.distanceFromFirstTap() <= TAP_DISTANCE_THRESHOLD) {
                    vibrateTick(context)
                    requestDeviceSleep(context)
                }
                resetDoubleTapState()
            }
        }

        MotionEvent.ACTION_UP -> {
            if (isFirstTapRunning && !isFirstTapComplete) {
                isFirstTapComplete = true
            }
        }

        MotionEvent.ACTION_MOVE -> {
            if (isFirstTapRunning && event.distanceFromFirstTap() > TAP_DISTANCE_THRESHOLD) {
                resetDoubleTapState()
            }
        }
    }
}

private fun MotionEvent.distanceFromFirstTap(): Double =
    hypot(
        (x - firstTapX).toDouble(),
        (y - firstTapY).toDouble(),
    )

private fun resetDoubleTapState() {
    isFirstTapRunning = false
    isFirstTapComplete = false
}

private fun vibrateTick(context: Context) {
    attempt(Unit) {
        val vibrator = context
            .getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?: return@attempt
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}

private fun XpOmniModule.requestDeviceSleep(context: Context) {
    try {
        context.goToSleepNow()
    } catch (_: Throwable) {
        context.sendBroadcast(
            Intent(SLEEP_ACTION)
                .setPackage(ANDROID_FRAMEWORK)
                .putExtra(SLEEP_TOKEN_EXTRA, SLEEP_TOKEN)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
    }
}

private fun Context.goToSleepNow() {
    val powerManager = getSystemService(Context.POWER_SERVICE)
        ?: throw IllegalStateException("PowerManager unavailable")
    val goToSleep = goToSleepMethod
        ?: powerManager.methodOrNull("goToSleep", parameterCount = 1)?.also {
            goToSleepMethod = it
        }
        ?: throw NoSuchMethodException("PowerManager.goToSleep")

    goToSleep.invoke(powerManager, SystemClock.uptimeMillis())
}

private fun Any.launcherTouchContext(): Context? =
    pixelLauncherContext ?: ((readField("mLauncher") as? Context)
        ?: (readField("mWorkspace") as? View)?.context)
        ?.let { context ->
            (context.applicationContext ?: context).also { pixelLauncherContext = it }
        }

private fun Any.hideLauncherSearchBar() {
    (readField("mQsb") as? View)?.apply {
        cacheQsbHeightId()
        visibility = View.GONE
        alpha = 0f
        isEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        layoutParams = layoutParams?.apply {
            height = 0
        }
    }
}

private fun Any.hideLauncherNavbarPill() {
    readField("mControllers")
        ?.readField("stashedHandleViewController")
        ?.writeField("mStashedHandleWidth", 0)
}

private fun Any.hideLauncherNavbarInsets() {
    val layoutParams = readField("mWindowLayoutParams") as? WindowManager.LayoutParams ?: return
    layoutParams.clearNavigationBarInsets()

    val rotationParams = layoutParams.readField("paramsForRotation") ?: return
    val length = attempt(0) { java.lang.reflect.Array.getLength(rotationParams) }
    repeat(length) { index ->
        val rotationLayoutParams = attempt(null) {
            java.lang.reflect.Array.get(rotationParams, index)
        } as? WindowManager.LayoutParams ?: return@repeat
        rotationLayoutParams.clearNavigationBarInsets()
    }
}

private fun WindowManager.LayoutParams.clearNavigationBarInsets() {
    val providedInsets = readField("providedInsets") ?: return
    val length = attempt(0) { java.lang.reflect.Array.getLength(providedInsets) }
    repeat(length) { index ->
        val insetsFrame = attempt(null) {
            java.lang.reflect.Array.get(providedInsets, index)
        } ?: return@repeat
        if (!insetsFrame.toString().contains("type=navigationBars")) return@repeat

        insetsFrame.invokeMethod("setInsetsSize", Insets.NONE)
    }
}

private fun View.cacheQsbHeightId() {
    if (qsbWidgetHeightId != 0) return

    val id = context.resources.getIdentifier(QSB_WIDGET_HEIGHT, "dimen", context.packageName)
    if (id != 0) qsbWidgetHeightId = id
}

private fun Resources.isLauncherQsbHeight(resId: Int): Boolean {
    val cachedId = qsbWidgetHeightId
    if (cachedId != 0) return resId == cachedId
    if (resId == 0 || resId in nonQsbWidgetHeightIds) return false

    val matched = attempt(false) {
        getResourceEntryName(resId) == QSB_WIDGET_HEIGHT &&
            getResourcePackageName(resId).isPixelLauncherPackage()
    }

    if (matched) qsbWidgetHeightId = resId else nonQsbWidgetHeightIds += resId
    return matched
}

private fun Any.clearLauncherDrawableBadge() {
    if (!invokeMethod("setBadge", null)) {
        writeField("badge", null)
        invokeMethod("updateFilter")
    }
}

private fun String.isPixelLauncherPackage(): Boolean =
    this == PIXEL_LAUNCHER || this == LAUNCHER3
