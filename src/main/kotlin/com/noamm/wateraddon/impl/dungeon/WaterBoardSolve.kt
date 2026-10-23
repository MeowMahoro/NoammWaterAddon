package com.noamm.wateraddon.impl.dungeon

import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import net.minecraft.world.level.block.Blocks

/**
 * Searches a lever sequence that opens every gate, starting from whatever state the board is in.
 *
 * Verified in game: flipping a lever toggles every cell whose front (z) or back (z + 1) layer holds
 * that lever's block, and a lever only ever controls its own block type.
 *
 * Every action is followed by letting the water settle, so the effect of a flip is always visible
 * in the gate states. That makes the search signal monotone instead of a plateau.
 */
object WaterBoardSolve {
    /** Lever name to the block it controls. */
    val LEVER_BLOCKS = linkedMapOf(
        "gold" to Blocks.GOLD_BLOCK,
        "coal" to Blocks.COAL_BLOCK,
        "quartz" to Blocks.QUARTZ_BLOCK,
        "clay" to Blocks.TERRACOTTA,
        "diamond" to Blocks.DIAMOND_BLOCK,
        "emerald" to Blocks.EMERALD_BLOCK,
    )

    /** Pseudo lever: toggles the water source on the top row. */
    const val WATER_LEVER = "water"

    private const val MAX_SETTLE_TICKS = 240

    /**
     * Water ticks to keep waiting once the last gate flipped (4 = 1s). Trailing water that no
     * longer reaches a gate never matters for the plan, so waiting for full stability only
     * inflates the timers: measured traces show the board is already static by then.
     */
    private const val FLIP_IDLE_TICKS = 4

    /** Time a human needs to flip one lever and aim at the next (2 water ticks = 0.5s). */
    private const val CLICK_COST_TICKS = 2

    data class Action(val lever: String?, val ticks: Int)

    data class Plan(val actions: List<Action>, val waterTicks: Int, val openGates: Int) {
        val solved get() = openGates == 5
    }

    /** Cell indices each lever toggles. Read once per room; the set never changes. */
    fun leverMasks(room: UniqueRoom): Map<String, IntArray> {
        val masks = LEVER_BLOCKS.keys.associateWith { ArrayList<Int>(16) }
        for (yi in 0 until WaterBoardSim.HEIGHT) {
            val y = WaterBoardGrid.MAX_Y - yi
            for (xi in 0 until WaterBoardSim.WIDTH) {
                val x = WaterBoardGrid.MIN_X + xi
                val index = yi * WaterBoardSim.WIDTH + xi
                val front = WaterBoardGrid.stateAt(room, x, y, WaterBoardGrid.PLANE_Z)
                val behind = WaterBoardGrid.stateAt(room, x, y, WaterBoardGrid.PLANE_Z + 1)

                for ((name, block) in LEVER_BLOCKS) {
                    if (front?.block == block || behind?.block == block) masks.getValue(name).add(index)
                }
            }
        }
        return masks.mapValues { it.value.toIntArray() }
    }

    /** Gate open flags in [WaterBoardGrid.OUTLET_GATES] order. */
    fun gateStates(room: UniqueRoom): BooleanArray {
        val gates = BooleanArray(WaterBoardGrid.OUTLET_GATES.size)
        var slot = 0
        for ((_, name) in WaterBoardGrid.OUTLET_GATES) gates[slot++] = ! WaterBoardGrid.isGateClosed(room, name)
        return gates
    }

