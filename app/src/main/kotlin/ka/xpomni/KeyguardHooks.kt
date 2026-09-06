@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.content.Intent
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

private const val KEYGUARD_INDICATION_CONTROLLER_GOOGLE =
    "com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle"
private const val KEYGUARD_INDICATION_CONTROLLER =
    "com.android.systemui.statusbar.KeyguardIndicationController"
private const val CARRIER_TEXT_CONTROLLER = "com.android.keyguard.CarrierTextController"
private const val BATTERY_STATUS = "com.android.settingslib.fuelgauge.BatteryStatus"
private const val EMPTY_CARRIER_TEXT = ""

private const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
private const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
private const val EXTRA_TEMPERATURE = "temperature"
private const val MICRO_UNITS = 1_000_000f
private const val TENTHS = 10f
private const val KEYGUARD_BATTERY_HOOK_ID = "keyguard.battery"
private const val KEYGUARD_POWER_HOOK_ID = "keyguard.power"
private const val KEYGUARD_CARRIER_INIT_HOOK_ID = "keyguard.carrier_init"
private const val KEYGUARD_CARRIER_UPDATE_HOOK_ID = "keyguard.carrier_update"

@Volatile
private var maxChargingCurrentAmps = 0f

@Volatile
private var maxChargingVoltageVolts = 0f

@Volatile
private var batteryTemperatureCelsius = 0f

private val carrierTextCallbackClasses = ConcurrentHashMap.newKeySet<Class<*>>()

internal fun XpOmniModule.hookKeyguardChargingInfo(classLoader: ClassLoader) {
    val indicationClass = classLoader.loadFirstClass(
        KEYGUARD_INDICATION_CONTROLLER_GOOGLE,
        KEYGUARD_INDICATION_CONTROLLER,
    )
    val batteryStatusClass = classLoader.loadClass(BATTERY_STATUS)

    hookConstructors(batteryStatusClass, KEYGUARD_BATTERY_HOOK_ID)

    hookMethods(indicationClass, KEYGUARD_POWER_HOOK_ID, "computePowerIndication")
}

internal fun XpOmniModule.hookKeyguardCarrierText(classLoader: ClassLoader) {
    val carrierTextControllerClass = classLoader.loadClass(CARRIER_TEXT_CONTROLLER)

    hookMethods(carrierTextControllerClass, KEYGUARD_CARRIER_INIT_HOOK_ID, "onInit")
}

internal fun XpOmniModule.resolveKeyguardHook(
    hookId: String?,
): Hooker? {
    return when (hookId) {
        KEYGUARD_BATTERY_HOOK_ID ->
            Hooker { chain -> handleBatteryStatus(chain) }

        KEYGUARD_POWER_HOOK_ID ->
            Hooker { chain -> handlePowerIndication(chain) }

        KEYGUARD_CARRIER_INIT_HOOK_ID ->
            Hooker { chain -> handleCarrierTextInit(chain) }

        KEYGUARD_CARRIER_UPDATE_HOOK_ID ->
            Hooker { null }

        else -> null
    }
}

private fun handleBatteryStatus(chain: Chain): Any? =
    with(chain) {
        proceed().also {
            (args.firstOrNull() as? Intent)?.cacheChargingInfo()
        }
    }

private fun handlePowerIndication(chain: Chain): Any? =
    with(chain) {
        val result = proceed()
        if (result is String) appendChargingInfo(result) else result
    }

private fun XpOmniModule.handleCarrierTextInit(chain: Chain): Any? =
    with(chain) {
        proceed().also {
            thisObject?.clearCarrierText()
            thisObject
                ?.readField("mCarrierTextCallback")
                ?.let { callback -> hookCarrierTextUpdates(callback) }
        }
    }

private fun Intent.cacheChargingInfo() {
    maxChargingCurrentAmps = getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / MICRO_UNITS
    maxChargingVoltageVolts = getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / MICRO_UNITS
    batteryTemperatureCelsius = getIntExtra(EXTRA_TEMPERATURE, 0) / TENTHS
}

private fun appendChargingInfo(text: String): String {
    val current = maxChargingCurrentAmps
    val voltage = maxChargingVoltageVolts
    val temperature = batteryTemperatureCelsius

    if (current <= 0f || voltage <= 0f) return text

    return String.format(
        Locale.US,
        "%s\n%.1fW (%.1fV, %.1fA) - %.0f\u00b0C",
        text,
        current * voltage,
        voltage,
        current,
        temperature,
    )
}

private fun Any.clearCarrierText() {
    val view = readField("mView") as? TextView ?: return
    view.post { view.text = EMPTY_CARRIER_TEXT }
}

private fun XpOmniModule.hookCarrierTextUpdates(callback: Any) {
    val callbackClass = callback.javaClass
    if (!carrierTextCallbackClasses.add(callbackClass)) return

    hookMethods(callbackClass, KEYGUARD_CARRIER_UPDATE_HOOK_ID, "updateCarrierInfo")
}

private fun ClassLoader.loadFirstClass(vararg names: String): Class<*> {
    for (name in names) {
        val clazz = runCatching { loadClass(name) }.getOrNull()
        if (clazz != null) return clazz
    }
    throw ClassNotFoundException(names.joinToString())
}
