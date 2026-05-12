package com.dp.advancedgunnerycontrol.utils

import com.dp.advancedgunnerycontrol.gui.refitscreen.ModuleIdManager
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.*
import com.dp.advancedgunnerycontrol.weaponais.TagBasedAI
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.AutofireAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.loading.WeaponGroupSpec
import com.fs.starfarer.campaign.fleet.FleetMember
import java.util.WeakHashMap

fun getVariantWeaponGroup(member: FleetMemberAPI, groupIndex: Int): WeaponGroupSpec? {
    if (groupIndex < 0) return null
    return try {
        // Use the numbered accessor; variant.weaponGroups is not a reliable
        // stand-in for fixed UI slots when empty groups are present.
        member.variant?.getGroup(groupIndex)?.takeIf { it.slots?.isNotEmpty() == true }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Shared persistence gateway for campaign and direct refit/combat editors.
 * Runtime ships need local custom data plus immediate AI application; campaign
 * fleet members only update persistent storage until the ship is later loaded.
 */
class ShipEditorPersistenceContext(
    val member: FleetMemberAPI,
    val runtimeShip: ShipAPI? = null,
) {
    private data class WeaponTagCacheKey(val groupIndex: Int, val loadoutIndex: Int)

    private val weaponTagsByKey = mutableMapOf<WeaponTagCacheKey, List<String>>()
    private val modesByLoadout = mutableMapOf<Int, List<String>>()

    val shipId: String by lazy {
        runtimeShip?.let { agcStableShipId(it) } ?: agcStableShipId(member)
    }

    fun loadWeaponTags(groupIndex: Int, loadoutIndex: Int): List<String> {
        val key = WeaponTagCacheKey(groupIndex, loadoutIndex)
        return weaponTagsByKey.getOrPut(key) {
            runtimeShip?.let { loadTags(it, groupIndex, loadoutIndex) }
                ?: loadPersistentTags(shipId, groupIndex, loadoutIndex)
        }
    }

    fun saveWeaponTags(groupIndex: Int, loadoutIndex: Int, tags: List<String>) {
        val canonicalTags = canonicalizeWeaponTagNames(tags)
        if (runtimeShip != null) {
            saveTags(runtimeShip, groupIndex, loadoutIndex, canonicalTags)
            applyTagsToWeaponGroup(runtimeShip, groupIndex, canonicalTags)
        } else {
            persistTags(shipId, groupIndex, loadoutIndex, canonicalTags)
        }
        weaponTagsByKey[WeaponTagCacheKey(groupIndex, loadoutIndex)] = canonicalTags
    }

    fun loadModes(loadoutIndex: Int): List<String> {
        return modesByLoadout.getOrPut(loadoutIndex) {
            runtimeShip?.let { loadShipModes(it, loadoutIndex) }
                ?: loadPersistedShipModes(shipId, loadoutIndex)
        }
    }

    fun saveModes(loadoutIndex: Int, modes: List<String>) {
        val canonicalModes = canonicalizeShipModeNames(modes)
        if (runtimeShip != null) {
            saveShipModes(runtimeShip, loadoutIndex, canonicalModes)
            assignShipModes(canonicalModes, runtimeShip)
        } else {
            persistShipModes(shipId, loadoutIndex, canonicalModes)
        }
        modesByLoadout[loadoutIndex] = canonicalModes
    }

    fun loadAllUsedTags(): List<String> = loadAllTags(member, shipId)
}

fun WeaponAPI.getAutofirePlugin() : AutofireAIPlugin?{
    return this.ship?.getWeaponGroupFor(this)?.getAutofirePlugin(this)
}
fun applyTagsToWeaponGroup(ship: ShipAPI, groupIndex: Int, tags: List<String>): Boolean {
    val weaponGroup = ship.weaponGroupsCopy?.getOrNull(groupIndex) ?: return false
    val canonicalTags = canonicalizeWeaponTagNames(tags)
    val plugins = weaponGroup.aiPlugins
    for (pluginIndex in 0 until plugins.size) {
        if(Settings.weaponBlacklist.contains(weaponGroup.weaponsCopy?.getOrNull(pluginIndex)?.id)) continue
        if (plugins[pluginIndex] !is TagBasedAI) {
            plugins[pluginIndex] = TagBasedAI(plugins[pluginIndex])
        }
        (plugins[pluginIndex] as? TagBasedAI)?.tags = createTags(canonicalTags, plugins[pluginIndex].weapon).toMutableList()
    }
    return plugins.all { plugin -> (plugin as? TagBasedAI)?.tags?.all { tag -> tag.isValid() } ?: true }
}

fun applyTagsToWeapon(weapon: WeaponAPI, tags: List<String>) {
    if(Settings.weaponBlacklist.contains(weapon.id)) return
    val canonicalTags = canonicalizeWeaponTagNames(tags)
    val weaponGroup = weapon.ship?.getWeaponGroupFor(weapon) ?: return
    val plugin = weaponGroup.getAutofirePlugin(weapon) ?: return

    if (plugin !is TagBasedAI) {
        setAutofirePlugin(weapon, TagBasedAI(plugin, createTags(canonicalTags, weapon).toMutableList()))
    } else {
        val combinedTags = plugin.tags.toMutableSet()
        combinedTags.addAll(createTags(canonicalTags, weapon))
        plugin.tags = combinedTags.toMutableList()
    }

}

fun setAutofirePlugin(weapon: WeaponAPI, plugin: AutofireAIPlugin) {
    val weaponGroup = weapon.ship?.getWeaponGroupFor(weapon) ?: return
    val currentPlugin = weaponGroup.getAutofirePlugin(weapon) ?: return
    val index = weaponGroup.aiPlugins.indexOf(currentPlugin)
    if (index < 0) return
    weaponGroup.aiPlugins[index] = plugin
}

fun reloadAllShips(storageIndex: Int) {
    reloadShips(storageIndex, Global.getCombatEngine()?.ships)
}

fun reloadShips(storageIndex: Int, ships: List<ShipAPI?>?) {
    ships?.filter { it?.owner == 0 }?.filterNotNull().let { relevantShips ->
        relevantShips?.forEach { ship ->
            for (groupIndex in 0 until ship.weaponGroupsCopy.size) {
                if(Settings.autoApplySuggestedTags){
                    ship.fleetMember?.let { applySuggestedModes(it, storageIndex, false) }
                }
                val tags = loadTags(ship, groupIndex, storageIndex)
                applyTagsToWeaponGroup(ship, groupIndex, tags)
            }
            val shipModes = loadShipModes(ship, storageIndex)
            assignShipModes(shipModes, ship)
        }
    }

}


fun persistTemporaryShipData(storageIndex: Int, ships: List<ShipAPI?>?) {
    ships?.filter { it?.owner == 0 }?.filterNotNull().let {
        it?.forEach { ship ->
            if (ship.fleetMember == null) return@forEach
            // Runtime ships, especially modules, must round-trip through the same universal id used by loadTags().
            val shipId = agcStableShipId(ship)
            for (groupIndex in 0 until ship.weaponGroupsCopy.size) {
                val tags = loadTags(ship, groupIndex, storageIndex)
                persistTags(shipId, groupIndex, storageIndex, tags)
            }
            val modes = loadShipModes(ship, storageIndex)
            persistShipModes(shipId, storageIndex, modes)
        }
    }
}

fun loadTags(ship: ShipAPI, index: Int, storageIndex: Int): List<String> {
    if (Settings.enableCombatChangePersistence() || !doesShipHaveLocalTags(ship, storageIndex)) {
        if(ship.fleetMember == null) return emptyList()
        val shipId = agcStableShipId(ship)
        return loadPersistentTags(shipId, index, storageIndex)
    }
    return loadTagsFromShip(ship, index, storageIndex)
}

/**
 * loads all tag types that have been used for a given ship.
 * the returned list won't contain duplicates
 * this is used to hot-load tags when viewing a ship in GUI
 */
fun loadAllTags(ship: FleetMemberAPI, universalId: String? = null): List<String> {
    val tags = mutableSetOf<String>()
    val shipId = universalId ?: agcStableShipId(ship)
    for (loadoutIndex in 0 until Settings.maxLoadouts()) {
        for (groupIndex in 0 until Values.MAX_WEAPON_GROUPS) {
            tags.addAll(loadPersistentTags(shipId, groupIndex, loadoutIndex))
        }
    }
    return tags.toList()
}

fun saveTags(ship: ShipAPI, groupIndex: Int, loadoutIndex: Int, tags: List<String>) {
    if (Settings.enableCombatChangePersistence()) {
        val shipId = agcStableShipId(ship)
        if(ship.fleetMember == null) return
        persistTags(shipId, groupIndex, loadoutIndex, tags)
    }
    saveTagsInShip(ship, groupIndex, tags, loadoutIndex)
}

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
    val parentId = parentShip.fleetMemberId ?: return ""
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

fun persistTags(shipId: String, groupIndex: Int, loadoutIndex: Int, tags: List<String>) {
    if (shipId == "") return
    val canonicalTags = canonicalizeWeaponTagNames(tags)
    val storage = Settings.tagStorage.getOrNull(loadoutIndex) ?: return
    val tagsByGroup = storage.modesByShip
        .getOrPut(shipId) { mutableMapOf() }
    tagsByGroup[groupIndex] = canonicalTags.toSet().toList()
}

fun WeaponAPI.hasAgcTag(tag: String): Boolean{
    val dummyTag = createTag(tag, this) ?: return false
    return (getAutofirePlugin() as? TagBasedAI)?.tags?.any { it::class == dummyTag::class } == true
}

fun WeaponAPI.hasAnyAgcTag(vararg tags: String): Boolean{
    return tags.any { hasAgcTag(it) }
}

fun saveTagsInShip(ship: ShipAPI, groupIndex: Int, tags: List<String>, storageIndex: Int) {
    if (!ship.customData.containsKey(Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY)) {
        ship.setCustomData(Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY, InShipTagStorage())
    }
    (ship.customData[Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY] as? InShipTagStorage)?.tagsByIndex?.get(storageIndex)
        ?.set(groupIndex, canonicalizeWeaponTagNames(tags))
}


fun loadPersistentTags(shipId: String, groupIndex: Int, loadoutIndex: Int): List<String> {
    if(shipId == "") return emptyList()
    val tags = Settings.tagStorage.getOrNull(loadoutIndex)?.modesByShip?.get(shipId)?.get(groupIndex)
    return canonicalizeWeaponTagNames(tags ?: emptyList())
}

fun getWeaponGroupIndex(weapon: WeaponAPI): Int {
    return weapon.ship.weaponGroupsCopy.indexOf(weapon.ship.getWeaponGroupFor(weapon))
}

fun loadTagsFromShip(ship: ShipAPI, groupIndex: Int, storageIndex: Int): List<String> {
    return canonicalizeWeaponTagNames((ship.customData[Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY] as? InShipTagStorage)?.tagsByIndex?.get(
        storageIndex
    )?.get(groupIndex) ?: emptyList())
}

fun doesShipHaveLocalTags(ship: ShipAPI, storageIndex: Int): Boolean {
    return ship.customData.containsKey(Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY)
            && (ship.customData[Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY] as? InShipTagStorage)?.tagsByIndex?.containsKey(
        storageIndex
    ) ?: false
}

fun doesShipHaveLocalShipModes(ship: ShipAPI, storageIndex: Int): Boolean {
    return ship.customData.containsKey(Values.CUSTOM_SHIP_DATA_SHIP_MODES_KEY)
            && (ship.customData[Values.CUSTOM_SHIP_DATA_SHIP_MODES_KEY] as? InShipShipModeStorage)?.modes?.containsKey(
        storageIndex
    ) ?: false
}