    /**
     * Beam search over `flip a lever, then let the water settle`.
     * Returns the best sequence found; check [Plan.solved] before using it.
     */
    fun solve(
        board: ByteArray,
        masks: Map<String, IntArray>,
        gateOpen: BooleanArray,
        beamWidth: Int = 16,
        maxActions: Int = 12,
        timeBudgetMs: Long = 1500,
    ): Plan? {
        val deadline = System.currentTimeMillis() + timeBudgetMs
        // levers without any cell in this layout do nothing at all
        val leverNames = masks.filterValues { it.isNotEmpty() }.keys.toList()

        var beam = listOf(Node(board.copyOf(), gateOpen.copyOf(), emptyList(), 0))
        var best = beam[0]

        for (step in 0 until maxActions) {
            if (System.currentTimeMillis() > deadline) break

            val next = ArrayList<Node>(beam.size * (leverNames.size + 1))
            for (node in beam) {
                for (name in leverNames) {
                    if (System.currentTimeMillis() > deadline) break
                    next.add(advance(node, name, masks.getValue(name)))
                }
                next.add(advance(node, WATER_LEVER, null))
            }
            if (next.isEmpty()) break

            next.sortWith(compareBy({ wrongGates(it.gates) }, { it.ticks }))
            if (wrongGates(next[0].gates) < wrongGates(best.gates)) best = next[0]
            if (wrongGates(next[0].gates) == 0) best = next[0]

            if (best.solved) break

            beam = ArrayList<Node>(beamWidth)
            val seen = HashSet<Long>(beamWidth * 8)
            for (node in next) {
                if (seen.add(node.key())) beam.add(node)
                if (beam.size >= beamWidth) break
            }
            if (beam.isEmpty()) break
        }

        if (best.actions.isEmpty()) return null
        return Plan(best.actions, best.ticks, 5 - wrongGates(best.gates))
    }

    private fun wrongGates(gates: BooleanArray): Int {
        var closed = 0
        for (open in gates) if (! open) closed++
        return closed
    }

    private fun advance(node: Node, lever: String?, cells: IntArray?): Node {
        var board = node.board.copyOf()
        val gates = node.gates.copyOf()

        if (lever == WATER_LEVER) {
            val index = WaterBoardSim.index(WaterBoardGrid.SOURCE_X, WaterBoardGrid.MAX_Y)
            board[index] = if (board[index].toInt() == WaterBoardSim.BLOCK) {
                WaterBoardSim.SOURCE.toByte()
            } else {
                WaterBoardSim.BLOCK.toByte()
            }
        } else if (lever != null && cells != null) {
            for (index in cells) {
                board[index] = if (board[index].toInt() == WaterBoardSim.BLOCK) {
                    WaterBoardSim.AIR.toByte()
                } else {
                    WaterBoardSim.BLOCK.toByte()
                }
            }
        }

        val flow = WaterBoardSim.flowMask(board)
        val next = ByteArray(board.size)
        var ticks = 0
        var lastFlipTick = - 1
        var afterFlip: ByteArray? = null

        while (ticks < MAX_SETTLE_TICKS) {
            val before = board.copyOf()
            val changed = WaterBoardSim.step(board, next, flow)
            System.arraycopy(next, 0, board, 0, board.size)
            if (detectFlips(before, board, gates) > 0) {
                lastFlipTick = ticks + 1
                afterFlip = board.copyOf()
            }
            ticks++

            if (! changed) {
                if (ticks == 1) ticks = 0 // nothing moved: the flip costs no time
                break
            }
        }

        // trailing water never reaches another gate: stop a short while after the last flip
        if (afterFlip != null && ticks > lastFlipTick + FLIP_IDLE_TICKS) {
            board = afterFlip
            ticks = lastFlipTick
            repeat(FLIP_IDLE_TICKS) {
                WaterBoardSim.step(board, next, flow)
                System.arraycopy(next, 0, board, 0, board.size)
                ticks++
            }
        }

        // every action now also costs the time a human needs to actually flip the lever
        ticks += CLICK_COST_TICKS
        return Node(board, gates, node.actions + Action(lever, ticks), node.ticks + ticks)
    }

    /** A gate toggles the moment water appears on its outlet cell. Returns how many flipped. */
    private fun detectFlips(before: ByteArray, after: ByteArray, gates: BooleanArray): Int {
        var slot = 0
        var flips = 0
        for ((x, _) in WaterBoardGrid.OUTLET_GATES) {
            val index = WaterBoardSim.index(x, WaterBoardGrid.MIN_Y)
            if (before[index].toInt() <= 0 && after[index].toInt() > 0) {
                gates[slot] = ! gates[slot]
                flips++
            }
            slot++
        }
        return flips
    }

    private class Node(
        val board: ByteArray,
        val gates: BooleanArray,
        val actions: List<Action>,
        val ticks: Int,
    ) {
        val solved get() = gates.all { it }

        fun key(): Long {
            var hash = 1125899906842597L
            for (value in board) hash = 31 * hash + value
            for (open in gates) hash = 31 * hash + if (open) 1 else 0
            return hash
        }
    }
}
