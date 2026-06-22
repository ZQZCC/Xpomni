package ka.xpomni

import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.text.DecimalFormat
import kotlin.math.max

private const val PHONE_STATUS_BAR_VIEW_CONTROLLER =
    "com.android.systemui.statusbar.phone.PhoneStatusBarViewController"
private const val STATUS_BAR_CLOCK = "com.android.systemui.statusbar.policy.Clock"
private const val STATUS_ICONS_ID = "statusIcons"
private const val CLOCK_ID = "clock"
private const val TRAFFIC_REFRESH_INTERVAL_MS = 1_000L
private const val TRAFFIC_AUTO_HIDE_THRESHOLD_BYTES = 100L * 1024L
private const val BITS_PER_BYTE = 8L
private const val KILO = 1024L
private const val MEGA = KILO * KILO
private const val GIGA = MEGA * KILO
private const val TRAFFIC_SYMBOL = "/s"
private const val TRAFFIC_INDICATOR_TAG = "ka.xpomni.STATUS_BAR_TRAFFIC"
private const val STATUS_BAR_CLOCK_STARTING_PADDING = "status_bar_clock_starting_padding"
private const val STATUS_BAR_LEFT_CLOCK_END_PADDING = "status_bar_left_clock_end_padding"

@Volatile
private var trafficIndicatorRef: WeakReference<StatusBarTrafficView>? = null

internal fun XpOmniModule.hookStatusBarTrafficIndicator(classLoader: ClassLoader) {
    val controllerClass = classLoader.loadClass(PHONE_STATUS_BAR_VIEW_CONTROLLER)

    hookMethods(controllerClass, "onViewAttached") {
        proceed().also {
            thisObject
                ?.statusBarViewOrNull()
                ?.installTrafficIndicator()
        }
    }

    runCatching { classLoader.loadClass(STATUS_BAR_CLOCK) }.getOrNull()?.let { clockClass ->
        hookMethods(clockClass, "onDarkChanged") {
            proceed().also {
                val color = (thisObject as? TextView)?.currentTextColor ?: return@also
                trafficIndicatorRef?.get()?.setTrafficColor(color)
            }
        }
    }

    log(Log.INFO, TAG, "hooked status bar traffic indicator")
}

internal fun XpOmniModule.handleStatusBarTrafficHotReloadHook(
    executable: Executable,
    chain: Chain,
): Any? =
    with(chain) {
        when {
            executable.declaringClass.name == PHONE_STATUS_BAR_VIEW_CONTROLLER &&
                executable.name == "onViewAttached" -> {
                proceed().also {
                    thisObject
                        ?.statusBarViewOrNull()
                        ?.installTrafficIndicator()
                }
            }

            executable.declaringClass.name == STATUS_BAR_CLOCK &&
                executable.name == "onDarkChanged" -> {
                proceed().also {
                    val clock = thisObject as? TextView ?: return@also
                    val color = clock.currentTextColor
                    trafficIndicatorRef?.get()?.setTrafficColor(color)
                    if (trafficIndicatorRef?.get()?.parent == null) {
                        (clock.rootView as? ViewGroup)?.installTrafficIndicator()
                    }
                }
            }

            else -> UnhandledHotReloadHook
        }
    }

private fun Any.statusBarViewOrNull(): ViewGroup? =
    (readField("mView") as? ViewGroup)
        ?: (readField("mPhoneStatusBarView") as? ViewGroup)

private fun ViewGroup.installTrafficIndicator() {
    val systemIcons = findSystemIcons() ?: return
    val parent = systemIcons.parent as? ViewGroup ?: return
    val indicator = StatusBarTrafficView(context)

    parent.removeTrafficIndicators(systemIcons)
    indicator.tag = TRAFFIC_INDICATOR_TAG

    findClock()?.let { clock ->
        indicator.setTrafficColor(clock.currentTextColor)
    }

    indicator.setPadding(
        context.statusBarDimen(STATUS_BAR_CLOCK_STARTING_PADDING),
        0,
        context.statusBarDimen(STATUS_BAR_LEFT_CLOCK_END_PADDING),
        0,
    )

    parent.addView(
        indicator,
        0,
        parent.trafficLayoutParams(),
    )

    trafficIndicatorRef = WeakReference(indicator)
}

