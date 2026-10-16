package com.dfdx047.dragonrage.engine

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build

/**
 * What the game is doing, told to Android's Game Mode (GameManager.setGameState, Android 13+): in a fight the system
 * keeps the clocks and the touch sampling up and may hold notifications back; in the menus it does not need to. A
 * fight is MODE_GAMEPLAY_INTERRUPTIBLE (it can be paused), an online one MODE_GAMEPLAY_UNINTERRUPTIBLE (the other
 * player's game goes on), the menus MODE_NONE. The signal is the engine's own "the menus run" (gPortInMenus), the one
 * the on-screen controller's automatic layout follows, polled with it ([update], ten times a second; a call to the
 * system only when the state changes).
 *
 * [enabled]: setting dr_game_state (1 default).
 */
class GameModeState(context: Context, private val enabled: Boolean) {
    private val manager: GameManager? =
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.getSystemService(GameManager::class.java) else null
    private var last = -1

    /** Once at start: whether it is used, and the Game Mode the player picked in the system's dashboard. */
    fun start() {
        when {
            !enabled -> EngineNative.log("game mode: state not reported (dr_game_state 0)")
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> EngineNative.log("game mode: not available (Android API ${Build.VERSION.SDK_INT}; it needs 33)")
            manager == null -> EngineNative.log("game mode: no GameManager on this device")
            else -> EngineNative.log("game mode: ${modeName(manager.gameMode)}; the game's state is reported")
        }
    }

    fun update() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val gm = manager ?: return
        val menus = EngineNative.inMenus()
        if (menus < 0) return // (the engine not loaded yet)
        // (online is asked only in a fight: by then the engine has long set up its network side on its own thread)
        val mode = when {
            menus != 0 -> GameState.MODE_NONE
            EngineNative.online() -> GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
            else -> GameState.MODE_GAMEPLAY_INTERRUPTIBLE
        }
        if (mode == last) return
        last = mode
        runCatching { gm.setGameState(GameState(false, mode)) }
            .onSuccess { EngineNative.log("game mode: state ${stateName(mode)}") }
            .onFailure { EngineNative.log("game mode: setGameState failed: ${it.message}") }
    }

    private fun stateName(m: Int): String = when (m) {
        GameState.MODE_NONE -> "menus"
        GameState.MODE_GAMEPLAY_INTERRUPTIBLE -> "fight"
        GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE -> "online fight"
        else -> m.toString()
    }

    private fun modeName(m: Int): String = when (m) {
        GameManager.GAME_MODE_STANDARD -> "standard"
        GameManager.GAME_MODE_PERFORMANCE -> "performance"
        GameManager.GAME_MODE_BATTERY -> "battery"
        GameManager.GAME_MODE_CUSTOM -> "custom"
        GameManager.GAME_MODE_UNSUPPORTED -> "unsupported"
        else -> m.toString()
    }
}
