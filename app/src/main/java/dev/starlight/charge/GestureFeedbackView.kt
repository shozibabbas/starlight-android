package dev.starlight.charge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.min

/**
 * Full-screen, non-touchable layer that draws gesture feedback in the Starlight style:
 * a back bubble that slides out of the edge, a glowing handle that rises with the finger,
 * a card outline once recent apps are armed, and the next app's icon while switching.
 */
class GestureFeedbackView(context: Context) : View(context) {

    enum class Mode { NONE, BACK, HOME, SWITCH }

    var mode = Mode.NONE
        private set

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E60B0E1C") }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val path = Path()
    private val rect = RectF()

    // Back
    private var fromLeft = true
    private var backY = 0f
    private var backProgress = 0f
    private var backArmed = false

    // Home / recents
    private var fingerX = 0f
    private var fingerY = 0f
    private var homeProgress = 0f
    private var recentsArmed = false

    // App switch
    private var switchDir = 0
    private var switchProgress = 0f
    private var switchIcon: Drawable? = null

    fun showBack(left: Boolean, y: Float, progress: Float, armed: Boolean) {
        mode = Mode.BACK; fromLeft = left; backY = y; backProgress = progress; backArmed = armed
        visibility = VISIBLE; invalidate()
    }

    fun showHome(x: Float, y: Float, progress: Float, recents: Boolean) {
        mode = Mode.HOME; fingerX = x; fingerY = y; homeProgress = progress; recentsArmed = recents
        visibility = VISIBLE; invalidate()
    }

    fun showSwitch(direction: Int, progress: Float, icon: Drawable?) {
        mode = Mode.SWITCH; switchDir = direction; switchProgress = progress; switchIcon = icon
        visibility = VISIBLE; invalidate()
    }

    fun hide() {
        mode = Mode.NONE
        switchIcon = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.BACK -> drawBack(canvas)
            Mode.HOME -> drawHome(canvas)
            Mode.SWITCH -> drawSwitch(canvas)
            Mode.NONE -> Unit
        }
    }

    private fun drawBack(canvas: Canvas) {
        val p = min(1f, backProgress)
        val bulge = 30 * density * p
        val half = 72 * density
        val edge = if (fromLeft) 0f else width.toFloat()
        val out = if (fromLeft) bulge else -bulge

        // A soft bubble bulging out of the screen edge.
        path.reset()
        path.moveTo(edge, backY - half)
        path.cubicTo(edge, backY - half * 0.45f, edge + out, backY - half * 0.4f, edge + out, backY)
        path.cubicTo(edge + out, backY + half * 0.4f, edge, backY + half * 0.45f, edge, backY + half)
        path.close()
        fill.alpha = (235 * p).toInt()
        canvas.drawPath(path, fill)
        glow.color = if (backArmed) Color.parseColor("#7DF9FF") else Color.parseColor("#B98CFF")
        glow.alpha = (200 * p).toInt()
        glow.strokeWidth = 1.5f * density
        canvas.drawPath(path, glow)

        // Chevron pointing back, firming up once the swipe is far enough.
        val cx = edge + out * 0.55f
        val size = (6 + 4 * p) * density
        val dir = if (fromLeft) 1 else -1
        arrow.strokeWidth = (2 + if (backArmed) 1f else 0f) * density
        arrow.alpha = (255 * p).toInt()
        path.reset()
        path.moveTo(cx + dir * size * 0.5f, backY - size)
        path.lineTo(cx - dir * size * 0.5f, backY)
        path.lineTo(cx + dir * size * 0.5f, backY + size)
        canvas.drawPath(path, arrow)
    }

    private fun drawHome(canvas: Canvas) {
        val p = min(1f, homeProgress)
        if (recentsArmed) {
            // Card outline: recent apps are about to open.
            val w = 150 * density
            val h = 250 * density
            rect.set(fingerX - w / 2, fingerY - h * 0.85f, fingerX + w / 2, fingerY + h * 0.15f)
            fill.alpha = 150
            canvas.drawRoundRect(rect, 24 * density, 24 * density, fill)
            glow.color = Color.parseColor("#7DF9FF")
            glow.alpha = 230
            glow.strokeWidth = 2 * density
            canvas.drawRoundRect(rect, 24 * density, 24 * density, glow)
            // Two cards peeking behind.
            glow.alpha = 110
            glow.strokeWidth = 1.5f * density
            rect.offset(-w * 0.62f, h * 0.08f)
            canvas.drawRoundRect(rect, 24 * density, 24 * density, glow)
            rect.offset(w * 1.24f, 0f)
            canvas.drawRoundRect(rect, 24 * density, 24 * density, glow)
            return
        }
        // Glowing handle that rises and stretches with the finger.
        val w = (84 + 56 * p) * density
        val h = 6 * density
        rect.set(fingerX - w / 2, fingerY - h / 2, fingerX + w / 2, fingerY + h / 2)
        for ((spread, alpha) in listOf(10f to 40, 5f to 90, 0f to 255)) {
            val r = RectF(rect.left - spread * density, rect.top - spread * density,
                rect.right + spread * density, rect.bottom + spread * density)
            glow.style = Paint.Style.FILL
            glow.color = Color.parseColor("#B98CFF")
            glow.alpha = (alpha * p).toInt()
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, glow)
        }
        glow.style = Paint.Style.STROKE
        arrow.style = Paint.Style.FILL
        arrow.alpha = (255 * p).toInt()
        canvas.drawRoundRect(rect, h / 2, h / 2, arrow)
        arrow.style = Paint.Style.STROKE
    }

    private fun drawSwitch(canvas: Canvas) {
        val p = min(1f, switchProgress)
        val y = height - 120 * density
        // The target app slides in from the side it is coming from.
        val from = if (switchDir > 0) -60 * density else width + 60 * density
        val cx = from + (width / 2f - from) * (0.25f + 0.75f * p)
        val r = 34 * density
        fill.alpha = (230 * p).toInt()
        canvas.drawCircle(cx, y, r + 8 * density, fill)
        glow.color = Color.parseColor("#7DF9FF")
        glow.alpha = (220 * p).toInt()
        glow.strokeWidth = 2 * density
        canvas.drawCircle(cx, y, r + 8 * density, glow)
        switchIcon?.let {
            it.setBounds((cx - r).toInt(), (y - r).toInt(), (cx + r).toInt(), (y + r).toInt())
            it.alpha = (255 * p).toInt()
            it.draw(canvas)
        }
        // Chevron showing the direction of travel.
        val dir = if (switchDir > 0) 1 else -1
        val ax = cx + dir * (r + 26 * density)
        val s = 7 * density
        arrow.strokeWidth = 2.5f * density
        arrow.alpha = (255 * p).toInt()
        path.reset()
        path.moveTo(ax - dir * s * 0.5f, y - s)
        path.lineTo(ax + dir * s * 0.5f, y)
        path.lineTo(ax - dir * s * 0.5f, y + s)
        canvas.drawPath(path, arrow)
    }
}
