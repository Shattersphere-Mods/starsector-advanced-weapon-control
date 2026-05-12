package com.dp.advancedgunnerycontrol.combatgui.agccombatgui

import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.dp.advancedgunnerycontrol.typesandvalues.defaultShipMode
import com.dp.advancedgunnerycontrol.typesandvalues.loadShipModes
import com.dp.advancedgunnerycontrol.typesandvalues.saveShipModes
import com.fs.starfarer.api.combat.ShipAPI
import org.magiclib.combatgui.buttongroups.MagicCombatDataButtonGroup
import org.magiclib.combatgui.buttongroups.MagicCombatRefreshButtonsAction

class RefreshShipAiButtons(private val ship: ShipAPI) : MagicCombatRefreshButtonsAction {
    override fun refreshButtons(group: MagicCombatDataButtonGroup) {
        val modes = loadShipModes(ship, Values.storageIndex)
        if (modes.isEmpty()) {
            val defaultModes = listOf(defaultShipMode)
            saveShipModes(ship, Values.storageIndex, defaultModes)
            group.refreshAllButtons(defaultModes)
        } else {
            group.refreshAllButtons(modes)
        }
    }
}
