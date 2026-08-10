package com.yieldinghartebeest13.watchvibe

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StatsActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var statsSessionCount: TextView
    private lateinit var statsTotalTime: TextView
    private lateinit var statsAvgTime: TextView
    private lateinit var statsModeList: LinearLayout
    private lateinit var statsSessionList: LinearLayout
    private var statsWindow: Int = 0

    // Mode colors (matching tile colors)
    private val modeColors = mapOf(
        AppConstants.MODE_CONSTANT to "#b04a4a",
        AppConstants.MODE_INTERMITTENT to "#b08a4a",
        AppConstants.MODE_RAMP to "#5a9a9a",
        AppConstants.MODE_BURST to "#8b6bab",
        AppConstants.MODE_WAVE to "#5a8aaa",
        AppConstants.MODE_RANDOM to "#5a9a6a"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        viewModel = ViewModelProvider(
            this,
            SavedStateViewModelFactory(application, this)
        ).get(MainViewModel::class.java)

        statsSessionCount = findViewById(R.id.statsSessionCount)
        statsTotalTime = findViewById(R.id.statsTotalTime)
        statsAvgTime = findViewById(R.id.statsAvgTime)
        statsModeList = findViewById(R.id.statsModeList)
        statsSessionList = findViewById(R.id.statsSessionList)

        findViewById<TextView>(R.id.statsBackBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.statsTabWeek).setOnClickListener { selectWindow(0) }
        findViewById<TextView>(R.id.statsTabMonth).setOnClickListener { selectWindow(1) }
        findViewById<TextView>(R.id.statsTabYear).setOnClickListener { selectWindow(2) }

        observeStats()
        viewModel.refreshStats()
    }

    private fun selectWindow(window: Int) {
        statsWindow = window
        val tabs = listOf(
            findViewById<TextView>(R.id.statsTabWeek),
            findViewById<TextView>(R.id.statsTabMonth),
            findViewById<TextView>(R.id.statsTabYear)
        )
        tabs.forEachIndexed { i, tab ->
            tab.setTextColor(Color.parseColor(if (i == window) "#a0a0b0" else "#777777"))
        }
        applyStats()
    }

    private fun observeStats() {
        lifecycleScope.launch {
            viewModel.weeklyStats.collectLatest { if (statsWindow == 0) applyStats() }
        }
        lifecycleScope.launch {
            viewModel.monthlyStats.collectLatest { if (statsWindow == 1) applyStats() }
        }
        lifecycleScope.launch {
            viewModel.yearlyStats.collectLatest { if (statsWindow == 2) applyStats() }
        }
    }

    private fun applyStats() {
        val stats = when (statsWindow) {
            0 -> viewModel.weeklyStats.value
            1 -> viewModel.monthlyStats.value
            2 -> viewModel.yearlyStats.value
            else -> return
        }

        // Summary cards
        statsSessionCount.text = stats.sessionCount.toString()

        val hours = stats.totalDurationMs / 3600_000L
        val minutes = (stats.totalDurationMs % 3600_000L) / 60_000L
        statsTotalTime.text = when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }

        val avgMs = if (stats.sessionCount > 0)
            stats.totalDurationMs / stats.sessionCount else 0L
        val avgMin = avgMs / 60_000L
        val avgSec = (avgMs % 60_000L) / 1000L
        statsAvgTime.text = if (avgMin > 0) "${avgMin}m ${avgSec}s" else "${avgSec}s"

        // Mode breakdown
        buildModeBars(stats)

        // Session list
        buildSessionList(stats)
    }

    private fun buildModeBars(stats: StatsDb.MergedStats) {
        statsModeList.removeAllViews()
        val maxDur = stats.modeBreakdown.maxOfOrNull { it.totalDurationMs } ?: 1L
        if (maxDur == 0L) return

        for (entry in stats.modeBreakdown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val name = TextView(this).apply {
                text = AppConstants.MODE_LABELS[entry.mode] ?: "?"
                setTextColor(Color.parseColor("#c0c0d0"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val durText = formatDuration(entry.totalDurationMs)
            val count = TextView(this).apply {
                text = "$durText · ${entry.sessionCount} runs"
                setTextColor(Color.parseColor("#808090"))
                textSize = 12f
            }
            labelRow.addView(name)
            labelRow.addView(count)
            row.addView(labelRow)

            // Progress bar
            val barContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(4)
                ).apply { topMargin = dp(4) }
                setBackgroundColor(Color.parseColor("#2a2a3e"))
            }
            val pct = (entry.totalDurationMs.toFloat() / maxDur.toFloat())
            val fill = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    pct
                )
                setBackgroundColor(Color.parseColor(modeColors[entry.mode] ?: "#5a9a9a"))
            }
            barContainer.addView(fill)
            // Fill remaining space with transparent
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f - pct
                )
            }
            barContainer.addView(spacer)
            row.addView(barContainer)
            statsModeList.addView(row)
        }
    }

    private fun buildSessionList(stats: StatsDb.MergedStats) {
        statsSessionList.removeAllViews()

        if (stats.sessions.isEmpty()) {
            statsSessionList.addView(TextView(this).apply {
                text = "No sessions yet"
                setTextColor(Color.parseColor("#606070"))
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            })
            return
        }

        for (session in stats.sessions.take(30)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.parseColor("#1e1e35"))
            }

            val modeDot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    topMargin = dp(5)
                    marginEnd = dp(8)
                }
                setBackgroundColor(Color.parseColor(modeColors[session.dominantMode] ?: "#5a9a9a"))
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val modeLabel = AppConstants.MODE_LABELS[session.dominantMode] ?: "?"
            val runInfo = if (session.runCount > 1) " · ${session.runCount} runs" else ""
            infoCol.addView(TextView(this).apply {
                text = "$modeLabel$runInfo"
                setTextColor(Color.parseColor("#c0c0d0"))
                textSize = 13f
            })
            infoCol.addView(TextView(this).apply {
                text = formatDuration(session.totalDurationMs)
                setTextColor(Color.parseColor("#808090"))
                textSize = 11f
            })

            row.addView(modeDot)
            row.addView(infoCol)
            statsSessionList.addView(row)
        }
    }

    private fun formatDuration(ms: Long): String {
        val h = ms / 3600_000L
        val m = (ms % 3600_000L) / 60_000L
        val s = (ms % 60_000L) / 1000L
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
