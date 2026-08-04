package com.yieldinghartebeest13.watchvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mode: Int = -1
    var level: Int = 0

    private var cachedBitmap: Bitmap? = null

    private val linePaint = Paint().apply {
        color = Color.argb(100, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint().apply {
        color = Color.argb(30, 255, 255, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setPattern(mode: Int, level: Int) {
        this.mode = mode
        this.level = level
        cachedBitmap = null // invalidate cache on mode/level change
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedBitmap = null // invalidate cache on size change
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (cachedBitmap == null) {
            cachedBitmap = renderToBitmap()
        }
        cachedBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun renderToBitmap(): Bitmap? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Clip to rounded bottom corners matching tile_bg.xml (radius 16dp ≈ 24px)
        val radius = 24f
        val clipPath = Path()
        clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(),
            radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        val cycleW = w / 2f
        val path = Path()
        buildCycle(path, cycleW, h.toFloat())

        // Draw cycle 1
        drawPathOn(canvas, path, w, h)

        // Draw cycle 2 at offset
        canvas.save()
        canvas.translate(cycleW, 0f)
        drawPathOn(canvas, path, w, h)
        canvas.restore()

        return bitmap
    }

    private fun buildCycle(path: Path, w: Float, h: Float) {
        when (mode) {
            AppConstants.MODE_CONSTANT -> {
                val y = h * 0.2f
                path.moveTo(0f, y)
                path.lineTo(w, y)
            }
            AppConstants.MODE_INTERMITTENT -> {
                // Waveform shape is speed-independent: always shows the
                // characteristic 70% duty-cycle pulse, not more pulses
                // at higher speeds.
                val pulseWidth = w / 2f
                val x2 = pulseWidth * 1.4f
                path.moveTo(0f, h * 0.6f)
                path.lineTo(0f, h * 0.1f)
                path.lineTo(x2, h * 0.1f)
                path.lineTo(x2, h * 0.6f)
                path.lineTo(w, h * 0.6f)
            }
            AppConstants.MODE_RAMP -> {
                val steps = 5
                path.moveTo(0f, h * 0.8f)
                for (i in 0 until steps) {
                    val x = w * (i + 1) / (steps + 1)
                    val y = h * (1f - (i + 1).toFloat() / steps) * 0.7f + h * 0.1f
                    path.lineTo(x, y)
                }
                path.lineTo(w, h * 0.8f)
            }
            AppConstants.MODE_BURST -> {
                val numTaps = 3
                val activeWidth = w * 0.7f
                val pairWidth = activeWidth / numTaps
                val tapWidth = pairWidth * 0.5f
                val baseY = h * 0.8f
                val topY = h * 0.1f

                path.moveTo(0f, baseY)
                for (i in 0 until numTaps) {
                    val x = i * pairWidth
                    path.lineTo(x, baseY)
                    path.lineTo(x, topY)
                    path.lineTo(x + tapWidth, topY)
                    path.lineTo(x + tapWidth, baseY)
                }
                path.lineTo(activeWidth, baseY)
                path.lineTo(w, baseY)
            }
            AppConstants.MODE_WAVE -> {
                val samples = 20
                for (i in 0..samples) {
                    val x = w * i / samples
                    val angle = -Math.PI / 2 + i * 2.0 * Math.PI / samples
                    val y = (-Math.sin(angle) * h * 0.35f + h / 2f).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            AppConstants.MODE_RANDOM -> {
                val samples = 15
                // Fixed seed 42 ensures the random waveform is visually stable
                // across redraws. Without a fixed seed, the chart would jitter on
                // every frame, which is distracting and not useful.
                val random = java.util.Random(42)
                path.moveTo(0f, h / 2f)
                for (i in 1..samples) {
                    val x = w * i / samples
                    val y = random.nextFloat() * h * 0.7f + h * 0.15f
                    path.lineTo(x, y)
                }
            }
        }
    }

    private fun drawPathOn(canvas: Canvas, path: Path, totalW: Int, totalH: Int) {
        val fillPath = Path(path)
        fillPath.lineTo(totalW / 2f, totalH.toFloat())
        fillPath.lineTo(0f, totalH.toFloat())
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
