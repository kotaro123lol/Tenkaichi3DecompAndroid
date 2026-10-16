package com.dfdx047.dragonrage.engine

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.Locale

/**
 * The device's heat while the game runs, in the game's log next to its speed lines: a fight that slows down after
 * ten minutes is usually the chip being throttled, and a report needs to show it. Only written down; nothing is
 * changed because of it (the resolution stays the player's).
 *   - the thermal status (Android 10+): each change, from "none" to "shutdown"
 *   - the headroom (Android 11+), every [PERIOD_MS]: 0.0 cool .. 1.0 where the system starts to throttle, as forecast
 *     ten seconds ahead (asked no more often than this: the system answers NaN to calls closer than a second apart)
 * On with the performance log (setting perf_log, on by default).
 */
class ThermalLog(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val status = PowerManager.OnThermalStatusChangedListener { s ->
        EngineNative.log("thermal: status ${statusName(s)}")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val h = power?.getThermalHeadroom(10) ?: Float.NaN
                if (!h.isNaN()) EngineNative.log(String.format(Locale.ROOT, "thermal: headroom %.2f (1.00 = throttling)", h))
            }
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    fun start() {
        if (running || power == null) return
        running = true
        power.addThermalStatusListener(status) // (also reports the current status at once)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handler.postDelayed(tick, PERIOD_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        runCatching { power?.removeThermalStatusListener(status) }
    }

    private fun statusName(s: Int): String = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe (throttling)"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> s.toString()
    }

    private companion object {
        const val PERIOD_MS = 10_000L
    }
}
