@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.content.Intent
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.Locale

private const val KEYGUARD_INDICATION_CONTROLLER_GOOGLE =
    "com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle"
private const val CARRIER_TEXT_CONTROLLER = "com.android.keyguard.CarrierTextController"
private const val BATTERY_STATUS = "com.android.settingslib.fuelgauge.BatteryStatus"

private const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
private const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
private const val EXTRA_TEMPERATURE = "temperature"
private const val MICRO_UNITS = 1_000_000f
private const val TENTHS = 10f
@Volatile
private var chargingInfo = ""

internal fun XpOmniModule.hookKeyguardChargingInfo(classLoader: ClassLoader) {
    val indicationClass = classLoader.loadClass(KEYGUARD_INDICATION_CONTROLLER_GOOGLE)
    val batteryStatusClass = classLoader.loadClass(BATTERY_STATUS)

    hook(batteryStatusClass.getDeclaredConstructor(Intent::class.java, Boolean::class.javaPrimitiveType))
        .intercept { chain ->
            chain.proceed().also { (chain.getArg(0) as Intent).cacheChargingInfo() }
        }
    hookMethods(indicationClass, Hooker { chain ->
        chain.proceed().let { result -> if (result is String) result + chargingInfo else result }
    }, "computePowerIndication")
}

internal fun XpOmniModule.hookKeyguardCarrierText(classLoader: ClassLoader) {
    val carrierTextControllerClass = classLoader.loadClass(CARRIER_TEXT_CONTROLLER)

    val callbackClass = carrierTextControllerClass.getDeclaredField("mCarrierTextCallback").type
    val viewField = carrierTextControllerClass.findField("mView")
    hookMethods(callbackClass, Hooker { null }, "updateCarrierInfo")
    hookMethods(carrierTextControllerClass, Hooker { chain ->
        chain.afterProceed { controller ->
            val view = viewField.get(controller) as TextView
            view.post { view.text = "" }
        }
    }, "onInit")
}

private fun Intent.cacheChargingInfo() {
    val current = getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / MICRO_UNITS
    val voltage = getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / MICRO_UNITS
    val temperature = getIntExtra(EXTRA_TEMPERATURE, 0) / TENTHS
    chargingInfo = if (current <= 0f || voltage <= 0f) "" else String.format(
        Locale.US,
        "\n%.1fW (%.1fV, %.1fA) - %.0f\u00b0C",
        current * voltage,
        voltage,
        current,
        temperature,
    )
}
