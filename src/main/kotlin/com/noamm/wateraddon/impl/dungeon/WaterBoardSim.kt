package com.noamm.wateraddon.impl.dungeon

import com.github.noamm9.utils.dungeons.map.core.UniqueRoom

/**
 * Water simulation for the Water Board.
 *
 * Calibrated against a live 26.3 server trace:
 *  - one water tick equals 5 game ticks, for both falling and horizontal spread
 *  - falling water always carries a full block (8)
 *  - horizontal spread loses one level per block and stops when the level would reach 0
 *  - a cell only spreads sideways while the block below it is closed, and then only towards
 *    the side that reaches an opening within [SLOPE_DISTANCE] blocks (both sides if neither side
 *    qualifies, one side if only one does)
 *
 * Cells are stored in a flat [ByteArray], row major, starting at [WaterBoardGrid.MAX_Y].
 */
object WaterBoardSim {
    const val AIR = 0
    const val BLOCK = -1
    const val SOURCE = 9

    const val SLOPE_DISTANCE = 4

    const val WIDTH = WaterBoardGrid.WIDTH
    const val HEIGHT = WaterBoardGrid.HEIGHT

    const val FLOW_DOWN = 1
    const val FLOW_LEFT = 2
    const val FLOW_RIGHT = 4

    fun index(x: Int, y: Int) = (WaterBoardGrid.MAX_Y - y) * WIDTH + (x - WaterBoardGrid.MIN_X)

    /** Reads the board plane. Unloaded cells are treated as blocks. */
    fun read(room: UniqueRoom): ByteArray {
        val board = ByteArray(WIDTH * HEIGHT) { BLOCK.toByte() }
        for (yi in 0 until HEIGHT) {
            val y = WaterBoardGrid.MAX_Y - yi
            for (xi in 0 until WIDTH) {
                val x = WaterBoardGrid.MIN_X + xi
                val state = WaterBoardGrid.stateAt(room, x, y, WaterBoardGrid.PLANE_Z) ?: continue
                val i = yi * WIDTH + xi

                val fluid = state.fluidState
                board[i] = when {
                    WaterBoardGrid.isWater(fluid) ->
                        if (fluid.isSource) SOURCE.toByte() else WaterBoardGrid.waterAmount(state).toByte()
                    state.isAir -> AIR.toByte()
                    else -> BLOCK.toByte()
                }
            }
        }
        return board
    }

    /**
     * Static flow direction mask, derived from the block layout only.
     * Water can only move sideways where this mask says so.
     */
    fun flowMask(blocks: ByteArray): ByteArray {
        val mask = ByteArray(WIDTH * HEIGHT)
        for (yi in 0 until HEIGHT) {
            val y = WaterBoardGrid.MAX_Y - yi
            for (xi in 0 until WIDTH) {
                val i = yi * WIDTH + xi
                if (blocks[i].toInt() == BLOCK) continue

                if (belowOpen(blocks, xi, y)) {
                    mask[i] = FLOW_DOWN.toByte()
                    continue
                }

                val left = distanceToOpening(blocks, xi, y, -1)
                val right = distanceToOpening(blocks, xi, y, 1)
                var dirs = 0
                when {
                    left == NO_OPENING && right == NO_OPENING -> dirs = FLOW_LEFT or FLOW_RIGHT
                    left == NO_OPENING -> dirs = FLOW_RIGHT
                    right == NO_OPENING -> dirs = FLOW_LEFT
                    left < right -> dirs = FLOW_LEFT
                    right < left -> dirs = FLOW_RIGHT
                    else -> dirs = FLOW_LEFT or FLOW_RIGHT
                }
                mask[i] = dirs.toByte()
            }
        }
        return mask
    }

    /** Advances the board by one water tick. Returns true when anything changed. */
    fun step(cur: ByteArray, next: ByteArray, flow: ByteArray): Boolean {
        var changed = false

        for (yi in 0 until HEIGHT) {
            for (xi in 0 until WIDTH) {
                val i = yi * WIDTH + xi
                val v = cur[i].toInt()

                if (v == BLOCK) {
                    next[i] = BLOCK.toByte()
                    continue
                }
                if (v == SOURCE) {
                    next[i] = SOURCE.toByte()
                    continue
                }

                var level = if (v > 0) v - 1 else 0

                // fed from above
                if (yi > 0 && cur[i - WIDTH].toInt() > 0) level = 8
                // the decorative stream above the board feeds the top row
                if (yi == 0 && v > 0) level = 8

                // horizontal inflow: the neighbour's flow mask is relative to that neighbour
                if (xi > 0) {
                    val nv = cur[i - 1].toInt()
                    if (nv > 0 && (flow[i - 1].toInt() and FLOW_RIGHT) != 0) {
                        level = maxOf(level, (if (nv == SOURCE) 8 else nv) - 1)
                    }
                }
                if (xi < WIDTH - 1) {
                    val nv = cur[i + 1].toInt()
                    if (nv > 0 && (flow[i + 1].toInt() and FLOW_LEFT) != 0) {
                        level = maxOf(level, (if (nv == SOURCE) 8 else nv) - 1)
                    }
                }

                level = level.coerceIn(0, 8)
                next[i] = level.toByte()
                if (level != v) changed = true
            }
        }

        return changed
    }

    /** Runs the simulation until nothing changes or [maxTicks] is reached. */
    fun settle(board: ByteArray, flow: ByteArray, maxTicks: Int = 200): Int {
        val next = ByteArray(board.size)
        var ticks = 0
        while (ticks < maxTicks) {
            val changed = step(board, next, flow)
            System.arraycopy(next, 0, board, 0, board.size)
            ticks++
            if (! changed) break
        }
        return ticks
    }

    /** True when water reached the outlet column at [x] (the gate toggle trigger). */
    fun isDelivered(board: ByteArray, x: Int): Boolean =
        board[index(x, WaterBoardGrid.MIN_Y)].toInt() > 0

    private const val NO_OPENING = 999

    private fun belowOpen(blocks: ByteArray, xi: Int, y: Int): Boolean {
        if (y - 1 < WaterBoardGrid.MIN_Y) return true
        return blocks[index(WaterBoardGrid.MIN_X + xi, y - 1)].toInt() != BLOCK
    }

    private fun distanceToOpening(blocks: ByteArray, xi: Int, y: Int, step: Int): Int {
        for (k in 1 .. SLOPE_DISTANCE) {
            val nx = xi + step * k
            if (nx < 0 || nx >= WIDTH) return NO_OPENING
            if (blocks[index(WaterBoardGrid.MIN_X + nx, y)].toInt() == BLOCK) return NO_OPENING
            if (y - 1 < WaterBoardGrid.MIN_Y) return k
            if (blocks[index(WaterBoardGrid.MIN_X + nx, y - 1)].toInt() != BLOCK) return k
        }
        return NO_OPENING
    }
}
