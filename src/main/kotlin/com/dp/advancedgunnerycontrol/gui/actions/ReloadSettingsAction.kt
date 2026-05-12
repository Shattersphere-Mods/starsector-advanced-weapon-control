package com.dp.advancedgunnerycontrol.gui.actions

import com.dp.advancedgunnerycontrol.gui.AGCGUI
import com.dp.advancedgunnerycontrol.gui.GUIAttributes
import com.dp.advancedgunnerycontrol.gui.ShipViewHotTagCache
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.TagListView
import com.dp.advancedgunnerycontrol.typesandvalues.Values

class ReloadSettingsAction(attributes: GUIAttributes) : GUIAction(attributes) {
    override fun execute() {
        Settings.loadSettings()
        AGCGUI.storageIndex = Values.storageIndex
        ShipViewHotTagCache.invalidate()
        attributes.tagView = TagListView()
    }

    override fun getTooltip(): String = "Reload Settings.editme from disk without restarting the game. This does not write to or override LunaSettings; LunaSettings values stay controlled by LunaLib and are already hotloaded automatically."

    override fun requiresConfirmation(): Boolean = true

    override fun getName(): String = "Reload Settings.EDITME"
}
