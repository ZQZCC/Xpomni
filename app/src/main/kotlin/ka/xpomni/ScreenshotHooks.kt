@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
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

internal fun XpOmniModule.hookScreenCapture(classLoader: ClassLoader) {
    val usesSecureContentPolicy =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

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

    hookMethods(screenCaptureClass, "nativeCaptureDisplay", "nativeCaptureLayers") {
        writeSecureCaptureFlag(getArg(0), usesSecureContentPolicy)
        proceed()
    }
}

private fun writeSecureCaptureFlag(
    captureArgs: Any?,
    usesSecureContentPolicy: Boolean,
): Boolean {
    if (captureArgs == null) return false
    val fields =
        if (usesSecureContentPolicy) {
            arrayOf("mSecureContentPolicy" to 1, "mCaptureSecureLayers" to true)
        } else {
            arrayOf("mCaptureSecureLayers" to true, "mSecureContentPolicy" to 1)
        }
    for ((name, value) in fields) {
        if (captureArgs.writeField(name, value)) return true
    }
    return false
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

    intercept(start) {
        if (isCalledFromSystemUiScreenshot()) {
            null
        } else {
            proceed()
        }
    }
}

private fun XpOmniModule.hookScreenshotSoundDispatcher(classLoader: ClassLoader) {
    val takeScreenshotExecutorClass = classLoader.loadClass(TAKE_SCREENSHOT_EXECUTOR_IMPL)

    hookMethods(takeScreenshotExecutorClass, "getScreenshotController") {
        proceed().also { controller ->
            controller.replaceScreenshotSoundDispatcher(classLoader)
        }
    }
}

private fun XpOmniModule.hookScreenshotSoundConstructor(classLoader: ClassLoader) {
    val screenshotSoundControllerClass = classLoader.loadClass(SCREENSHOT_SOUND_CONTROLLER_IMPL)

    hookConstructors(screenshotSoundControllerClass) {
        proceedWithNoOpDispatcherArg(classLoader)
    }
}

