package com.jin.bitperfect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class SpectrumAnalyzerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.divider); strokeWidth = density }
    private val minorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.divider); alpha = 90; strokeWidth = .6f * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_secondary); textSize = 9.5f * density; typeface = android.graphics.Typeface.MONOSPACE }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent); textSize = 11f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent); style = Paint.Style.STROKE; strokeWidth = 1.25f * density }
    private val dryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFFFFB74D.toInt();style=Paint.Style.STROKE;strokeWidth=1f*density }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD15C.toInt(); strokeWidth = density }
    private val path = Path()
    private val fillPath = Path()
    private var inspectX = -1f
    private var attached = false

    private val animate = object : Runnable {
        override fun run() {
            if (!attached) return
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; postOnAnimation(animate) }
    override fun onDetachedFromWindow() { attached = false; removeCallbacks(animate); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(context.getColor(R.color.surface_alt))
        val left = 44f * density
        val right = width - 12f * density
        val top = 52f * density
        val bottom = height - 29f * density
        if (right <= left || bottom <= top) return

        val postFrame = SpectrumRuntime.latest.get()
        val frame = when (SpectrumRuntime.displayMode) {
            2 -> SpectrumRuntime.latestGenerated.get()
            else -> postFrame
        }
        val rate = frame?.sampleRate ?: 48_000
        val nyquist = rate / 2f
        val live = frame != null && System.nanoTime() - frame.timestampNs < 700_000_000L
        titlePaint.color = if (live) context.getColor(R.color.accent) else context.getColor(R.color.text_muted)
        val liveTitle = when(SpectrumRuntime.displayMode){1->"● POST-DAU + ORIGINAL ESTIMATE";2->"● AI-GENERATED COMPONENT";3->"● HIGH-BAND 10kHz+";else->if (DauControl.active) "● LIVE POST-DAU SPECTRUM" else "● LIVE OUTPUT SPECTRUM"}
        canvas.drawText(if (live) liveTitle else "○ SPECTRAL FIELD / STANDBY", left, 17f * density, titlePaint)
        textPaint.color = context.getColor(R.color.text_muted)
        val resolution = rate.toFloat() / SpectrumRuntime.FFT_SIZE
        canvas.drawText("8192 FFT  |  4097 BINS  |  Δf ${"%.2f".format(resolution)} Hz", left, 35f * density, textPaint)

        val dbLines = intArrayOf(0, -12, -24, -36, -48, -60, -72, -84, -96)
        for (db in dbLines) {
            val y = dbToY(db.toFloat(), top, bottom)
            canvas.drawLine(left, y, right, y, if (db % 24 == 0) gridPaint else minorGridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("$db", left - 5f * density, y + 3f * density, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        val frequencies = intArrayOf(20, 30, 50, 100, 200, 300, 500, 1000, 2000, 3000, 5000, 10000, 15000, 20000)
        for (frequency in frequencies) {
            if (frequency >= nyquist) continue
            val x = frequencyToX(frequency.toFloat(), left, right, nyquist)
            canvas.drawLine(x, top, x, bottom, if (frequency == 100 || frequency == 1000 || frequency == 10000) gridPaint else minorGridPaint)
            if (frequency in intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)) {
                val label = if (frequency >= 1000) "${frequency / 1000}k" else frequency.toString()
                textPaint.textAlign = when { x < left + 20f * density -> Paint.Align.LEFT; x > right - 20f * density -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                canvas.drawText(label, x, bottom + 17f * density, textPaint)
            }
        }
        textPaint.textAlign = Paint.Align.LEFT

        if (frame == null) return
        val plotWidth = max(1, (right - left).roundToInt())
        path.reset(); fillPath.reset()
        for (pixel in 0..plotWidth) {
            val x = left + pixel
            val frequency = xToFrequency(x, left, right, nyquist)
            val bin = frequency / rate * SpectrumRuntime.FFT_SIZE
            val db = interpolate(frame.db, bin)
            val y = dbToY(db, top, bottom)
            if (pixel == 0) { path.moveTo(x, y); fillPath.moveTo(x, bottom); fillPath.lineTo(x, y) }
            else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(right, bottom); fillPath.close()
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(0x8F25D8FF.toInt(), 0x3025A6C8, 0x0025A6C8), null, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, spectrumPaint)
        if(SpectrumRuntime.displayMode==1){SpectrumRuntime.latestDry.get()?.let { dryFrame -> path.reset();for(pixel in 0..plotWidth){val x=left+pixel;val frequency=xToFrequency(x,left,right,nyquist);val bin=frequency/rate*SpectrumRuntime.FFT_SIZE;val y=dbToY(interpolate(dryFrame.db,bin),top,bottom);if(pixel==0)path.moveTo(x,y)else path.lineTo(x,y)};canvas.drawPath(path,dryPaint)}}

        if (inspectX in left..right) {
            val frequency = xToFrequency(inspectX, left, right, nyquist)
            val bin = frequency / rate * SpectrumRuntime.FFT_SIZE
            val db = interpolate(frame.db, bin)
            val y = dbToY(db, top, bottom)
            canvas.drawLine(inspectX, top, inspectX, bottom, cursorPaint)
            canvas.drawLine(left, y, right, y, cursorPaint)
            val label = "${"%.2f".format(frequency)} Hz  ${noteName(frequency)}  ${"%.2f".format(db)} dBFS  bin ${"%.2f".format(bin)}"
            val labelWidth = textPaint.measureText(label) + 12f * density
            val boxLeft = min(max(left, inspectX - labelWidth / 2), right - labelWidth)
            cursorPaint.style = Paint.Style.FILL; cursorPaint.color = 0xEE17232A.toInt()
            canvas.drawRect(boxLeft, top + 5f * density, boxLeft + labelWidth, top + 27f * density, cursorPaint)
            textPaint.color = 0xFFFFD15C.toInt(); textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, boxLeft + 6f * density, top + 20f * density, textPaint)
            cursorPaint.style = Paint.Style.STROKE; cursorPaint.color = 0xFFFFD15C.toInt()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { inspectX = event.x; parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); if (event.actionMasked == MotionEvent.ACTION_CANCEL) inspectX = -1f; invalidate(); return true }
        }
        return super.onTouchEvent(event)
    }

    private fun frequencyToX(frequency: Float, left: Float, right: Float, nyquist: Float): Float {
        val minHz = if(SpectrumRuntime.displayMode==3)10000f else 20f
        val normalized = (log2(max(minHz, frequency) / minHz) / log2(nyquist / minHz)).coerceIn(0f, 1f)
        return left + normalized * (right - left)
    }

    private fun xToFrequency(x: Float, left: Float, right: Float, nyquist: Float): Float {
        val normalized = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val minHz=if(SpectrumRuntime.displayMode==3)10000f else 20f;return minHz * (nyquist / minHz).pow(normalized)
    }

    private fun dbToY(db: Float, top: Float, bottom: Float): Float = top + ((0f - db.coerceIn(-96f, 0f)) / 96f) * (bottom - top)

    private fun interpolate(values: FloatArray, position: Float): Float {
        val low = floor(position).toInt().coerceIn(0, values.lastIndex)
        val high = min(values.lastIndex, low + 1)
        val fraction = position - low
        return values[low] + (values[high] - values[low]) * fraction
    }

    private fun noteName(frequency: Float): String {
        if (frequency <= 0f) return "—"
        val exactMidi = 69.0 + 12.0 * log2(frequency / 440.0)
        val midi = exactMidi.roundToInt()
        val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        val cents = ((exactMidi - midi) * 100).roundToInt()
        return "${names[(midi % 12 + 12) % 12]}${midi / 12 - 1} ${if (cents >= 0) "+" else ""}$cents¢"
    }
}
