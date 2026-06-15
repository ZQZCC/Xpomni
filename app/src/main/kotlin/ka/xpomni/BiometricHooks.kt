@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")

package ka.xpomni

import android.util.Log
import android.view.View
import android.widget.Button
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable

private const val BIOMETRIC_TARGET_CLASS = "com.android.systemui.biometrics.AuthContainerView"
private const val BIOMETRIC_TARGET_METHOD = "onDialogAnimatedIn"
private const val BIOMETRIC_BUTTON_CONFIRM_ID = "button_confirm"
private const val BIOMETRIC_MAX_RETRIES = 3
private const val BIOMETRIC_INITIAL_DELAY_MS = 100L

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
}

private fun View.biometricOpPackageName(): String =
    runCatching {
        val config = readField("mConfig") ?: return@runCatching null
        config.readField("mOpPackageName") as? String
    }.onFailure { error ->
        Log.w(TAG, "biometric op package reflection failed", error)
    }.getOrNull() ?: "unknown"

private fun XpOmniModule.scheduleBiometricConfirmClick(
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

private fun XpOmniModule.clickBiometricConfirmButton(
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
