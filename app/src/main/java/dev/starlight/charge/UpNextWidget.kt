package dev.starlight.charge

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Date
import kotlin.math.max

/**
 * "Up next": the next calendar event in a quiet glass card — title, time, how soon,
 * and how many more are left today. Taps open the event (or Google Calendar).
 */
class UpNextWidget : AppWidgetProvider() {

    private data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int)

    companion object {
        private const val ACTION_TICK = "dev.starlight.charge.UPNEXT_TICK"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UpNextWidget::class.java))
            if (ids.isNotEmpty()) UpNextWidget().render(context, manager, ids)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_PROVIDER_CHANGED, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh(context)
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_upnext)
        val now = System.currentTimeMillis()
        val time = DateFormat.getTimeFormat(context)

        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            views.setTextViewText(R.id.label, "UP NEXT")
            views.setTextViewText(R.id.title, "Calendar access needed")
            views.setTextViewText(R.id.when_, "Open Starlight to allow it")
            views.setTextViewText(R.id.soon, "")
            views.setTextViewText(R.id.more, "")
            ids.forEach { manager.updateAppWidget(it, views) }
            return
        }

        val endOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }.timeInMillis
        val events = query(context, now, now + 3 * DateUtils.DAY_IN_MILLIS)
        // Timed events still running or ahead; all-day events only if nothing else today.
        val timed = events.filter { !it.allDay && it.end > now }
        val next = timed.firstOrNull() ?: events.firstOrNull { it.allDay }

        val openIntent: Intent
        if (next == null) {
            views.setTextViewText(R.id.label, "UP NEXT")
            views.setTextViewText(R.id.title, "Nothing scheduled")
            views.setTextViewText(R.id.when_, "Your next three days are clear")
            views.setTextViewText(R.id.soon, "")
            views.setTextViewText(R.id.more, "")
            views.setInt(R.id.accent, "setColorFilter", Color.parseColor("#7DF9FF"))
            openIntent = calendarApp(context)
            schedule(context, now + 30 * DateUtils.MINUTE_IN_MILLIS)
        } else {
            val ongoing = !next.allDay && next.begin <= now
            views.setTextViewText(R.id.label, if (ongoing) "NOW" else "UP NEXT")
            views.setTextViewText(R.id.title, next.title)
            views.setTextViewText(R.id.when_, when {
                next.allDay -> "All day · " + DateUtils.formatDateTime(context, next.begin,
                    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_UTC)
                DateUtils.isToday(next.begin) -> "${time.format(Date(next.begin))} – ${time.format(Date(next.end))}"
                else -> DateUtils.formatDateTime(context, next.begin,
                    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY) + " · " + time.format(Date(next.begin))
            })
            views.setTextViewText(R.id.soon, when {
                next.allDay -> ""
                ongoing -> "ends in " + span(next.end - now)
                else -> "in " + span(next.begin - now)
            })
            val laterToday = timed.count { it !== next && it.begin in now..endOfToday }
            views.setTextViewText(R.id.more, if (laterToday > 0) "+$laterToday more today" else "")
            views.setInt(R.id.accent, "setColorFilter", if (next.color != 0) next.color else Color.parseColor("#7DF9FF"))
            openIntent = Intent(Intent.ACTION_VIEW,
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, next.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, next.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, next.end)
            // Re-render when it starts or ends, and at least every 15 minutes for the "in …" text.
            val change = if (ongoing) next.end else next.begin
            schedule(context, minOf(change + 1000, now + 15 * DateUtils.MINUTE_IN_MILLIS))
        }

        views.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 20,
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun query(context: Context, from: Long, to: Long): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - DateUtils.DAY_IN_MILLIS)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
        )
        // Events without attendees have no status at all; only skip ones you declined.
        val status = CalendarContract.Instances.SELF_ATTENDEE_STATUS
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "($status IS NULL OR $status != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})"
        val out = mutableListOf<Event>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) == 1
                    val begin = c.getLong(2)
                    val end = c.getLong(3)
                    // All-day instances are stored in UTC; keep only those covering today onwards.
                    if (allDay && end <= utcMidnightToday()) continue
                    out += Event(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: "(No title)",
                        begin, end, allDay, c.getInt(5))
                }
            }
        }
        return out
    }

    private fun utcMidnightToday(): Long {
        val local = Calendar.getInstance()
        return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun span(ms: Long): String {
        val minutes = max(1, (ms / 60_000).toInt())
        return when {
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> "${minutes / 60} h ${minutes % 60} min".replace(" 0 min", "")
            else -> "${minutes / (24 * 60)} d"
        }
    }

    private fun calendarApp(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage("com.google.android.calendar")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time"))

    private fun schedule(context: Context, at: Long) {
        val tick = PendingIntent.getBroadcast(context, 21,
            Intent(context, UpNextWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, at, tick)
    }
}
