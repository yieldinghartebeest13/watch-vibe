package com.example.vibecontrol

import android.content.Context
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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // Clip to rounded bottom corners matching tile_bg.xml (radius 16dp ≈ 24px)
        val radius = 24f
        val clipPath = Path()
        clipPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(),
            radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        val cycleW = width / 2f
        val h = height.toFloat()
        val path = Path()

        // Build ONE cycle spanning [0, cycleW]
        buildCycle(path, cycleW, h)

        // Draw cycle 1
        drawPath(canvas, path)

        // Draw cycle 2 at offset
        canvas.save()
        canvas.translate(cycleW, 0f)
        drawPath(canvas, path)
        canvas.restore()
    }

    private fun buildCycle(path: Path, w: Float, h: Float) {
        when (mode) {
            MainViewModel.MODE_CONSTANT -> {
                val y = h * 0.2f
                path.moveTo(0f, y)
                path.lineTo(w, y)
            }
            MainViewModel.MODE_INTERMITTENT -> {
                val pulseCount = if (level == 0) 1 else 2
                val pulseWidth = w / (pulseCount * 2)
                for (i in 0 until pulseCount) {
                    val x1 = i * pulseWidth * 2
                    val x2 = x1 + pulseWidth * 1.4f
                    path.moveTo(x1, h * 0.6f)
                    path.lineTo(x1, h * 0.1f)
                    path.lineTo(x2, h * 0.1f)
                    path.lineTo(x2, h * 0.6f)
                }
                path.lineTo(w, h * 0.6f)
            }
            MainViewModel.MODE_RAMP -> {
                val steps = 5
                path.moveTo(0f, h * 0.8f)
                for (i in 0 until steps) {
                    val x = w * (i + 1) / (steps + 1)
                    val y = h * (1f - (i + 1).toFloat() / steps) * 0.7f + h * 0.1f
                    path.lineTo(x, y)
                }
                path.lineTo(w, h * 0.8f)
            }
            MainViewModel.MODE_BURST -> {
                // 3 taps with equal on/off, then pause
                val numTaps = 3
                val activeWidth = w * 0.7f          // taps+gaps span 70%
                val pairWidth = activeWidth / numTaps // each tap+gap pair
                val tapWidth = pairWidth * 0.5f      // half on, half off
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
            MainViewModel.MODE_WAVE -> {
                val samples = 20
                for (i in 0..samples) {
                    val x = w * i / samples
                    val angle = -Math.PI / 2 + i * 2.0 * Math.PI / samples
                    val y = (-Math.sin(angle) * h * 0.35f + h / 2f).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            MainViewModel.MODE_RANDOM -> {
                val samples = 15
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

    private fun drawPath(canvas: Canvas, path: Path) {
        // Fill
        val fillPath = Path(path)
        fillPath.lineTo(canvas.width / 2f, height.toFloat())
        fillPath.lineTo(0f, height.toFloat())
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        // Line
        canvas.drawPath(path, linePaint)
    }
}
