package com.dp.advancedgunnerycontrol.weaponais.shipais

import com.dp.advancedgunnerycontrol.weaponais.determineUniversalShipTarget
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.ext.minus
import kotlin.math.max

class StayAwayAI(ship: ShipAPI) : ShipCommandGenerator(ship) {

    companion object{
        const val SCANNING_RANGE = 2500f
        const val MAX_ENGAGEMENT_RANGE = 1500f
    }

    private fun computeIdealEngagementRange(): Float{
        return maxOf(
            MAX_ENGAGEMENT_RANGE,
            ship.allWeapons?.maxOfOrNull { w -> w.range } ?: 0f,
            ship.allWings?.maxOfOrNull { fighter -> fighter.range } ?: 0f
        )
    }

    override fun generateCommands(): List<ShipCommandWrapper> {
        return if (shouldBackOff()){
            listOf(ShipCommandWrapper(ShipCommand.ACCELERATE_BACKWARDS))
        }else{
            emptyList()
        }
    }

    override fun blockCommands(): List<ShipCommand> {
        return if (shouldBackOff()){
            listOf(ShipCommand.ACCELERATE)
        }else{
            emptyList()
        }
    }

    private fun shouldBackOff(): Boolean{
        val shipTargetDistance = ship.determineUniversalShipTarget()?.location?.minus(ship.location)?.length()
            ?: return false
        return shipTargetDistance < computeIdealEngagementRange()
    }
}