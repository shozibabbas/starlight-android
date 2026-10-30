package dev.starlight.charge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class BatteryInfo(
    val level: Int,
    val plugged: Boolean,
    val full: Boolean,
)

object Battery {
    fun read(context: Context, intent: Intent? = sticky(context)): BatteryInfo {
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (raw >= 0 && scale > 0) raw * 100 / scale else 0
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return BatteryInfo(level, plugged, status == BatteryManager.BATTERY_STATUS_FULL || level >= 100)
    }

    fun sticky(context: Context): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}

/**
 * Samples the battery once a second while a charging screen is open: smoothed charging
 * current, power, and an estimate of the time until full.
 */
class ChargeMonitor(private val context: Context, private val demo: Boolean = false) {
    var level = 0
        private set
    var plugged = false
        private set
    var full = false
        private set
    /** Smoothed charging current in mA (0 when unplugged). */
    var milliamps = 0f
        private set
    var watts = 0f
        private set
    /** Minutes until full, or -1 when unknown. */
    var minutesToFull = -1
        private set

    private val manager = context.getSystemService(BatteryManager::class.java)
    private var smoothed = -1f

    fun update() {
        val intent = Battery.sticky(context)
        val info = Battery.read(context, intent)
        level = info.level
        plugged = info.plugged
        full = info.full

        var now = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
        if (now == Int.MIN_VALUE.toLong()) now = 0
        now = abs(now)
        val ma = if (now in 1..19_999) now.toFloat() else now / 1000f // some kernels report mA, most µA
        smoothed = if (smoothed < 0) ma else smoothed + (ma - smoothed) * 0.35f
        milliamps = if (plugged) smoothed else 0f

        var millivolts = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (demo && milliamps < 300) {
            // Preview without real charging: sweep from a slow charger up to HyperCharge.
            val sweep = 0.5f - 0.5f * kotlin.math.cos(System.currentTimeMillis() / 4000.0).toFloat()
            milliamps = 500f + sweep * 7500f
            millivolts = 4200
            plugged = true
            full = false
            if (level >= 100) level = 64
        }
        watts = milliamps / 1000f * millivolts / 1000f
        minutesToFull = estimate()
    }

    private fun estimate(): Int {
        if (!plugged || full || milliamps < 50) return -1
        val own = ownEstimate()
        // HyperOS's estimate can be wildly off (days, after a charger change or a stale
        // reading). Use it only when it is plausible and roughly agrees with ours.
        val system = if (demo) -1L else manager.computeChargeTimeRemaining()
        if (system > 0) {
            val minutes = (system / 60_000).toInt()
            if (minutes in 1..MAX_MINUTES && minutes <= own * 3 && minutes * 3 >= own) return minutes
        }
        return own
    }

    private fun ownEstimate(): Int {
        // Capacity from the charge counter (µAh at the current level), else a typical 5000 mAh.
        val counter = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val capacity = if (counter > 0 && level > 0) counter / 1000f * 100f / level else 5000f
        val charged = capacity * level / 100f
        val fastLimit = capacity * 0.8f
        // Charging tapers off above 80%; assume the last stretch runs at under half the current.
        val hours = if (charged < fastLimit) {
            (fastLimit - charged) / milliamps + (capacity - fastLimit) / (milliamps * 0.45f)
        } else {
            (capacity - charged) / max(milliamps, 50f)
        }
        return (hours * 60 * 1.05f).roundToInt().coerceIn(1, MAX_MINUTES)
    }

    private companion object {
        const val MAX_MINUTES = 20 * 60 // even a slow USB port fills the battery within a day
    }
}
