package com.dp.advancedgunnerycontrol.gui.actions

import com.dp.advancedgunnerycontrol.gui.AGCGUI.Companion.storageIndex
import com.dp.advancedgunnerycontrol.gui.CustomListModalMode
import com.dp.advancedgunnerycontrol.gui.GUIAttributes
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.Values
import org.lwjgl.input.Keyboard

class CycleLoadoutAction(attributes: GUIAttributes) : GUIAction(attributes) {
    override fun execute() {
        cycle(1)
    }

    override fun supportsRightClick(): Boolean = true

    override fun executeRightClick(): Boolean {
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            attributes.clearCustomListModalState()
            attributes.customListModalMode = CustomListModalMode.RENAME_LOADOUT
            attributes.customListDraftValues["loadoutIndex"] = storageIndex.toString()
            attributes.customListDraftValues["loadoutName"] = Settings.loadoutDisplayName(storageIndex)
            return true
        }
        cycle(-1)
        return true
    }

    private fun cycle(delta: Int) {
        val maxLoadouts = Settings.maxLoadouts().coerceAtLeast(1)
        storageIndex = (storageIndex + delta + maxLoadouts) % maxLoadouts
        Values.storageIndex = storageIndex
    }

    override fun getTooltip(): String {
        return "Switch fleet loadouts. Left-click for next; right-click for previous. Each loadout stores weapon tags and ship modes separately, " +
                "so you can keep different setups for different enemy fleets." +
                "\nLoadout names can be edited in Settings.editme." +
                "\nNote: loadouts are fleet-wide, not per ship."
    }

    override fun getName(): String = "Cycle loadout [" +
            "${storageIndex + 1} / ${Settings.maxLoadouts()}] <${
                Settings.loadoutDisplayName(storageIndex)
            }>"

    override fun getStableLayoutName(): String = "Cycle loadout [1 / ${Settings.maxLoadouts()}] <Normal>"
}