internal fun XpOmniModule.hookDisplayControl(classLoader: ClassLoader) {
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

internal fun XpOmniModule.hookVirtualDisplayAdapter(classLoader: ClassLoader) {
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

    intercept(method) {
        null
    }
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

    intercept(method) {
        false
    }
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
internal fun XpOmniModule.hookHyperOS(classLoader: ClassLoader) {
    val windowManagerServiceImplClass =
        classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl")
    hookMethods(windowManagerServiceImplClass, "notAllowCaptureDisplay") {
        false
    }
}

internal fun XpOmniModule.hookScreenshotHardwareBuffer(classLoader: ClassLoader) {
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
internal fun XpOmniModule.hookOplusScreenCapture(classLoader: ClassLoader) {
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

internal fun XpOmniModule.hookOplus(classLoader: ClassLoader) {
    val longshotMainClass =
        classLoader.loadClass("com.android.server.wm.OplusLongshotMainWindow")
    hookMethods(longshotMainClass, "hasSecure") {
        false
    }
}

internal fun XpOmniModule.hookOneUI(classLoader: ClassLoader) {
    val screenshotControllerClass =
        classLoader.loadClass("com.android.server.wm.WmScreenshotController")
    hookMethods(screenshotControllerClass, "canBeScreenshotTarget") {
        true
    }
}

internal fun XpOmniModule.handleScreenshotHotReloadHook(
    executable: Executable,
    chain: Chain,
): Any? =
    with(chain) {
        when {
            executable.declaringClass.name == "com.android.server.wm.WindowState" &&
                executable.name == "isSecureLocked" -> {
                val loader = executable.reloadClassLoader()
                if (isCalledFromSurfaceCreation(loader, executable.declaringClass.classLoader)) {
                    proceed()
                } else {
                    false
                }
            }

            executable.name == "nativeCaptureDisplay" ||
                executable.name == "nativeCaptureLayers" -> {
                val usesSecureContentPolicy =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
                writeSecureCaptureFlag(getArg(0), usesSecureContentPolicy)
                proceed()
            }

            executable.declaringClass == MediaPlayer::class.java &&
                executable.name == "start" -> {
                if (isCalledFromSystemUiScreenshot()) null else proceed()
            }

            executable.declaringClass.name == TAKE_SCREENSHOT_EXECUTOR_IMPL &&
                executable.name == "getScreenshotController" -> {
                proceed().also { controller ->
                    controller.replaceScreenshotSoundDispatcher(executable.reloadClassLoader())
                }
            }

            executable is Constructor<*> &&
                executable.declaringClass.name == SCREENSHOT_SOUND_CONTROLLER_IMPL -> {
                proceedWithNoOpDispatcherArg(executable.reloadClassLoader())
            }

            executable.name == "createVirtualDisplay" ||
                executable.name == "createDisplay" -> {
                val loader = executable.reloadClassLoader()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    isCalledFromVirtualDisplayCreation(loader, executable.declaringClass.classLoader)
                ) {
                    return@with proceed()
                }

                val args = argsArray()
                args[1] = true
                proceed(args)
            }

            executable.declaringClass.name == "com.android.server.display.VirtualDisplayAdapter" &&
                executable.name == "createVirtualDisplayLocked" -> {
                val caller = getArg(2) as Int
                if (caller >= APP_UID_START && getArg(1) == null) {
                    return@with proceed()
                }

                for (index in 3 until args.size) {
                    val flags = getArg(index) as? Int ?: continue
                    val updatedArgs = argsArray()
                    updatedArgs[index] = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                    return@with proceed(updatedArgs)
                }

                log(Log.WARN, TAG, "flag not found in CreateVirtualDisplayLockedHooker")
                proceed()
            }

            executable.declaringClass.name == "com.android.server.wm.ActivityTaskManagerService" &&
                executable.name == "registerScreenCaptureObserver" -> null

            executable.declaringClass.name == "com.android.server.wm.WindowManagerService" &&
                executable.name == "registerScreenRecordingCallback" -> false

            executable.declaringClass.name == "com.android.server.am.ActivityManagerService" &&
                executable.name == "checkPermission" -> {
                if (getArg(0) == CAPTURE_BLACKOUT_CONTENT_PERMISSION) {
                    val args = argsArray()
                    args[0] = READ_FRAME_BUFFER_PERMISSION
                    proceed(args)
                } else {
                    proceed()
                }
            }

            executable.declaringClass.name == "com.android.server.wm.WindowManagerServiceImpl" &&
                executable.name == "notAllowCaptureDisplay" -> false

            executable.name == "containsSecureLayers" &&
                (
                    executable.declaringClass.name.endsWith("ScreenCapture\$ScreenshotHardwareBuffer") ||
                        executable.declaringClass.name.endsWith("SurfaceControl\$ScreenshotHardwareBuffer")
                    ) -> false

            executable.declaringClass.name == "com.oplus.screenshot.OplusScreenCapture\$CaptureArgs\$Builder" &&
                executable.name == "setUid" -> {
                val args = argsArray()
                args[0] = -1L
                proceed(args)
            }

            executable.declaringClass.name == "com.android.server.wm.OplusLongshotMainWindow" &&
                executable.name == "hasSecure" -> false

            executable.declaringClass.name == "com.android.server.wm.WmScreenshotController" &&
                executable.name == "canBeScreenshotTarget" -> true

            else -> UnhandledHotReloadHook
        }
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
    val dispatcher = classLoader.newNoOpCoroutineDispatcherOrNull() ?: return
    this
        ?.readField("screenshotSoundController")
        ?.writeField("bgDispatcher", dispatcher)
}

private fun Chain.proceedWithNoOpDispatcherArg(classLoader: ClassLoader): Any? {
    val updatedArgs = argsArray()
    val dispatcherIndex = updatedArgs.indexOfFirst { arg ->
        arg?.javaClass?.name?.contains("dispatcher", ignoreCase = true) == true
    }
    if (dispatcherIndex < 0) return proceed()

    val dispatcher = classLoader.newNoOpCoroutineDispatcherOrNull() ?: return proceed()
    updatedArgs[dispatcherIndex] = dispatcher
    return proceed(updatedArgs)
}

private fun ClassLoader.newNoOpCoroutineDispatcherOrNull(): Any? =
    runCatching {
        val dispatcherClass = loadClass(EXECUTOR_COROUTINE_DISPATCHER_IMPL)
        val constructor = dispatcherClass.declaredConstructors
            .firstOrNull { it.parameterCount == 1 }
            ?.apply { isAccessible = true }
            ?: return@runCatching null

        constructor.newInstance(NoOpExecutorService)
    }.getOrNull()

private object NoOpExecutorService : AbstractExecutorService() {
    override fun shutdown() = Unit

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown(): Boolean = false

    override fun isTerminated(): Boolean = false

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = false

    override fun execute(command: Runnable) = Unit
}