private fun ViewGroup.trafficLayoutParams(): ViewGroup.LayoutParams =
    if (this is LinearLayout) {
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    } else {
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

private fun ViewGroup.removeTrafficIndicators(systemIcons: View) {
    val hostClassLoader = context.classLoader
    for (index in childCount - 1 downTo 0) {
        val child = getChildAt(index)
        if (child === systemIcons) continue
        if (child.isTrafficIndicator(hostClassLoader)) {
            removeView(child)
        }
    }
}

private fun View.isTrafficIndicator(hostClassLoader: ClassLoader?): Boolean {
    if (tag == TRAFFIC_INDICATOR_TAG) return true
    if (this !is LinearLayout && this !is FrameLayout) return false

    val loader = javaClass.classLoader ?: return false
    return loader != hostClassLoader && loader != View::class.java.classLoader
}

private fun View.findSystemIcons(): ViewGroup? =
    findChildByResourceName(STATUS_ICONS_ID) as? ViewGroup

private fun View.findClock(): TextView? =
    findChildByResourceName(CLOCK_ID) as? TextView

private fun View.findChildByResourceName(entryName: String): View? {
    val id = context.resources.getIdentifier(entryName, "id", SYSTEMUI)
    if (id == 0) return null
    return findViewById(id)
}

private class StatusBarTrafficView(context: Context) : FrameLayout(context) {
    private val trafficText = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var lastRxBytes = TrafficStats.UNSUPPORTED.toLong()
    private var lastTxBytes = TrafficStats.UNSUPPORTED.toLong()
    private var lastUpdateTime = 0L

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTraffic()
            handler.postDelayed(this, TRAFFIC_REFRESH_INTERVAL_MS)
        }
    }

    init {
        alpha = 1f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false

        val contentLayout = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            )
        }

        trafficText.gravity = Gravity.CENTER
        trafficText.maxLines = 2
        trafficText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        trafficText.includeFontPadding = false
        trafficText.typeface = context.headlineBoldTypeface()
        trafficText.setLineSpacing(0.85f, 0.85f)
        trafficText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)

        addView(contentLayout)
        contentLayout.addView(
            trafficText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resetCounters()
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(updateRunnable)
        super.onDetachedFromWindow()
    }

    fun setTrafficColor(color: Int) {
        trafficText.setTextColor(color)
    }

    private fun updateTraffic() {
        val now = SystemClock.elapsedRealtime()
        val rxBytes = TrafficStats.getTotalRxBytes()
        val txBytes = TrafficStats.getTotalTxBytes()

        if (
            rxBytes == TrafficStats.UNSUPPORTED.toLong() ||
            txBytes == TrafficStats.UNSUPPORTED.toLong()
        ) {
            visibility = GONE
            resetCounters()
            return
        }

        val elapsedMs = max(now - lastUpdateTime, 1L)
        val rxBytesPerSecond = max(rxBytes - lastRxBytes, 0L) * 1_000L / elapsedMs
        val txBytesPerSecond = max(txBytes - lastTxBytes, 0L) * 1_000L / elapsedMs

        lastRxBytes = rxBytes
        lastTxBytes = txBytes
        lastUpdateTime = now

        val totalBytesPerSecond = rxBytesPerSecond + txBytesPerSecond
        if (!hasActiveNetwork() || shouldAutoHide(totalBytesPerSecond)) {
            visibility = GONE
            return
        }

        trafficText.text = formatPixelXpertTrafficText(totalBytesPerSecond)
        visibility = VISIBLE
    }

    private fun hasActiveNetwork(): Boolean =
        attempt(false) { connectivityManager.activeNetwork != null }

    private fun shouldAutoHide(totalBytesPerSecond: Long): Boolean =
        totalBytesPerSecond < TRAFFIC_AUTO_HIDE_THRESHOLD_BYTES

    private fun resetCounters() {
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastUpdateTime = SystemClock.elapsedRealtime()
    }
}

private fun formatPixelXpertTrafficText(bytesPerSecond: Long): SpannableStringBuilder {
    val bitsPerSecond = bytesPerSecond * BITS_PER_BYTE
    val (formattedData, unit) =
        when {
            bitsPerSecond >= GIGA ->
                DecimalFormat("0.00").format(bitsPerSecond / GIGA.toFloat()) to "Gb"

            bitsPerSecond >= 100 * MEGA ->
                DecimalFormat("000").format(bitsPerSecond / MEGA.toFloat()) to "Mb"

            bitsPerSecond >= 10 * MEGA ->
                DecimalFormat("00.0").format(bitsPerSecond / MEGA.toFloat()) to "Mb"

            bitsPerSecond >= MEGA ->
                DecimalFormat("0.00").format(bitsPerSecond / MEGA.toFloat()) to "Mb"

            bitsPerSecond >= 100 * KILO ->
                DecimalFormat("000").format(bitsPerSecond / KILO.toFloat()) to "Kb"

            bitsPerSecond >= 10 * KILO ->
                DecimalFormat("00.0").format(bitsPerSecond / KILO.toFloat()) to "Kb"

            else ->
                DecimalFormat("0.00").format(bitsPerSecond / KILO.toFloat()) to "Kb"
        }

    val unitString = SpannableString(unit + TRAFFIC_SYMBOL).apply {
        setSpan(
            RelativeSizeSpan(0.7f),
            0,
            length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    return SpannableStringBuilder()
        .append(SpannableString(formattedData))
        .append("\n")
        .append(unitString)
}

private fun Context.statusBarDimen(name: String): Int {
    val id = resources.getIdentifier(name, "dimen", SYSTEMUI)
    return if (id == 0) 0 else resources.getDimensionPixelSize(id)
}

private fun Context.headlineBoldTypeface() =
    android.graphics.Typeface.create(
        attempt("sans-serif") {
            val id = resources.getIdentifier("config_headlineFontFamily", "string", "android")
            if (id == 0) "sans-serif" else resources.getString(id)
        },
        android.graphics.Typeface.BOLD,
    )
