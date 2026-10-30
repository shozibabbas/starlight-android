package dev.starlight.charge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

object Notifier {
    private const val CH_STATUS = "status"
    private const val CH_CHARGING = "charging"
    private const val CH_FULL = "full"
    // Default importance (but soundless) so HyperOS shows it on the lock screen; the
    // original low-importance "night" channel was hidden there.
    private const val CH_NIGHT = "night_lockscreen"
    private const val ID_NIGHT = 4

    const val ID_STATUS = 1
    private const val ID_CHARGING = 2
    private const val ID_FULL = 3

    private val handler = Handler(Looper.getMainLooper())

    fun createChannels(context: Context) {
        val status = NotificationChannel(CH_STATUS, "Battery watcher", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps Starlight running so it can react when you plug in"
            setShowBadge(false)
        }
        val charging = NotificationChannel(CH_CHARGING, "Charging animation", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Opens the charging animation when you plug in"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val full = NotificationChannel(CH_FULL, "Fully charged", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Plays a chime when the battery reaches 100%"
            val sound = Uri.parse("android.resource://${context.packageName}/${R.raw.full_chime}")
            setSound(sound, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 120, 180)
            enableLights(true)
            lightColor = Color.parseColor("#FFC86B")
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val night = NotificationChannel(CH_NIGHT, "Night mode", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Stays on the lock screen at night; tap to open the sleeping screen"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("night")
            createNotificationChannels(listOf(status, charging, full, night))
        }
    }

    /** Ongoing "Night mode" notification while it is night; tapping opens the sleeping screen. */
    fun updateNight(context: Context, night: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!night) {
            manager.cancel(ID_NIGHT)
            return
        }
        val open = open(context, ChargeScreenActivity.intent(context, Mode.NIGHT), 3)
        val alarm = Night.nextAlarm(context)
            ?.takeIf { it - System.currentTimeMillis() < 16 * 3_600_000L }
            ?.let { "Alarm " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)) }
        val notification = Notification.Builder(context, CH_NIGHT)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setColor(Color.parseColor("#FFE7B3"))
            .setContentTitle("Night mode")
            .setContentText(listOfNotNull("Tap to open the sleeping screen", alarm).joinToString("  ·  "))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        manager.notify(ID_NIGHT, notification)
    }

    fun status(context: Context): Notification =
        Notification.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setContentTitle("Starlight is ready")
            .setContentText("Shows the charging animation and tells you when to unplug")
            .setContentIntent(open(context, Intent(context, MainActivity::class.java), 0))
            .setOngoing(true)
            .build()

    /** Opens the charging screen, directly if allowed, otherwise through a full-screen notification. */
    fun showScreen(context: Context, full: Boolean) {
        val night = !full && Prefs.standby(context) && Night.isNight(context)
        val mode = when {
            full -> Mode.FULL
            night -> Mode.NIGHT // no bright intro in the middle of the night
            else -> Mode.INTRO
        }
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(ChargeScreenActivity.intent(context, mode))
                return
            } catch (_: RuntimeException) {
                // Fall through to the notification route.
            }
        }
        if (full) return // alertFull() already carries a full-screen intent
        val screen = open(context, ChargeScreenActivity.intent(context, mode), 1)
        val notification = Notification.Builder(context, CH_CHARGING)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setContentTitle("Charging")
            .setContentText("${Battery.read(context).level}%")
            .setCategory(Notification.CATEGORY_STATUS)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(ID_CHARGING, notification)
        handler.postDelayed({ manager.cancel(ID_CHARGING) }, 10_000)
    }

    fun alertFull(context: Context) {
        val screen = open(context, ChargeScreenActivity.intent(context, Mode.FULL), 2)
        val notification = Notification.Builder(context, CH_FULL)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setColor(Color.parseColor("#FFC86B"))
            .setContentTitle("Fully charged")
            .setContentText("Battery is at 100%. Unplug your phone.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_FULL, notification)
        showScreen(context, full = true)
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ID_CHARGING)
        manager.cancel(ID_FULL)
    }

    private fun open(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
