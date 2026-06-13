@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import java.util.function.BiConsumer
import java.util.function.BiPredicate

private const val SYNTHETIC_CLASS_SCAN_LIMIT = 20
private const val APP_UID_START = 10_000
private const val CAPTURE_BLACKOUT_CONTENT_PERMISSION = "android.permission.CAPTURE_BLACKOUT_CONTENT"
private const val READ_FRAME_BUFFER_PERMISSION = "android.permission.READ_FRAME_BUFFER"

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

private fun String.isSurfaceCreationMethod(): Boolean =
    this == "setInitialSurfaceControlProperties" || this == "createSurfaceLocked"
