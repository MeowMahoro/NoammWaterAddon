package com.noamm.wateraddon.impl.dungeon

import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

/**
 * Geometry and block access for the Water Board puzzle.
 *
 * Every coordinate in here is "grid space": room center relative and unrotated.
 * Use [toReal] before touching the world, exactly like the rest of the room data.
 *
 * The numeric constants are the expected layout and are validated in game with
 * `/na waterboard dump`. If the dump shows an offset, only the constants in this object need adjusting.
 */
object WaterBoardGrid {
    // Board plane: 19 columns wide, 25 rows tall. Row 82 holds the water source block.
    const val MIN_X = -9
    const val MAX_X = 9
    const val MIN_Y = 58
    const val MAX_Y = 82
    const val PLANE_Z = 11

    const val WIDTH = MAX_X - MIN_X + 1
    const val HEIGHT = MAX_Y - MIN_Y + 1

    /** The five columns where water leaves the board at [MIN_Y]. */
    val OUTLETS: List<Int> = listOf(-9, -5, 0, 5, 9)

    /** Outlet column to the gate it feeds. Verified in game from the wool markers next to each hole. */
    val OUTLET_GATES: Map<Int, String> = linkedMapOf(
        -9 to "red",
        -5 to "green",
        0 to "blue",
        5 to "orange",
        9 to "purple",
    )

    /** Column of the water source on [MAX_Y]. */
    const val SOURCE_X = 0

    /** Room center relative lever positions. */
    val LEVERS: Map<String, BlockPos> = linkedMapOf(
        "water" to BlockPos(0, 60, -10),
        "quartz" to BlockPos(5, 61, 5),
        "gold" to BlockPos(5, 61, 0),
        "coal" to BlockPos(5, 61, -5),
        "diamond" to BlockPos(-5, 61, 5),
        "emerald" to BlockPos(-5, 61, 0),
        "clay" to BlockPos(-5, 61, -5),
    )

    /** Room center relative gate positions. A gate is closed while a wool block sits here. */
    val GATES: List<Pair<String, BlockPos>> = listOf(
        "purple" to BlockPos(0, 56, 4),
        "orange" to BlockPos(0, 56, 3),
        "blue" to BlockPos(0, 56, 2),
        "green" to BlockPos(0, 56, 1),
        "red" to BlockPos(0, 56, 0),
    )

    /** Converts a grid space position into a world position. Null while the room rotation is unknown. */
    fun toReal(rel: BlockPos, room: UniqueRoom): BlockPos? {
        val rotation = room.rotation ?: return null
        return ScanUtils.getRealCoord(rel, room.centerPos, 360 - rotation)
    }

    /** Block state at a grid space position, or null when the chunk is not loaded yet. */
    fun stateAt(room: UniqueRoom, x: Int, y: Int, z: Int): BlockState? {
        val pos = toReal(BlockPos(x, y, z), room) ?: return null
        if (! WorldUtils.isChunkLoaded(pos.x, pos.z)) return null
        return WorldUtils.getStateAt(pos)
    }

    fun stateAt(room: UniqueRoom, rel: BlockPos): BlockState? = stateAt(room, rel.x, rel.y, rel.z)

    fun blockId(state: BlockState?): String =
        state?.let { BuiltInRegistries.BLOCK.getKey(it.block).toString() } ?: "unloaded"

    /** True when the gate at [name] is still closed (wool block present). */
    fun isGateClosed(room: UniqueRoom, name: String): Boolean {
        val rel = GATES.firstOrNull { it.first == name }?.second ?: return false
        val state = stateAt(room, rel) ?: return false
        return ! state.isAir
    }

    /** True once every gate is open, meaning the puzzle is solved. */
    fun allGatesOpen(room: UniqueRoom): Boolean = GATES.all { ! isGateClosed(room, it.first) }

    /** True while any water exists on the board plane. */
    fun hasWater(room: UniqueRoom): Boolean {
        for (y in MIN_Y .. MAX_Y) for (x in MIN_X .. MAX_X) {
            val state = stateAt(room, x, y, PLANE_Z) ?: continue
            if (isWater(state.fluidState)) return true
        }
        return false
    }

    fun isWater(fluid: FluidState): Boolean =
        ! fluid.isEmpty && (fluid.type == Fluids.WATER || fluid.type == Fluids.FLOWING_WATER)

