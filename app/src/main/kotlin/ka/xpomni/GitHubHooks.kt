@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal const val GITHUB = "com.github.android"

private const val GITHUB_TWO_FACTOR_ACTIVITY = "com.github.android.twofactor.TwoFactorActivity"
private const val GITHUB_TWO_FACTOR_DIALOG = "com.github.android.twofactor.TwoFactorDialog"
private const val GITHUB_VERIFICATION_APPROVED = "FINISHED_APPROVED"
private const val GITHUB_ACTIVITY_HOOK_ID = "github.activity"
private const val GITHUB_STATE_HOOK_ID = "github.state"

@Volatile
private var pendingGitHubVerificationActivity: WeakReference<Activity>? = null
private val githubMainHandler = Handler(Looper.getMainLooper())

internal fun clearGitHubHotReloadState() {
    pendingGitHubVerificationActivity = null
}

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

    intercept(onCreate, GITHUB_ACTIVITY_HOOK_ID)

    intercept(stateMapper, GITHUB_STATE_HOOK_ID)
}

internal fun XpOmniModule.resolveGitHubHook(
    hookId: String?,
): Hooker? {
    return when (hookId) {
        GITHUB_ACTIVITY_HOOK_ID ->
            Hooker { chain -> handleGitHubActivity(chain) }

        GITHUB_STATE_HOOK_ID ->
            Hooker { chain -> handleGitHubState(chain) }

        else -> null
    }
}

private fun handleGitHubActivity(chain: Chain): Any? =
    with(chain) {
        proceed().also {
            pendingGitHubVerificationActivity = WeakReference(thisObject as Activity)
        }
    }

private fun XpOmniModule.handleGitHubState(chain: Chain): Any? =
    with(chain) {
        val result = proceed()
        val state = result as? Enum<*>
        if (state?.name == GITHUB_VERIFICATION_APPROVED) {
            dismissPendingGitHubVerification()
        }
        result
    }

private fun XpOmniModule.dismissPendingGitHubVerification() {
    val activity = pendingGitHubVerificationActivity
        ?.get()
        ?.takeUnless { it.isFinishing || it.isDestroyed }
        ?: return

    pendingGitHubVerificationActivity = null
    githubMainHandler.post { activity.finish() }
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
