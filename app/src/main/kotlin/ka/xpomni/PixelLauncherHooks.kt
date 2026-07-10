@file:android.annotation.SuppressLint(
    "PrivateApi",
    "BlockedPrivateApi",
    "DiscouragedApi",
    "MissingPermission",
    "UnspecifiedRegisterReceiverFlag",
)

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
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

private const val QSB_WIDGET_HEIGHT = "qsb_widget_height"
private const val TASKBAR_ACTIVITY_CONTEXT = "com.android.launcher3.taskbar.TaskbarActivityContext"
private const val SLEEP_ACTION = "ka.xpomni.action.PIXEL_LAUNCHER_SLEEP"
private const val SLEEP_TOKEN_EXTRA = "ka.xpomni.extra.SLEEP_TOKEN"
private const val SLEEP_TOKEN = "xpomni_pixel_launcher_sleep"
private const val SLEEP_SENDER_PERMISSION = "android.permission.BIND_APPWIDGET"
private const val PIXEL_BADGE_VIEW_HOOK_ID = "pixel.badge_view"
private const val PIXEL_BADGE_APPLY_HOOK_ID = "pixel.badge_apply"
private const val PIXEL_BADGE_ICON_HOOK_ID = "pixel.badge_icon"
private const val PIXEL_SEARCH_BAR_HOOK_ID = "pixel.search_bar"
private const val PIXEL_QSB_DIMENSION_HOOK_ID = "pixel.qsb_dimension"
private const val PIXEL_QSB_PIXEL_HOOK_ID = "pixel.qsb_pixel"
private const val PIXEL_DOUBLE_TAP_HOOK_ID = "pixel.double_tap"
private const val PIXEL_NAVBAR_PILL_HOOK_ID = "pixel.navbar_pill"
private const val PIXEL_NAVBAR_INSETS_HOOK_ID = "pixel.navbar_insets"
private const val PIXEL_SLEEP_RECEIVER_HOOK_ID = "pixel.sleep_receiver"

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

    hookConstructors(bubbleTextViewClass, PIXEL_BADGE_VIEW_HOOK_ID) {
        handlePixelBadgeView(this)
    }

    hookMethods(bubbleTextViewClass, PIXEL_BADGE_VIEW_HOOK_ID, "setHideBadge") {
        handlePixelBadgeView(this)
    }

    runOptionalHook("hook Pixel Launcher bitmap badges") {
        val bitmapInfoClass = classLoader.loadClass("com.android.launcher3.icons.BitmapInfo")

        hookMethods(bitmapInfoClass, PIXEL_BADGE_APPLY_HOOK_ID, "applyFlags") {
            handlePixelBadgeApply(this)
        }

        hookMethods(bitmapInfoClass, PIXEL_BADGE_ICON_HOOK_ID, "newIcon") {
            handlePixelBadgeIcon(this)
        }
    }
}

private fun XpOmniModule.hookPixelBottomSearchBar(classLoader: ClassLoader) {
    val hotseatClass = classLoader.loadClass("com.android.launcher3.Hotseat")

    hookConstructors(hotseatClass, PIXEL_SEARCH_BAR_HOOK_ID) {
        handlePixelSearchBar(this)
    }

    hookMethods(hotseatClass, PIXEL_SEARCH_BAR_HOOK_ID, "setInsets") {
        handlePixelSearchBar(this)
    }
}

private fun XpOmniModule.hookPixelSearchBarResources() {
    synchronized(XpOmniModule::class.java) {
        if (pixelResourceHooksInstalled) return@synchronized
        hookResourceDimension("getDimension", PIXEL_QSB_DIMENSION_HOOK_ID, 0f)
        hookResourceDimension("getDimensionPixelOffset", PIXEL_QSB_PIXEL_HOOK_ID, 0)
        hookResourceDimension("getDimensionPixelSize", PIXEL_QSB_PIXEL_HOOK_ID, 0)
        pixelResourceHooksInstalled = true
    }
}

private fun XpOmniModule.hookResourceDimension(
    methodName: String,
    hookId: String,
    replacement: Any,
) {
    val method = Resources::class.java.getDeclaredMethod(
        methodName,
        Int::class.javaPrimitiveType!!,
    )

    intercept(method, hookId) {
        handlePixelQsbDimension(this, replacement)
    }
}

