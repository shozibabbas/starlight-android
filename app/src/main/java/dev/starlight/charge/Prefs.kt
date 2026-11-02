package dev.starlight.charge

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val STANDBY = "standby"

    private fun prefs(context: Context) = context.getSharedPreferences("starlight", Context.MODE_PRIVATE)

    fun listen(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    fun standby(context: Context) = prefs(context).getBoolean(STANDBY, true)

    fun setStandby(context: Context, on: Boolean) = prefs(context).edit().putBoolean(STANDBY, on).apply()

    private const val RING_ON = "ring_on"
    private const val RING_LEVEL = "ring_level"

    fun ringOn(context: Context) = prefs(context).getBoolean(RING_ON, false)
    fun setRingOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(RING_ON, on).apply()

    /** Light ring intensity, 0..100. */
    fun ringLevel(context: Context) = prefs(context).getInt(RING_LEVEL, 35)
    fun setRingLevel(context: Context, level: Int) = prefs(context).edit().putInt(RING_LEVEL, level).apply()

    const val VIDEO_LOOP = "video_loop"

    /** File name of the video loop the Video loops wallpaper plays. */
    fun videoLoop(context: Context): String? = prefs(context).getString(VIDEO_LOOP, null)
    fun setVideoLoop(context: Context, loop: String) = prefs(context).edit().putString(VIDEO_LOOP, loop).apply()
}
