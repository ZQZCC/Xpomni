@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.util.concurrent.Executor
import java.util.function.BiConsumer
import java.util.function.BiPredicate

private const val SYNTHETIC_CLASS_SCAN_LIMIT = 20
private const val APP_UID_START = 10_000
private const val CAPTURE_BLACKOUT_CONTENT_PERMISSION = "android.permission.CAPTURE_BLACKOUT_CONTENT"
private const val READ_FRAME_BUFFER_PERMISSION = "android.permission.READ_FRAME_BUFFER"
private const val SYSTEMUI_SCREENSHOT_PACKAGE = "com.android.systemui.screenshot"
private const val TAKE_SCREENSHOT_EXECUTOR_IMPL =
    "com.android.systemui.screenshot.TakeScreenshotExecutorImpl"
private const val SCREENSHOT_SOUND_CONTROLLER_IMPL =
    "com.android.systemui.screenshot.ScreenshotSoundControllerImpl"
private const val EXECUTOR_COROUTINE_DISPATCHER_IMPL =
    "kotlinx.coroutines.ExecutorCoroutineDispatcherImpl"
private const val SCREENSHOT_WINDOW_STATE_HOOK_ID = "screenshot.window_state"
private const val SCREENSHOT_CAPTURE_HOOK_ID = "screenshot.capture"
private const val SCREENSHOT_MEDIA_HOOK_ID = "screenshot.media"
private const val SCREENSHOT_DISPATCHER_HOOK_ID = "screenshot.dispatcher"
private const val SCREENSHOT_SOUND_CONSTRUCTOR_HOOK_ID = "screenshot.sound_constructor"
private const val SCREENSHOT_DISPLAY_HOOK_ID = "screenshot.display"
private const val SCREENSHOT_VIRTUAL_DISPLAY_HOOK_ID = "screenshot.virtual_display"
private const val SCREENSHOT_OBSERVER_HOOK_ID = "screenshot.observer"
private const val SCREENSHOT_RECORDING_HOOK_ID = "screenshot.recording"
private const val SCREENSHOT_PERMISSION_HOOK_ID = "screenshot.permission"
private const val SCREENSHOT_HYPEROS_HOOK_ID = "screenshot.hyperos"
private const val SCREENSHOT_HARDWARE_BUFFER_HOOK_ID = "screenshot.hardware_buffer"
private const val SCREENSHOT_OPLUS_BUILDER_HOOK_ID = "screenshot.oplus_builder"
private const val SCREENSHOT_OPLUS_HOOK_ID = "screenshot.oplus"
private const val SCREENSHOT_ONEUI_HOOK_ID = "screenshot.oneui"

internal fun XpOmniModule.hookScreenshotHardwareBufferIfPresent(classLoader: ClassLoader) {
    runOptionalHook("hook ScreenshotHardwareBuffer") {
        hookScreenshotHardwareBuffer(classLoader)
    }
}

