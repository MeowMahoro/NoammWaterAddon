package com.noamm.wateraddon.impl.dungeon

import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import java.io.File

/**
 * Full recording of a recalculated solution: armed when a plan is produced, starts on the first
 * lever click, records every server tick, keeps running for a while after the last click (water
 * still has to reach the gates), then writes everything to a file.
 *
 * Used to compare the simulation against reality when a plan does not open the expected gates.
 */
object RecalcTrace {
    private const val MAX_RECORD_TICKS = 20 * 180

    /** Ticks recorded after the last planned click, so late gate flips are captured too. */
    private const val TAIL_TICKS = 20 * 15

    private val file = File("config/NoammWaterAddon/waterboard-recalc-trace.txt")

    private val header = StringBuilder()
    private val body = StringBuilder()
    private var armed = false
    private var recording = false
    private var startTick = 0L
    private var tailUntil = - 1L
    private var lastTick = - 1L

    fun arm(plan: String) {
        header.setLength(0)
        body.setLength(0)
        header.append(plan)
        armed = true
        recording = false
        tailUntil = - 1L
    }

    fun onFirstClick(tick: Long) {
        if (! armed || recording) return
        recording = true
        startTick = tick
        body.append("# recording started at tick $tick\n")
        ChatUtils.modMessage("&e[Trace] recording started")
    }

    fun onLeverClick(name: String, tick: Long) {
        if (! recording) return
        body.append("# CLICK $name at t=${tick - startTick}\n")
    }

    /** Called when the plan is done: keep recording for [TAIL_TICKS] so the water can arrive. */
    fun beginTail(now: Long) {
        if (! recording) {
            finish()
            return
        }
        if (tailUntil > 0) return
        tailUntil = now + TAIL_TICKS
        body.append("# last click at t=${now - startTick}, tail for ${TAIL_TICKS / 20}s\n")
    }

    fun tick(now: Long) {
        if (! recording) return
        // the event can fire twice within one game tick, keep a single frame per tick
        if (now == lastTick) return
        lastTick = now
        if (tailUntil > 0 && now >= tailUntil) {
            finish()
            return
        }

        val elapsed = now - startTick
        if (elapsed > MAX_RECORD_TICKS) {
            finish()
            return
        }

        val room = ScanUtils.currentRoom ?: return
        if (room.name != "Water Board" || room.rotation == null) return

        val gates = WaterBoardGrid.gateString(room)
        val levers = WaterBoardGrid.leverString(room)
        val board = WaterBoardGrid.snapshot(room)
        body.append("t=$elapsed gates=$gates levers=$levers $board\n")
    }

    fun finish() {
        if (! armed) return
        val wasRecording = recording
        armed = false
        recording = false

        if (! wasRecording) return

        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(header.toString() + body.toString())
        }.onSuccess {
            ChatUtils.modMessage("&a[Trace] written to &f${file.absolutePath} &7(${body.length / 1024} KB)")
        }.onFailure {
            ChatUtils.modMessage("&c[Trace] failed to write: ${it.message}")
        }
    }
}
