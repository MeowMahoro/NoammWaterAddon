package com.noamm.wateraddon.impl.dungeon

import com.github.noamm9.features.FeatureManager
import net.fabricmc.api.ClientModInitializer

/**
 * Fabric instantiates entrypoints itself, so this must be a class: a Kotlin object has a
 * private constructor and the default language adapter cannot create it.
 */
class WaterAddonInit: ClientModInitializer {
    override fun onInitializeClient() {
        // the upstream scanner only walks com.github.noamm9, so the feature is registered manually
        FeatureManager.features.add(WaterAddonFeature)
        WaterAddonFeature.initialize()
    }
}
