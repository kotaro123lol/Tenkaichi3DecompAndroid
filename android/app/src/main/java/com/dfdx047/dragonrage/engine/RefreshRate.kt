package com.dfdx047.dragonrage.engine

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import java.util.Locale
import kotlin.math.abs

/**
 * The screen's refresh rate while the game runs. The game shows 30 pictures a second in a fight (60 in the menus;
 * 60 or 120 with smooth motion), and a screen left at a rate that is no multiple of that (144 Hz on the Xiaomi Pad 7,
 * 90 Hz on many phones) shows some pictures for one refresh longer than others: an uneven picture (judder) at a
 * steady 30. So the game's surface asks for a rate it divides evenly: Surface.setFrameRate with FIXED_SOURCE (the
 * content has its own rate, a video's kind of vote, which the system honours over its own "smooth" policies), and the
 * window asks for a display mode at that rate as well, for systems that weigh setFrameRate little or lack it (API 29).
 *
 * [setting] (dr_refresh): 0 from smooth motion (off or 60 -> 60 Hz, 120 or 240 -> 120 Hz), 1 60 Hz, 2 120 Hz,
 * 3 nothing is asked (the system's own choice, as before).
 */
class RefreshRate(private val activity: Activity, private val setting: Int) {
    private val handler = Handler(Looper.getMainLooper())
    private var interp = -1
    private var asked = 0f
    private var surface: Surface? = null
    private var lastSeen = 0f

    /** The rate wanted for smooth motion [interp] (the engine's, 0..3), or 0 for none. */
    private fun wanted(interp: Int): Float = when (setting) {
        3 -> 0f
        1 -> 60f
        2 -> 120f
        else -> if (interp >= 2) 120f else 60f
    }

    /** The game's surface was made or changed (SDLSurface's callbacks): it gets the vote again (a new surface has none). */
    fun onSurface(s: Surface?) {
        surface = s
        if (s == null) return // (gone: the next one asks again)
        asked = 0f
        apply()
    }

    /** Smooth motion as the engine has it now (polled; the in-game menu changes it while the game runs). */
    fun follow(engineInterp: Int) {
        if (engineInterp < 0 || engineInterp == interp) return
        interp = engineInterp
        apply()
    }

    fun start(initialInterp: Int) {
        interp = initialInterp
        if (setting == 3) {
            EngineNative.log("refresh: left to the system (dr_refresh 3); the screen runs at ${rateNow()} Hz")
            return
        }
        (activity.getSystemService(DisplayManager::class.java))?.registerDisplayListener(displays, handler)
    }

    fun stop() {
        (activity.getSystemService(DisplayManager::class.java))?.unregisterDisplayListener(displays)
        handler.removeCallbacksAndMessages(null)
    }

    private fun apply() {
        val rate = wanted(interp)
        if (rate == 0f || rate == asked) return
        asked = rate
        val how = mutableListOf<String>()
        val s = surface
        if (s != null && s.isValid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    s.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                } else {
                    s.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                }
            }
            how += if (ok.isSuccess) "setFrameRate" else "setFrameRate failed (${ok.exceptionOrNull()?.message})"
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            how += "no setFrameRate before Android 11"
        }
        val mode = modeFor(rate)
        if (mode != null) {
            val attrs = activity.window.attributes
            if (attrs.preferredDisplayModeId != mode.modeId) {
                attrs.preferredDisplayModeId = mode.modeId
                activity.window.attributes = attrs
            }
            how += "display mode ${mode.modeId} (${mode.physicalWidth}x${mode.physicalHeight} ${hz(mode.refreshRate)} Hz)"
        } else {
            how += "no display mode at $rate Hz"
        }
        EngineNative.log("refresh: asked ${rate.toInt()} Hz (dr_refresh $setting, smooth motion $interp): ${how.joinToString(", ")}")
        // (the switch takes the system a moment: what the screen really runs at, once it has settled)
        handler.postDelayed({ EngineNative.log("refresh: the screen runs at ${rateNow()} Hz (asked ${rate.toInt()})") }, 1500)
    }

    /** The screen's mode at [rate] in its current resolution (a mode of another resolution would rescale the picture). */
    private fun modeFor(rate: Float): Display.Mode? {
        val d = display() ?: return null
        val cur = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && abs(it.refreshRate - rate) < 1f }
            .minByOrNull { abs(it.refreshRate - rate) }
    }

    private fun display(): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay
    }

    private fun hz(f: Float): String = String.format(Locale.ROOT, "%.1f", f)

    private fun rateNow(): String = display()?.refreshRate?.let { hz(it) } ?: "?"

    /** Every change of the screen's rate while the game runs (the system or another app may change it again). */
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            val d = display() ?: return
            if (d.displayId != displayId) return
            val r = d.refreshRate
            if (abs(r - lastSeen) >= 0.5f) {
                lastSeen = r
                EngineNative.log("refresh: the screen now runs at ${hz(r)} Hz")
            }
        }

        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }
}
