package dev.starlight.charge

import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * Live wallpaper that loops a video, muted, playing only while visible.
 * The video is `res/raw/wallpaper_loop.mp4` from the local-only source folder `app/src/local/res`,
 * which stays out of git; without it the wallpaper is plain black.
 */
class VideoWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine() {
        private var player: MediaPlayer? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            val video = videoId(this@VideoWallpaper)
            if (video == 0) {
                holder.lockCanvas()?.let { it.drawColor(Color.BLACK); holder.unlockCanvasAndPost(it) }
                return
            }
            player = MediaPlayer().apply {
                setDataSource(this@VideoWallpaper, Uri.parse("android.resource://$packageName/$video"))
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

        override fun onSurfaceDestroyed(holder: SurfaceHolder) = release()

        override fun onDestroy() {
            release()
            super.onDestroy()
        }

        private fun release() {
            player?.release()
            player = null
        }
    }

    companion object {
        fun videoId(context: Context): Int =
            context.resources.getIdentifier("wallpaper_loop", "raw", context.packageName)
    }
}