private fun XpOmniModule.hookPixelDoubleTapSleep(classLoader: ClassLoader) {
    val workspaceTouchListenerClass =
        classLoader.loadClass("com.android.launcher3.touch.WorkspaceTouchListener")

    hookMethods(workspaceTouchListenerClass, PIXEL_DOUBLE_TAP_HOOK_ID, "onTouch") {
        handlePixelDoubleTap(this)
    }
}

private fun XpOmniModule.hookPixelLauncherNavbarPill(classLoader: ClassLoader) {
    val taskbarActivityContextClass = classLoader.loadClass(TASKBAR_ACTIVITY_CONTEXT)

    hookMethods(taskbarActivityContextClass, PIXEL_NAVBAR_PILL_HOOK_ID, "init") {
        handlePixelNavbarPill(this)
    }
}

private fun XpOmniModule.hookPixelLauncherNavbarInsets(classLoader: ClassLoader) {
    val taskbarActivityContextClass = classLoader.loadClass(TASKBAR_ACTIVITY_CONTEXT)

    hookMethods(
        taskbarActivityContextClass,
        PIXEL_NAVBAR_INSETS_HOOK_ID,
        "notifyUpdateLayoutParams",
    ) {
        handlePixelNavbarInsets(this)
    }
}

internal fun XpOmniModule.hookLauncherSleepReceiver(classLoader: ClassLoader) {
    val phoneWindowManagerClass =
        classLoader.loadClass("com.android.server.policy.PhoneWindowManager")

    hookMethods(phoneWindowManagerClass, PIXEL_SLEEP_RECEIVER_HOOK_ID, "init") {
        handleLauncherSleepReceiver(this)
    }
}

