package com.noamm.wateraddon.impl.dungeon

import com.github.noamm9.features.impl.dungeon.solvers.PuzzleSolvers
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.MathUtils.center
import com.github.noamm9.utils.MathUtils.toVec
import com.github.noamm9.utils.NumbersUtils.toFixed
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.render.world.Render3D.renderLine
import com.github.noamm9.utils.render.world.Render3D.renderString
import com.github.noamm9.utils.render.world.Render3D.renderTracer
import com.github.noamm9.utils.render.world.RenderContext
import net.minecraft.core.BlockPos

/**
 * On demand re-solve of the Water Board from its current state.
 *
 * The plan is a list of "click this lever at this tick" steps. Every step waits for the water to
 * settle, so the countdown is the earliest safe time: clicking later is harmless.
 */
object WaterBoardRecalc {
    /** One water tick equals five game ticks. */
    private const val TICKS_PER_WATER_TICK = 5

    private data class Step(val lever: String, val startTick: Long)

    private var steps: List<Step> = emptyList()
    private var baseTick: Long = - 1

    /** How many steps the player already clicked. Display advances only on real clicks, like the table solver. */
    private var progress = 0

    /** Tick at which the water switch was clicked; the timeline is anchored here once. */
    private var startedTick: Long = - 1

    /** Index of the water switch step. Everything up to and including it costs no wait. */
    private var waterIndex = - 1

    /** True while a recalculated plan is being displayed. The static table solver stays silent then. */
    val isActive get() = steps.isNotEmpty() && baseTick >= 0 && progress < steps.size

    /** Value [PuzzleSolvers.water] had before we silenced it. Null while not silenced. */
    private var savedTableSolver: Boolean? = null

    /**
     * The upstream table solver cannot be patched from here, so it is switched off for the duration
     * of the plan. Only ever touched when it was on: a user who keeps it disabled must not end up
     * with it enabled afterwards.
     */
    private fun silenceTableSolver() {
        if (savedTableSolver != null) return
        val original = PuzzleSolvers.water.value
        savedTableSolver = original
        if (original) PuzzleSolvers.water.value = false
    }

    private fun restoreTableSolver() {
        val original = savedTableSolver ?: return
        savedTableSolver = null
        if (PuzzleSolvers.water.value != original) PuzzleSolvers.water.value = original
    }

    fun tick() {
        // polled before the room check so the tail recording can finish even after leaving
        RecalcTrace.tick(DungeonListener.currentTime)

        val room = ScanUtils.currentRoom
        if (room == null || room.name != "Water Board") {
            clear()
            return
        }

        if (isActive && WaterBoardGrid.allGatesOpen(room)) {
            clear()
            return
        }

        RecalcTrace.tick(DungeonListener.currentTime)

        if (WaterAddonFeature.waterRecalc.value && WaterAddonFeature.waterRecalcKey.isPressed()) solve(room)
    }

    /** Called on a lever click. Advances the plan when the clicked lever is the expected one. */
    fun onInteract(pos: BlockPos) {
        if (! isActive) return
        val room = ScanUtils.currentRoom ?: return
        val step = steps.getOrNull(progress) ?: return
        if (leverPos(room, step.lever) != pos) return

        // the timeline starts running on the first click only
        val now = DungeonListener.currentTime
        if (progress == 0) RecalcTrace.onFirstClick(now)

        // everything before and including the water switch is free, so the timeline starts there
        if (step.lever == WaterBoardSolve.WATER_LEVER && startedTick < 0) startedTick = now

        RecalcTrace.onLeverClick(step.lever, now)

        progress++
        if (progress >= steps.size) clear()
    }

