package dev.starlight.charge

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import android.location.LocationManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Date

/**
 * Home-screen prayer widget: next prayer with a live countdown, the five daily times
 * (next one highlighted) and the Hijri date. Taps open ShiaLink when installed.
 * Re-renders itself just after each prayer time.
 */
class PrayerWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TICK = "dev.starlight.charge.PRAYER_TICK"
        private const val SHIALINK = "iLog.SL"
        private val NAME_IDS = intArrayOf(R.id.p0_name, R.id.p1_name, R.id.p2_name, R.id.p3_name, R.id.p4_name)
        private val TIME_IDS = intArrayOf(R.id.p0_time, R.id.p1_time, R.id.p2_time, R.id.p3_time, R.id.p4_time)
        private val GOLD = Color.parseColor("#FFC86B")
        private const val DIM = 0xB3FFFFFF.toInt()

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_prayer)
            val place = location(context)
            if (place == null) {
                views.setTextViewText(R.id.next_name, "Location needed")
                views.setTextViewText(R.id.next_time, "")
                views.setTextViewText(R.id.hijri, "Open Starlight once to set it")
                ids.forEach { manager.updateAppWidget(it, views) }
                return
            }

            val now = System.currentTimeMillis()
            val today = Calendar.getInstance()
            val times = PrayerTimes.forDay(today, place.first, place.second)
            val prayers = PrayerTimes.PRAYERS.map { times[it] }

            // Next prayer: the first still ahead today, else tomorrow's Fajr.
            var nextIndex = prayers.indexOfFirst { it > now }
            val nextAt: Long
            if (nextIndex >= 0) {
                nextAt = prayers[nextIndex]
            } else {
                val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
                nextAt = PrayerTimes.forDay(tomorrow, place.first, place.second)[0]
                nextIndex = 0
            }
            val time = DateFormat.getTimeFormat(context)

            views.setTextViewText(R.id.hijri, hijri(today.time))
            views.setTextViewText(R.id.next_name, PrayerTimes.NAMES[PrayerTimes.PRAYERS[nextIndex]])
            views.setTextViewText(R.id.next_time, time.format(Date(nextAt)))
            views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + (nextAt - now), "in %s", true)
            views.setChronometerCountDown(R.id.countdown, true)

            for (i in 0 until 5) {
                views.setTextViewText(NAME_IDS[i], PrayerTimes.NAMES[PrayerTimes.PRAYERS[i]])
                views.setTextViewText(TIME_IDS[i], time.format(Date(prayers[i])).replace(" ", " "))
                val color = if (i == nextIndex) GOLD else DIM
                views.setTextColor(NAME_IDS[i], color)
                views.setTextColor(TIME_IDS[i], if (i == nextIndex) GOLD else Color.WHITE)
            }

            val open = context.packageManager.getLaunchIntentForPackage(SHIALINK)
                ?: Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 10,
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))

            ids.forEach { manager.updateAppWidget(it, views) }
            schedule(context, nextAt + 30_000)
        }

        private fun schedule(context: Context, at: Long) {
            val tick = PendingIntent.getBroadcast(context, 11,
                Intent(context, PrayerWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC, at, tick)
        }

        // Umm al-Qura runs a day ahead of Pakistan's moon sighting; this matches ShiaLink.
        private const val HIJRI_OFFSET_DAYS = -1

        private fun hijri(date: Date): String {
            val weekday = SimpleDateFormat("EEEE", ULocale.ENGLISH).format(date)
            val shifted = Date(date.time + HIJRI_OFFSET_DAYS * 86_400_000L)
            return weekday + " · " +
                SimpleDateFormat("d MMMM y", ULocale("en@calendar=islamic-umalqura")).format(shifted)
        }

        /** Latest known location, cached so the widget keeps working without a fresh fix. */
        private fun location(context: Context): Pair<Double, Double>? {
            val prefs = context.getSharedPreferences("starlight", Context.MODE_PRIVATE)
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val lm = context.getSystemService(LocationManager::class.java)
                val best = lm.getProviders(true)
                    .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
                if (best != null) {
                    prefs.edit().putFloat("lat", best.latitude.toFloat())
                        .putFloat("lng", best.longitude.toFloat()).apply()
                }
            }
            if (!prefs.contains("lat")) return null
            return prefs.getFloat("lat", 0f).toDouble() to prefs.getFloat("lng", 0f).toDouble()
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        render(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED -> refresh(context)
        }
    }
}
