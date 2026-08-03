package com.example.vibecontrol

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var statusText: TextView
    private lateinit var wearStatus: TextView
    private lateinit var btnStop: Button
    private lateinit var btnMore: Button
    private lateinit var btnLess: Button
    private lateinit var speedLabel: TextView
    // Animation state
    private val dotAnimators = mutableMapOf<Int, ObjectAnimator>()
    private val pulseRunnables = mutableMapOf<Int, PulseRunner>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActiveMode: Int = -1
    private lateinit var tileMap: Map<Int, TileViews>

    data class TileViews(
        val container: FrameLayout,
        val bg: View,
        val title: TextView,
        val activeContainer: FrameLayout,
        val dots: ImageView,
        val pulseBackdrop: View,
        val center: View,
        val chart: WaveformView?,
        val defaultColor: String,
        val activeColor: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = MainViewModel(application)

        statusText = findViewById(R.id.statusText)
        wearStatus = findViewById(R.id.wearStatus)
        btnStop = findViewById(R.id.btnStop)
        btnMore = findViewById(R.id.btnMore)
        btnLess = findViewById(R.id.btnLess)
        speedLabel = findViewById(R.id.speedLabel)
        buildTileMap()
        // Initialize all waveform charts at startup
        for ((mode, tile) in tileMap) {
            tile.chart?.setPattern(mode, 0)
        }
        setupListeners()
        observeViewModel()
    }

    private fun buildTileMap() {
        tileMap = mapOf(
            MainViewModel.MODE_CONSTANT to TileViews(
                container = findViewById(R.id.tileConstant),
                bg = findViewById(R.id.tileConstantBg),
                title = findViewById(R.id.tileConstantTitle),
                activeContainer = findViewById(R.id.tileConstantActive),
                dots = findViewById(R.id.tileConstantDots),
                pulseBackdrop = findViewById(R.id.tileConstantPulseBackdrop),
                center = findViewById(R.id.tileConstantCenter),
                chart = findViewById(R.id.tileConstantChart),
                defaultColor = "#8b6b6b",
                activeColor = "#b04a4a"
            ),
            MainViewModel.MODE_INTERMITTENT to TileViews(
                container = findViewById(R.id.tileIntermittent),
                bg = findViewById(R.id.tileIntermittentBg),
                title = findViewById(R.id.tileIntermittentTitle),
                activeContainer = findViewById(R.id.tileIntermittentActive),
                dots = findViewById(R.id.tileIntermittentDots),
                pulseBackdrop = findViewById(R.id.tileIntermittentPulseBackdrop),
                center = findViewById(R.id.tileIntermittentCenter),
                chart = findViewById(R.id.tileIntermittentChart),
                defaultColor = "#8b7442",
                activeColor = "#b08a4a"
            ),
            MainViewModel.MODE_RAMP to TileViews(
                container = findViewById(R.id.tileRamp),
                bg = findViewById(R.id.tileRampBg),
                title = findViewById(R.id.tileRampTitle),
                activeContainer = findViewById(R.id.tileRampActive),
                dots = findViewById(R.id.tileRampDots),
                pulseBackdrop = findViewById(R.id.tileRampPulseBackdrop),
                center = findViewById(R.id.tileRampCenter),
                chart = findViewById(R.id.tileRampChart),
                defaultColor = "#4a7a7a",
                activeColor = "#5a9a9a"
            ),
            MainViewModel.MODE_BURST to TileViews(
                container = findViewById(R.id.tileBurst),
                bg = findViewById(R.id.tileBurstBg),
                title = findViewById(R.id.tileBurstTitle),
                activeContainer = findViewById(R.id.tileBurstActive),
                dots = findViewById(R.id.tileBurstDots),
                pulseBackdrop = findViewById(R.id.tileBurstPulseBackdrop),
                center = findViewById(R.id.tileBurstCenter),
                chart = findViewById(R.id.tileBurstChart),
                defaultColor = "#6b5b8b",
                activeColor = "#8b6bab"
            ),
            MainViewModel.MODE_WAVE to TileViews(
                container = findViewById(R.id.tileWave),
                bg = findViewById(R.id.tileWaveBg),
                title = findViewById(R.id.tileWaveTitle),
                activeContainer = findViewById(R.id.tileWaveActive),
                dots = findViewById(R.id.tileWaveDots),
                pulseBackdrop = findViewById(R.id.tileWavePulseBackdrop),
                center = findViewById(R.id.tileWaveCenter),
                chart = findViewById(R.id.tileWaveChart),
                defaultColor = "#4a6a8a",
                activeColor = "#5a8aaa"
            ),
            MainViewModel.MODE_RANDOM to TileViews(
                container = findViewById(R.id.tileRandom),
                bg = findViewById(R.id.tileRandomBg),
                title = findViewById(R.id.tileRandomTitle),
                activeContainer = findViewById(R.id.tileRandomActive),
                dots = findViewById(R.id.tileRandomDots),
                pulseBackdrop = findViewById(R.id.tileRandomPulseBackdrop),
                center = findViewById(R.id.tileRandomCenter),
                chart = null,
                defaultColor = "#4a7a5a",
                activeColor = "#5a9a6a"
            )
        )
    }

    private fun setupListeners() {
        tileMap[MainViewModel.MODE_CONSTANT]?.container?.setOnClickListener {
            if (viewModel.mode.value == MainViewModel.MODE_CONSTANT && viewModel.isVibrating.value) {
                viewModel.modeStop()
            } else {
                viewModel.modeConstant()
            }
        }
        tileMap[MainViewModel.MODE_INTERMITTENT]?.container?.setOnClickListener {
            if (viewModel.mode.value == MainViewModel.MODE_INTERMITTENT && viewModel.isVibrating.value) {
                viewModel.modeStop()
            } else {
                viewModel.modeIntermittent()
            }
        }
        tileMap[MainViewModel.MODE_RAMP]?.container?.setOnClickListener {
            if (viewModel.mode.value == MainViewModel.MODE_RAMP && viewModel.isVibrating.value) {
                viewModel.modeStop()
            } else {
                viewModel.modeRamp()
            }
        }
        tileMap[MainViewModel.MODE_WAVE]?.container?.setOnClickListener {
            if (viewModel.mode.value == MainViewModel.MODE_WAVE && viewModel.isVibrating.value) {
                viewModel.modeStop()
            } else {
                viewModel.modeWave()
            }
        }
        tileMap[MainViewModel.MODE_RANDOM]?.container?.setOnClickListener {
            if (viewModel.mode.value == MainViewModel.MODE_RANDOM && viewModel.isVibrating.value) {
                viewModel.modeStop()
            } else {
                viewModel.modeRandom()
            }
        }
        tileMap[MainViewModel.MODE_BURST]?.container?.setOnClickListener {
            if (viewModel.mode.value == MainViewModel.MODE_BURST && viewModel.isVibrating.value) {
                viewModel.modeStop()
            } else {
                viewModel.modeBurst()
            }
        }
        btnStop.setOnClickListener { viewModel.modeStop() }
        btnMore.setOnClickListener { viewModel.moreCadence() }
        btnLess.setOnClickListener { viewModel.minusCadence() }

    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.statusText.collectLatest { text ->
                statusText.text = text
            }
        }
        lifecycleScope.launch {
            viewModel.watchConnected.collectLatest { connected ->
                wearStatus.text = if (connected) "Connected" else "No watch connected"
            }
        }
        lifecycleScope.launch {
            viewModel.level.collectLatest { lvl ->
                val labels = arrayOf("Slow", "Medium", "Fast", "Very Fast")
                speedLabel.text = "Speed: ${labels[lvl.coerceIn(0, 3)]}"
            }
        }
        lifecycleScope.launch {
            viewModel.isVibrating.collectLatest { vibrating ->
                btnStop.isEnabled = vibrating
                btnStop.backgroundTintList = if (vibrating)
                    ColorStateList.valueOf(Color.parseColor("#e74c3c"))
                else
                    ColorStateList.valueOf(Color.parseColor("#2d3436"))
                btnStop.setTextColor(if (vibrating) Color.WHITE else Color.parseColor("#777777"))
            }
        }
        lifecycleScope.launch {
            viewModel.mode.collectLatest { mode ->
                highlightActiveMode(mode)
            }
        }
        lifecycleScope.launch {
            viewModel.level.collectLatest { level ->
                if (currentActiveMode != -1) {
                    tileMap[currentActiveMode]?.chart?.setPattern(currentActiveMode, level)
                    startTileAnimation(currentActiveMode, level)
                }
            }
        }
    }

    // ── Animation methods ──

    /** Exact cycle duration matching the watch VibratorEngine. */
    private fun getCycleMs(mode: Int, level: Int): Long {
        return when (mode) {
            MainViewModel.MODE_CONSTANT -> 1000L
            MainViewModel.MODE_INTERMITTENT -> {
                val (on, off) = when (level.coerceIn(0, 3)) {
                    0 -> 700L to 300L
                    1 -> 228L to 97L
                    2 -> 140L to 60L
                    3 -> 88L to 37L
                    else -> 700L to 300L
                }
                on + off  // single on-off pair, matching watch vibrate() cycle
            }
            MainViewModel.MODE_RAMP -> when (level.coerceIn(0, 3)) {
                0 -> 200L; 1 -> 130L; 2 -> 80L; 3 -> 50L; else -> 200L
            } * 5
            MainViewModel.MODE_BURST -> {
                val (tap, pause) = when (level.coerceIn(0, 3)) {
                    0 -> 140L to 300L
                    1 -> 90L to 195L
                    2 -> 55L to 120L
                    3 -> 35L to 75L
                    else -> 140L to 300L
                }
                tap * 5 + pause
            }
            MainViewModel.MODE_WAVE -> when (level.coerceIn(0, 3)) {
                0 -> 50L; 1 -> 32L; 2 -> 20L; 3 -> 12L; else -> 50L
            } * 20
            MainViewModel.MODE_RANDOM -> when (level.coerceIn(0, 3)) {
                0 -> 1000L; 1 -> 650L; 2 -> 400L; 3 -> 250L; else -> 1000L
            }
            else -> 1000L
        }
    }

    /** Return raw vibration amplitude (0 or 1, or fractional for wave/ramp)
     * at a given fraction through the cycle. Smoothing applied in the animator. */
    private fun getAmplitudeAtFraction(mode: Int, level: Int, fraction: Float): Float {
        val f = fraction - fraction.toInt().toFloat()
        return when (mode) {
            MainViewModel.MODE_CONSTANT -> 1.0f
            MainViewModel.MODE_INTERMITTENT -> {
                // duty cycle matches watch: on/(on+off)
                val duty = when (level.coerceIn(0, 3)) {
                    0 -> 0.700f  // 700/1000
                    1 -> 0.702f  // 228/325
                    2 -> 0.700f  // 140/200
                    3 -> 0.704f  // 88/125
                    else -> 0.700f
                }
                if (f < duty) 1f else 0f
            }
            MainViewModel.MODE_RAMP -> {
                val step = (f * 6f).toInt()
                if (step < 5) (step + 1f) / 5f else 0f
            }
            MainViewModel.MODE_BURST -> {
                // 3 taps at [0,.14) [.28,.42) [.56,.70), pause [.70,1.0)
                if (f < 0.14f) 1f
                else if (f < 0.28f) 0f
                else if (f < 0.42f) 1f
                else if (f < 0.56f) 0f
                else if (f < 0.70f) 1f
                else 0f
            }
            MainViewModel.MODE_WAVE -> {
                (Math.sin(-Math.PI / 2 + 2 * Math.PI * f).toFloat() + 1f) / 2f
            }
            MainViewModel.MODE_RANDOM -> {
                if (f < 0.5f) f * 2f else (1f - f) * 2f
            }
            else -> 0.5f
        }
    }

    private fun startTileAnimation(mode: Int, level: Int) {
        val tile = tileMap[mode] ?: return
        val periodMs = getCycleMs(mode, level)

        // Stop previous animation on same tile if restarting (level change)
        dotAnimators[mode]?.cancel()
        pulseRunnables[mode]?.cancel()

        // Show active state
        tile.title.visibility = View.GONE
        tile.activeContainer.visibility = View.VISIBLE
        tile.bg.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tile.activeColor))

        // Rotating dots animation
        val dotRotation = ObjectAnimator.ofFloat(tile.dots, "rotation", 0f, 360f).apply {
            duration = 2000L  // fixed 2s rotation, consistent across all modes
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        dotAnimators[mode] = dotRotation

        // Pulse animation — uses system clock for accurate timing
        val pulseStart = SystemClock.elapsedRealtime()
        val runner = PulseRunner(tile, mode, level, periodMs, pulseStart)
        pulseRunnables[mode] = runner
        mainHandler.post(runner)
    }

    private fun stopTileAnimation(mode: Int) {
        val tile = tileMap[mode] ?: return
        dotAnimators[mode]?.cancel()
        dotAnimators.remove(mode)
        pulseRunnables.remove(mode)?.cancel()

        tile.title.visibility = View.VISIBLE
        tile.activeContainer.visibility = View.GONE
        tile.bg.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tile.defaultColor))
        tile.pulseBackdrop.scaleX = 1f
        tile.pulseBackdrop.scaleY = 1f
        tile.center.scaleX = 1f
        tile.center.scaleY = 1f
        tile.dots.rotation = 0f
    }

    private fun highlightActiveMode(mode: Int) {
        // Stop animation on previously active tile
        if (currentActiveMode != -1 && currentActiveMode != mode) {
            stopTileAnimation(currentActiveMode)
        }

        // Reset all inactive tiles to their default colors
        for ((m, tile) in tileMap) {
            if (m != mode && m != currentActiveMode) {
                tile.bg.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tile.defaultColor))
            }
        }

        // Handle stop/pause
        if (mode == MainViewModel.MODE_STOP || mode == MainViewModel.MODE_PAUSE) {
            if (currentActiveMode != -1) {
                stopTileAnimation(currentActiveMode)
            }
            currentActiveMode = -1
            return
        }

        currentActiveMode = mode
        val level = viewModel.level.value
        tileMap[mode]?.chart?.setPattern(mode, level)
        startTileAnimation(mode, level)
    }

    // ── PulseRunner: time-based animation loop ──

    private inner class PulseRunner(
        private val tile: TileViews,
        private val mode: Int,
        private val level: Int,
        private val periodMs: Long,
        private val startTime: Long
    ) : Runnable {
        @Volatile var cancelled = false
        private var prevAmp = 0.3f

        override fun run() {
            if (cancelled) return
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val fraction = ((elapsed % periodMs).toFloat() / periodMs.toFloat())
            val target = getAmplitudeAtFraction(mode, level, fraction)
            // Light smoothing to avoid jitter, but fast enough for short cycles
            prevAmp = prevAmp + (target - prevAmp) * 0.9f
            val amp = prevAmp
            val f = 0.3f + amp * 0.7f
            tile.pulseBackdrop.alpha = f * 0.4f
            val scale = 1.0f + (f - 0.3f) * 0.5f
            tile.pulseBackdrop.scaleX = scale
            tile.pulseBackdrop.scaleY = scale
            tile.center.scaleX = 1.0f + (f - 0.3f) * 0.2f
            tile.center.scaleY = 1.0f + (f - 0.3f) * 0.2f
            mainHandler.postDelayed(this, 16L) // ~60 fps
        }

        fun cancel() {
            cancelled = true
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startHeartbeat()
        viewModel.startConnectionMonitor()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopHeartbeat()
        viewModel.stopConnectionMonitor()
    }

    override fun onDestroy() {
        super.onDestroy()
        for ((mode, _) in dotAnimators) {
            dotAnimators[mode]?.cancel()
        }
        for ((_, runner) in pulseRunnables) {
            runner.cancel()
        }
        pulseRunnables.clear()
        viewModel.modeStop()
    }
}
