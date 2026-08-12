package com.yieldinghartebeest13.watchvibe

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var statusText: TextView
    private lateinit var wearStatus: TextView
    private lateinit var batteryRow: LinearLayout
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryText: TextView
    private lateinit var btnStats: ImageView
    private lateinit var btnSettings: ImageView
    private var unlockedInSession = false
    private var lockRequestInProgress = false
    private var skipNextLock = false
    private lateinit var btnStop: Button
    private lateinit var btnMore: Button
    private lateinit var btnLess: Button
    private lateinit var speedLabel: TextView
    private val dotAnimators = mutableMapOf<Int, ObjectAnimator>()
    private val pulseRunnables = mutableMapOf<Int, PulseRunner>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActiveMode: Int = -1
    private lateinit var tileMap: Map<Int, TileViews>

    // ── Data-driven tile configuration ─────────────────────

    private data class TileConfig(
        val mode: Int,
        val label: String,
        val defaultColor: String,
        val activeColor: String,
        val containerId: Int,
        val bgId: Int,
        val titleId: Int,
        val activeContainerId: Int,
        val dotsId: Int,
        val pulseBackdropId: Int,
        val centerId: Int,
        val chartId: Int? // null for Random (no chart)
    )

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

    private val tileConfigs = listOf(
        TileConfig(AppConstants.MODE_CONSTANT, "Constant", "#8b6b6b", "#b04a4a",
            R.id.tileConstant, R.id.tileConstantBg, R.id.tileConstantTitle,
            R.id.tileConstantActive, R.id.tileConstantDots,
            R.id.tileConstantPulseBackdrop, R.id.tileConstantCenter,
            R.id.tileConstantChart),
        TileConfig(AppConstants.MODE_INTERMITTENT, "Intermittent", "#8b7442", "#b08a4a",
            R.id.tileIntermittent, R.id.tileIntermittentBg, R.id.tileIntermittentTitle,
            R.id.tileIntermittentActive, R.id.tileIntermittentDots,
            R.id.tileIntermittentPulseBackdrop, R.id.tileIntermittentCenter,
            R.id.tileIntermittentChart),
        TileConfig(AppConstants.MODE_RAMP, "Ramp", "#4a7a7a", "#5a9a9a",
            R.id.tileRamp, R.id.tileRampBg, R.id.tileRampTitle,
            R.id.tileRampActive, R.id.tileRampDots,
            R.id.tileRampPulseBackdrop, R.id.tileRampCenter,
            R.id.tileRampChart),
        TileConfig(AppConstants.MODE_BURST, "Burst", "#6b5b8b", "#8b6bab",
            R.id.tileBurst, R.id.tileBurstBg, R.id.tileBurstTitle,
            R.id.tileBurstActive, R.id.tileBurstDots,
            R.id.tileBurstPulseBackdrop, R.id.tileBurstCenter,
            R.id.tileBurstChart),
        TileConfig(AppConstants.MODE_WAVE, "Wave", "#4a6a8a", "#5a8aaa",
            R.id.tileWave, R.id.tileWaveBg, R.id.tileWaveTitle,
            R.id.tileWaveActive, R.id.tileWaveDots,
            R.id.tileWavePulseBackdrop, R.id.tileWaveCenter,
            R.id.tileWaveChart),
        TileConfig(AppConstants.MODE_RANDOM, "Random", "#4a7a5a", "#5a9a6a",
            R.id.tileRandom, R.id.tileRandomBg, R.id.tileRandomTitle,
            R.id.tileRandomActive, R.id.tileRandomDots,
            R.id.tileRandomPulseBackdrop, R.id.tileRandomCenter,
            null)
    )

    private var uiReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Stealth check — apply FLAG_SECURE early to prevent recents leak,
        // but don't launch LockActivity here. Defer to onResume() to avoid
        // a race where onResume fires before LockActivity is visible,
        // consuming the lock-request flag prematurely and causing a double lock.
        val prefs = getSharedPreferences("stealth_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("stealth_enabled", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val pinHash = prefs.getString("pin_hash", null)
            if (pinHash != null) {
                return  // UI setup deferred to onResume after unlock
            }
        }

        setupUi()
    }

    private fun setupUi() {
        if (uiReady) return
        uiReady = true
        setContentView(R.layout.activity_main)

        viewModel = androidx.lifecycle.ViewModelProvider(
            this,
            androidx.lifecycle.SavedStateViewModelFactory(application, this)
        ).get(MainViewModel::class.java)

        statusText = findViewById(R.id.statusText)
        wearStatus = findViewById(R.id.wearStatus)
        batteryRow = findViewById(R.id.batteryRow)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryText = findViewById(R.id.batteryText)
        btnStats = findViewById(R.id.btnStats)
        btnSettings = findViewById(R.id.btnSettings)
        btnSettings.setOnClickListener {
            viewModel.suppressNextMinimize()
            skipNextLock = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnStop = findViewById(R.id.btnStop)
        btnMore = findViewById(R.id.btnMore)
        btnLess = findViewById(R.id.btnLess)
        speedLabel = findViewById(R.id.speedLabel)

        buildTileMap()
        for (cfg in tileConfigs) {
            cfg.chartId?.let { id ->
                (findViewById<WaveformView>(id))?.setPattern(cfg.mode, 0)
            }
        }
        setupListeners()
        observeViewModel()
    }

    private fun buildTileMap() {
        tileMap = tileConfigs.associate { cfg ->
            val chart: WaveformView? = cfg.chartId?.let { findViewById(it) }
            cfg.mode to TileViews(
                container = findViewById(cfg.containerId),
                bg = findViewById(cfg.bgId),
                title = findViewById(cfg.titleId),
                activeContainer = findViewById(cfg.activeContainerId),
                dots = findViewById(cfg.dotsId),
                pulseBackdrop = findViewById(cfg.pulseBackdropId),
                center = findViewById(cfg.centerId),
                chart = chart,
                defaultColor = cfg.defaultColor,
                activeColor = cfg.activeColor
            )
        }
    }

    private fun setupListeners() {
        btnStats.setOnClickListener {
            viewModel.suppressNextMinimize()
            skipNextLock = true
            startActivity(android.content.Intent(this, StatsActivity::class.java))
        }
        for (cfg in tileConfigs) {
            tileMap[cfg.mode]?.container?.setOnClickListener {
                if (viewModel.mode.value == cfg.mode && viewModel.isVibrating.value) {
                    viewModel.modeStop()
                } else {
                    // Dispatch to the appropriate mode setter
                    when (cfg.mode) {
                        AppConstants.MODE_CONSTANT -> viewModel.modeConstant()
                        AppConstants.MODE_INTERMITTENT -> viewModel.modeIntermittent()
                        AppConstants.MODE_RAMP -> viewModel.modeRamp()
                        AppConstants.MODE_WAVE -> viewModel.modeWave()
                        AppConstants.MODE_RANDOM -> viewModel.modeRandom()
                        AppConstants.MODE_BURST -> viewModel.modeBurst()
                    }
                }
            }
        }
        btnStop.setOnClickListener { viewModel.modeStop() }
        btnMore.setOnClickListener { viewModel.moreCadence() }
        btnLess.setOnClickListener { viewModel.minusCadence() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.statusText.collectLatest { text -> statusText.text = text }
        }
        lifecycleScope.launch {
            viewModel.watchConnected.collectLatest { connected ->
                wearStatus.text = if (connected) "Connected" else "No watch connected"
            }
        }
        lifecycleScope.launch {
            viewModel.watchBatteryLevel.collectLatest { level ->
                updateBatteryDisplay(level)
            }
        }
        lifecycleScope.launch {
            viewModel.watchBatteryPending.collectLatest { pending ->
                // Re-apply display when pending state changes
                updateBatteryDisplay(viewModel.watchBatteryLevel.value)
            }
        }
        lifecycleScope.launch {
            viewModel.level.collectLatest { lvl ->
                speedLabel.text = "Speed: ${AppConstants.SPEED_LABELS[lvl.coerceIn(0, 3)]}"
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
            viewModel.mode.collectLatest { mode -> highlightActiveMode(mode) }
        }
        lifecycleScope.launch {
            viewModel.level.collectLatest { level ->
                if (currentActiveMode != -1) {
                    tileMap[currentActiveMode]?.chart?.setPattern(currentActiveMode, level)
                    startTileAnimation(currentActiveMode, level)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.crownExitRequested.collectLatest { requested ->
                if (requested) {
                    moveTaskToBack(true)
                    viewModel.onCrownExitHandled()
                }
            }
        }
    }

    // ── Battery display ──────────────────────────────────

    private fun updateBatteryDisplay(level: Int) {
        val pending = viewModel.watchBatteryPending.value

        if (pending) {
            batteryIcon.setColorFilter(Color.parseColor("#606070"))  // dim placeholder
            batteryText.text = "--%"
            batteryText.setTextColor(Color.parseColor("#606070"))
            return
        }

        if (level < 0 || level > 100) {
            batteryIcon.setColorFilter(Color.parseColor("#606070"))
            batteryText.text = "--%"
            batteryText.setTextColor(Color.parseColor("#606070"))
            return
        }

        batteryText.text = "$level%"

        val color = when {
            level <= 15 -> Color.parseColor("#e74c3c")  // red
            level <= 30 -> Color.parseColor("#f39c12")  // orange
            else -> Color.parseColor("#a0a0b0")          // neutral
        }
        batteryIcon.setColorFilter(color)
        batteryText.setTextColor(color)
    }

    // ── Animation methods ──────────────────────────────────

    private fun getCycleMs(mode: Int, level: Int): Long {
        return when (mode) {
            AppConstants.MODE_CONSTANT -> 1000L
            AppConstants.MODE_INTERMITTENT -> {
                val (on, off) = when (level.coerceIn(0, 3)) {
                    0 -> 700L to 300L; 1 -> 228L to 97L
                    2 -> 140L to 60L; 3 -> 88L to 37L
                    else -> 700L to 300L
                }
                on + off
            }
            AppConstants.MODE_RAMP -> when (level.coerceIn(0, 3)) {
                0 -> 200L; 1 -> 130L; 2 -> 80L; 3 -> 50L; else -> 200L
            } * 5
            AppConstants.MODE_BURST -> {
                val (tap, pause) = when (level.coerceIn(0, 3)) {
                    0 -> 150L to 250L; 1 -> 100L to 200L
                    2 -> 70L to 150L;  3 -> 50L to 120L
                    else -> 150L to 250L
                }
                tap * 5 + pause
            }
            AppConstants.MODE_WAVE -> when (level.coerceIn(0, 3)) {
                0 -> 50L; 1 -> 32L; 2 -> 20L; 3 -> 12L; else -> 50L
            } * 20
            AppConstants.MODE_RANDOM -> when (level.coerceIn(0, 3)) {
                0 -> 1000L; 1 -> 650L; 2 -> 400L; 3 -> 250L; else -> 1000L
            }
            else -> 1000L
        }
    }

    private fun getAmplitudeAtFraction(mode: Int, level: Int, fraction: Float): Float {
        val f = fraction - fraction.toInt().toFloat()
        return when (mode) {
            AppConstants.MODE_CONSTANT -> 1.0f
            AppConstants.MODE_INTERMITTENT -> {
                val duty = when (level.coerceIn(0, 3)) {
                    0 -> 0.700f; 1 -> 0.702f; 2 -> 0.700f; 3 -> 0.704f
                    else -> 0.700f
                }
                if (f < duty) 1f else 0f
            }
            AppConstants.MODE_RAMP -> {
                val step = (f * 6f).toInt()
                if (step < 5) (step + 1f) / 5f else 0f
            }
            AppConstants.MODE_BURST -> {
                if (f < 0.14f) 1f else if (f < 0.28f) 0f
                else if (f < 0.42f) 1f else if (f < 0.56f) 0f
                else if (f < 0.70f) 1f else 0f
            }
            AppConstants.MODE_WAVE -> {
                (Math.sin(-Math.PI / 2 + 2 * Math.PI * f).toFloat() + 1f) / 2f
            }
            AppConstants.MODE_RANDOM -> {
                if (f < 0.5f) f * 2f else (1f - f) * 2f
            }
            else -> 0.5f
        }
    }

    private fun startTileAnimation(mode: Int, level: Int) {
        val tile = tileMap[mode] ?: return
        val periodMs = getCycleMs(mode, level)

        dotAnimators[mode]?.cancel()
        pulseRunnables[mode]?.cancel()

        tile.title.visibility = View.GONE
        tile.activeContainer.visibility = View.VISIBLE
        tile.bg.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tile.activeColor))

        val dotRotation = ObjectAnimator.ofFloat(tile.dots, "rotation", 0f, 360f).apply {
            duration = 2000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        dotAnimators[mode] = dotRotation

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
        if (currentActiveMode != -1 && currentActiveMode != mode) {
            stopTileAnimation(currentActiveMode)
        }
        for ((m, tile) in tileMap) {
            if (m != mode && m != currentActiveMode) {
                tile.bg.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tile.defaultColor))
            }
        }
        if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
            if (currentActiveMode != -1) stopTileAnimation(currentActiveMode)
            currentActiveMode = -1
            return
        }
        currentActiveMode = mode
        val level = viewModel.level.value
        tileMap[mode]?.chart?.setPattern(mode, level)
        startTileAnimation(mode, level)
    }

    // ── PulseRunner ────────────────────────────────────────

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
            prevAmp = prevAmp + (target - prevAmp) * 0.9f
            val amp = prevAmp
            val f = 0.3f + amp * 0.7f
            tile.pulseBackdrop.alpha = f * 0.4f
            val scale = 1.0f + (f - 0.3f) * 0.5f
            tile.pulseBackdrop.scaleX = scale
            tile.pulseBackdrop.scaleY = scale
            tile.center.scaleX = 1.0f + (f - 0.3f) * 0.2f
            tile.center.scaleY = 1.0f + (f - 0.3f) * 0.2f
            mainHandler.postDelayed(this, 16L)
        }

        fun cancel() { cancelled = true }
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) setupUi()

        // Keep FLAG_SECURE in sync with stealth setting
        val prefs = getSharedPreferences("stealth_prefs", MODE_PRIVATE)
        val stealthEnabled = prefs.getBoolean("stealth_enabled", false)
        if (stealthEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        val wasLocking = lockRequestInProgress
        lockRequestInProgress = false
        if (wasLocking) {
            unlockedInSession = true
        }
        if (!unlockedInSession && !skipNextLock) {
            val pinHash = prefs.getString("pin_hash", null)
            if (stealthEnabled && pinHash != null && !viewModel.isVibrating.value) {
                lockRequestInProgress = true
                startActivity(Intent(this, LockActivity::class.java))
                return
            }
            unlockedInSession = true
        }
        skipNextLock = false
        viewModel.onForeground()
    }

    override fun onPause() {
        super.onPause()
        // Don't call onBackground() when transitioning to the lock screen —
        // the connection hasn't started yet and we don't want to send
        // a spurious /minimize to the watch before the user has even unlocked.
        if (!lockRequestInProgress) {
            viewModel.onBackground()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!skipNextLock) {
            unlockedInSession = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        for ((mode, _) in dotAnimators) dotAnimators[mode]?.cancel()
        for ((_, runner) in pulseRunnables) runner.cancel()
        pulseRunnables.clear()
        // Stop vibration and heartbeat when the user explicitly closes the app.
        // modeStop() sends STOP to watch; onCleared() will do final cleanup.
        viewModel.modeStop()
        viewModel.stopHeartbeat()
        viewModel.stopConnectionMonitor()
    }
}
