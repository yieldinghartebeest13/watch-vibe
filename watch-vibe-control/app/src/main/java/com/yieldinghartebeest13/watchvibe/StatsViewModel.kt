package com.yieldinghartebeest13.watchvibe

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only screen state: statistics must never own or stop a vibration session. */
class StatsViewModel(application: Application) : AndroidViewModel(application) {
    data class Snapshot(
        val week: StatsDb.MergedStats,
        val month: StatsDb.MergedStats,
        val year: StatsDb.MergedStats
    )

    private val empty = StatsDb.MergedStats(0, 0L, emptyList(), emptyList())
    private val _stats = MutableStateFlow(Snapshot(empty, empty, empty))
    val stats = _stats.asStateFlow()
    private var refreshJob: Job? = null

    fun refreshStats() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            _stats.value = withContext(Dispatchers.IO) {
                // Query and close on the same thread; screen destruction cannot
                // close a handle beneath an in-flight SQLite query.
                StatsDb(getApplication()).use { db ->
                    Snapshot(db.mergedQuery(now - 7 * 24 * 3600_000L),
                        db.mergedQuery(now - 30 * 24 * 3600_000L),
                        db.mergedQuery(now - 365 * 24 * 3600_000L))
                }
            }
        }
    }
}
