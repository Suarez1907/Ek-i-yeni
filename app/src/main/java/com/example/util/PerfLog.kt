package com.example.util

import android.util.Log
import com.example.BuildConfig

/**
 * Debug derlemeleri için hafif performans ölçüm günlüğü.
 *
 * Güvenlik ve Gizlilik Kuralları:
 * - Yalnızca `BuildConfig.DEBUG && ENABLED` koşulu sağlandığında log yazar.
 * - ASLA URL, çerez (`Cookie`, `cf_clearance`) veya API anahtarı içermez; sadece milisaniye süreleri yazar.
 */
object PerfLog {
    const val TAG = "EksinPerf"
    const val ENABLED = true

    fun logDuration(metricName: String, durationMs: Long) {
        if (BuildConfig.DEBUG && ENABLED) {
            Log.d(TAG, "$metricName completed in ${durationMs}ms")
        }
    }

    fun logEvent(eventName: String, durationMs: Long) {
        if (BuildConfig.DEBUG && ENABLED) {
            Log.d(TAG, "$eventName: ${durationMs}ms")
        }
    }
}
