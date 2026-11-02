package dev.starlight.charge

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * Live wallpaper that loops a video, muted, playing only while visible.
 * The videos are `assets/loops/<Name - Subtitle>.mp4` from the local-only source folder
 * `app/src/local/assets`, which stays out of git; the file name is the name shown in Starlight.
 * Starlight's setup screen picks which loop plays, and the wallpaper switches straight away.
 */
class VideoWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private var player: MediaPlayer? = null
        private var holder: SurfaceHolder? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            Prefs.listen(this@VideoWallpaper, this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            this.holder = holder
            play()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            if (key == Prefs.VIDEO_LOOP && holder != null) play()
        }

        private fun play() {
            val holder = holder ?: return
            release()
            val loop = current(this@VideoWallpaper)
            if (loop == null) {
                holder.lockCanvas()?.let { it.drawColor(Color.BLACK); holder.unlockCanvasAndPost(it) }
                return
            }
            player = MediaPlayer().apply {
                assets.openFd("$DIR/$loop").use { setDataSource(it) }
                setSurface(holder.surface)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener {
                    it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    if (isVisible) it.start()
                }
                prepareAsync()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            val p = player ?: return
            try {
                if (visible) p.start() else p.pause()
            } catch (_: IllegalStateException) {
                // Still preparing; the prepared listener starts it if visible.
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            this.holder = null
            release()
        }

        override fun onDestroy() {
            Prefs.unlisten(this@VideoWallpaper, this)
            release()
            super.onDestroy()
        }

        private fun release() {
            player?.release()
            player = null
        }
    }

    companion object {
        private const val DIR = "loops"

        /** Loop file names, such as "Sun Breathing - Tanjiro.mp4". */
        fun loops(context: Context): List<String> =
            context.assets.list(DIR).orEmpty().filter { it.endsWith(".mp4") }.sorted()

        /** "Sun Breathing - Tanjiro.mp4" → "Sun Breathing · Tanjiro". */
        fun title(loop: String) = loop.removeSuffix(".mp4").replace(" - ", " · ")

        fun current(context: Context): String? {
            val all = loops(context)
            return Prefs.videoLoop(context)?.takeIf { it in all } ?: all.firstOrNull()
        }

        /** True when this wallpaper is the one on the home screen. */
        fun isActive(context: Context): Boolean =
            WallpaperManager.getInstance(context).wallpaperInfo?.component ==
                ComponentName(context, VideoWallpaper::class.java)

        /**
         * Copies a loop into Movies/Starlight (once) so the Gallery can set it on the HyperOS lock
         * screen, which only takes videos from there. Returns its content URI.
         */
        fun exportForLockScreen(context: Context, loop: String): Uri {
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val folder = "${Environment.DIRECTORY_MOVIES}/Starlight/"
            resolver.query(collection, arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                arrayOf(loop, folder), null)?.use {
                if (it.moveToFirst()) return Uri.withAppendedPath(collection, it.getLong(0).toString())
            }
            val uri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, loop)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, folder)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }) ?: error("MediaStore refused $loop")
            resolver.openOutputStream(uri)!!.use { out -> context.assets.open("$DIR/$loop").use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
    }
}
