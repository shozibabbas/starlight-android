package dev.starlight.charge

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Stays in the background (as a minimal foreground service, so HyperOS doesn't kill it)
 * and reacts to the charger: the charging screen on plug-in, and the chime at 100%.
 */
class ChargeService : Service() {

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ChargeService::class.java))
        }
    }

    // True once this charging session has been seen below 100%, so plugging in an
    // already-full phone doesn't chime.
    private var armed = false

    // Keeps the night notification in step with night hours, checked every minute.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wasNight: Boolean? = null
    private val nightCheck = object : Runnable {
        override fun run() {
            val night = Night.isNight(this@ChargeService)
            if (night != wasNight) {
                wasNight = night
                Notifier.updateNight(this@ChargeService, night)
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> Notifier.showScreen(context, full = false)
                Intent.ACTION_POWER_DISCONNECTED -> {
                    armed = false
                    Notifier.clear(context)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val battery = Battery.read(context, intent)
                    when {
                        !battery.plugged -> armed = false
                        !battery.full -> armed = true
                        armed -> {
                            armed = false
                            Notifier.alertFull(context)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        NotificationWatcher.rebind(this)
        startForeground(Notifier.ID_STATUS, Notifier.status(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        handler.post(nightCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(nightCheck)
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
