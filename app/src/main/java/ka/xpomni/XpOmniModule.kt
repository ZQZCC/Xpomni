package ka.xpomni

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutManager
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.widget.Button
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import kotlin.math.hypot

@SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")
class XpOmniModule : XposedModule() {
    private var firstTapTime = 0L
    private var firstTapX = 0f
    private var firstTapY = 0f
    private var isFirstTapRunning = false
    private var isFirstTapComplete = false
    private var pixelLauncherContext: Context? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Xpomni loaded")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader

        runHook("deoptimize system server") {
            deoptimizeSystemServer(classLoader)
        }

        runHook("hook launcher sleep receiver") {
            hookLauncherSleepReceiver(classLoader)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runHook("hook WindowManagerService") {
                hookWindowManagerService(classLoader)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runHook("hook ActivityTaskManagerService") {
                hookActivityTaskManagerService(classLoader)
            }
            runOptionalHook("hook HyperOS") {
                hookHyperOS(classLoader)
            }
        }

        runHook("hook ScreenCapture") {
            hookScreenCapture(classLoader)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runHook("hook ActivityManagerService") {
                hookActivityManagerService(classLoader)
            }
        }

        runHook("hook DisplayControl") {
            hookDisplayControl(classLoader)
        }

        runHook("hook VirtualDisplayAdapter") {
            hookVirtualDisplayAdapter(classLoader)
        }

        runOptionalHook("hook ScreenshotHardwareBuffer") {
            hookScreenshotHardwareBuffer(classLoader)
        }

        runOptionalHook("hook OneUI") {
            hookOneUI(classLoader)
        }

        runHook("hook WindowState") {
            hookWindowState(classLoader)
        }

        runOptionalHook("hook Oplus") {
            hookOplus(classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return

        val classLoader = param.classLoader
        when (val packageName = param.packageName) {
            OPLUS_SCREENSHOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    runOptionalHook("hook OplusScreenCapture") {
                        hookOplusScreenCapture(classLoader)
                    }
                }
                hookScreenshotHardwareBufferIfPresent(classLoader)
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            FLYME_SYSTEMUIEX, OPLUS_APPPLATFORM -> {
                hookScreenshotHardwareBufferIfPresent(classLoader)
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            SYSTEMUI, MIUI_SCREENSHOT -> {
                if (packageName == SYSTEMUI) {
                    runBiometricHook(classLoader)
                }
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            SHARE_SHEET_PACKAGE -> {
                hookHideDirectShare(classLoader)
            }

            ANDROID_FRAMEWORK, INTENT_RESOLVER -> {
                // Static scope covers both old and new platform split points.
            }

            ANDROID_SYSTEM_INTELLIGENCE -> {
                hookShareTargets()
            }

            PIXEL_LAUNCHER, LAUNCHER3 -> {
                hookPixelLauncherFeatures(classLoader)
            }

            else -> runCatching {
                hookOnResume()
            }
        }
    }

    private fun runHook(
        name: String,
        block: () -> Unit,
    ) {
        runCatching(block).onFailure { error ->
            log(Log.ERROR, TAG, "$name failed", error)
        }
    }

    private fun runOptionalHook(
        name: String,
        block: () -> Unit,
    ) {
        runCatching(block).onFailure { error ->
            if (error !is ClassNotFoundException) {
                log(Log.ERROR, TAG, "$name failed", error)
            }
        }
    }

    private fun runBiometricHook(classLoader: ClassLoader) {
        runCatching {
            hookBiometricBypass(classLoader)
        }.onFailure { error ->
            if (error !is ClassNotFoundException && error !is NoSuchMethodException) {
                log(Log.ERROR, TAG, "hook BiometricBypass failed", error)
            }
        }
    }

    private fun hookScreenshotHardwareBufferIfPresent(classLoader: ClassLoader) {
        runOptionalHook("hook ScreenshotHardwareBuffer") {
            hookScreenshotHardwareBuffer(classLoader)
        }
    }

    private fun hookScreenCaptureInPackage(
        classLoader: ClassLoader,
        packageName: String,
    ) {
        if (packageName == OPLUS_APPPLATFORM ||
            packageName == OPLUS_SCREENSHOT ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            runHook("hook ScreenCapture") {
                hookScreenCapture(classLoader)
            }
        }
    }

    private fun deoptimizeSystemServer(classLoader: ClassLoader) {
        deoptimizeMethods(
            classLoader.loadClass("com.android.server.wm.WindowStateAnimator"),
            "createSurfaceLocked",
        )
        deoptimizeMethods(
            classLoader.loadClass("com.android.server.wm.WindowManagerService"),
            "relayoutWindow",
        )

        repeat(SYNTHETIC_CLASS_SCAN_LIMIT) { index ->
            runCatching {
                classLoader
                    .loadClass("com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$index")
                    .takeIf(BiConsumer::class.java::isAssignableFrom)
                    ?.let { deoptimizeMethods(it, "accept") }
            }

            runCatching {
                classLoader
                    .loadClass("com.android.server.wm.DisplayContent\$$index")
                    .takeIf(BiPredicate::class.java::isAssignableFrom)
                    ?.let { deoptimizeMethods(it, "test") }
            }
        }
    }

    private fun deoptimizeMethods(
        clazz: Class<*>,
        vararg names: String,
    ) {
        for (method in clazz.declaredMethods) {
            if (method.name.matchesAny(names)) deoptimize(method)
        }
    }

    private fun hookWindowState(classLoader: ClassLoader) {
        val windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState")
        val systemServerClassLoader = windowStateClass.classLoader
        val isSecureLocked = windowStateClass.getDeclaredMethod("isSecureLocked")

        intercept(isSecureLocked) {
            if (isCalledFromSurfaceCreation(classLoader, systemServerClassLoader)) {
                proceed()
            } else {
                false
            }
        }
    }

    private fun isCalledFromSurfaceCreation(
        classLoader: ClassLoader,
        systemServerClassLoader: ClassLoader?,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            walker.walk { frames ->
                frames.anyMatch { frame ->
                    frame.declaringClass?.classLoader == systemServerClassLoader &&
                        frame.methodName.isSurfaceCreationMethod()
                }
            }
        } else {
            Throwable().stackTrace.any { frame ->
                frame.methodName.isSurfaceCreationMethod() &&
                    attempt(false) {
                        classLoader.loadClass(frame.className).classLoader == systemServerClassLoader
                    }
            }
        }

    private fun hookScreenCapture(classLoader: ClassLoader) {
        val usesSecureContentPolicy =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

        val (screenCaptureClass, captureArgsClass) =
            when {
                usesSecureContentPolicy -> {
                    classLoader.loadClass("android.window.ScreenCaptureInternal") to
                        classLoader.loadClass("android.window.ScreenCaptureInternal\$CaptureArgs")
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    classLoader.loadClass("android.window.ScreenCapture") to
                        classLoader.loadClass("android.window.ScreenCapture\$CaptureArgs")
                }

                else -> {
                    SurfaceControl::class.java to
                        classLoader.loadClass("android.view.SurfaceControl\$CaptureArgs")
                }
            }

        val secureCaptureField = captureArgsClass
            .getDeclaredField(if (usesSecureContentPolicy) "mSecureContentPolicy" else "mCaptureSecureLayers")
            .apply { isAccessible = true }

        hookMethods(screenCaptureClass, "nativeCaptureDisplay", "nativeCaptureLayers") {
            val captureArgs = getArg(0)
            runCatching {
                secureCaptureField.set(captureArgs, if (usesSecureContentPolicy) 1 else true)
            }.onFailure { error ->
                log(Log.ERROR, TAG, "ScreenCaptureHooker failed", error)
            }
            proceed()
        }
    }

    private fun hookDisplayControl(classLoader: ClassLoader) {
        val displayControlClass =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                classLoader.loadClass("com.android.server.display.DisplayControl")
            } else {
                SurfaceControl::class.java
            }
        val systemServerClassLoader = displayControlClass.classLoader
        val methodName =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                "createVirtualDisplay"
            } else {
                "createDisplay"
            }
        val method = displayControlClass.getDeclaredMethod(
            methodName,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        )

        intercept(method) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                isCalledFromVirtualDisplayCreation(classLoader, systemServerClassLoader)
            ) {
                return@intercept proceed()
            }

            val args = argsArray()
            args[1] = true
            proceed(args)
        }
    }

    private fun isCalledFromVirtualDisplayCreation(
        classLoader: ClassLoader,
        systemServerClassLoader: ClassLoader?,
    ): Boolean =
        Throwable().stackTrace.any { frame ->
            frame.methodName == "createVirtualDisplayLocked" &&
                attempt(false) {
                    classLoader.loadClass(frame.className).classLoader == systemServerClassLoader
                }
        }

    private fun hookVirtualDisplayAdapter(classLoader: ClassLoader) {
        val virtualDisplayAdapterClass =
            classLoader.loadClass("com.android.server.display.VirtualDisplayAdapter")

        hookMethods(virtualDisplayAdapterClass, "createVirtualDisplayLocked") {
            val caller = getArg(2) as Int
            if (caller >= APP_UID_START && getArg(1) == null) {
                return@hookMethods proceed()
            }

            for (index in 3 until args.size) {
                val flags = getArg(index) as? Int ?: continue
                val updatedArgs = argsArray()
                updatedArgs[index] = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                return@hookMethods proceed(updatedArgs)
            }

            log(Log.WARN, TAG, "flag not found in CreateVirtualDisplayLockedHooker")
            proceed()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun hookActivityTaskManagerService(classLoader: ClassLoader) {
        val activityTaskManagerServiceClass =
            classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
        val iBinderClass = classLoader.loadClass("android.os.IBinder")
        val screenCaptureObserverClass = classLoader.loadClass("android.app.IScreenCaptureObserver")
        val method = activityTaskManagerServiceClass.getDeclaredMethod(
            "registerScreenCaptureObserver",
            iBinderClass,
            screenCaptureObserverClass,
        )

        intercept(method) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun hookWindowManagerService(classLoader: ClassLoader) {
        val windowManagerServiceClass =
            classLoader.loadClass("com.android.server.wm.WindowManagerService")
        val screenRecordingCallbackClass =
            classLoader.loadClass("android.window.IScreenRecordingCallback")
        val method = windowManagerServiceClass.getDeclaredMethod(
            "registerScreenRecordingCallback",
            screenRecordingCallbackClass,
        )

        intercept(method) {
            false
        }
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        val activityManagerServiceClass =
            classLoader.loadClass("com.android.server.am.ActivityManagerService")
        val method = activityManagerServiceClass.getDeclaredMethod(
            "checkPermission",
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )

        intercept(method) {
            if (getArg(0) == CAPTURE_BLACKOUT_CONTENT_PERMISSION) {
                val args = argsArray()
                args[0] = READ_FRAME_BUFFER_PERMISSION
                proceed(args)
            } else {
                proceed()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun hookHyperOS(classLoader: ClassLoader) {
        val windowManagerServiceImplClass =
            classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl")
        hookMethods(windowManagerServiceImplClass, "notAllowCaptureDisplay") {
            false
        }
    }

    private fun hookScreenshotHardwareBuffer(classLoader: ClassLoader) {
        val screenshotHardwareBufferClass =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                classLoader.loadClass("android.window.ScreenCapture\$ScreenshotHardwareBuffer")
            } else {
                classLoader.loadClass("android.view.SurfaceControl\$ScreenshotHardwareBuffer")
            }
        val containsSecureLayers = screenshotHardwareBufferClass.getDeclaredMethod("containsSecureLayers")

        intercept(containsSecureLayers) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun hookOplusScreenCapture(classLoader: ClassLoader) {
        val captureArgsBuilderClass =
            classLoader.loadClass("com.oplus.screenshot.OplusScreenCapture\$CaptureArgs\$Builder")
        val setUid = captureArgsBuilderClass.getDeclaredMethod(
            "setUid",
            Long::class.javaPrimitiveType!!,
        )

        intercept(setUid) {
            val args = argsArray()
            args[0] = -1L
            proceed(args)
        }
    }

    private fun hookOplus(classLoader: ClassLoader) {
        val longshotMainClass =
            classLoader.loadClass("com.android.server.wm.OplusLongshotMainWindow")
        hookMethods(longshotMainClass, "hasSecure") {
            false
        }
    }

    private fun hookOneUI(classLoader: ClassLoader) {
        val screenshotControllerClass =
            classLoader.loadClass("com.android.server.wm.WmScreenshotController")
        hookMethods(screenshotControllerClass, "canBeScreenshotTarget") {
            true
        }
    }

    private fun hookBiometricBypass(classLoader: ClassLoader) {
        val authContainerViewClass = classLoader.loadClass(BIOMETRIC_TARGET_CLASS)
        val onDialogAnimatedIn = authContainerViewClass.getDeclaredMethod(BIOMETRIC_TARGET_METHOD)

        intercept(onDialogAnimatedIn) {
            val result = proceed()
            (thisObject as? View)?.let { authContainerView ->
                val context = authContainerView.context
                val confirmButtonId = context.resources.getIdentifier(
                    BIOMETRIC_BUTTON_CONFIRM_ID,
                    "id",
                    context.packageName,
                )
                scheduleBiometricConfirmClick(
                    parentView = authContainerView,
                    buttonId = confirmButtonId,
                    opPackageName = authContainerView.biometricOpPackageName(),
                    attempt = 0,
                    delayMs = BIOMETRIC_INITIAL_DELAY_MS,
                )
            }
            result
        }

        log(Log.INFO, TAG, "Hooked $BIOMETRIC_TARGET_METHOD in $BIOMETRIC_TARGET_CLASS")
    }

    private fun hookHideDirectShare(classLoader: ClassLoader) {
        runHook("hook DirectShare low-ram") {
            hookLowRamDeviceStatic()
        }

        runOptionalHook("hook DirectShare service-target-count") {
            hookServiceTargetCountFallback(classLoader)
        }
    }

    private fun hookLowRamDeviceStatic() {
        val isLowRamDeviceStatic =
            ActivityManager::class.java.getDeclaredMethod("isLowRamDeviceStatic")
                .apply { isAccessible = true }

        intercept(isLowRamDeviceStatic) {
            true
        }

        log(Log.INFO, TAG, "hooked DirectShare low-ram")
    }

    private fun hookServiceTargetCountFallback(classLoader: ClassLoader) {
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

    private fun hookShareTargets() {
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

    private fun hookPixelLauncherFeatures(classLoader: ClassLoader) {
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
    }

    private fun hookPixelShortcutBadges(classLoader: ClassLoader) {
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

        log(Log.INFO, TAG, "hooked Pixel Launcher shortcut badges")
    }

    private fun hookPixelBottomSearchBar(classLoader: ClassLoader) {
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

        log(Log.INFO, TAG, "hooked Pixel Launcher bottom search bar")
    }

    private fun hookPixelSearchBarResources() {
        synchronized(XpOmniModule::class.java) {
            if (pixelResourceHooksInstalled) return@synchronized
            hookResourceDimension("getDimension", 0f)
            hookResourceDimension("getDimensionPixelOffset", 0)
            hookResourceDimension("getDimensionPixelSize", 0)
            pixelResourceHooksInstalled = true
        }
    }

    private fun hookResourceDimension(
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

    private fun hookPixelDoubleTapSleep(classLoader: ClassLoader) {
        val workspaceTouchListenerClass =
            classLoader.loadClass("com.android.launcher3.touch.WorkspaceTouchListener")

        hookMethods(workspaceTouchListenerClass, "onTouch") {
            val result = proceed()
            val event = getArg(1) as MotionEvent
            val context = thisObject?.launcherTouchContext() ?: return@hookMethods result
            handleDoubleTapToSleep(context, event)
            result
        }

        log(Log.INFO, TAG, "hooked Pixel Launcher double tap sleep")
    }

    private fun hookLauncherSleepReceiver(classLoader: ClassLoader) {
        val phoneWindowManagerClass =
            classLoader.loadClass("com.android.server.policy.PhoneWindowManager")

        hookMethods(phoneWindowManagerClass, "init") {
            proceed().also {
                (args.firstOrNull { it is Context } as? Context)
                    ?.let(::registerLauncherSleepReceiver)
            }
        }
    }

    private fun registerLauncherSleepReceiver(context: Context) {
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

            sleepReceiverRegistered = true
            log(Log.INFO, TAG, "registered launcher sleep receiver")
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

    private fun handleDoubleTapToSleep(
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

    @SuppressLint("MissingPermission")
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

    private fun Any.invokeMethod(
        name: String,
        vararg args: Any?,
    ): Boolean =
        methodOrNull(name, args.size)?.let { method ->
            attempt(false) {
                method.invoke(this, *args)
                true
            }
        } ?: false

    private fun Any.writeField(
        name: String,
        value: Any?,
    ): Boolean =
        fieldOrNull(name)?.let { field ->
            attempt(false) {
                field.set(this, value)
                true
            }
        } ?: false

    private fun View.biometricOpPackageName(): String =
        runCatching {
            val config = readField("mConfig") ?: return@runCatching null
            config.readField("mOpPackageName") as? String
        }.onFailure { error ->
            log(Log.WARN, TAG, "biometric op package reflection failed", error)
        }.getOrNull() ?: "unknown"

    private fun Any.readField(name: String): Any? =
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

    private fun Any.methodOrNull(
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

    private fun Chain.afterProceed(action: (Any?) -> Unit): Any? {
        val result = proceed()
        action(thisObject)
        return result
    }

    private inline fun <T> attempt(
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (_: Throwable) {
            fallback
        }

    private fun String.isPixelLauncherPackage(): Boolean =
        this == PIXEL_LAUNCHER || this == LAUNCHER3

    private fun String.isSurfaceCreationMethod(): Boolean =
        this == "setInitialSurfaceControlProperties" || this == "createSurfaceLocked"

    private fun String.matchesAny(names: Array<out String>): Boolean {
        for (name in names) {
            if (this == name) return true
        }
        return false
    }

    private fun scheduleBiometricConfirmClick(
        parentView: View,
        buttonId: Int,
        opPackageName: String,
        attempt: Int,
        delayMs: Long,
    ) {
        parentView.postDelayed(
            {
                clickBiometricConfirmButton(
                    parentView = parentView,
                    buttonId = buttonId,
                    opPackageName = opPackageName,
                    attempt = attempt,
                    delayMs = delayMs,
                )
            },
            delayMs,
        )
    }

    private fun clickBiometricConfirmButton(
        parentView: View,
        buttonId: Int,
        opPackageName: String,
        attempt: Int,
        delayMs: Long,
    ) {
        val button = parentView.findViewById<View>(buttonId) as? Button
        if (button?.isShown == true) {
            button.performClick()
            log(Log.INFO, TAG, "Biometric confirm clicked [$opPackageName]")
            return
        }

        val nextAttempt = attempt + 1
        if (nextAttempt >= BIOMETRIC_MAX_RETRIES) {
            log(Log.WARN, TAG, "Biometric confirm button not found [$opPackageName]")
            return
        }

        scheduleBiometricConfirmClick(
            parentView = parentView,
            buttonId = buttonId,
            opPackageName = opPackageName,
            attempt = nextAttempt,
            delayMs = delayMs * 2,
        )
    }

    private fun hookOnResume() {
        val onResume = Activity::class.java.getDeclaredMethod("onResume")

        intercept(onResume) {
            AlertDialog.Builder(thisObject as Activity)
                .setTitle("Xpomni")
                .setMessage("Incorrect module usage, remove this app from scope.")
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> kotlin.system.exitProcess(0) }
                .show()
            proceed()
        }
    }

    private fun hookMethods(
        clazz: Class<*>,
        vararg names: String,
        block: Chain.() -> Any?,
    ) {
        for (method in clazz.declaredMethods) {
            if (!method.name.matchesAny(names)) continue
            intercept(method, block)
        }
    }

    private fun hookConstructors(
        clazz: Class<*>,
        block: Chain.() -> Any?,
    ) {
        clazz.declaredConstructors.forEach { constructor ->
            intercept(constructor, block)
        }
    }

    private fun intercept(
        executable: Executable,
        block: Chain.() -> Any?,
    ) {
        hook(executable).intercept(Hooker { chain -> chain.block() })
    }

    private fun Chain.argsArray(): Array<Any?> = args.toTypedArray()

    private companion object {
        private const val TAG = "Xpomni"
        private const val ANDROID_FRAMEWORK = "android"
        private const val INTENT_RESOLVER = "com.android.intentresolver"
        private const val ANDROID_SYSTEM_INTELLIGENCE = "com.google.android.as"
        private const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"
        private const val LAUNCHER3 = "com.android.launcher3"
        private const val SYSTEMUI = "com.android.systemui"
        private const val OPLUS_APPPLATFORM = "com.oplus.appplatform"
        private const val OPLUS_SCREENSHOT = "com.oplus.screenshot"
        private const val FLYME_SYSTEMUIEX = "com.flyme.systemuiex"
        private const val MIUI_SCREENSHOT = "com.miui.screenshot"
        private const val BIOMETRIC_TARGET_CLASS = "com.android.systemui.biometrics.AuthContainerView"
        private const val BIOMETRIC_TARGET_METHOD = "onDialogAnimatedIn"
        private const val BIOMETRIC_BUTTON_CONFIRM_ID = "button_confirm"
        private const val BIOMETRIC_MAX_RETRIES = 3
        private const val BIOMETRIC_INITIAL_DELAY_MS = 100L
        private const val SYNTHETIC_CLASS_SCAN_LIMIT = 20
        private const val DOUBLE_TAP_TIMEOUT = 400L
        private const val TAP_DISTANCE_THRESHOLD = 50f
        private const val QSB_WIDGET_HEIGHT = "qsb_widget_height"
        private const val SLEEP_ACTION = "ka.xpomni.action.PIXEL_LAUNCHER_SLEEP"
        private const val SLEEP_TOKEN_EXTRA = "ka.xpomni.extra.SLEEP_TOKEN"
        private const val SLEEP_TOKEN = "xpomni_pixel_launcher_sleep"
        private const val APP_UID_START = 10_000
        private const val CAPTURE_BLACKOUT_CONTENT_PERMISSION = "android.permission.CAPTURE_BLACKOUT_CONTENT"
        private const val READ_FRAME_BUFFER_PERMISSION = "android.permission.READ_FRAME_BUFFER"
        @Volatile
        private var sleepReceiverRegistered = false
        @Volatile
        private var pixelResourceHooksInstalled = false
        @Volatile
        private var qsbWidgetHeightId = 0
        @Volatile
        private var goToSleepMethod: Method? = null

        private val fieldCache = ConcurrentHashMap<String, Field>()
        private val methodCache = ConcurrentHashMap<String, Method>()
        private val nonQsbWidgetHeightIds = ConcurrentHashMap.newKeySet<Int>()
        private val sentFromUidMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
            BroadcastReceiver::class.java
                .getDeclaredMethod("getSentFromUid")
                .apply { isAccessible = true }
        }

        private val SHARE_SHEET_PACKAGE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                INTENT_RESOLVER
            } else {
                ANDROID_FRAMEWORK
            }

    }
}
