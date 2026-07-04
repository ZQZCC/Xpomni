@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.util.Log
import android.os.SystemClock
import android.view.View
import android.widget.Button
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable

private const val BIOMETRIC_TARGET_CLASS = "com.android.systemui.biometrics.AuthContainerView"
private const val BIOMETRIC_TARGET_METHOD = "onDialogAnimatedIn"
private const val BIOMETRIC_BUTTON_CONFIRM_ID = "button_confirm"
private const val BIOMETRIC_MAX_WAIT_MS = 1_500L

@Volatile
private var biometricConfirmButtonId = 0

internal fun XpOmniModule.runBiometricHook(classLoader: ClassLoader) {
    runCatching {
        hookBiometricBypass(classLoader)
    }.onFailure { error ->
        if (error !is ClassNotFoundException && error !is NoSuchMethodException) {
            log(Log.ERROR, TAG, "hook BiometricBypass failed", error)
        }
    }
}

private fun XpOmniModule.hookBiometricBypass(classLoader: ClassLoader) {
    val authContainerViewClass = classLoader.loadClass(BIOMETRIC_TARGET_CLASS)
    val onDialogAnimatedIn = authContainerViewClass.getDeclaredMethod(BIOMETRIC_TARGET_METHOD)

    intercept(onDialogAnimatedIn) {
        val result = proceed()
        (thisObject as? View)?.let { authContainerView ->
            requestBiometricConfirm(authContainerView)
        }
        result
    }
}

internal fun XpOmniModule.handleBiometricHotReloadHook(
    executable: Executable,
    chain: Chain,
): Any? {
    if (executable.declaringClass.name != BIOMETRIC_TARGET_CLASS ||
        executable.name != BIOMETRIC_TARGET_METHOD
    ) {
        return UnhandledHotReloadHook
    }
    return with(chain) {
        val result = proceed()
        (thisObject as? View)?.let { authContainerView ->
            requestBiometricConfirm(authContainerView)
        }
        result
    }
}

private fun XpOmniModule.requestBiometricConfirm(authContainerView: View) {
    val buttonId = authContainerView.biometricConfirmButtonId()

    if (buttonId == 0) {
        log(Log.WARN, TAG, "Biometric confirm button id not found")
        return
    }

    val startUptimeMs = SystemClock.uptimeMillis()
    if (clickBiometricConfirmButton(authContainerView, buttonId)) {
        return
    }

    retryBiometricConfirmUntilShown(
        parentView = authContainerView,
        buttonId = buttonId,
        startUptimeMs = startUptimeMs,
    )
}

private fun View.biometricConfirmButtonId(): Int {
    val cachedId = biometricConfirmButtonId
    if (cachedId != 0) return cachedId

    val id = context.resources.getIdentifier(
        BIOMETRIC_BUTTON_CONFIRM_ID,
        "id",
        context.packageName,
    )
    biometricConfirmButtonId = id
    return id
}

private fun clickBiometricConfirmButton(
    parentView: View,
    buttonId: Int,
): Boolean {
    val button = parentView.findViewById<Button?>(buttonId)
    if (button == null || !button.isShown) {
        return false
    }

    button.performClick()
    return true
}

private fun XpOmniModule.retryBiometricConfirmUntilShown(
    parentView: View,
    buttonId: Int,
    startUptimeMs: Long,
) {
    parentView.postOnAnimation {
        if (clickBiometricConfirmButton(parentView, buttonId)) {
            return@postOnAnimation
        }

        val elapsedMs = SystemClock.uptimeMillis() - startUptimeMs
        if (elapsedMs >= BIOMETRIC_MAX_WAIT_MS) {
            log(Log.WARN, TAG, "Biometric confirm button not found after ${elapsedMs}ms")
            return@postOnAnimation
        }

        retryBiometricConfirmUntilShown(parentView, buttonId, startUptimeMs)
    }
}
