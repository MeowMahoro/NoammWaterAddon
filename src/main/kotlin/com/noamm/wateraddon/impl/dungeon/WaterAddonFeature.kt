package com.noamm.wateraddon.impl.dungeon

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.enums.CategoryType
import java.awt.Color

/**
 * Settings holder and event wiring for the addon.
 *
 * Registered manually because the upstream class scanner only walks its own package.
 */
object WaterAddonFeature: Feature(
    description = "Solves the Water Board puzzle from its current state.",
    name = "Water Board Addon",
    toggled = true,
) {
    override val category = CategoryType.DUNGEON

    val currentClickColor by ColorSetting("Click Color", Color.GREEN)
    val nextColor by ColorSetting("Next Click Color", Color.YELLOW)

    val waterRecalc by ToggleSetting("Recalculate Solver", true)
    val waterRecalcKey by KeybindSetting("Recalculate Shortcut").showIf { waterRecalc.value }

    val recordTrace by ToggleSetting("Debug: Record Recalc Trace")
        .withDescription("Records every tick of a recalculated plan to config/NoammWaterAddon/waterboard-recalc-trace.txt")
        .showIf { waterRecalc.value }

    override fun onDisable() {
        super.onDisable()
        WaterBoardRecalc.clear()
    }

    override fun init() {
        register<RenderWorldEvent> { WaterBoardRecalc.render(event.ctx) }
        register<TickEvent.Server> { WaterBoardRecalc.tick() }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> { WaterBoardRecalc.onInteract(event.pos) }
    }
}
