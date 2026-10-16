package com.dfdx047.dragonrage.engine

import android.util.Log

/**
 * The game's activity's line to the engine's loader (libmain.so, src/main/cpp/loader.c): lines for the game's own
 * log, and the engine's state the activity follows. Only in the game's process; in the launcher's, where nothing is
 * loaded, every call falls back quietly.
 */
object EngineNative {
    @JvmStatic private external fun nativeLog(text: String)
    @JvmStatic private external fun nativeInterp(): Int

    /** A line in bt3_log.txt (what Debug and Export log read) and in logcat. */
    fun log(text: String) {
        runCatching { nativeLog("bt3: $text") }.onFailure { Log.i("bt3", text) }
    }

    /** Smooth motion as the engine has it now (0 off .. 3), -1 when not known yet. */
    fun interp(): Int = runCatching { nativeInterp() }.getOrDefault(-1)
}