internal fun XpOmniModule.hookScreenCaptureInPackage(
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

internal fun XpOmniModule.deoptimizeSystemServer(classLoader: ClassLoader) {
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

private fun XpOmniModule.deoptimizeMethods(
    clazz: Class<*>,
    vararg names: String,
) {
    for (method in clazz.declaredMethods) {
        if (method.name.matchesAny(names)) deoptimize(method)
    }
}

internal fun XpOmniModule.hookWindowState(classLoader: ClassLoader) {
    val windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState")
    val isSecureLocked = windowStateClass.getDeclaredMethod("isSecureLocked")

    intercept(isSecureLocked, SCREENSHOT_WINDOW_STATE_HOOK_ID, classLoader)
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

internal fun XpOmniModule.hookScreenCapture(classLoader: ClassLoader) {
    val usesSecureContentPolicy = usesSecureContentPolicy()

    val screenCaptureClass =
        when {
            usesSecureContentPolicy -> {
                classLoader.loadClass("android.window.ScreenCaptureInternal")
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                classLoader.loadClass("android.window.ScreenCapture")
            }

            else -> {
                SurfaceControl::class.java
            }
        }

    hookMethods(
        screenCaptureClass,
        SCREENSHOT_CAPTURE_HOOK_ID,
        "nativeCaptureDisplay",
        "nativeCaptureLayers",
    )
}

private fun usesSecureContentPolicy(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

private fun writeSecureCaptureFlag(
    captureArgs: Any?,
    usesSecureContentPolicy: Boolean,
): Boolean {
    if (captureArgs == null) return false
    return if (usesSecureContentPolicy) {
        captureArgs.writeField("mSecureContentPolicy", 1) ||
            captureArgs.writeField("mCaptureSecureLayers", true)
    } else {
        captureArgs.writeField("mCaptureSecureLayers", true) ||
            captureArgs.writeField("mSecureContentPolicy", 1)
    }
}

internal fun XpOmniModule.hookSystemUiScreenshotMute(classLoader: ClassLoader) {
    hookScreenshotMediaPlayerStart()

    runOptionalHook("hook screenshot sound dispatcher") {
        hookScreenshotSoundDispatcher(classLoader)
    }

    runOptionalHook("hook screenshot sound constructor") {
        hookScreenshotSoundConstructor(classLoader)
    }
}

private fun XpOmniModule.hookScreenshotMediaPlayerStart() {
    val start = MediaPlayer::class.java.getDeclaredMethod("start")

    intercept(start, SCREENSHOT_MEDIA_HOOK_ID)
}

private fun XpOmniModule.hookScreenshotSoundDispatcher(classLoader: ClassLoader) {
    val takeScreenshotExecutorClass = classLoader.loadClass(TAKE_SCREENSHOT_EXECUTOR_IMPL)

    hookMethods(
        takeScreenshotExecutorClass,
        SCREENSHOT_DISPATCHER_HOOK_ID,
        "getScreenshotController",
        classLoader = classLoader,
    )
}

private fun XpOmniModule.hookScreenshotSoundConstructor(classLoader: ClassLoader) {
    val screenshotSoundControllerClass = classLoader.loadClass(SCREENSHOT_SOUND_CONTROLLER_IMPL)

    hookConstructors(screenshotSoundControllerClass, SCREENSHOT_SOUND_CONSTRUCTOR_HOOK_ID, classLoader)
}

internal fun XpOmniModule.hookDisplayControl(classLoader: ClassLoader) {
    val displayControlClass =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            classLoader.loadClass("com.android.server.display.DisplayControl")
        } else {
            SurfaceControl::class.java
        }
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

    intercept(method, SCREENSHOT_DISPLAY_HOOK_ID, classLoader)
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

internal fun XpOmniModule.hookVirtualDisplayAdapter(classLoader: ClassLoader) {
    val virtualDisplayAdapterClass =
        classLoader.loadClass("com.android.server.display.VirtualDisplayAdapter")

    hookMethods(
        virtualDisplayAdapterClass,
        SCREENSHOT_VIRTUAL_DISPLAY_HOOK_ID,
        "createVirtualDisplayLocked",
    )
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun XpOmniModule.hookActivityTaskManagerService(classLoader: ClassLoader) {
    val activityTaskManagerServiceClass =
        classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
    val iBinderClass = classLoader.loadClass("android.os.IBinder")
    val screenCaptureObserverClass = classLoader.loadClass("android.app.IScreenCaptureObserver")
    val method = activityTaskManagerServiceClass.getDeclaredMethod(
        "registerScreenCaptureObserver",
        iBinderClass,
        screenCaptureObserverClass,
    )

    intercept(method, SCREENSHOT_OBSERVER_HOOK_ID)
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
internal fun XpOmniModule.hookWindowManagerService(classLoader: ClassLoader) {
    val windowManagerServiceClass =
        classLoader.loadClass("com.android.server.wm.WindowManagerService")
    val screenRecordingCallbackClass =
        classLoader.loadClass("android.window.IScreenRecordingCallback")
    val method = windowManagerServiceClass.getDeclaredMethod(
        "registerScreenRecordingCallback",
        screenRecordingCallbackClass,
    )

    intercept(method, SCREENSHOT_RECORDING_HOOK_ID)
}

internal fun XpOmniModule.hookActivityManagerService(classLoader: ClassLoader) {
    val activityManagerServiceClass =
        classLoader.loadClass("com.android.server.am.ActivityManagerService")
    val method = activityManagerServiceClass.getDeclaredMethod(
        "checkPermission",
        String::class.java,
        Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!,
    )

    intercept(method, SCREENSHOT_PERMISSION_HOOK_ID)
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun XpOmniModule.hookHyperOS(classLoader: ClassLoader) {
    val windowManagerServiceImplClass =
        classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl")
    hookMethods(windowManagerServiceImplClass, SCREENSHOT_HYPEROS_HOOK_ID, "notAllowCaptureDisplay")
}

internal fun XpOmniModule.hookScreenshotHardwareBuffer(classLoader: ClassLoader) {
    val screenshotHardwareBufferClass =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            classLoader.loadClass("android.window.ScreenCapture\$ScreenshotHardwareBuffer")
        } else {
            classLoader.loadClass("android.view.SurfaceControl\$ScreenshotHardwareBuffer")
        }
    val containsSecureLayers = screenshotHardwareBufferClass.getDeclaredMethod("containsSecureLayers")

    intercept(containsSecureLayers, SCREENSHOT_HARDWARE_BUFFER_HOOK_ID)
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
internal fun XpOmniModule.hookOplusScreenCapture(classLoader: ClassLoader) {
    val captureArgsBuilderClass =
        classLoader.loadClass("com.oplus.screenshot.OplusScreenCapture\$CaptureArgs\$Builder")
    val setUid = captureArgsBuilderClass.getDeclaredMethod(
        "setUid",
        Long::class.javaPrimitiveType!!,
    )

    intercept(setUid, SCREENSHOT_OPLUS_BUILDER_HOOK_ID)
}

internal fun XpOmniModule.hookOplus(classLoader: ClassLoader) {
    val longshotMainClass =
        classLoader.loadClass("com.android.server.wm.OplusLongshotMainWindow")
    hookMethods(longshotMainClass, SCREENSHOT_OPLUS_HOOK_ID, "hasSecure")
}

internal fun XpOmniModule.hookOneUI(classLoader: ClassLoader) {
    val screenshotControllerClass =
        classLoader.loadClass("com.android.server.wm.WmScreenshotController")
    hookMethods(screenshotControllerClass, SCREENSHOT_ONEUI_HOOK_ID, "canBeScreenshotTarget")
}

internal fun XpOmniModule.resolveScreenshotHook(
    hookId: String?,
    executable: Executable,
    classLoader: ClassLoader? = null,
): Hooker? {
    return when (hookId) {
        SCREENSHOT_WINDOW_STATE_HOOK_ID -> {
            val loader = classLoader ?: executable.reloadClassLoader()
            val hostLoader = executable.declaringClass.classLoader
            Hooker { chain -> handleWindowState(chain, loader, hostLoader) }
        }

        SCREENSHOT_CAPTURE_HOOK_ID -> {
            val usesSecureContentPolicy = usesSecureContentPolicy()
            Hooker { chain -> handleScreenCapture(chain, usesSecureContentPolicy) }
        }

        SCREENSHOT_MEDIA_HOOK_ID ->
            Hooker { chain -> handleScreenshotMedia(chain) }

        SCREENSHOT_DISPATCHER_HOOK_ID -> {
            val loader = classLoader ?: executable.reloadClassLoader()
            Hooker { chain -> handleScreenshotDispatcher(chain, loader) }
        }

        SCREENSHOT_SOUND_CONSTRUCTOR_HOOK_ID -> {
            val loader = classLoader ?: executable.reloadClassLoader()
            Hooker { chain -> chain.proceedWithNoOpDispatcherArg(loader) }
        }

        SCREENSHOT_DISPLAY_HOOK_ID -> {
            val loader = classLoader ?: executable.reloadClassLoader()
            val hostLoader = executable.declaringClass.classLoader
            Hooker { chain -> handleDisplayControl(chain, loader, hostLoader) }
        }

        SCREENSHOT_VIRTUAL_DISPLAY_HOOK_ID ->
            Hooker { chain -> handleVirtualDisplay(chain) }

        SCREENSHOT_OBSERVER_HOOK_ID -> Hooker { null }
        SCREENSHOT_RECORDING_HOOK_ID -> Hooker { false }
        SCREENSHOT_PERMISSION_HOOK_ID ->
            Hooker { chain -> handleCapturePermission(chain) }

        SCREENSHOT_HYPEROS_HOOK_ID -> Hooker { false }
        SCREENSHOT_HARDWARE_BUFFER_HOOK_ID -> Hooker { false }
        SCREENSHOT_OPLUS_BUILDER_HOOK_ID ->
            Hooker { chain -> handleOplusBuilder(chain) }

        SCREENSHOT_OPLUS_HOOK_ID -> Hooker { false }
        SCREENSHOT_ONEUI_HOOK_ID -> Hooker { true }
        else -> null
    }
}

private fun handleWindowState(
    chain: Chain,
    classLoader: ClassLoader,
    systemServerClassLoader: ClassLoader?,
): Any? =
    with(chain) {
        if (isCalledFromSurfaceCreation(classLoader, systemServerClassLoader)) proceed() else false
    }

private fun handleScreenCapture(
    chain: Chain,
    usesSecureContentPolicy: Boolean,
): Any? =
    with(chain) {
        writeSecureCaptureFlag(getArg(0), usesSecureContentPolicy)
        proceed()
    }

private fun handleScreenshotMedia(chain: Chain): Any? =
    with(chain) {
        if (isCalledFromSystemUiScreenshot()) null else proceed()
    }

private fun handleScreenshotDispatcher(
    chain: Chain,
    classLoader: ClassLoader,
): Any? =
    with(chain) {
        proceed().also { controller ->
            controller.replaceScreenshotSoundDispatcher(classLoader)
        }
    }

private fun handleDisplayControl(
    chain: Chain,
    classLoader: ClassLoader,
    systemServerClassLoader: ClassLoader?,
): Any? =
    with(chain) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isCalledFromVirtualDisplayCreation(classLoader, systemServerClassLoader)
        ) {
            proceed()
        } else {
            val updatedArgs = argsArray()
            updatedArgs[1] = true
            proceed(updatedArgs)
        }
    }

private fun XpOmniModule.handleVirtualDisplay(chain: Chain): Any? =
    with(chain) {
        val caller = getArg(2) as Int
        if (caller >= APP_UID_START && getArg(1) == null) return@with proceed()

        for (index in 3 until args.size) {
            val flags = getArg(index) as? Int ?: continue
            val updatedArgs = argsArray()
            updatedArgs[index] = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
            return@with proceed(updatedArgs)
        }

        log(Log.WARN, TAG, "flag not found in CreateVirtualDisplayLockedHooker")
        proceed()
    }

private fun handleCapturePermission(chain: Chain): Any? =
    with(chain) {
        if (getArg(0) == CAPTURE_BLACKOUT_CONTENT_PERMISSION) {
            val updatedArgs = argsArray()
            updatedArgs[0] = READ_FRAME_BUFFER_PERMISSION
            proceed(updatedArgs)
        } else {
            proceed()
        }
    }

private fun handleOplusBuilder(chain: Chain): Any? =
    with(chain) {
        val updatedArgs = argsArray()
        updatedArgs[0] = -1L
        proceed(updatedArgs)
    }

private fun Executable.reloadClassLoader(): ClassLoader =
    declaringClass.classLoader
        ?: Thread.currentThread().contextClassLoader
        ?: ClassLoader.getSystemClassLoader()

private fun String.isSurfaceCreationMethod(): Boolean =
    this == "setInitialSurfaceControlProperties" || this == "createSurfaceLocked"

private fun isCalledFromSystemUiScreenshot(): Boolean =
    Throwable().stackTrace.any { frame ->
        frame.className.startsWith(SYSTEMUI_SCREENSHOT_PACKAGE)
    }

private fun Any?.replaceScreenshotSoundDispatcher(classLoader: ClassLoader) {
    val soundController = this?.readField("screenshotSoundController") ?: return
    val dispatcher = classLoader.newNoOpCoroutineDispatcherOrNull() ?: return
    soundController.writeField("bgDispatcher", dispatcher)
}

private fun Chain.proceedWithNoOpDispatcherArg(classLoader: ClassLoader): Any? {
    val dispatcherIndex = args.indexOfFirst { arg ->
        arg?.javaClass?.name?.contains("dispatcher", ignoreCase = true) == true
    }
    if (dispatcherIndex < 0) return proceed()

    val dispatcher = classLoader.newNoOpCoroutineDispatcherOrNull() ?: return proceed()
    val updatedArgs = argsArray()
    updatedArgs[dispatcherIndex] = dispatcher
    return proceed(updatedArgs)
}

private fun ClassLoader.newNoOpCoroutineDispatcherOrNull(): Any? =
    runCatching {
        val dispatcherClass = loadClass(EXECUTOR_COROUTINE_DISPATCHER_IMPL)
        val constructor = dispatcherClass.getDeclaredConstructor(Executor::class.java)
            .apply { isAccessible = true }

        constructor.newInstance(NoOpExecutor)
    }.getOrNull()

private object NoOpExecutor : Executor {
    override fun execute(command: Runnable) = Unit
}
