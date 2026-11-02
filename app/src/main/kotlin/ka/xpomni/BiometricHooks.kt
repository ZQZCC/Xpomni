@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button

internal fun XpOmniModule.hookBiometricBypass(classLoader: ClassLoader) {
    val method = classLoader.loadClass("com.android.systemui.biometrics.AuthContainerView")
        .getDeclaredMethod("onDialogAnimatedIn")
    var confirmId = 0
    hook(method).intercept { chain ->
        chain.proceed().also {
            val view = chain.thisObject as View
            if (confirmId == 0)
                confirmId = view.resources.getIdentifier("button_confirm", "id", SYSTEMUI)
            if (confirmId == 0) {
                log(Log.WARN, TAG, "Biometric confirm button id not found")
            } else {
                val deadline = SystemClock.uptimeMillis() + 1_500L
                object : Runnable {
                    override fun run() {
                        if (!view.isAttachedToWindow) return
                        val button = view.findViewById<Button>(confirmId)
                        if (button != null && button.isShown && button.performClick()) return
                        if (SystemClock.uptimeMillis() < deadline) view.postOnAnimation(this)
                        else log(Log.WARN, TAG, "Biometric confirmation timed out")
                    }
                }.run()
            }
        }
    }
}
