package com.yieldinghartebeest13.watchvibe

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaveformViewTest {
    @Test
    fun `all displayed modes share the constant chart peak height`() {
        val context = contextAtDensity(160)
        val modes = listOf(AppConstants.MODE_CONSTANT, AppConstants.MODE_INTERMITTENT,
            AppConstants.MODE_RAMP, AppConstants.MODE_BURST, AppConstants.MODE_WAVE)
        val peaks = modes.map { mode ->
            val bitmap = drawChart(context, mode)
            try {
                (0 until bitmap.height).first { y ->
                    (0 until bitmap.width).any { x -> Color.alpha(bitmap.getPixel(x, y)) >= 65 }
                }
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue("Mode peaks differ: $peaks", peaks.max() - peaks.min() <= 1)
        assertTrue("Constant is not at the shared 10% top margin", peaks.first() in 2..5)
    }

    @Test
    fun `bottom clip matches tile radius across densities and does not round top edge`() {
        for (density in listOf(160, 320, 640)) {
            val context = contextAtDensity(density)
            val radius = context.resources.getDimension(R.dimen.mode_tile_corner_radius)
            val tile = context.getDrawable(R.drawable.tile_bg) as GradientDrawable
            val active = context.getDrawable(R.drawable.tile_bg_active) as GradientDrawable
            assertEquals(16f * density / 160f, radius, 0.01f)
            assertEquals(radius, tile.cornerRadius, 0.01f)
            assertEquals(radius, active.cornerRadius, 0.01f)
            val bitmap = drawChart(context, AppConstants.MODE_CONSTANT)
            try {
                assertEquals("Bottom outside arc at density $density", 0,
                    Color.alpha(bitmap.getPixel((radius * 0.15f).roundToInt(),
                        bitmap.height - 1 - (radius * 0.15f).roundToInt())))
                assertTrue("Bottom inside arc at density $density",
                    Color.alpha(bitmap.getPixel((radius * 0.6f).roundToInt(),
                        bitmap.height - 1 - (radius * 0.3f).roundToInt())) > 0)
                assertTrue("Chart top must remain square at density $density",
                    Color.alpha(bitmap.getPixel(0, (bitmap.height * 0.1f).roundToInt())) > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `changing pattern invalidates the rendered chart`() {
        val context = contextAtDensity(160)
        val view = WaveformView(context)
        view.layout(0, 0, 200, 40)
        view.setPattern(AppConstants.MODE_CONSTANT, 0)
        val first = draw(view)
        view.setPattern(AppConstants.MODE_BURST, 0)
        val second = draw(view)
        try {
            assertFalse(first.sameAs(second))
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    private fun contextAtDensity(density: Int): Context {
        val application = RuntimeEnvironment.getApplication()
        val config = Configuration(application.resources.configuration).apply { densityDpi = density }
        return application.createConfigurationContext(config)
    }

    private fun drawChart(context: Context, mode: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val view = WaveformView(context)
        view.layout(0, 0, (200 * density).roundToInt(), (40 * density).roundToInt())
        view.setPattern(mode, 0)
        return draw(view)
    }

    private fun draw(view: WaveformView): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
}
