@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.os.IBinder
import io.github.libxposed.api.XposedInterface.Hooker

internal fun XpOmniModule.deoptimizeSystemServer(classLoader: ClassLoader) {
    // Direct callers and their inlined wrappers in the target Android 17 services.jar.
    val callers = arrayOf(
        "com.android.server.wm.WindowStateAnimator" to "createSurfaceLocked",
        "com.android.server.wm.WindowState" to "setInitialSurfaceControlProperties",
        "com.android.server.wm.WindowState" to "canScreenshotIme",
        "com.android.server.wm.WindowManagerService" to "relayoutWindow",
        "com.android.server.wm.Session" to "relayout",
        "com.android.server.wm.DisplayContent\$4" to "test",
        "com.android.server.wm.ActivityRecord\$\$ExternalSyntheticLambda24" to "apply",
        "com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda27" to "accept",
    )
    for ((name, methodName) in callers) {
        for (method in classLoader.loadClass(name).declaredMethods)
            if (method.name == methodName) deoptimize(method)
    }
    val windowState = classLoader.loadClass("com.android.server.wm.WindowState")
    for (method in classLoader.loadClass("com.android.server.wm.RootWindowContainer").declaredMethods) {
        if (method.name.startsWith("\$r8\$lambda\$") &&
            method.parameterTypes.contentEquals(arrayOf(windowState))) deoptimize(method)
    }
}

internal fun XpOmniModule.hookWindowState(classLoader: ClassLoader) {
    val method = classLoader.loadClass("com.android.server.wm.WindowState")
        .getDeclaredMethod("isSecureLocked")
    val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    hook(method).intercept { chain ->
        val creatingSurface = walker.walk { frames ->
            frames.anyMatch {
                it.declaringClass.classLoader === classLoader &&
                    (it.methodName == "setInitialSurfaceControlProperties" ||
                        it.methodName == "createSurfaceLocked")
            }
        }
        if (creatingSurface) chain.proceed() else false
    }
}

internal fun XpOmniModule.hookScreenCapture(classLoader: ClassLoader) {
    val captureClass = classLoader.loadClass("android.window.ScreenCaptureInternal")
    val policyField = classLoader.loadClass("android.window.ScreenCaptureInternal\$CaptureArgs")
        .getDeclaredField("mSecureContentPolicy").apply { isAccessible = true }
    hookMethods(captureClass, Hooker { chain ->
        policyField.setInt(chain.getArg(0), 1)
        chain.proceed()
    }, "nativeCaptureDisplay", "nativeCaptureLayers")
}

internal fun XpOmniModule.hookSystemUiScreenshotMute() {
    val walker = StackWalker.getInstance()
    hook(MediaPlayer::class.java.getDeclaredMethod("start")).intercept { chain ->
        val screenshot = walker.walk { frames ->
            frames.anyMatch { it.className.startsWith("com.android.systemui.screenshot.") }
        }
        if (screenshot) null else chain.proceed()
    }
}

internal fun XpOmniModule.hookDisplayControl(classLoader: ClassLoader) {
    val method = classLoader.loadClass("com.android.server.display.DisplayControl")
        .getDeclaredMethod("createVirtualDisplay", String::class.java, Boolean::class.javaPrimitiveType)
    hook(method).intercept { chain ->
        val args = chain.argsArray()
        args[1] = true
        chain.proceed(args)
    }
}

internal fun XpOmniModule.hookVirtualDisplayAdapter(classLoader: ClassLoader) {
    hookMethods(classLoader.loadClass("com.android.server.display.VirtualDisplayAdapter"), Hooker { chain ->
        if ((chain.getArg(2) as Int) >= 10_000 && chain.getArg(1) == null) {
            chain.proceed()
        } else {
            val args = chain.argsArray()
            args[6] = (args[6] as Int) or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
            chain.proceed(args)
        }
    }, "createVirtualDisplayLocked")
}

internal fun XpOmniModule.hookActivityTaskManagerService(classLoader: ClassLoader) {
    val method = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
        .getDeclaredMethod(
            "registerScreenCaptureObserver", IBinder::class.java,
            classLoader.loadClass("android.app.IScreenCaptureObserver"),
        )
    hook(method).intercept { null }
}

internal fun XpOmniModule.hookWindowManagerService(classLoader: ClassLoader) {
    val method = classLoader.loadClass("com.android.server.wm.WindowManagerService")
        .getDeclaredMethod(
            "registerScreenRecordingCallback",
            classLoader.loadClass("android.window.IScreenRecordingCallback"),
        )
    hook(method).intercept { false }
}

internal fun XpOmniModule.hookScreenshotHardwareBuffer(classLoader: ClassLoader) {
    val method = classLoader.loadClass("android.window.ScreenCaptureInternal\$ScreenshotHardwareBuffer")
        .getDeclaredMethod("containsSecureLayers")
    hook(method).intercept { false }
}
