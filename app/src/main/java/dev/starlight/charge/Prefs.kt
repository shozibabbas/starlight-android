package dev.starlight.charge

import android.content.Context

object Prefs {
    private const val STANDBY = "standby"

    private fun prefs(context: Context) = context.getSharedPreferences("starlight", Context.MODE_PRIVATE)

    fun standby(context: Context) = prefs(context).getBoolean(STANDBY, true)

    fun setStandby(context: Context, on: Boolean) = prefs(context).edit().putBoolean(STANDBY, on).apply()

    private const val RING_ON = "ring_on"
    private const val RING_LEVEL = "ring_level"

    fun ringOn(context: Context) = prefs(context).getBoolean(RING_ON, false)
    fun setRingOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(RING_ON, on).apply()

    /** Light ring intensity, 0..100. */
    fun ringLevel(context: Context) = prefs(context).getInt(RING_LEVEL, 35)
    fun setRingLevel(context: Context, level: Int) = prefs(context).edit().putInt(RING_LEVEL, level).apply()

    private const val UNLOCK_REVEAL = "unlock_reveal"

    /** Play the Milky Way reveal when unlocking to the home screen. */
    fun unlockReveal(context: Context) = prefs(context).getBoolean(UNLOCK_REVEAL, true)
    fun setUnlockReveal(context: Context, on: Boolean) = prefs(context).edit().putBoolean(UNLOCK_REVEAL, on).apply()

    private const val KEEP_GESTURES = "keep_gestures"

    /** Switch back to gesture navigation whenever HyperOS resets it to buttons. */
    fun keepGestures(context: Context) = prefs(context).getBoolean(KEEP_GESTURES, true)

    fun setKeepGestures(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEEP_GESTURES, on).apply()
}