    /**
     * Water amount 1..8.
     * [BlockStateProperties.LEVEL] is 0 for a source/full block, 1..7 for decreasing flow
     * and 8..15 for falling water, which always carries a full block.
     */
    fun waterAmount(state: BlockState): Int {
        val level = if (state.hasProperty(BlockStateProperties.LEVEL)) {
            state.getValue(BlockStateProperties.LEVEL)
        } else 0
        return if (level >= 8) 8 else 8 - level
    }

    /**
     * Compact board snapshot used by the trace recorder.
     * Row order is top to bottom, so the first [WIDTH] chars are [MAX_Y].
     */
    fun snapshot(room: UniqueRoom): String {
        val sb = StringBuilder(WIDTH * HEIGHT)
        for (y in MAX_Y downTo MIN_Y) for (x in MIN_X .. MAX_X) {
            val state = stateAt(room, x, y, PLANE_Z)
            sb.append(
                when {
                    state == null -> '?'
                    isWater(state.fluidState) -> if (state.fluidState.isSource) 'S' else ('0' + waterAmount(state))
                    state.isAir -> '.'
                    else -> '#'
                }
            )
        }
        return sb.toString()
    }

    /** One char per gate in [GATES] order: X = closed, . = open. */
    fun gateString(room: UniqueRoom): String = buildString {
        // iterate the outlet order so the string lines up with the plan's gate array
        for ((_, name) in OUTLET_GATES) {
            append(if (isGateClosed(room, name)) 'X' else '.')
        }
    }

