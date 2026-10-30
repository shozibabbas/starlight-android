package dev.starlight.charge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Unlock reveal: the Milky Way covers the screen, then opens as a glowing iris from the
 * fingerprint sensor while star streaks rush outward, uncovering the home screen beneath.
 * Never takes touches; removes itself when done.
 */
class UnlockRevealView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val sky = BitmapFactory.decodeResource(resources, R.drawable.starfield)
    private val skyMatrix = Matrix()
    private val skyPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    private val random = Random(11)
    // angle, start offset (0..1), speed, width (dp), cyan(0)/violet(1)
    private val streaks = List(80) {
        floatArrayOf(random.nextFloat() * 6.2832f, random.nextFloat(), 0.6f + random.nextFloat() * 0.9f,
            0.8f + random.nextFloat() * 1.6f, if (random.nextBoolean()) 1f else 0f)
    }
    // angle, speed, radius (dp), delay (0..0.4)
    private val sparks = List(46) {
        floatArrayOf(random.nextFloat() * 6.2832f, 0.4f + random.nextFloat() * 0.8f,
            1f + random.nextFloat() * 2.2f, random.nextFloat() * 0.4f)
    }

    private var progress = 0f
    private var cx = 0f
    private var cy = 0f
    private var maxR = 1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = max(w / sky.width.toFloat(), h / sky.height.toFloat())
        skyMatrix.setScale(scale, scale)
        skyMatrix.postTranslate((w - sky.width * scale) / 2f, (h - sky.height * scale) / 2f)
        cx = w / 2f
        cy = h * ORIGIN_Y
        maxR = max(hypot(cx, cy), hypot(cx, h - cy)) * 1.08f
    }

    fun play(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = progress
        val ease = 1 - (1 - t).pow(3)                 // ease-out cubic
        val r = maxR * ease
        val fade = if (t > 0.78f) 1 - (t - 0.78f) / 0.22f else 1f
        val w = width.toFloat()
        val h = height.toFloat()

        // The cover: starfield with a hole that grows from the sensor.
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawColor(Color.BLACK)
        skyPaint.alpha = 210
        canvas.drawBitmap(sky, skyMatrix, skyPaint)
        if (r > 0) canvas.drawCircle(cx, cy, r, clear)
        canvas.restoreToCount(layer)

        // Soft light pouring out of the opening.
        if (r > 4 * density) {
            glow.shader = RadialGradient(cx, cy, r * 1.25f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((70 * fade).toInt(), 125, 249, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.72f, 0.8f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.25f, glow)
        }

        // Glowing iris edge.
        for ((width, alpha) in listOf(18f to 0.12f, 8f to 0.3f, 2.5f to 1f)) {
            edge.strokeWidth = width * density
            edge.color = Color.argb((255 * alpha * fade).toInt(), 185, 225, 255)
            canvas.drawCircle(cx, cy, r, edge)
        }

        // Warp streaks racing outward just beyond the edge.
        for (s in streaks) {
            val local = ((t * s[2] + s[1] * 0.35f)).coerceIn(0f, 1f)
            val inner = r + (local * 0.5f) * maxR * 0.35f
            val len = (18 + 120 * ease) * density * s[2]
            val a = s[0]
            val x1 = cx + cos(a) * inner
            val y1 = cy + sin(a) * inner
            val x2 = cx + cos(a) * (inner + len)
            val y2 = cy + sin(a) * (inner + len)
            streak.strokeWidth = s[3] * density
            streak.color = if (s[4] > 0.5f) Color.rgb(185, 140, 255) else Color.rgb(125, 249, 255)
            streak.alpha = (200 * fade * (1 - local * 0.6f)).toInt()
            canvas.drawLine(x1, y1, x2, y2, streak)
        }

        // Sparks thrown off the edge.
        for (s in sparks) {
            val local = ((t - s[3]) / (1 - s[3])).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val d = r + local * s[1] * 90 * density
            dot.alpha = (230 * fade * (1 - local)).toInt()
            canvas.drawCircle(cx + cos(s[0]) * d, cy + sin(s[0]) * d, s[2] * density * (1 - local * 0.5f), dot)
        }

        // A brief flash at the sensor as it begins.
        if (t < 0.18f) {
            val k = 1 - t / 0.18f
            glow.shader = RadialGradient(cx, cy, 70 * density,
                Color.argb((200 * k).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, 70 * density, glow)
        }
    }

    private companion object {
        const val DURATION_MS = 900L
        const val ORIGIN_Y = 0.835f // in-display fingerprint sensor on the Redmi Note 13 Pro+
    }
}
