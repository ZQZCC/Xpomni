package ka.xpomni

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

@SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")
class XpOmniModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Xpomni loaded")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader

        runHook("deoptimize system server") {
            deoptimizeSystemServer(classLoader)
        }

        runHook("hook launcher sleep receiver") {
            hookLauncherSleepReceiver(classLoader)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runHook("hook WindowManagerService") {
                hookWindowManagerService(classLoader)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runHook("hook ActivityTaskManagerService") {
                hookActivityTaskManagerService(classLoader)
            }
            runOptionalHook("hook HyperOS") {
                hookHyperOS(classLoader)
            }
        }

        runHook("hook ScreenCapture") {
            hookScreenCapture(classLoader)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runHook("hook ActivityManagerService") {
                hookActivityManagerService(classLoader)
            }
        }

        runHook("hook DisplayControl") {
            hookDisplayControl(classLoader)
        }

        runHook("hook VirtualDisplayAdapter") {
            hookVirtualDisplayAdapter(classLoader)
        }

        runOptionalHook("hook ScreenshotHardwareBuffer") {
            hookScreenshotHardwareBuffer(classLoader)
        }

        runOptionalHook("hook OneUI") {
            hookOneUI(classLoader)
        }

        runHook("hook WindowState") {
            hookWindowState(classLoader)
        }

        runOptionalHook("hook Oplus") {
            hookOplus(classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return

        val classLoader = param.classLoader
        when (val packageName = param.packageName) {
            OPLUS_SCREENSHOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    runOptionalHook("hook OplusScreenCapture") {
                        hookOplusScreenCapture(classLoader)
                    }
                }
                hookScreenshotHardwareBufferIfPresent(classLoader)
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            FLYME_SYSTEMUIEX, OPLUS_APPPLATFORM -> {
                hookScreenshotHardwareBufferIfPresent(classLoader)
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            SYSTEMUI, MIUI_SCREENSHOT -> {
                if (packageName == SYSTEMUI) {
                    runBiometricHook(classLoader)
                }
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            SHARE_SHEET_PACKAGE -> {
                hookHideDirectShare(classLoader)
            }

            ANDROID_FRAMEWORK, INTENT_RESOLVER -> {
                // Static scope covers both old and new platform split points.
            }

            ANDROID_SYSTEM_INTELLIGENCE -> {
                hookShareTargets()
            }

            GITHUB -> {
                runHook("hook GitHub FastPass") {
                    hookGitHubFastPass(classLoader)
                }
            }

            PIXEL_LAUNCHER, LAUNCHER3 -> {
                hookPixelLauncherFeatures(classLoader)
            }

            else -> runCatching {
                hookOnResume()
            }
        }
    }
}
