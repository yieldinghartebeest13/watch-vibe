package com.example.vibecontrol

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
    private val pulseAnimators = mutableMapOf<Int, ValueAnimator>()
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
        setupListeners()
        observeViewModel()

        viewModel.checkWearConnection()
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
            viewModel.wearConnected.collectLatest { connected ->
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
        // Restart animation when level changes and a mode is active
        lifecycleScope.launch {
            viewModel.level.collectLatest { level ->
                if (currentActiveMode != -1) {
                    startTileAnimation(currentActiveMode, level)
                }
            }
        }
    }

    // ── Animation methods ──

    private fun getBeatPeriodMs(mode: Int, level: Int): Long {
        return when (mode) {
            MainViewModel.MODE_CONSTANT -> 5000L
            MainViewModel.MODE_INTERMITTENT -> when (level) {
                0 -> 1000L
                1 -> 500L
                2 -> 250L
                3 -> 150L
                else -> 1000L
            }
            MainViewModel.MODE_RAMP -> when (level) {
                0 -> 2000L
                1 -> 1300L
                2 -> 800L
                3 -> 500L
                else -> 2000L
            }
            MainViewModel.MODE_WAVE -> when (level) {
                0 -> 315L
                1 -> 220L
                2 -> 150L
                3 -> 98L
                else -> 315L
            }
            MainViewModel.MODE_RANDOM -> when (level) {
                0 -> 550L
                1 -> 360L
                2 -> 205L
                3 -> 116L
                else -> 550L
            }
            MainViewModel.MODE_BURST -> when (level) {
                0 -> 1150L
                1 -> 850L
                2 -> 650L
                3 -> 500L
                else -> 1150L
            }
            else -> 1000L
        }
    }

    private fun startTileAnimation(mode: Int, level: Int) {
        val tile = tileMap[mode] ?: return
        val periodMs = getBeatPeriodMs(mode, level)

        // Stop previous animation on same tile if restarting (level change)
        dotAnimators[mode]?.cancel()
        pulseAnimators[mode]?.cancel()

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

        // Pulse animation for backdrop and center
        val pulseAnim = ValueAnimator.ofFloat(0.3f, 1.0f, 0.3f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                tile.pulseBackdrop.alpha = fraction * 0.4f
                val scale = 1.0f + (fraction - 0.3f) * 0.5f
                tile.pulseBackdrop.scaleX = scale
                tile.pulseBackdrop.scaleY = scale
                tile.center.scaleX = 1.0f + (fraction - 0.3f) * 0.2f
                tile.center.scaleY = 1.0f + (fraction - 0.3f) * 0.2f
            }
            start()
        }
        pulseAnimators[mode] = pulseAnim
    }

    private fun stopTileAnimation(mode: Int) {
        val tile = tileMap[mode] ?: return
        dotAnimators[mode]?.cancel()
        pulseAnimators[mode]?.cancel()
        dotAnimators.remove(mode)
        pulseAnimators.remove(mode)

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
        startTileAnimation(mode, level)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all animators
        for ((mode, _) in dotAnimators) {
            stopTileAnimation(mode)
        }
        viewModel.modeStop()
    }
}