    private fun solve(room: UniqueRoom) {
        if (room.rotation == null) return
        // nothing to do when the puzzle is already finished
        if (WaterBoardGrid.allGatesOpen(room)) return

        // the simulation and the plan both assume a dry board: the first step is "turn the water on"
        if (WaterBoardGrid.isWaterLeverOn(room)) {
            ChatUtils.modMessage("&cTurn the water lever off first.")
            return
        }

        val board = WaterBoardSim.read(room)
        // row 0 is y = 82: it always holds the decorative stream at +/-1 and never dries,
        // so only the rows below it decide whether the board is actually empty
        val playable = board.copyOfRange(WaterBoardSim.WIDTH, board.size)
        if (playable.any { it.toInt() > 0 }) {
            ChatUtils.modMessage("&cWait for the board to drain.")
            return
        }

        // row 0 is excluded from the dry check, so a leftover source would make the "water"
        // action turn the stream OFF instead of ON. Normalise it: the lever is off, so it is dry.
        val sourceIndex = WaterBoardSim.index(WaterBoardGrid.SOURCE_X, WaterBoardGrid.MAX_Y)
        val sourceWasWet = board[sourceIndex].toInt() > 0
        if (sourceWasWet) board[sourceIndex] = WaterBoardSim.BLOCK.toByte()

        val masks = WaterBoardSolve.leverMasks(room)
        val gates = WaterBoardSolve.gateStates(room)

        val started = System.currentTimeMillis()
        val plan = WaterBoardSolve.solve(board, masks, gates)
        val took = System.currentTimeMillis() - started

        if (plan == null || ! plan.solved || plan.actions.isEmpty()) {
            clear()
            if (WaterAddonFeature.recordTrace.value) {
                val masksInfo = masks.entries.joinToString(" ") { "${it.key}=${it.value.size}" }
                val gatesInfo = gates.joinToString("") { if (it) "O" else "X" }
                ChatUtils.modMessage("&cNo solution (gates &f$gatesInfo&c, best ${plan?.openGates ?: 0}/5, srcWet &f$sourceWasWet&c, masks &f$masksInfo&c).")
            } else {
                ChatUtils.modMessage("&cNo solution found for this board.")
            }
            return
        }

        var elapsed = 0L
        val list = ArrayList<Step>(plan.actions.size)
        for (action in plan.actions) {
            if (action.lever != null) list.add(Step(action.lever, elapsed))
            elapsed += action.ticks.toLong() * TICKS_PER_WATER_TICK
        }

        steps = list
        waterIndex = list.indexOfFirst { it.lever == WaterBoardSolve.WATER_LEVER }
        progress = 0
        baseTick = DungeonListener.currentTime
        startedTick = - 1
        silenceTableSolver()

        if (WaterAddonFeature.recordTrace.value) {
            val sb = StringBuilder()
            sb.append("# plan ").append(list.size).append(" clicks, ").append((elapsed * 0.05).toFixed(1)).append("s\n")
            for (step in list) sb.append("# ").append((step.startTick * 0.05).toFixed(2)).append("s ").append(step.lever).append('\n')
            var index = 0
            for (action in plan.actions) {
                index++
                sb.append("# step ").append(index).append(' ').append(action.lever ?: "wait")
                    .append(" wait=").append((action.ticks * 0.25).toFixed(2))
                    .append("s (").append(action.ticks).append(" water ticks)\n")
            }
            sb.append("# masks ").append(masks.entries.joinToString(" ") { "${it.key}=${it.value.size}" }).append('\n')
            sb.append("# gates ").append(gates.joinToString("") { if (it) "O" else "X" }).append('\n')
            RecalcTrace.arm(sb.toString())
        }
        // one short line; the solve time is only useful while debugging
        val debugInfo = if (WaterAddonFeature.recordTrace.value) " (${took}ms)" else ""
        ChatUtils.modMessage("&a${list.size} clicks, ${(elapsed * 0.05).toFixed(1)}s$debugInfo.")
    }

    fun render(ctx: RenderContext) {
        if (! isActive) return
        if (! WaterAddonFeature.waterRecalc.value) {
            clear()
            return
        }
        val room = ScanUtils.currentRoom ?: return
        if (room.name != "Water Board") return

        val now = DungeonListener.currentTime
        val clickColor = WaterAddonFeature.currentClickColor.value
        val nextColor = WaterAddonFeature.nextColor.value

        // steps only leave the display when the player actually clicks the lever.
        // free steps before the water switch are always clickable, the rest follow the switch.
        val started = startedTick >= 0
        val anchorStart = steps.getOrNull(waterIndex)?.startTick ?: steps[progress].startTick
        val firstIndex = progress
        val pending = steps.subList(progress, steps.size).mapIndexedNotNull { offset, step ->
            val index = firstIndex + offset
            val remaining = when {
                waterIndex < 0 || index <= waterIndex -> 0L
                started -> startedTick + (step.startTick - anchorStart) - now
                else -> step.startTick - anchorStart
            }
            leverPos(room, step.lever)?.let { Triple(step, it, remaining) }
        }
        if (pending.isEmpty()) return

        val nextClick = pending.first()
        ctx.renderTracer(nextClick.second.toVec().center(), clickColor, 2.5f)

        val second = pending.getOrNull(1)
        if (second != null && second.second != nextClick.second) {
            ctx.renderLine(nextClick.second.toVec().center(), second.second.toVec().center(), nextColor, 1.5f)
        }

        val perLever = HashMap<String, Int>()
        for ((step, blockPos, remaining) in pending) {
            val stack = perLever.getOrDefault(step.lever, 0)
            perLever[step.lever] = stack + 1

            val text = if (remaining <= 0) "§a§lCLICK" else {
                val seconds = remaining * 0.05
                val color = if (seconds < 2) "§c" else if (seconds < 6) "§e" else "§a"
                "$color${seconds.toFixed(1)}s"
            }

            ctx.renderString(text, blockPos.toVec().add(0.5, stack * 0.5 + 1.5, 0.5), scale = 1.35f, phase = true)
        }
    }

    fun clear() {
        // keep recording for a while: after the last click the water still has to reach the gates
        RecalcTrace.beginTail(DungeonListener.currentTime)
        restoreTableSolver()
        if (steps.isEmpty()) return
        steps = emptyList()
        baseTick = - 1
        progress = 0
        startedTick = - 1
        waterIndex = - 1
    }

    private fun leverPos(room: UniqueRoom, name: String): BlockPos? =
        WaterBoardGrid.LEVERS[name]?.let { WaterBoardGrid.toReal(it, room) }
}
