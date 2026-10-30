package dev.starlight.charge

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class Mode { INTRO, STANDBY, FULL, NIGHT }

/**
 * Draws every Starlight screen over the starfield wallpaper:
 *  - INTRO: big ring filling to the battery level, with current streaming up from the
 *    charging port. Particle count, speed, colour and lightning all scale with power.
 *  - FULL: gold ring with expanding pulses.
 *  - STANDBY: dimmed clock, date, notifications and a small charging ring, drifting
 *    slowly to protect the OLED panel. Redraws at a low frame rate.
 */
class StarlightView(context: Context, private val monitor: ChargeMonitor) : View(context) {

    var mode = Mode.INTRO
        set(value) {
            field = value
            modeStartedAt = SystemClock.uptimeMillis()
        }
    var notes: List<NoteItem> = emptyList()

    /** Night screen extras: alarm and next-prayer lines, and the light ring (level 0..100, 0 = off). */
    var nightLines: List<String> = emptyList()
    var ringLevel = 0

    private val density = resources.displayMetrics.density
    private val background = BitmapFactory.decodeResource(resources, R.drawable.starfield)
    private val backgroundMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val startedAt = SystemClock.uptimeMillis()
    private var modeStartedAt = startedAt

    private val random = Random(42)
    // x, y (0..1), radius (dp), phase, speed
    private val twinkles = List(90) {
        floatArrayOf(random.nextFloat(), random.nextFloat(), 0.5f + random.nextFloat() * 1.3f,
            random.nextFloat() * 6.28f, 0.5f + random.nextFloat() * 1.6f)
    }
    // side (-1..1), phase, speed, radius (dp)
    private val particles = List(100) {
        floatArrayOf(random.nextFloat() * 2 - 1, random.nextFloat(), 0.6f + random.nextFloat() * 0.8f,
            0.8f + random.nextFloat() * 1.8f)
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val arc = RectF()
    private val shaderMatrix = Matrix()
    private val bolt = Path()
    private val moon = Path()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cut = Path()
    private val timeFormat = DateFormat.getTimeFormat(context)
    private val clockFormat = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    // Charging intensity 0..1, eased towards the measured power so the animation never jumps.
    private var intensity = 0.3f
    private var lastFrame = startedAt

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = max(w / background.width.toFloat(), h / background.height.toFloat()) * 1.04f
        backgroundMatrix.setScale(scale, scale)
        backgroundMatrix.postTranslate((w - background.width * scale) / 2f, (h - background.height * scale) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - startedAt) / 1000f
        val dt = min(0.1f, (now - lastFrame) / 1000f)
        lastFrame = now

        val target = when {
            monitor.watts >= 1 -> (monitor.watts / 60f).coerceIn(0.08f, 1f)
            else -> 0.3f // preview or trickle: a calm default
        }
        intensity += (target - intensity) * min(1f, dt * 1.5f)

        val standby = mode == Mode.STANDBY
        val night = mode == Mode.NIGHT
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.BLACK)
        canvas.save()
        if (standby || night) {
            // Slow drift of everything, a few dp over minutes, so no pixel stays lit in place.
            canvas.translate(sin(t / 97f) * 10 * density, cos(t / 131f) * 14 * density)
        }
        bitmapPaint.alpha = when { night -> 60; standby -> 150; else -> 255 }
        canvas.drawBitmap(background, backgroundMatrix, bitmapPaint)
        drawTwinkles(canvas, if (night) t * 0.4f else t, w, h, when { night -> 0.35f; standby -> 0.6f; else -> 1f })

        when {
            night -> drawNight(canvas, t, w, h)
            standby -> drawStandby(canvas, t, w, h)
            else -> drawMain(canvas, t, w, h)
        }
        canvas.restore()
        if (night && ringLevel > 0) drawRing(canvas, t, w, h)

