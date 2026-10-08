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
        private set
    var level: Int = 0
        private set

    private var cachedBitmap: Bitmap? = null

    private val linePaint = Paint().apply {
        color = Color.argb(100, 255, 255, 255)
        strokeWidth = 1.5f * resources.displayMetrics.density
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
        if (this.mode == mode && this.level == level) return
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

        // The chart occupies only the bottom strip. Its top edge is square;
        // bottom corners share the tile's actual density-scaled radius.
        val radius = resources.getDimension(R.dimen.mode_tile_corner_radius)
        val clipPath = Path()
        clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(),
            floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius), Path.Direction.CW)
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
        val topY = h * 0.1f
        val bottomY = h * 0.8f
        when (mode) {
            AppConstants.MODE_CONSTANT -> {
                path.moveTo(0f, topY)
                path.lineTo(w, topY)
            }
            AppConstants.MODE_INTERMITTENT -> {
                // Waveform shape is speed-independent: always shows the
                // characteristic 70% duty-cycle pulse, not more pulses
                // at higher speeds.
                val pulseWidth = w / 2f
                val x2 = pulseWidth * 1.4f
                path.moveTo(0f, h * 0.6f)
                path.lineTo(0f, topY)
                path.lineTo(x2, topY)
                path.lineTo(x2, h * 0.6f)
                path.lineTo(w, h * 0.6f)
            }
            AppConstants.MODE_RAMP -> {
                val steps = 5
                path.moveTo(0f, bottomY)
                for (i in 0 until steps) {
                    val x = w * (i + 1) / (steps + 1)
                    val y = topY + (1f - (i + 1).toFloat() / steps) * (bottomY - topY)
                    path.lineTo(x, y)
                }
                path.lineTo(w, bottomY)
            }
            AppConstants.MODE_BURST -> {
                val numTaps = 3
                val activeWidth = w * 0.7f
                val pairWidth = activeWidth / numTaps
                val tapWidth = pairWidth * 0.5f
                val baseY = bottomY

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
                    val y = (-Math.sin(angle) * (bottomY - topY) / 2f +
                        (topY + bottomY) / 2f).toFloat()
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
