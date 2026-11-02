package ka.xpomni

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class XpOmniModule : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val loader = param.classLoader
        runHook("deoptimize capture callers") { deoptimizeSystemServer(loader) }
        runHook("launcher sleep receiver") { hookLauncherSleepReceiver(loader) }
        runHook("screen recording callback") { hookWindowManagerService(loader) }
        runHook("screen capture observer") { hookActivityTaskManagerService(loader) }
        runHook("secure capture") { hookScreenCapture(loader) }
        runHook("display control") { hookDisplayControl(loader) }
        runHook("virtual display") { hookVirtualDisplayAdapter(loader) }
        runHook("screenshot buffer") { hookScreenshotHardwareBuffer(loader) }
        runHook("secure window") { hookWindowState(loader) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val loader = param.classLoader
        when (param.packageName) {
            SYSTEMUI -> {
                runHook("screenshot mute") { hookSystemUiScreenshotMute() }
                if (processName == SYSTEMUI) {
                    runHook("biometric confirmation") { hookBiometricBypass(loader) }
                    runHook("charging information") { hookKeyguardChargingInfo(loader) }
                    runHook("carrier text") { hookKeyguardCarrierText(loader) }
                    runHook("quick settings rows") { hookQuickSettingsTileRows() }
                    runHook("network traffic") { hookStatusBarTrafficIndicator(loader) }
                }
            }
            INTENT_RESOLVER -> hookHideDirectShare(loader)
            ANDROID_SYSTEM_INTELLIGENCE -> runHook("share targets") { hookShareTargets() }
            GITHUB -> runHook("GitHub verification") { hookGitHubFastPass(loader) }
            FLUD_PLUS -> runHook("Flud trackers") { hookFludTrackerUpdater() }
            PIXEL_LAUNCHER -> hookPixelLauncherFeatures(loader)
        }
    }
}
