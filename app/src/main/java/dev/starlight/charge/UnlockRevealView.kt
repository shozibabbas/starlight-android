package dev.starlight.charge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Unlock burst: a flash at the fingerprint sensor, then a ring of starlight sweeping out
 * across the home screen with warp streaks and sparks racing to the edges. It only adds
 * light (nothing covers the home screen), so it looks right however early or late the
 * unlock is detected. Draws nothing until play(); never takes touches.
 */
class UnlockRevealView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    private val random = Random(11)
    // angle, start offset (0..1), speed, width (dp), cyan(0)/violet(1)
    private val streaks = List(90) {
        floatArrayOf(random.nextFloat() * 6.2832f, random.nextFloat(), 0.6f + random.nextFloat() * 0.9f,
            0.8f + random.nextFloat() * 1.6f, if (random.nextBoolean()) 1f else 0f)
    }
    // angle, speed, radius (dp), delay (0..0.35)
    private val sparks = List(60) {
        floatArrayOf(random.nextFloat() * 6.2832f, 0.4f + random.nextFloat() * 0.9f,
            1f + random.nextFloat() * 2.4f, random.nextFloat() * 0.35f)
    }

    private var progress = 0f
    // Idle until play(): the window stays visible (so it keeps a surface ready to draw on)
    // but draws nothing.
    private var playing = false
    private var cx = 0f
    private var cy = 0f
    private var maxR = 1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h * ORIGIN_Y
        maxR = max(hypot(cx, cy), hypot(cx, h - cy)) * 1.05f
    }

    fun play(onEnd: () -> Unit) {
        playing = true
        progress = 0f
        invalidate()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    playing = false
                    invalidate()
                    onEnd()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!playing) return
        val t = progress
        val ease = 1 - (1 - t).pow(3)                  // ease-out cubic
        val r = maxR * ease
        val fade = if (t > 0.55f) 1 - (t - 0.55f) / 0.45f else 1f

        // Flash at the sensor, strongest in the first frames.
        if (t < 0.3f) {
            val k = 1 - t / 0.3f
            val fr = (60 + 120 * t) * density
            glow.shader = RadialGradient(cx, cy, fr,
                intArrayOf(Color.argb((230 * k).toInt(), 255, 255, 255), Color.argb((120 * k).toInt(), 125, 249, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, fr, glow)
        }

        // A thin band of light just inside the ring.
        if (r > 6 * density) {
            glow.shader = RadialGradient(cx, cy, r,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((60 * fade).toInt(), 140, 200, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.82f, 0.97f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, glow)
        }

        // The ring of starlight.
        for ((width, alpha) in listOf(20f to 0.10f, 9f to 0.28f, 2.5f to 1f)) {
            edge.strokeWidth = width * density
            edge.color = Color.argb((255 * alpha * fade).toInt(), 190, 228, 255)
            canvas.drawCircle(cx, cy, r, edge)
        }

        // Warp streaks racing outward just beyond the ring.
        for (s in streaks) {
            val local = (t * s[2] + s[1] * 0.35f).coerceIn(0f, 1f)
            val inner = r + local * maxR * 0.18f
            val len = (16 + 110 * ease) * density * s[2]
            val a = s[0]
            streak.strokeWidth = s[3] * density
            streak.color = if (s[4] > 0.5f) Color.rgb(185, 140, 255) else Color.rgb(125, 249, 255)
            streak.alpha = (210 * fade * (1 - local * 0.6f)).toInt()
            canvas.drawLine(cx + cos(a) * inner, cy + sin(a) * inner,
                cx + cos(a) * (inner + len), cy + sin(a) * (inner + len), streak)
        }

        // Sparks thrown off the ring.
        for (s in sparks) {
            val local = ((t - s[3]) / (1 - s[3])).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val d = r + local * s[1] * 100 * density
            dot.alpha = (235 * fade * (1 - local)).toInt()
            canvas.drawCircle(cx + cos(s[0]) * d, cy + sin(s[0]) * d, s[2] * density * (1 - local * 0.5f), dot)
        }
    }

    private companion object {
        const val DURATION_MS = 850L
        const val ORIGIN_Y = 0.835f // in-display fingerprint sensor on the Redmi Note 13 Pro+
    }
}
