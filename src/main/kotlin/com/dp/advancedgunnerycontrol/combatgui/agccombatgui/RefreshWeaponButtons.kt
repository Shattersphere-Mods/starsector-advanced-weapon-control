package com.dp.advancedgunnerycontrol.combatgui.agccombatgui

import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.dp.advancedgunnerycontrol.typesandvalues.createTag
import com.dp.advancedgunnerycontrol.typesandvalues.getTagTooltip
import com.dp.advancedgunnerycontrol.typesandvalues.isIncompatibleWithExistingTags
import com.dp.advancedgunnerycontrol.utils.loadTags
import com.fs.starfarer.api.combat.ShipAPI
import org.magiclib.combatgui.buttongroups.MagicCombatDataButtonGroup
import org.magiclib.combatgui.buttongroups.MagicCombatRefreshButtonsAction

class RefreshWeaponButtons(private val ship: ShipAPI, private val index: Int) : MagicCombatRefreshButtonsAction {
    override fun refreshButtons(group: MagicCombatDataButtonGroup) {
        val currentTags = loadTags(ship, index, Values.storageIndex)
        group.refreshAllButtons(currentTags)
        group.enableAllButtons()
        group.buttons.forEach {
            it.info.tooltip.txt = getTagTooltip(it.info.txt)
            val str = it.data as? String ?: ""
            var (isInvalid, invalidityReason) = isIncompatibleWithExistingTags(str, currentTags)
            if(!isInvalid) {
                isInvalid = false == ship.weaponGroupsCopy.getOrNull(index)?.weaponsCopy?.any { w ->
                    createTag(str, w)?.isValid() == true
                }
                invalidityReason = "$str is invalid (for these weapons)"
            }
            if(it.isActive && isInvalid){
                it.isActive = false
                group.executeAction(listOf(), null, it.data)
            }
            if(isInvalid){
                it.isDisabled = true
                it.info.tooltip.txt += "\n>>$invalidityReason<<"
            }
        }
    }
}