    /** True while the lever that starts the water stream is flipped on. */
    fun isWaterLeverOn(room: UniqueRoom): Boolean {
        val rel = LEVERS["water"] ?: return false
        val state = stateAt(room, rel) ?: return false
        return state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)
    }

    /** One char per lever in [LEVERS] order: 1 = powered, 0 = not powered. */
    fun leverString(room: UniqueRoom): String = buildString {
        for ((_, rel) in LEVERS) {
            val state = stateAt(room, rel)
            val powered = state != null &&
                state.hasProperty(BlockStateProperties.POWERED) &&
                state.getValue(BlockStateProperties.POWERED)
            append(if (powered) '1' else '0')
        }
    }

    /**
     * Text dump used to validate the constants in game.
     * Block types are rendered through a per-section palette so the file stays readable
     * even though the board mixes glass, wool, clay and the six lever blocks.
     */
    fun dump(room: UniqueRoom): String {
        val rotation = room.rotation
        val sb = StringBuilder()
        val padX = 3

        sb.appendLine("=== NoammAddons :: Water Board dump ===")
        sb.appendLine("room      : ${room.name}")
        sb.appendLine("center    : ${room.centerPos}")
        sb.appendLine("rotation  : $rotation")
        sb.appendLine("clayPos   : ${room.clayPos}")
        sb.appendLine("grid      : x ${MIN_X}..${MAX_X}  y ${MIN_Y}..${MAX_Y}  z $PLANE_Z")

        if (rotation == null) {
            sb.appendLine("rotation not resolved yet, aborting.")
            return sb.toString()
        }

        sb.appendLine()
        sb.appendLine("plane summary (board size ${WIDTH}x${HEIGHT}):")
        for (z in PLANE_Z - 2 .. PLANE_Z + 2) {
            var solid = 0
            var water = 0
            var air = 0
            for (y in MIN_Y .. MAX_Y) for (x in MIN_X .. MAX_X) {
                val state = stateAt(room, x, y, z) ?: continue
                if (isWater(state.fluidState)) water++ else if (state.isAir) air++ else solid++
            }
            sb.appendLine("  relZ=$z absZ=${toReal(BlockPos(0, 0, z), room)?.z} solid=$solid air=$air water=$water")
        }

        for (z in PLANE_Z - 2 .. PLANE_Z + 2) {
            sb.appendLine()
            sb.appendLine("--- vertical plane relZ=$z  (rows = absolute Y, columns = relX ${MIN_X - padX}..${MAX_X + padX}) ---")
            sb.appendLine("      " + axis(MIN_X - padX, MAX_X + padX))
            val palette = Palette()
            for (y in MAX_Y + 3 downTo MIN_Y - 6) {
                val row = StringBuilder()
                for (x in MIN_X - padX .. MAX_X + padX) row.append(cellChar(room, x, y, z, palette))
                sb.appendLine("y=%-3d %s".format(y, row.toString()))
            }
            sb.appendLine("      palette: " + palette.legend())
        }

        for (y in listOf(56, 57, 58, 59, 60)) {
            sb.appendLine()
            sb.appendLine("--- horizontal slice y=$y  (rows = relZ ${PLANE_Z - 8}..${PLANE_Z + 8}, columns = relX ${MIN_X - padX}..${MAX_X + padX}) ---")
            sb.appendLine("      " + axis(MIN_X - padX, MAX_X + padX))
            val palette = Palette()
            for (z in PLANE_Z + 8 downTo PLANE_Z - 8) {
                val row = StringBuilder()
                for (x in MIN_X - padX .. MAX_X + padX) row.append(cellChar(room, x, y, z, palette))
                sb.appendLine("z=%-4d %s".format(z, row.toString()))
            }
            sb.appendLine("      palette: " + palette.legend())
        }

        sb.appendLine()
        sb.appendLine("outlet columns (relX .. vertical scan y=${MIN_Y + 4}..${MIN_Y - 4} at relZ=$PLANE_Z):")
        for (x in OUTLETS) {
            sb.append("  relX=%-3d ".format(x))
            for (y in MIN_Y + 4 downTo MIN_Y - 4) {
                val state = stateAt(room, x, y, PLANE_Z)
                sb.append(
                    when {
                        state == null -> '?'
                        isWater(state.fluidState) -> 'W'
                        state.isAir -> '.'
                        else -> '#'
                    }
                )
            }
            sb.appendLine("  (y=${MIN_Y + 4}..${MIN_Y - 4})")
        }

        sb.appendLine()
        sb.appendLine("entrance column (relX=0 relZ=$PLANE_Z):")
        for (y in MAX_Y + 3 downTo MAX_Y - 6) {
            val state = stateAt(room, 0, y, PLANE_Z)
            sb.appendLine("  y=$y ${cellChar(room, 0, y, PLANE_Z, null)} ${blockId(state)}")
        }

        sb.appendLine()
        sb.appendLine("levers:")
        for ((name, rel) in LEVERS) {
            val state = stateAt(room, rel)
            val powered = if (state != null && state.hasProperty(BlockStateProperties.POWERED)) {
                state.getValue(BlockStateProperties.POWERED).toString()
            } else "n/a"
            sb.appendLine("  $name rel=$rel real=${toReal(rel, room)} block=${blockId(state)} powered=$powered")
        }

        sb.appendLine()
        sb.appendLine("gates:")
        for ((name, rel) in GATES) {
            val state = stateAt(room, rel)
            val above = stateAt(room, rel.x, rel.y + 1, rel.z)
            sb.appendLine(
                "  $name rel=$rel real=${toReal(rel, room)} block=${blockId(state)} " +
                "| y+1 block=${blockId(above)} closed=${! (state?.isAir ?: true)}"
            )
        }

        sb.appendLine()
        sb.appendLine("hasWater=${
            runCatching { hasWater(room) }.getOrElse { "error: ${it.message}" }
        }")

        return sb.toString()
    }

    private fun axis(minX: Int, maxX: Int): String {
        val sb = StringBuilder()
        for (x in minX .. maxX) sb.append(
            when {
                x == 0 -> '0'
                x % 5 == 0 -> '|'
                else -> '-'
            }
        )
        return sb.toString()
    }

    /** Renders one cell. Water is always a digit (level) or S (source); everything else goes through [palette]. */
    private fun cellChar(room: UniqueRoom, x: Int, y: Int, z: Int, palette: Palette?): Char {
        val state = stateAt(room, x, y, z) ?: return '?'

        val fluid = state.fluidState
        if (isWater(fluid)) {
            if (fluid.isSource) return 'S'
            return ('0' + waterAmount(state))
        }

        if (state.isAir) return '.'

        val id = BuiltInRegistries.BLOCK.getKey(state.block).path
        return palette?.charOf(id) ?: '#'
    }

    private class Palette {
        private val chars = LinkedHashMap<String, Char>()

        fun charOf(id: String): Char = chars.getOrPut(id) {
            PALETTE[chars.size % PALETTE.length]
        }

        fun legend(): String = chars.entries.joinToString("  ") { (id, c) -> "$c=$id" }

        companion object {
            private const val PALETTE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        }
    }
}
