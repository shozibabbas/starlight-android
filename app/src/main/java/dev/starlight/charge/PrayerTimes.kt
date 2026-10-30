package dev.starlight.charge

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Prayer times by the Shia Ithna-Ashari (Jafari) method of the Leva Institute, Qum:
 * Fajr at 16°, Maghrib at 4° and Isha at 14° below the horizon, Asr at shadow factor 1.
 * Follows the PrayTimes.org formulas.
 */
object PrayerTimes {

    val NAMES = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Sunset", "Maghrib", "Isha")

    /** The five prayers shown on the widget, as indices into [NAMES]. */
    val PRAYERS = listOf(0, 2, 3, 5, 6)

    /** Times for the given local date as epoch millis, in [NAMES] order. */
    fun forDay(day: Calendar, latitude: Double, longitude: Double): List<Long> {
        val y = day.get(Calendar.YEAR)
        val m = day.get(Calendar.MONTH) + 1
        val d = day.get(Calendar.DAY_OF_MONTH)
        val jDate = julian(y, m, d) - longitude / (15 * 24)

        fun sun(time: Double): Pair<Double, Double> {
            val dd = jDate + time - 2451545.0
            val g = fixAngle(357.529 + 0.98560028 * dd)
            val q = fixAngle(280.459 + 0.98564736 * dd)
            val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
            val e = 23.439 - 0.00000036 * dd
            val ra = fixHour(datan2(dcos(e) * dsin(l), dcos(l)) / 15)
            val eqt = q / 15 - ra
            val decl = dasin(dsin(e) * dsin(l))
            return decl to eqt
        }

        fun midDay(time: Double) = fixHour(12 - sun(time).second)

        fun angleTime(angle: Double, time: Double, beforeNoon: Boolean): Double {
            val decl = sun(time).first
            val noon = midDay(time)
            val t = dacos((-dsin(angle) - dsin(decl) * dsin(latitude)) /
                (dcos(decl) * dcos(latitude))) / 15
            return noon + if (beforeNoon) -t else t
        }

        fun asr(factor: Double, time: Double): Double {
            val decl = sun(time).first
            val angle = -darccot(factor + dtan(abs(latitude - decl)))
            return angleTime(angle, time, false)
        }

        val hours = listOf(
            angleTime(16.0, 5 / 24.0, true),      // Fajr
            angleTime(0.833, 6 / 24.0, true),     // Sunrise
            midDay(12 / 24.0),                    // Dhuhr
            asr(1.0, 13 / 24.0),                  // Asr
            angleTime(0.833, 18 / 24.0, false),   // Sunset
            angleTime(4.0, 18 / 24.0, false),     // Maghrib
            angleTime(14.0, 18 / 24.0, false),    // Isha
        )

        val midnight = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val zone = TimeZone.getDefault().getOffset(midnight.timeInMillis + 12 * 3_600_000L) / 3_600_000.0
        // Round to the nearest minute (time formats would otherwise drop the seconds),
        // which matches ShiaLink's published times.
        return hours.map { h ->
            val local = h + zone - longitude / 15
            midnight.timeInMillis + Math.round(local * 60.0) * 60_000L
        }
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun rad(d: Double) = d * Math.PI / 180
    private fun deg(r: Double) = r * 180 / Math.PI
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun dasin(x: Double) = deg(asin(x))
    private fun dacos(x: Double) = deg(acos(x.coerceIn(-1.0, 1.0)))
    private fun datan2(y: Double, x: Double) = deg(atan2(y, x))
    private fun darccot(x: Double) = deg(atan(1 / x))
    private fun fixAngle(a: Double) = (a % 360 + 360) % 360
    private fun fixHour(h: Double) = (h % 24 + 24) % 24
}
