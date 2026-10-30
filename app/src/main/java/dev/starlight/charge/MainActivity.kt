package dev.starlight.charge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Setup screen: starts the watcher, checks the permissions it needs, and offers previews. */
class MainActivity : Activity() {

    private lateinit var checks: LinearLayout
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ChargeService.start(this)
        setContentView(layout())
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refreshChecks()
    }

    private fun layout(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(72), dp(24), dp(48))
        }
        content.addView(label("Starlight", 34f, Color.WHITE, thin = true))
        content.addView(label("A charging animation, and a chime when it's time to unplug.", 15f, 0xB3FFFFFF.toInt()).apply {
            setPadding(0, dp(4), 0, dp(28))
        })

        content.addView(label("Setup", 13f, 0x99FFFFFF.toInt()).apply { setPadding(0, 0, 0, dp(8)) })
        checks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(checks)

        content.addView(label("Navigation", 13f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(28), 0, dp(8)) })
        content.addView(toggle("Always use gestures (undo HyperOS switching to buttons)", Prefs.keepGestures(this)) {
            Prefs.setKeepGestures(this, it)
        })
        content.addView(toggle("Milky Way reveal when unlocking to the home screen", Prefs.unlockReveal(this)) {
            Prefs.setUnlockReveal(this, it)
        })

        content.addView(label("Stand-by", 13f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(28), 0, dp(8)) })
        content.addView(toggle("Stay on as a dimmed clock while charging", Prefs.standby(this)) {
            Prefs.setStandby(this, it)
        })

        content.addView(label("Wallpaper", 13f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(28), 0, dp(8)) })
        content.addView(button("Set Milky Way live wallpaper") {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, MilkyWayWallpaper::class.java)))
        })

        content.addView(label("Preview", 13f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(28), 0, dp(8)) })
        content.addView(button("Unlock reveal") {
            val service = GestureService.instance
            if (service == null) {
                android.widget.Toast.makeText(this, "Turn on Swipe gestures first", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                service.showReveal()
            }
        })
        content.addView(button("Charging animation") {
            startActivity(ChargeScreenActivity.intent(this, Mode.INTRO, preview = true))
        })
        content.addView(button("Stand-by clock") {
            startActivity(ChargeScreenActivity.intent(this, Mode.STANDBY, preview = true))
        })
        content.addView(button("Night screen") {
            startActivity(ChargeScreenActivity.intent(this, Mode.NIGHT, preview = true))
        })
        content.addView(button("Fully charged screen") {
            startActivity(ChargeScreenActivity.intent(this, Mode.FULL, preview = true))
        })
        content.addView(button("Fully charged alert with sound") { Notifier.alertFull(this) })

        val background = ImageView(this).apply {
            setImageResource(R.drawable.starfield)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.55f
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(background, FrameLayout.LayoutParams(-1, -1))
            addView(ScrollView(this@MainActivity).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun refreshChecks() {
        if (!::checks.isInitialized) return
        checks.removeAllViews()
        val notifications = getSystemService(NotificationManager::class.java)
        val power = getSystemService(PowerManager::class.java)

        check("Notifications", notifications.areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        check("Show over lock screen and other apps", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            check("Full-screen alerts", notifications.canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        check("Swipe gestures", gesturesEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        check("Notification access (for stand-by)", NotificationWatcher.isEnabled(this)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        check("No battery restrictions", power.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        checks.addView(label("On Xiaomi phones, also turn on Autostart and \"Show on Lock screen\" in the app's settings.", 13f, 0x99FFFFFF.toInt()).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        checks.addView(button("Open app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
    }

    private fun gesturesEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, GestureService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun check(name: String, ok: Boolean, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0x1AFFFFFF)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            if (!ok) setOnClickListener { fix() }
        }
        row.addView(label(if (ok) "✓" else "○", 18f, if (ok) 0xFF7DF9FF.toInt() else 0x99FFFFFF.toInt()).apply {
            setPadding(0, 0, dp(14), 0)
        })
        row.addView(label(name, 16f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        if (!ok) row.addView(label("Turn on", 14f, 0xFFB98CFF.toInt()))
        checks.addView(row)
    }

    private fun toggle(name: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = name
        textSize = 16f
        setTextColor(Color.WHITE)
        isChecked = on
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(0x1AFFFFFF)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = rounded(0x267DF9FF)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, color: Int, thin: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (thin) "sans-serif-thin" else "sans-serif", Typeface.NORMAL)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun dp(value: Int) = (value * density).toInt()
}
