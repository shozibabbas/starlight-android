package dev.starlight.charge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Calendar
import java.util.Date

/**
 * Full-screen charging screens, shown over the lock screen: the plug-in animation, the
 * fully charged screen, the dimmed stand-by clock while charging, and at night the
 * sleeping screen with an optional light ring.
 */
class ChargeScreenActivity : Activity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PREVIEW = "preview"
        private const val INTRO_MS = 6_500L
        private const val FULL_MS = 45_000L
        private const val CONTROLS_MS = 6_000L
        private const val IDLE_ALPHA = 0.35f
        private const val STANDBY_BRIGHTNESS = 0.08f
        private const val NIGHT_BRIGHTNESS = 0.01f

        fun intent(context: Context, mode: Mode, preview: Boolean = false): Intent =
            Intent(context, ChargeScreenActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_PREVIEW, preview)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private lateinit var monitor: ChargeMonitor
    private lateinit var view: StarlightView
    private lateinit var controls: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var lamp: TextView
    private lateinit var slider: SeekBar
    private var preview = false
    private val handler = Handler(Looper.getMainLooper())
    private val next = Runnable { afterTimeout() }
    // Idle controls stay on screen, dimmed, so a tap always lands on them.
    private val hideControls = Runnable { controls.animate().alpha(IDLE_ALPHA).setDuration(400).start() }
    private val sample = object : Runnable {
        override fun run() {
            monitor.update()
            handler.postDelayed(this, 1_000)
        }
    }
    // Once a minute: refresh the night lines and move between night and stand-by.
    private val minute = object : Runnable {
        override fun run() {
            checkNight()
            handler.postDelayed(this, 60_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED && !preview) fadeOut()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        monitor = ChargeMonitor(this, demo = preview).also { it.update() }
        view = StarlightView(this, monitor)
        view.setOnClickListener {
            if (view.mode == Mode.NIGHT) toggleControls() else fadeOut()
        }
        controls = buildControls()
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        root.alpha = 0f
        setContentView(root)
        hideSystemBars()
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Intent.ACTION_POWER_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        NotificationWatcher.onChange = { view.notes = NotificationWatcher.items }
        view.notes = NotificationWatcher.items
        handler.post(sample)
        handler.post(minute)
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        NotificationWatcher.onChange = null
        handler.removeCallbacks(sample)
        handler.removeCallbacks(minute)
        super.onStop()
    }

    private fun show(intent: Intent) {
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        val mode = Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Mode.INTRO.name)
        switchTo(mode)
        handler.removeCallbacks(next)
        when (mode) {
            Mode.INTRO -> handler.postDelayed(next, INTRO_MS)
            Mode.FULL -> handler.postDelayed(next, FULL_MS)
            Mode.STANDBY, Mode.NIGHT -> Unit
        }
    }

    /** After the intro or full screen: stay as night/stand-by while charging, otherwise close. */
    private fun afterTimeout() {
        monitor.update()
        if (Prefs.standby(this) && (monitor.plugged || preview)) {
            val target = if (Night.isNight(this)) Mode.NIGHT else Mode.STANDBY
            root.animate().alpha(0f).setDuration(400).withEndAction { switchTo(target) }.start()
        } else {
            fadeOut()
        }
    }

    private fun checkNight() {
        if (view.mode != Mode.NIGHT && view.mode != Mode.STANDBY) return
        val night = Night.isNight(this)
        if (night && view.mode == Mode.STANDBY && Prefs.standby(this)) switchTo(Mode.NIGHT)
        if (!night && view.mode == Mode.NIGHT && !preview) {
            if (monitor.plugged) switchTo(Mode.STANDBY) else fadeOut()
        }
        if (view.mode == Mode.NIGHT) view.nightLines = nightLines()
    }

    private fun switchTo(mode: Mode) {
        view.mode = mode
        if (mode == Mode.NIGHT) {
            view.nightLines = nightLines()
            view.ringLevel = if (Prefs.ringOn(this)) Prefs.ringLevel(this) else 0
            updateLampUi()
            showControlsBriefly()
        } else {
            view.ringLevel = 0
            controls.visibility = View.GONE
        }
        applyBrightness()
        root.animate().alpha(1f).setDuration(600).start()
    }

    private fun applyBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = when (view.mode) {
                Mode.STANDBY -> STANDBY_BRIGHTNESS
                Mode.NIGHT -> if (view.ringLevel > 0) 0.05f + 0.6f * view.ringLevel / 100f else NIGHT_BRIGHTNESS
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    private fun nightLines(): List<String> {
        val time = DateFormat.getTimeFormat(this)
        val lines = mutableListOf<String>()
        Night.nextAlarm(this)?.let { alarm ->
            if (alarm - System.currentTimeMillis() < 16 * 3_600_000L) lines += "⏰  Alarm ${time.format(Date(alarm))}"
        }
        nextPrayer()?.let { (name, at) -> lines += "$name ${time.format(Date(at))}" }
        return lines
    }

    private fun nextPrayer(): Pair<String, Long>? {
        val prefs = getSharedPreferences("starlight", Context.MODE_PRIVATE)
        if (!prefs.contains("lat")) return null
        val lat = prefs.getFloat("lat", 0f).toDouble()
        val lng = prefs.getFloat("lng", 0f).toDouble()
        val now = System.currentTimeMillis()
        for (dayOffset in 0..1) {
            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, dayOffset) }
            val times = PrayerTimes.forDay(day, lat, lng)
            for (i in PrayerTimes.PRAYERS) if (times[i] > now) return PrayerTimes.NAMES[i] to times[i]
        }
        return null
    }

    // ---- night controls ---------------------------------------------------------------

    private fun buildControls(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(28).toFloat() }

        lamp = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener {
                android.util.Log.d("StarlightNight", "light ring tapped")
                Prefs.setRingOn(this@ChargeScreenActivity, !Prefs.ringOn(this@ChargeScreenActivity))
                view.ringLevel = if (Prefs.ringOn(this@ChargeScreenActivity)) Prefs.ringLevel(this@ChargeScreenActivity) else 0
                updateLampUi(); applyBrightness(); showControlsBriefly()
            }
        }
        val close = TextView(this).apply {
            text = "Close"
            textSize = 15f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER
            background = pill(0x1FFFFFFF)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { fadeOut() }
        }
        slider = SeekBar(this).apply {
            max = 100
            progress = Prefs.ringLevel(this@ChargeScreenActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val level = value.coerceAtLeast(5)
                    Prefs.setRingLevel(this@ChargeScreenActivity, level)
                    view.ringLevel = level
                    applyBrightness()
                    showControlsBriefly()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = handler.removeCallbacks(hideControls)
                override fun onStopTrackingTouch(bar: SeekBar) = showControlsBriefly()
            })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(lamp, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
            addView(close, LinearLayout.LayoutParams(-2, -2))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(12), dp(32), dp(48))
            visibility = View.GONE
            addView(slider, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            addView(buttons, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun updateLampUi() {
        val on = Prefs.ringOn(this)
        lamp.text = if (on) "◯  Light ring on" else "◯  Light ring"
        lamp.setTextColor(if (on) Color.parseColor("#FFE2B8") else 0xB3FFFFFF.toInt())
        lamp.background = GradientDrawable().apply {
            setColor(if (on) 0x33FFE2B8 else 0x1FFFFFFF)
            cornerRadius = 28 * resources.displayMetrics.density
        }
        slider.progress = Prefs.ringLevel(this)
        slider.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun toggleControls() = showControlsBriefly()

    private fun showControlsBriefly() {
        handler.removeCallbacks(hideControls)
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(250).start()
        handler.postDelayed(hideControls, CONTROLS_MS)
    }

    private fun fadeOut() {
        if (isFinishing) return
        handler.removeCallbacks(next)
        root.animate().alpha(0f).setDuration(450).withEndAction { finishAndRemoveTask() }.start()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
