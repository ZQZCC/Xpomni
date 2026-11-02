@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

internal const val GITHUB = "com.github.android"

private const val GITHUB_TWO_FACTOR_ACTIVITY = "com.github.android.twofactor.TwoFactorActivity"
private const val GITHUB_TWO_FACTOR_DIALOG = "com.github.android.twofactor.TwoFactorDialog"
private const val GITHUB_VERIFICATION_APPROVED = "FINISHED_APPROVED"

@Volatile
private var pendingGitHubVerificationActivity: WeakReference<Activity>? = null
private val githubMainHandler = Handler(Looper.getMainLooper())

internal fun XpOmniModule.hookGitHubFastPass(classLoader: ClassLoader) {
    val dialogClass = classLoader.loadClass(GITHUB_TWO_FACTOR_DIALOG)
    val activityClass = classLoader.loadClass(GITHUB_TWO_FACTOR_ACTIVITY)
    val stateMapper =
        dialogClass.declaredMethods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) && method.parameterCount == 1 &&
                method.returnType.isEnum && method.returnType.enumConstants!!.any {
                    (it as Enum<*>).name == GITHUB_VERIFICATION_APPROVED
                }
        }
            ?: return log(Log.WARN, TAG, "GitHub verification state mapper not found")
    val approvedState = stateMapper.returnType.enumConstants!!.first {
        (it as Enum<*>).name == GITHUB_VERIFICATION_APPROVED
    }
    val onCreate = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)

    hook(onCreate).intercept { chain ->
        pendingGitHubVerificationActivity = WeakReference(chain.thisObject as Activity)
        chain.proceed()
    }
    hook(stateMapper).intercept { chain ->
        chain.proceed().also { result ->
            if (result === approvedState) {
                val activity = pendingGitHubVerificationActivity?.get()
                pendingGitHubVerificationActivity = null
                if (activity != null) githubMainHandler.post {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
        }
    }
}
