package dev.starlight.charge

import android.app.AlarmManager
import android.content.Context
import java.util.Calendar

/**
 * Night hours: 21:00 until 07:00, ending earlier at the first alarm of the morning.
 *
 * The first alarm inside a night is remembered, so a second alarm (snooze, or 3:45 then
 * 4:45) doesn't bring the night back, and alarms after 07:00 never extend it.
 */
object Night {
    private const val START_HOUR = 21
    private const val END_HOUR = 7
    private const val PREFS = "starlight"
    private const val KEY_WINDOW = "night_window_start"
    private const val KEY_FIRST_ALARM = "night_first_alarm"

    fun isNight(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val (start, latestEnd) = window(now) ?: return false
        val firstAlarm = firstAlarm(context, start, latestEnd)
        val end = if (firstAlarm != null) minOf(firstAlarm, latestEnd) else latestEnd
        return now in start until end
    }

    /** When tonight's night ends: its first alarm, otherwise 07:00. */
    fun morningEnd(context: Context, now: Long = System.currentTimeMillis()): Long {
        val (start, latestEnd) = window(now) ?: return now
        val firstAlarm = firstAlarm(context, start, latestEnd)
        return if (firstAlarm != null) minOf(firstAlarm, latestEnd) else latestEnd
    }

    /**
     * The next alarm set in a clock app. Other apps (prayer, reminder, calendar apps) can
     * also register alarm clocks; those are ignored so they never end or extend the night.
     */
    fun nextAlarm(context: Context): Long? {
        val info = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val owner = info.showIntent?.creatorPackage ?: return null
        return info.triggerTime.takeIf { isClockApp(owner) }
    }

    private fun isClockApp(pkg: String) =
        pkg in CLOCK_APPS || pkg.endsWith(".deskclock") || pkg.contains(".clock")

    private val CLOCK_APPS = setOf(
        "com.android.deskclock",        // Xiaomi Clock
        "com.google.android.deskclock", // Google Clock
        "com.sec.android.app.clockpackage",
    )

    /** The 21:00–07:00 window containing [now], or null during the day. */
    private fun window(now: Long): Pair<Long, Long>? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour in END_HOUR until START_HOUR) return null
        val start = (cal.clone() as Calendar).apply {
            if (hour < END_HOUR) add(Calendar.DAY_OF_MONTH, -1)
            set(Calendar.HOUR_OF_DAY, START_HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, END_HOUR)
        }
        return start.timeInMillis to end.timeInMillis
    }

    /** The earliest alarm seen inside this night window, remembered once seen. */
    private fun firstAlarm(context: Context, start: Long, end: Long): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var first = if (prefs.getLong(KEY_WINDOW, 0L) == start) prefs.getLong(KEY_FIRST_ALARM, 0L).takeIf { it > 0 } else null
        val next = nextAlarm(context)
        if (next != null && next in start until end && (first == null || next < first)) first = next
        prefs.edit().putLong(KEY_WINDOW, start).putLong(KEY_FIRST_ALARM, first ?: 0L).apply()
        return first
    }
}
