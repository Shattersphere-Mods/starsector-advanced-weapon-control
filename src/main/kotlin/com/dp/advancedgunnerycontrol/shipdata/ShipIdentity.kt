package com.dp.advancedgunnerycontrol.shipdata

import com.dp.advancedgunnerycontrol.gui.refitscreen.ModuleIdManager
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.campaign.fleet.FleetMember
import java.util.WeakHashMap

fun agcStableShipId(ship: ShipAPI): String {
    return generateUniversalFleetMemberId(ship)
}

fun agcStableShipId(ship: FleetMemberAPI): String {
    return generateUniversalFleetMemberId(ship).ifBlank { ship.id.orEmpty() }
}

fun agcShortShipId(rawId: String?, fallback: String = "UNKNOWN"): String {
    return rawId
        .orEmpty()
        .filter { it.isLetterOrDigit() }
        .take(6)
        .uppercase()
        .ifBlank { fallback }
}

fun agcShortShipId(ship: FleetMemberAPI?, fallback: String = "UNKNOWN"): String {
    return ship?.let { agcShortShipId(agcStableShipId(it), fallback) } ?: fallback
}

fun agcShortShipId(ship: ShipAPI?, fallback: String = "UNKNOWN"): String {
    return ship?.let { agcShortShipId(agcStableShipId(it), fallback) } ?: fallback
}

fun generateUniversalFleetMemberId(parentId: String, moduleIndex: Int): String{
    if (moduleIndex < 0) return ""
    return parentId + moduleIndex.toString()
}

private val fleetMemberUniversalIdCache = WeakHashMap<FleetMemberAPI, String>()

/**
 * generate unique & persistent fleetMemberId
 * return fleetMemberId-equivalent for modules of big ships
 * return fleetMemberId for regular ships
 * return empty string if something goes wrong
 */
fun generateUniversalFleetMemberId(ship: ShipAPI): String {
    (ModuleIdManager.getUniversalIdIfApplicable(ship))?.let {
        return it
    }
    if (!ship.isStationModule) return ship.fleetMemberId ?: ""
    val parentShip = ship.parentStation ?: return ""
    val parentId = parentShip.fleetMemberId ?: ""
    val index = parentShip.childModulesCopy?.indexOf(ship) ?: -1
    return generateUniversalFleetMemberId(parentId, index)
}

fun generateUniversalFleetMemberId(ship: FleetMemberAPI): String{
    fleetMemberUniversalIdCache[ship]?.let { return it }
    // Instantiating a combat ship is the only reliable path for module-aware ids,
    // but doing it repeatedly during GUI rebuilds is visibly expensive.
    val id = ((ship as? FleetMember)?.instantiateForCombat(null, 0, null) as? ShipAPI)?.let {
        generateUniversalFleetMemberId(it)
    } ?: ship.id.orEmpty()
    fleetMemberUniversalIdCache[ship] = id
    return id
}

fun getWeaponGroupIndex(weapon: WeaponAPI): Int {
    return weapon.ship.weaponGroupsCopy.indexOf(weapon.ship.getWeaponGroupFor(weapon))
}
