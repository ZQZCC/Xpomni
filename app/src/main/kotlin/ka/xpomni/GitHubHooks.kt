@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal const val GITHUB = "com.github.android"

private const val GITHUB_TWO_FACTOR_ACTIVITY = "com.github.android.twofactor.TwoFactorActivity"
private const val GITHUB_TWO_FACTOR_DIALOG = "com.github.android.twofactor.TwoFactorDialog"
private const val GITHUB_VERIFICATION_APPROVED = "FINISHED_APPROVED"

@Volatile
private var pendingGitHubVerificationActivity: WeakReference<Activity>? = null

internal fun XpOmniModule.hookGitHubFastPass(classLoader: ClassLoader) {
    val dialogClass = classLoader.loadClass(GITHUB_TWO_FACTOR_DIALOG)
    val activityClass = classLoader.loadClass(GITHUB_TWO_FACTOR_ACTIVITY)
    val approvedStateClass =
        dialogClass.findEnumState(GITHUB_VERIFICATION_APPROVED)
            ?: return log(Log.WARN, TAG, "GitHub verification state enum not found")
    val stateMapper =
        dialogClass.findStateMapper(approvedStateClass)
            ?: return log(Log.WARN, TAG, "GitHub verification state mapper not found")
    val onCreate = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)

    intercept(onCreate) {
        proceed().also {
            pendingGitHubVerificationActivity = WeakReference(thisObject as Activity)
        }
    }

    intercept(stateMapper) {
        val result = proceed()
        val state = result as? Enum<*> ?: return@intercept result
        if (state.name != GITHUB_VERIFICATION_APPROVED) return@intercept result

        val activity = pendingGitHubVerificationActivity
            ?.get()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return@intercept result

        pendingGitHubVerificationActivity = null
        Handler(Looper.getMainLooper()).post { activity.finish() }
        log(Log.INFO, TAG, "dismissed GitHub verification dialog")
        result
    }

    log(Log.INFO, TAG, "hooked GitHub FastPass")
}

private fun Class<*>.findEnumState(name: String): Class<*>? =
    declaredClasses.firstOrNull { candidate ->
        candidate.isEnum &&
            candidate.enumConstants.orEmpty().any { (it as Enum<*>).name == name }
    }

private fun Class<*>.findStateMapper(stateClass: Class<*>): Method? =
    declaredMethods.firstOrNull { method ->
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            method.returnType == stateClass
    }