        when {
            night -> postInvalidateDelayed(40)
            standby -> postInvalidateDelayed(50)
            else -> postInvalidateOnAnimation()
        }
    }

    private fun drawMain(canvas: Canvas, t: Float, w: Float, h: Float) {
        val full = mode == Mode.FULL || monitor.full && monitor.plugged
        val cx = w / 2f
        val cy = h * 0.55f
        val r = w * 0.29f
        val (inner, outer) = palette(full)
        val p = min(1f, (SystemClock.uptimeMillis() - modeStartedAt) / 1600f)
        val shownLevel = monitor.level * (1 - (1 - p) * (1 - p) * (1 - p))

        if (full) drawPulses(canvas, t, cx, cy, r, inner)
        else drawCurrent(canvas, t, w, h, cx, cy + r, r, inner, outer, 1f)
        drawRing(canvas, t, cx, cy, r, shownLevel, inner, outer, 10f)
        drawPercent(canvas, cx, cy, w * 0.19f, shownLevel)

        // Text sits above the ring, so the current below has a clear path into it.
        val top = cy - r - w * 0.08f
        text.textAlign = Paint.Align.CENTER
        text.typeface = light
        text.textSize = w * 0.055f
        text.alpha = 240
        canvas.drawText(if (full) "Fully charged" else tier(), cx, top - w * 0.15f, text)
        text.textSize = w * 0.045f
        text.alpha = 220
        canvas.drawText(if (full) "Unplug to keep your battery healthy" else stats(), cx, top - w * 0.075f, text)
        if (!full) {
            text.textSize = w * 0.036f
            text.alpha = 160
            canvas.drawText(eta(), cx, top, text)
        }

        val date = Date()
        text.typeface = thin
        text.textSize = w * 0.15f
        text.alpha = 240
        canvas.drawText(timeFormat.format(date), cx, h * 0.1f, text)
        text.typeface = light
        text.textSize = w * 0.04f
        text.alpha = 190
        canvas.drawText(dateFormat.format(date), cx, h * 0.1f + w * 0.075f, text)
        text.textSize = w * 0.032f
        text.alpha = (100 * min(1f, t / 2f)).toInt()
        canvas.drawText("Tap to dismiss", cx, h * 0.1f + w * 0.135f, text)
    }

    private fun drawStandby(canvas: Canvas, t: Float, w: Float, h: Float) {
        val full = monitor.full && monitor.plugged
        val cx = w / 2f
        val (inner, outer) = palette(full)
        val date = Date()

        text.textAlign = Paint.Align.CENTER
        text.typeface = thin
        text.textSize = w * 0.3f
        text.alpha = 235
        canvas.drawText(clockFormat.format(date), cx, h * 0.2f, text)
        text.typeface = light
        text.textSize = w * 0.045f
        text.alpha = 200
        canvas.drawText(dateFormat.format(date), cx, h * 0.2f + w * 0.09f, text)

        // Notifications: app icon, app name and title.
        var y = h * 0.33f
        val iconSize = (24 * density).toInt()
        text.textAlign = Paint.Align.LEFT
        text.textSize = w * 0.038f
        if (notes.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.alpha = 90
            canvas.drawText("No new notifications", cx, y + iconSize * 0.7f, text)
        }
        val left = w * 0.12f
        for (note in notes) {
            note.icon?.let {
                it.setBounds(left.toInt(), y.toInt(), left.toInt() + iconSize, y.toInt() + iconSize)
                it.alpha = 200
                it.draw(canvas)
            }
            text.alpha = 210
            val line = "${note.app}  ·  ${note.title}"
            val maxWidth = w * 0.76f - iconSize
            val shown = if (text.measureText(line) > maxWidth) {
                line.take(text.breakText(line, true, maxWidth - text.measureText("…"), null)) + "…"
            } else line
            canvas.drawText(shown, left + iconSize + 14 * density, y + iconSize * 0.72f, text)
            y += 44 * density
        }

        val ringY = h * 0.75f
        val r = w * 0.13f
        if (full) drawPulses(canvas, t, cx, ringY, r, inner)
        else drawCurrent(canvas, t, w, h, cx, ringY + r, r, inner, outer, 0.45f)
        drawRing(canvas, t, cx, ringY, r, monitor.level.toFloat(), inner, outer, 6f)
        drawPercent(canvas, cx, ringY, w * 0.085f, monitor.level.toFloat())

        val top = ringY - r - w * 0.07f
        text.textAlign = Paint.Align.CENTER
        text.typeface = light
        text.textSize = w * 0.042f
        text.alpha = 220
        canvas.drawText(if (full) "Fully charged  ·  Unplug" else "${tier()}  ·  ${stats()}", cx, top - w * 0.06f, text)
        if (!full) {
            text.textSize = w * 0.034f
            text.alpha = 150
            canvas.drawText(eta(), cx, top, text)
        }
    }

    /** Sleeping screen: a breathing crescent moon with drifting z's, a dim clock and small info lines. */
    private fun drawNight(canvas: Canvas, t: Float, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.56f + sin(t / 2.6f) * 7 * density
        val r = w * 0.15f
        val breathe = 0.75f + 0.25f * sin(t / 1.9f)

        // While charging: energy streaming up from the port into a thin battery-level ring
        // around the moon. Pace and colour follow the charging power, dimmed for the night.
        val charging = monitor.plugged && !monitor.full
        if (monitor.plugged) {
            val (inner, outer) = palette(monitor.full)
            val ringR = r * 1.32f
            if (charging) {
                canvas.saveLayerAlpha(0f, 0f, w, h, 120)
                drawCurrent(canvas, t, w, h, cx, cy + ringR, ringR, inner, outer, 0.55f)
                canvas.restore()
            }
            canvas.saveLayerAlpha(0f, 0f, w, h, 150)
            drawRing(canvas, t, cx, cy, ringR, monitor.level.toFloat(), inner, outer, 3.5f)
            canvas.restore()
        }

        // Soft halo: a smooth radial glow that breathes.
        val haloR = r * 2.6f
        val glowA = (60 * breathe).toInt()
        halo.shader = RadialGradient(cx, cy, haloR,
            intArrayOf(Color.argb(glowA, 255, 231, 179), Color.argb(glowA / 3, 255, 231, 179), Color.TRANSPARENT),
            floatArrayOf(0.25f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, haloR, halo)
        // Crescent: the full disc minus an offset disc.
        moon.reset()
        moon.addCircle(cx, cy, r, Path.Direction.CW)
        cut.reset()
        cut.addCircle(cx + r * 0.48f, cy - r * 0.32f, r * 0.9f, Path.Direction.CW)
        moon.op(cut, Path.Op.DIFFERENCE)
        dot.color = MOON
        dot.alpha = (190 + 50 * breathe).toInt()
        canvas.drawPath(moon, dot)

        // Three z's rising from beside the moon, growing and fading.
        text.textAlign = Paint.Align.CENTER
        text.typeface = light
        for (i in 0..2) {
            val phase = ((t / 4.5f) + i / 3f) % 1f
            val zx = cx + r * 0.9f + phase * r * 1.1f + sin(phase * 6f + i) * 6 * density
            val zy = cy - r * 0.6f - phase * r * 1.5f
            text.textSize = w * (0.035f + 0.05f * phase)
            text.alpha = (sin(phase * PI).toFloat() * 170).toInt()
            canvas.save()
            canvas.rotate(-12f + phase * 18f, zx, zy)
            canvas.drawText(if (i == 2) "Z" else "z", zx, zy, text)
            canvas.restore()
        }

        // Dim clock and date at the top, quiet info lines just below them.
        val date = Date()
        val clockY = h * 0.12f
        text.typeface = thin
        text.textSize = w * 0.17f
        text.alpha = 120
        canvas.drawText(clockFormat.format(date), cx, clockY, text)
        text.typeface = light
        text.textSize = w * 0.038f
        text.alpha = 90
        val dateY = clockY + w * 0.075f
        canvas.drawText(dateFormat.format(date), cx, dateY, text)

        text.textSize = w * 0.034f
        var y = dateY + w * 0.1f
        val step = w * 0.058f
        for (line in nightLines) {
            text.alpha = 95
            canvas.drawText(line, cx, y, text)
            y += step
        }
        text.alpha = 85
        when {
            !monitor.plugged -> canvas.drawText("${monitor.level}%", cx, y, text)
            monitor.full -> canvas.drawText("Charged · 100%", cx, y, text)
            else -> {
                canvas.drawText("${monitor.level}%  ·  ${tier()}", cx, y, text)
                text.alpha = 70
                canvas.drawText(stats(), cx, y + step, text)
                canvas.drawText(eta(), cx, y + step * 2, text)
            }
        }
    }

    /** Warm light ring around the screen edge, for use as a soft night light. */
    private fun drawRing(canvas: Canvas, t: Float, w: Float, h: Float) {
        if (ringBitmap == null || ringBitmapLevel != ringLevel) buildRing(w, h)
        val bmp = ringBitmap ?: return
        ringDst.set(0f, 0f, w, h)
        canvas.drawBitmap(bmp, null, ringDst, bitmapPaint.apply { alpha = 255 })
    }

    private var ringBitmap: android.graphics.Bitmap? = null
    private var ringBitmapLevel = -1
    private val ringDst = RectF()

    private fun buildRing(w: Float, h: Float) {
        val k = ringLevel / 100f
        val scale = 0.25f
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = RING
        }
        val d = density * scale
        // A wide soft glow plus a brighter core right at the edge.
        for ((width, blur, alpha) in listOf(Triple(10f + 40f * k, 18f + 26f * k, 0.55f + 0.45f * k),
                                           Triple(4f + 8f * k, 4f, 0.7f + 0.3f * k))) {
            p.strokeWidth = width * d
            p.maskFilter = android.graphics.BlurMaskFilter(blur * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
            p.alpha = (255 * alpha).toInt()
            val inset = p.strokeWidth / 2
            c.drawRoundRect(RectF(inset, inset, bw - inset, bh - inset), 46 * d, 46 * d, p)
        }
        ringBitmap?.recycle()
        ringBitmap = bmp
        ringBitmapLevel = ringLevel
    }

    private fun drawTwinkles(canvas: Canvas, t: Float, w: Float, h: Float, strength: Float) {
        dot.color = Color.WHITE
        for (s in twinkles) {
            val a = 0.5f + 0.5f * sin(s[3] + t * s[4])
            dot.alpha = (a * a * 210 * strength).toInt()
            canvas.drawCircle(s[0] * w, s[1] * h, s[2] * density, dot)
        }
    }

    /** Current flowing from the charging port (bottom centre) up into the ring. */
    private fun drawCurrent(canvas: Canvas, t: Float, w: Float, h: Float, cx: Float, top: Float,
                            r: Float, inner: Int, outer: Int, scale: Float) {
        val k = intensity
        val bottom = h * 1.01f

        // The wire, with pulses whose pace follows the current.
        ring.shader = null
        ring.color = inner
        ring.alpha = 40
        ring.strokeWidth = 1.5f * density
        canvas.drawLine(cx, bottom, cx, top + 10 * density, ring)
        val pulses = 3 + (k * 7).toInt()
        val pace = 0.35f + k * 1.6f
        for (i in 0 until pulses) {
            val phase = (t * pace + i / pulses.toFloat()) % 1f
            val y = bottom + (top - bottom) * phase
            dot.color = outer
            dot.alpha = (sin(phase * PI).toFloat() * 230).toInt()
            canvas.drawCircle(cx, y, (2.2f + k * 1.8f) * density, dot)
            dot.alpha /= 4
            canvas.drawCircle(cx, y, (6f + k * 5f) * density, dot)
        }

        // Particles bulge out from the wire and converge into the ring.
        val active = ((18 + k * 82) * scale).toInt().coerceAtMost(particles.size)
        val speed = 0.18f + k * 0.9f
        for (i in 0 until active) {
            val q = particles[i]
            val phase = (q[1] + t * speed * q[2]) % 1f
            val y = bottom + (top - bottom) * phase
            val spread = sin(phase * PI).toFloat() * w * 0.38f * scale
            val x = cx + q[0] * spread + sin(t * 2f + q[1] * 40f) * 4 * density * (1 - phase)
            dot.color = if (i % 3 == 0) outer else inner
            dot.alpha = (sin(phase * PI).toFloat() * 200).toInt()
            canvas.drawCircle(x, y, q[3] * density * (0.8f + k * 0.6f) * (1 - phase * 0.5f), dot)
        }

        // Lightning arcs at high power.
        if (k > 0.55f) {
            val strength = ((k - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val seed = (t * 9).toInt() // new shape ~9 times a second
            for (b in 0 until 2) {
                val rnd = Random(seed * 31 + b)
                if (rnd.nextFloat() > 0.35f + strength * 0.5f) continue
                bolt.reset()
                val startX = cx + (rnd.nextFloat() - 0.5f) * w * 0.25f * scale
                val endX = cx + (rnd.nextFloat() - 0.5f) * r * 0.5f
                bolt.moveTo(startX, bottom)
                val steps = 9
                for (s in 1..steps) {
                    val f = s / steps.toFloat()
                    val jitter = if (s == steps) 0f else (rnd.nextFloat() - 0.5f) * 36 * density * scale
                    bolt.lineTo(startX + (endX - startX) * f + jitter, bottom + (top - bottom) * f)
                }
                ring.color = outer
                ring.alpha = (50 * strength).toInt()
                ring.strokeWidth = 7 * density
                canvas.drawPath(bolt, ring)
                ring.color = Color.WHITE
                ring.alpha = (220 * strength).toInt()
                ring.strokeWidth = 1.6f * density
                canvas.drawPath(bolt, ring)
            }
        }
    }

    private fun drawPulses(canvas: Canvas, t: Float, cx: Float, cy: Float, r: Float, color: Int) {
        for (k in 0..2) {
            val phase = (t * 0.45f + k / 3f) % 1f
            ring.shader = null
            ring.color = color
            ring.alpha = ((1 - phase) * 120).toInt()
            ring.strokeWidth = 2 * density
            canvas.drawCircle(cx, cy, r * (1 + phase * 0.85f), ring)
        }
    }

    private fun drawRing(canvas: Canvas, t: Float, cx: Float, cy: Float, r: Float, level: Float,
                         inner: Int, outer: Int, stroke: Float) {
        ring.shader = null
        ring.color = Color.WHITE
        ring.alpha = 24
        ring.strokeWidth = stroke * density
        canvas.drawCircle(cx, cy, r, ring)

        val sweep = max(0.5f, 360f * level / 100f)
        arc.set(cx - r, cy - r, cx + r, cy + r)
        val gradient = SweepGradient(cx, cy, intArrayOf(inner, outer, inner), null)
        shaderMatrix.setRotate(t * (15f + intensity * 120f), cx, cy)
        gradient.setLocalMatrix(shaderMatrix)
        ring.shader = gradient
        val breathe = 0.8f + 0.2f * sin(t * (2f + intensity * 6f))
        for ((width, alpha) in GLOW) {
            ring.color = Color.WHITE
            ring.alpha = if (alpha == 255) 255 else (alpha * breathe).toInt()
            ring.strokeWidth = width * stroke / 10f * density
            canvas.drawArc(arc, -90f, sweep, false, ring)
        }
        ring.shader = null

        val angle = Math.toRadians((-90f + sweep).toDouble())
        val hx = cx + r * cos(angle).toFloat()
        val hy = cy + r * sin(angle).toFloat()
        dot.color = outer
        for ((radius, alpha) in HEAD) {
            dot.alpha = alpha
            canvas.drawCircle(hx, hy, radius * stroke / 10f * density, dot)
        }
        dot.color = Color.WHITE
        dot.alpha = 255
        canvas.drawCircle(hx, hy, 2.5f * stroke / 10f * density, dot)
    }

    private fun drawPercent(canvas: Canvas, cx: Float, cy: Float, size: Float, level: Float) {
        val number = level.roundToInt().toString()
        text.typeface = thin
        text.textAlign = Paint.Align.LEFT
        text.alpha = 255
        text.textSize = size
        val numberWidth = text.measureText(number)
        val baseline = cy + size * 0.35f
        text.textSize = size * 0.37f
        val percentWidth = text.measureText("%")
        val left = cx - (numberWidth + percentWidth) / 2f
        canvas.drawText("%", left + numberWidth, baseline - size * 0.42f, text)
        text.textSize = size
        canvas.drawText(number, left, baseline, text)
    }

    private fun palette(full: Boolean): Pair<Int, Int> {
        if (full) return GOLD
        val k = intensity
        return if (k < 0.5f) {
            mix(SLOW.first, FAST.first, k * 2) to mix(SLOW.second, FAST.second, k * 2)
        } else {
            mix(FAST.first, HYPER.first, (k - 0.5f) * 2) to mix(FAST.second, HYPER.second, (k - 0.5f) * 2)
        }
    }

    private fun tier() = when {
        monitor.watts >= 33 -> "HyperCharge"
        monitor.watts >= 15 -> "Fast charging"
        monitor.watts >= 1 -> "Charging"
        monitor.plugged -> "Trickle charging"
        else -> "Charging"
    }

    private fun stats(): String {
        if (monitor.milliamps < 20) return "Powering up"
        val amps = monitor.milliamps / 1000f
        val current = if (amps >= 1) String.format(Locale.US, "%.2f A", amps) else "${monitor.milliamps.roundToInt()} mA"
        return "$current  ·  ${String.format(Locale.US, "%.1f W", monitor.watts)}"
    }

    private fun eta(): String {
        val minutes = monitor.minutesToFull
        if (minutes <= 0) return "Estimating time to full…"
        val done = Date(System.currentTimeMillis() + minutes * 60_000L)
        val length = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
        return "≈ $length to full  ·  done by ${timeFormat.format(done)}"
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val g = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * g).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * g).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * g).toInt(),
        )
    }

    private companion object {
        val SLOW = Color.parseColor("#7DF9FF") to Color.parseColor("#5AA9FF")
        val FAST = Color.parseColor("#7DF9FF") to Color.parseColor("#B98CFF")
        val HYPER = Color.parseColor("#FFE29A") to Color.parseColor("#FFFFFF")
        val GOLD = Color.parseColor("#FFC86B") to Color.parseColor("#FFF1D0")
        val MOON = Color.parseColor("#FFE7B3")
        val RING = Color.parseColor("#FFE2B8")
        val GLOW = listOf(34f to 16, 22f to 40, 10f to 255) // stroke (dp at 10dp ring) to alpha
        val HEAD = listOf(18f to 40, 10f to 90, 5f to 255) // radius (dp at 10dp ring) to alpha
    }
}
