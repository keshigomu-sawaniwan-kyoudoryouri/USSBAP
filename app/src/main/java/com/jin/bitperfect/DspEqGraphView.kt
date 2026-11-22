package com.jin.bitperfect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.round

/** Five-band EQ editor: logarithmic frequency on X, gain in dB on Y. */
class DspEqGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val frequencies = floatArrayOf(60f, 250f, 1000f, 4000f, 12000f)
    private val gains = FloatArray(5)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.divider); strokeWidth = dp(1f) }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_muted); strokeWidth = dp(1.5f) }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent); strokeWidth = dp(2.5f); style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent); alpha = 36; style = Paint.Style.FILL }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent); style = Paint.Style.FILL }
    private val selectedPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_primary); style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_secondary); textSize = sp(10f) }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_primary); textSize = sp(11f); textAlign = Paint.Align.CENTER }
    private var selected = -1
    var onGainChanged: ((index: Int, gainDb: Float) -> Unit)? = null

    init { setBackgroundColor(context.getColor(R.color.surface_alt)); contentDescription = "Interactive EQ graph" }

    fun setGains(values: FloatArray) {
        for (i in gains.indices) gains[i] = values.getOrElse(i) { 0f }.coerceIn(-12f, 12f)
        invalidate()
    }

    private fun left() = dp(34f)
    private fun right() = width - dp(12f)
    private fun top() = dp(22f)
    private fun bottom() = height - dp(30f)
    private fun xFor(freq: Float): Float {
        val lo = log10(40f); val hi = log10(16000f)
        return left() + (log10(freq) - lo) / (hi - lo) * (right() - left())
    }
    private fun yFor(db: Float) = top() + (12f - db) / 24f * (bottom() - top())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (db in listOf(12, 6, 0, -6, -12)) {
            val y = yFor(db.toFloat())
            canvas.drawLine(left(), y, right(), y, if (db == 0) zero else grid)
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(if (db > 0) "+$db" else "$db", left() - dp(5f), y + dp(3f), label)
        }
        frequencies.forEachIndexed { i, hz ->
            val x = xFor(hz)
            canvas.drawLine(x, top(), x, bottom(), grid)
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString(), x, height - dp(9f), label)
            if (i == selected) canvas.drawText("${if (gains[i] >= 0) "+" else ""}${"%.1f".format(gains[i])} dB", x, dp(14f), value)
        }
        val path = Path()
        frequencies.indices.forEach { i -> val x=xFor(frequencies[i]); val y=yFor(gains[i]); if(i==0)path.moveTo(x,y) else path.lineTo(x,y) }
        val area = Path(path).apply { lineTo(xFor(frequencies.last()), yFor(0f)); lineTo(xFor(frequencies.first()), yFor(0f)); close() }
        canvas.drawPath(area, fill); canvas.drawPath(path, curve)
        frequencies.indices.forEach { i -> canvas.drawCircle(xFor(frequencies[i]), yFor(gains[i]), dp(if(i==selected) 7f else 5f), if(i==selected) selectedPoint else point) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                selected = frequencies.indices.minByOrNull { abs(event.x - xFor(frequencies[it])) } ?: 0
                updateGain(event.y); return true
            }
            MotionEvent.ACTION_MOVE -> { updateGain(event.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); performClick(); return true }
        }
        return super.onTouchEvent(event)
    }
    private fun updateGain(y: Float) {
        if (selected !in gains.indices) return
        gains[selected] = (12f - (y - top()) / (bottom() - top()) * 24f).coerceIn(-12f, 12f).let { round(it * 2f) / 2f }
        onGainChanged?.invoke(selected, gains[selected]); invalidate()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