private fun XpOmniModule.registerLauncherSleepReceiver(context: Context) {
    if (sleepReceiverRegistered) return

    synchronized(XpOmniModule::class.java) {
        if (sleepReceiverRegistered) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent?) {
                if (intent?.action != SLEEP_ACTION ||
                    intent.getStringExtra(SLEEP_TOKEN_EXTRA) != SLEEP_TOKEN
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
            context.registerReceiver(
                receiver,
                filter,
                SLEEP_SENDER_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter, SLEEP_SENDER_PERMISSION, null)
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

internal fun XpOmniModule.resolvePixelLauncherHotReloadHook(
    hookId: String?,
    executable: Executable,
): Hooker? {
    val className = executable.declaringClass.name
    val legacyBadgeView = className == "com.android.launcher3.BubbleTextView"
    val legacyBadgeApply =
        className == "com.android.launcher3.icons.BitmapInfo" && executable.name == "applyFlags"
    val legacyBadgeIcon =
        className == "com.android.launcher3.icons.BitmapInfo" && executable.name == "newIcon"
    val legacySearchBar = className == "com.android.launcher3.Hotseat"
    val legacyQsbDimension =
        executable.declaringClass == Resources::class.java && executable.name == "getDimension"
    val legacyQsbPixel =
        executable.declaringClass == Resources::class.java &&
            (executable.name == "getDimensionPixelOffset" ||
                executable.name == "getDimensionPixelSize")
    val legacyDoubleTap =
        className == "com.android.launcher3.touch.WorkspaceTouchListener" &&
            executable.name == "onTouch"
    val legacyNavbarPill = className == TASKBAR_ACTIVITY_CONTEXT && executable.name == "init"
    val legacyNavbarInsets =
        className == TASKBAR_ACTIVITY_CONTEXT && executable.name == "notifyUpdateLayoutParams"
    val legacySleepReceiver =
        className == "com.android.server.policy.PhoneWindowManager" && executable.name == "init"

    return when {
        hookId == PIXEL_BADGE_VIEW_HOOK_ID || legacyBadgeView ->
            Hooker { chain -> handlePixelBadgeView(chain) }

        hookId == PIXEL_BADGE_APPLY_HOOK_ID || legacyBadgeApply ->
            Hooker { chain -> handlePixelBadgeApply(chain) }

        hookId == PIXEL_BADGE_ICON_HOOK_ID || legacyBadgeIcon ->
            Hooker { chain -> handlePixelBadgeIcon(chain) }

        hookId == PIXEL_SEARCH_BAR_HOOK_ID || legacySearchBar ->
            Hooker { chain -> handlePixelSearchBar(chain) }

        hookId == PIXEL_QSB_DIMENSION_HOOK_ID || legacyQsbDimension ->
            Hooker { chain -> handlePixelQsbDimension(chain, 0f) }

        hookId == PIXEL_QSB_PIXEL_HOOK_ID || legacyQsbPixel ->
            Hooker { chain -> handlePixelQsbDimension(chain, 0) }

        hookId == PIXEL_DOUBLE_TAP_HOOK_ID || legacyDoubleTap ->
            Hooker { chain -> handlePixelDoubleTap(chain) }

        hookId == PIXEL_NAVBAR_PILL_HOOK_ID || legacyNavbarPill ->
            Hooker { chain -> handlePixelNavbarPill(chain) }

        hookId == PIXEL_NAVBAR_INSETS_HOOK_ID || legacyNavbarInsets ->
            Hooker { chain -> handlePixelNavbarInsets(chain) }

        hookId == PIXEL_SLEEP_RECEIVER_HOOK_ID || legacySleepReceiver ->
            Hooker { chain -> handleLauncherSleepReceiver(chain) }

        else -> null
    }
}

private fun handlePixelBadgeView(chain: Chain): Any? =
    with(chain) {
        afterProceed { view -> view?.writeField("mHideBadge", true) }
    }

private fun handlePixelBadgeApply(chain: Chain): Any? =
    with(chain) {
        val result = proceed()
        getArg(1)?.clearLauncherDrawableBadge()
        result
    }

private fun handlePixelBadgeIcon(chain: Chain): Any? =
    with(chain) {
        proceed().also { icon -> icon?.clearLauncherDrawableBadge() }
    }

private fun handlePixelSearchBar(chain: Chain): Any? =
    with(chain) {
        afterProceed { hotseat -> hotseat?.hideLauncherSearchBar() }
    }

private fun handlePixelQsbDimension(
    chain: Chain,
    replacement: Any,
): Any? =
    with(chain) {
        val resources = thisObject as Resources
        val resId = getArg(0) as Int
        if (resources.isLauncherQsbHeight(resId)) replacement else proceed()
    }

private fun handlePixelDoubleTap(chain: Chain): Any? =
    with(chain) {
        val result = proceed()
        val event = getArg(1) as MotionEvent
        thisObject?.launcherTouchContext()?.let { context ->
            handleDoubleTapToSleep(context, event)
        }
        result
    }

private fun handlePixelNavbarPill(chain: Chain): Any? =
    with(chain) {
        afterProceed { taskbarContext -> taskbarContext?.hideLauncherNavbarPill() }
    }

private fun handlePixelNavbarInsets(chain: Chain): Any? =
    with(chain) {
        thisObject?.hideLauncherNavbarInsets()
        proceed()
    }

private fun XpOmniModule.handleLauncherSleepReceiver(chain: Chain): Any? =
    with(chain) {
        proceed().also {
            (args.firstOrNull { it is Context } as? Context)
                ?.let(::registerLauncherSleepReceiver)
        }
    }

private fun handleDoubleTapToSleep(
    context: Context,
    event: MotionEvent,
) {
    val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    val viewConfiguration = ViewConfiguration.get(context)
    val doubleTapDistance = viewConfiguration.scaledDoubleTapSlop.toDouble()
    val moveDistance = viewConfiguration.scaledTouchSlop.toDouble()

    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val currentTime = SystemClock.uptimeMillis()
            if (currentTime - firstTapTime > doubleTapTimeout) {
                resetDoubleTapState()
            }

            if (!isFirstTapRunning) {
                firstTapTime = currentTime
                firstTapX = event.x
                firstTapY = event.y
                isFirstTapRunning = true
            } else if (isFirstTapComplete) {
                if (event.distanceFromFirstTap() <= doubleTapDistance) {
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
            if (isFirstTapRunning && event.distanceFromFirstTap() > moveDistance) {
                resetDoubleTapState()
            }
        }

        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> resetDoubleTapState()
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

private fun requestDeviceSleep(context: Context) {
    context.sendBroadcast(
        Intent(SLEEP_ACTION)
            .setPackage(ANDROID_FRAMEWORK)
            .putExtra(SLEEP_TOKEN_EXTRA, SLEEP_TOKEN)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
    )
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
