package dev.starlight.charge

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live wallpaper: the Milky Way drifting and breathing very slowly, with twinkling stars,
 * an occasional shooting star, and a gentle parallax as you swipe between home pages.
 * Draws at 30 fps only while visible.
 */
class MilkyWayWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = SkyEngine()

    private inner class SkyEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val frame = Runnable { drawFrame() }
        private val sky = BitmapFactory.decodeResource(resources, R.drawable.milkyway)
        private val skyPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val matrix = Matrix()
        private val startedAt = SystemClock.uptimeMillis()
        private val random = Random(7)
        private val density = resources.displayMetrics.density

        // x, y (0..1), radius (dp), phase, speed
        private val stars = List(140) {
            floatArrayOf(random.nextFloat(), random.nextFloat(), 0.4f + random.nextFloat() * 1.2f,
                random.nextFloat() * 6.28f, 0.4f + random.nextFloat() * 1.8f)
        }

        private var visible = false
        private var width = 0
        private var height = 0
        private var pageOffset = 0.5f
        private var easedOffset = 0.5f

        // Shooting star: start time (s), start x/y (px), direction x/y (px over its life).
        private var shotAt = 5f
        private var shotStart = -1f
        private val shot = FloatArray(4)

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) drawFrame() else handler.removeCallbacks(frame)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this.width = width
            this.height = height
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float,
                                      xPixels: Int, yPixels: Int) {
            pageOffset = xOffset
        }

        private fun drawFrame() {
            handler.removeCallbacks(frame)
            if (!visible || width == 0) return
            val canvas = try {
                surfaceHolder.lockHardwareCanvas()
            } catch (_: Exception) {
                null
            } ?: return
            try {
                render(canvas)
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
            handler.postDelayed(frame, 33)
        }

        private fun render(canvas: Canvas) {
            val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
            val w = width.toFloat()
            val h = height.toFloat()
            easedOffset += (pageOffset - easedOffset) * 0.12f

            // Cover the screen with room to drift, breathing a little in scale.
            val scale = max(w / sky.width, h / sky.height) * 1.14f * (1f + 0.02f * sin(t / 23f))
            val spareX = sky.width * scale - w
            val spareY = sky.height * scale - h
            val fx = (0.5f + 0.2f * sin(t / 41f) + (easedOffset - 0.5f) * 0.35f).coerceIn(0f, 1f)
            val fy = (0.5f + 0.2f * cos(t / 53f)).coerceIn(0f, 1f)
            matrix.setScale(scale, scale)
            matrix.postTranslate(-spareX * fx, -spareY * fy)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(sky, matrix, skyPaint)

            for (s in stars) {
                val a = 0.5f + 0.5f * sin(s[3] + t * s[4])
                dot.alpha = (a * a * a * 230).toInt()
                canvas.drawCircle(s[0] * w, s[1] * h, s[2] * density, dot)
            }

            drawShootingStar(canvas, t, w, h)
        }

        private fun drawShootingStar(canvas: Canvas, t: Float, w: Float, h: Float) {
            if (shotStart < 0 && t >= shotAt) {
                shotStart = t
                shot[0] = w * (0.25f + random.nextFloat() * 0.7f)
                shot[1] = h * (0.05f + random.nextFloat() * 0.4f)
                shot[2] = -w * (0.35f + random.nextFloat() * 0.25f)
                shot[3] = h * (0.12f + random.nextFloat() * 0.12f)
            }
            if (shotStart < 0) return
            val p = (t - shotStart) / 0.9f
            if (p >= 1f) {
                shotStart = -1f
                shotAt = t + 6f + random.nextFloat() * 10f
                return
            }
            val headX = shot[0] + shot[2] * p
            val headY = shot[1] + shot[3] * p
            val tail = 0.22f
            val tailX = headX - shot[2] * tail
            val tailY = headY - shot[3] * tail
            val fade = sin(p * Math.PI).toFloat()
            trail.shader = LinearGradient(tailX, tailY, headX, headY,
                Color.TRANSPARENT, Color.argb((230 * fade).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
            trail.strokeWidth = 2.2f * density
            canvas.drawLine(tailX, tailY, headX, headY, trail)
            dot.alpha = (255 * fade).toInt()
            canvas.drawCircle(headX, headY, 2.2f * density, dot)
        }
    }
}
