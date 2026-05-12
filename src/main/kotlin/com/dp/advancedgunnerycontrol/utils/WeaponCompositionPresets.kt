package com.dp.advancedgunnerycontrol.utils

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.CustomWeaponTagListStore
import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagNames
import com.dp.advancedgunnerycontrol.typesandvalues.isIncompatibleWithExistingTags
import com.dp.advancedgunnerycontrol.typesandvalues.shouldTagBeDisabled
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils

private const val PRESET_SCHEMA_VERSION = 1
private const val SUGGESTED_PRESET_LOADOUT_INDEX = -1
private const val GLOBAL_SCOPE_PREFIX = "global:"

enum class WeaponPresetScope {
    SINGLE,
    CLASS,
    GLOBAL,
    SUGGESTED;

    fun label(): String = when (this) {
        SINGLE -> "Single"
        CLASS -> "Class"
        GLOBAL -> "Global"
        SUGGESTED -> "Suggested"
    }

    fun next(): WeaponPresetScope {
        val values = values()
        return values[(ordinal + 1) % values.size]
    }
}

enum class WeaponPresetBackend {
    CAMPAIGN,
    EXTERNAL;

    fun label(): String = when (this) {
        CAMPAIGN -> "Campaign"
        EXTERNAL -> "External"
    }
}

enum class WeaponCompositionPresetSaveStatus {
    SAVED,
    NO_WEAPON_GROUP_KEY,
    SUGGESTED_REQUIRES_SINGLE_WEAPON,
    FAILED
}

enum class WeaponCompositionPresetLoadStatus {
    LOADED,
    NO_WEAPON_GROUP_KEY,
    NO_PRESET_FOUND,
    FAILED
}

enum class WeaponCompositionPresetPeekStatus {
    FOUND,
    NO_WEAPON_GROUP_KEY,
    NO_PRESET_FOUND,
    FAILED
}

data class WeaponCompositionPresetSaveResult(
    val status: WeaponCompositionPresetSaveStatus,
    val weaponKey: String? = null,
    val tags: List<String> = emptyList(),
    val overwrittenGroups: Int = 0,
    val error: String? = null
)

data class WeaponCompositionPresetLoadResult(
    val status: WeaponCompositionPresetLoadStatus,
    val weaponKey: String? = null,
    val tags: List<String> = emptyList(),
    val importedCustomTags: List<String> = emptyList(),
    val error: String? = null
)

data class WeaponCompositionPresetPeekResult(
    val status: WeaponCompositionPresetPeekStatus,
    val weaponKey: String? = null,
    val tags: List<String> = emptyList(),
    val error: String? = null
)

private data class WeaponCompositionPresetFileData(
    val schemaVersion: Int = PRESET_SCHEMA_VERSION,
    val presetsByLoadout: MutableMap<Int, MutableMap<String, List<String>>> = mutableMapOf()
)

private data class PresetTagSanitizationResult(
    val tags: List<String>,
    val importedCustomTags: List<String> = emptyList(),
    val unsupportedCustomTags: List<String> = emptyList(),
)

private interface WeaponPresetStore {
    fun get(loadoutIndex: Int, key: String): List<String>?
    fun put(loadoutIndex: Int, key: String, tags: List<String>)
}

private class ExternalWeaponPresetStore(private val file: String) : WeaponPresetStore {
    private val fileData: WeaponCompositionPresetFileData by lazy { loadPresetData(file) }

    override fun get(loadoutIndex: Int, key: String): List<String>? {
        return fileData.presetsByLoadout[loadoutIndex]?.get(key)
    }

    override fun put(loadoutIndex: Int, key: String, tags: List<String>) {
        val byLoadout = fileData.presetsByLoadout.getOrPut(loadoutIndex) { mutableMapOf() }
        byLoadout[key] = canonicalizeWeaponTagNames(tags)
        savePresetData(file, fileData)
    }
}

private class CampaignWeaponPresetStore : WeaponPresetStore {
    private val persistentKey = "$" + Values.THIS_MOD_NAME + Values.WEAPON_COMP_TAG_PRESETS_CAMPAIGN_KEY

    private fun data(): MutableMap<Int, MutableMap<String, List<String>>> {
        val persistentData = agcPersistentDataOrNull("campaign weapon preset store") ?: return mutableMapOf()
        val existing = validCampaignPresetDataOrNull(persistentData[persistentKey])
        if (existing != null) return existing
        if (persistentData.containsKey(persistentKey)) {
            logPresetWarn("Ignoring malformed campaign weapon preset data. Creating an empty preset store.")
        }
        val created = mutableMapOf<Int, MutableMap<String, List<String>>>()
        persistentData[persistentKey] = created
        return created
    }

    @Suppress("UNCHECKED_CAST")
    private fun validCampaignPresetDataOrNull(raw: Any?): MutableMap<Int, MutableMap<String, List<String>>>? {
        val map = raw as? MutableMap<*, *> ?: return null
        val valid = map.all { (loadoutIndex, presets) ->
            loadoutIndex is Int &&
                presets is MutableMap<*, *> &&
                presets.all { (weaponKey, tags) ->
                    weaponKey is String &&
                        tags is List<*> &&
                        tags.all { it is String }
                }
        }
        return if (valid) map as MutableMap<Int, MutableMap<String, List<String>>> else null
    }

    override fun get(loadoutIndex: Int, key: String): List<String>? {
        return data()[loadoutIndex]?.get(key)
    }

    override fun put(loadoutIndex: Int, key: String, tags: List<String>) {
        data().getOrPut(loadoutIndex) { mutableMapOf() }[key] = canonicalizeWeaponTagNames(tags)
    }
}

private fun presetStore(backend: WeaponPresetBackend, file: String): WeaponPresetStore {
    return when (backend) {
        WeaponPresetBackend.CAMPAIGN -> CampaignWeaponPresetStore()
        WeaponPresetBackend.EXTERNAL -> ExternalWeaponPresetStore(file)
    }
}

fun getWeaponCompositionPresetKey(member: FleetMemberAPI, groupIndex: Int): String? {
    return getWeaponCompositionPresetWeaponIds(member, groupIndex)?.joinToString(separator = "|")
}

fun getWeaponCompositionPresetKey(ship: ShipAPI, groupIndex: Int): String? {
    return getWeaponCompositionPresetWeaponIds(ship, groupIndex)?.joinToString(separator = "|")
}

fun getWeaponCompositionPresetWeaponNames(member: FleetMemberAPI, groupIndex: Int): List<String> {
    val group = getVariantWeaponGroup(member, groupIndex) ?: return emptyList()
    return group.slots
        .mapNotNull { member.variant?.getWeaponId(it)?.trim() }
        .filter { it.isNotEmpty() }
        .toSortedSet()
        .map { weaponId ->
            runCatching { Global.getSettings().getWeaponSpec(weaponId).weaponName }.getOrDefault(weaponId)
        }
}

private fun getWeaponCompositionPresetWeaponIds(member: FleetMemberAPI, groupIndex: Int): List<String>? {
    val group = getVariantWeaponGroup(member, groupIndex) ?: return null
    val weaponIds = group.slots
        .mapNotNull { member.variant?.getWeaponId(it)?.trim() }
        .filter { it.isNotEmpty() }
        .toSortedSet()
        .toList()
    return weaponIds.ifEmpty { null }
}

private fun getWeaponCompositionPresetWeaponIds(ship: ShipAPI, groupIndex: Int): List<String>? {
    val group = ship.weaponGroupsCopy?.getOrNull(groupIndex) ?: return null
    val weaponIds = group.weaponsCopy
        .mapNotNull { it?.id?.trim() }
        .filter { it.isNotEmpty() }
        .toSortedSet()
        .toList()
    return weaponIds.ifEmpty { null }
}

private fun presetLoadoutIndex(scope: WeaponPresetScope, loadoutIndex: Int): Int {
    return if (scope == WeaponPresetScope.SUGGESTED) SUGGESTED_PRESET_LOADOUT_INDEX else loadoutIndex
}

private fun storageKey(
    member: FleetMemberAPI,
    groupIndex: Int,
    scope: WeaponPresetScope,
    runtimeShip: ShipAPI? = null,
): String? {
    val weaponIds = getWeaponCompositionPresetWeaponIds(member, groupIndex)
        ?: return null
    return when (scope) {
        WeaponPresetScope.SINGLE -> {
            val shipId = exactSinglePresetShipId(member)
            if (shipId.isBlank()) null else "single:$shipId:${weaponIds.joinToString("|")}"
        }
        WeaponPresetScope.CLASS -> "class:${member.hullId}:${weaponIds.joinToString("|")}"
        WeaponPresetScope.GLOBAL -> "$GLOBAL_SCOPE_PREFIX${weaponIds.joinToString("|")}"
        WeaponPresetScope.SUGGESTED -> {
            if (weaponIds.size != 1) null else "suggested:${weaponIds.first()}"
        }
    }
}

private fun exactSinglePresetShipId(member: FleetMemberAPI): String {
    // Single-scope presets must identify the exact campaign fleet member, not a
    // combat-facing fleetMemberId that can look hull/class-like after temporary
    // ship instantiation.
    return member.id?.trim().orEmpty()
}

private fun suggestedLoadKeys(
    member: FleetMemberAPI,
    groupIndex: Int,
): List<String>? {
    val weaponIds = getWeaponCompositionPresetWeaponIds(member, groupIndex)
        ?: return null
    return weaponIds.map { "suggested:$it" }
}

fun saveExternalWeaponCompositionPreset(
    member: FleetMemberAPI,
    groupIndex: Int,
    loadoutIndex: Int,
    file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME,
    runtimeShip: ShipAPI? = null
): WeaponCompositionPresetSaveResult {
    return saveWeaponPreset(
        member = member,
        groupIndex = groupIndex,
        loadoutIndex = loadoutIndex,
        scope = WeaponPresetScope.GLOBAL,
        backend = WeaponPresetBackend.EXTERNAL,
        overwrite = false,
        file = file,
        runtimeShip = runtimeShip
    )
}

fun saveWeaponPreset(
    member: FleetMemberAPI,
    groupIndex: Int,
    loadoutIndex: Int,
    scope: WeaponPresetScope,
    backend: WeaponPresetBackend,
    overwrite: Boolean,
    file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME,
    runtimeShip: ShipAPI? = null,
    persistenceContext: ShipEditorPersistenceContext? = null,
): WeaponCompositionPresetSaveResult {
    val key = storageKey(member, groupIndex, scope, runtimeShip)
        ?: return if (scope == WeaponPresetScope.SUGGESTED) {
            WeaponCompositionPresetSaveResult(WeaponCompositionPresetSaveStatus.SUGGESTED_REQUIRES_SINGLE_WEAPON)
        } else {
            WeaponCompositionPresetSaveResult(WeaponCompositionPresetSaveStatus.NO_WEAPON_GROUP_KEY)
    }
    return try {
        val context = persistenceContext ?: ShipEditorPersistenceContext(member, runtimeShip)
        val selectedTags = context.loadWeaponTags(groupIndex, loadoutIndex)
        val sanitized = sanitizeTagsForWeaponGroup(member, groupIndex, selectedTags)

        val overwriteAllLoadouts = overwrite && scope != WeaponPresetScope.SUGGESTED
        val presetLoadouts = if (overwriteAllLoadouts) {
            (0 until Settings.maxLoadouts()).toList()
        } else {
            listOf(loadoutIndex)
        }
        val store = presetStore(backend, file)
        presetLoadouts.forEach { targetLoadoutIndex ->
            store.put(presetLoadoutIndex(scope, targetLoadoutIndex), key, sanitized)
        }
        val overwritten = if (overwrite && scope != WeaponPresetScope.SUGGESTED) {
            overwriteMatchingGroups(member, groupIndex, presetLoadouts, scope, sanitized, runtimeShip)
        } else {
            0
        }

        WeaponCompositionPresetSaveResult(
            status = WeaponCompositionPresetSaveStatus.SAVED,
            weaponKey = key,
            tags = sanitized,
            overwrittenGroups = overwritten
        )
    } catch (ex: Exception) {
        logPresetWarn("Failed saving weapon preset for key=$key, loadout=$loadoutIndex, scope=$scope, backend=$backend", ex)
        WeaponCompositionPresetSaveResult(
            status = WeaponCompositionPresetSaveStatus.FAILED,
            weaponKey = key,
            error = ex.message
        )
    }
}

fun loadExternalWeaponCompositionPreset(
    member: FleetMemberAPI,
    groupIndex: Int,
    loadoutIndex: Int,
    file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME,
    runtimeShip: ShipAPI? = null
): WeaponCompositionPresetLoadResult {
    return loadWeaponPreset(
        member = member,
        groupIndex = groupIndex,
        loadoutIndex = loadoutIndex,
        scope = WeaponPresetScope.GLOBAL,
        backend = WeaponPresetBackend.EXTERNAL,
        file = file,
        runtimeShip = runtimeShip
    )
}

fun loadWeaponPreset(
    member: FleetMemberAPI,
    groupIndex: Int,
    loadoutIndex: Int,
    scope: WeaponPresetScope,
    backend: WeaponPresetBackend,
    file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME,
    runtimeShip: ShipAPI? = null,
    persistenceContext: ShipEditorPersistenceContext? = null,
): WeaponCompositionPresetLoadResult {
    val key = storageKey(member, groupIndex, scope, runtimeShip)
    val loadKeys = if (scope == WeaponPresetScope.SUGGESTED) {
        suggestedLoadKeys(member, groupIndex)
    } else {
        key?.let(::listOf)
    } ?: return WeaponCompositionPresetLoadResult(WeaponCompositionPresetLoadStatus.NO_WEAPON_GROUP_KEY)
    return try {
        val store = presetStore(backend, file)
        val loadedEntries = loadKeys.mapNotNull { store.get(presetLoadoutIndex(scope, loadoutIndex), it) }
        val savedTags = if (loadedEntries.isNotEmpty()) {
            loadedEntries.flatten()
        } else if (scope == WeaponPresetScope.GLOBAL && backend == WeaponPresetBackend.EXTERNAL && key != null) {
            store.get(loadoutIndex, key.removePrefix(GLOBAL_SCOPE_PREFIX))
                ?: loadLegacyDpGlobalWeaponPreset(
                    member = member,
                    groupIndex = groupIndex,
                    runtimeShip = runtimeShip,
                    primaryFile = file,
                )
        } else {
            null
        }
        if (savedTags == null) {
            return WeaponCompositionPresetLoadResult(
                status = WeaponCompositionPresetLoadStatus.NO_PRESET_FOUND,
                weaponKey = key ?: loadKeys.joinToString(",")
            )
        }

        val sanitized = sanitizePresetTagsForWeaponGroup(
            member = member,
            groupIndex = groupIndex,
            tags = savedTags,
            runtimeShip = runtimeShip,
            importSupportedCustomTags = true
        )
        if (sanitized.unsupportedCustomTags.isNotEmpty()) {
            logPresetWarn(
                "Ignored unsupported custom preset tags for key=$key, loadout=$loadoutIndex: " +
                    sanitized.unsupportedCustomTags.joinToString(", ")
            )
        }
        (persistenceContext ?: ShipEditorPersistenceContext(member, runtimeShip))
            .saveWeaponTags(groupIndex, loadoutIndex, sanitized.tags)

        WeaponCompositionPresetLoadResult(
            status = WeaponCompositionPresetLoadStatus.LOADED,
            weaponKey = key,
            tags = sanitized.tags,
            importedCustomTags = sanitized.importedCustomTags,
        )
    } catch (ex: Exception) {
        logPresetWarn("Failed loading weapon preset for key=$key, loadout=$loadoutIndex, scope=$scope, backend=$backend", ex)
        WeaponCompositionPresetLoadResult(
            status = WeaponCompositionPresetLoadStatus.FAILED,
            weaponKey = key,
            error = ex.message
        )
    }
}

fun peekExternalWeaponCompositionPreset(
    member: FleetMemberAPI,
    groupIndex: Int,
    loadoutIndex: Int,
    file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME
): WeaponCompositionPresetPeekResult {
    return peekWeaponPreset(member, groupIndex, loadoutIndex, WeaponPresetScope.GLOBAL, WeaponPresetBackend.EXTERNAL, file)
}

fun peekWeaponPreset(
    member: FleetMemberAPI,
    groupIndex: Int,
    loadoutIndex: Int,
    scope: WeaponPresetScope,
    backend: WeaponPresetBackend,
    file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME,
): WeaponCompositionPresetPeekResult {
    val key = storageKey(member, groupIndex, scope)
    val loadKeys = if (scope == WeaponPresetScope.SUGGESTED) {
        suggestedLoadKeys(member, groupIndex)
    } else {
        key?.let(::listOf)
    } ?: return if (scope == WeaponPresetScope.SUGGESTED) {
        WeaponCompositionPresetPeekResult(WeaponCompositionPresetPeekStatus.NO_PRESET_FOUND)
    } else {
        WeaponCompositionPresetPeekResult(WeaponCompositionPresetPeekStatus.NO_WEAPON_GROUP_KEY)
    }
    return try {
        val store = presetStore(backend, file)
        val loadedEntries = loadKeys.mapNotNull { store.get(presetLoadoutIndex(scope, loadoutIndex), it) }
        val savedTags = if (loadedEntries.isNotEmpty()) {
            loadedEntries.flatten()
        } else if (scope == WeaponPresetScope.GLOBAL && backend == WeaponPresetBackend.EXTERNAL && key != null) {
            store.get(loadoutIndex, key.removePrefix(GLOBAL_SCOPE_PREFIX))
                ?: loadLegacyDpGlobalWeaponPreset(
                    member = member,
                    groupIndex = groupIndex,
                    primaryFile = file,
                )
        } else {
            null
        }
            ?: return WeaponCompositionPresetPeekResult(
                status = WeaponCompositionPresetPeekStatus.NO_PRESET_FOUND,
                weaponKey = key ?: loadKeys.joinToString(",")
            )
        WeaponCompositionPresetPeekResult(
            status = WeaponCompositionPresetPeekStatus.FOUND,
            weaponKey = key ?: loadKeys.joinToString(","),
            tags = sanitizeTagsForWeaponGroup(member, groupIndex, savedTags)
        )
    } catch (ex: Exception) {
        logPresetWarn("Failed peeking weapon preset for key=$key, loadout=$loadoutIndex, scope=$scope, backend=$backend", ex)
        WeaponCompositionPresetPeekResult(
            status = WeaponCompositionPresetPeekStatus.FAILED,
            weaponKey = key,
            error = ex.message
        )
    }
}

fun sanitizeWeaponCompositionPresetTagsForGroup(
    member: FleetMemberAPI,
    groupIndex: Int,
    tags: List<String>
): List<String> = sanitizeTagsForWeaponGroup(member, groupIndex, tags)

private fun sanitizeTagsForWeaponGroup(
    member: FleetMemberAPI,
    groupIndex: Int,
    tags: List<String>,
    runtimeShip: ShipAPI? = null,
    importSupportedCustomTags: Boolean = false,
): List<String> = sanitizePresetTagsForWeaponGroup(
    member = member,
    groupIndex = groupIndex,
    tags = tags,
    runtimeShip = runtimeShip,
    importSupportedCustomTags = importSupportedCustomTags
).tags

private fun sanitizePresetTagsForWeaponGroup(
    member: FleetMemberAPI,
    groupIndex: Int,
    tags: List<String>,
    runtimeShip: ShipAPI? = null,
    importSupportedCustomTags: Boolean = false,
): PresetTagSanitizationResult {
    val canonicalTags = canonicalizeWeaponTagNames(tags)
    val shipId = ShipEditorPersistenceContext(member, runtimeShip).shipId
    val defaultTags = Settings.getCurrentWeaponTagList()
    val currentVisibleTags = if (Settings.isAdvancedMode) {
        CustomWeaponTagListStore.effectiveTagsForShipOrDefault(shipId, defaultTags)
    } else {
        defaultTags
    }
    var importedCustomTags = emptyList<String>()
    var unsupportedCustomTags = emptyList<String>()
    if (importSupportedCustomTags && Settings.isAdvancedMode) {
        // Preset loads may contain exact editable/custom tags that are not in the
        // current visible list yet. Import only those missing valid tags so a
        // normal preset load does not switch the ship to Custom unnecessarily.
        val missingTags = canonicalTags.filterNot { currentVisibleTags.contains(it) }
        if (missingTags.isNotEmpty()) {
            val importResult = CustomWeaponTagListStore.importValidTagsIntoCustomList(
                shipId,
                missingTags,
                switchToCustom = true
            )
            importedCustomTags = importResult.importedTags
            unsupportedCustomTags = importResult.unsupportedTags
            if (importedCustomTags.isNotEmpty()) {
                // Preset imports must also refresh the session tag registry;
                // older combat/refit paths still consult Settings tag lists.
                Settings.hotAddTags(importedCustomTags)
            }
        }
    }
    val allVisibleTags = if (Settings.isAdvancedMode) {
        CustomWeaponTagListStore.effectiveTagsForShipOrDefault(shipId, defaultTags)
    } else {
        defaultTags
    }
    val sanitized = canonicalizeWeaponTagNames(tags)
        .filter { allVisibleTags.contains(it) }
        .toMutableList()
    var changed = true
    while (changed) {
        changed = false
        sanitized.toList().forEach { persistedTag ->
            val otherTags = sanitized.toMutableList().apply { remove(persistedTag) }
            if (isIncompatibleWithExistingTags(persistedTag, otherTags) || shouldTagBeDisabled(groupIndex, member, persistedTag)) {
                sanitized.remove(persistedTag)
                changed = true
            }
        }
    }
    return PresetTagSanitizationResult(
        tags = sanitized,
        importedCustomTags = importedCustomTags,
        unsupportedCustomTags = unsupportedCustomTags,
    )
}

private fun overwriteMatchingGroups(
    sourceMember: FleetMemberAPI,
    sourceGroupIndex: Int,
    loadoutIndexes: List<Int>,
    scope: WeaponPresetScope,
    tags: List<String>,
    runtimeShip: ShipAPI?,
): Int {
    val sourceWeaponKey = getWeaponCompositionPresetKey(sourceMember, sourceGroupIndex)
        ?: return 0
    val sourceShipId = exactSinglePresetShipId(sourceMember)
    var count = overwriteMatchingRuntimeGroups(
        sourceMember,
        runtimeShip,
        sourceWeaponKey,
        sourceShipId,
        loadoutIndexes,
        scope,
        tags
    )
    val ships = overwriteTargetMembers()
        ?: return count
    ships.forEach { target ->
        if (!scope.matchesTarget(sourceMember, sourceShipId, target)) return@forEach
        for (index in 0 until Values.MAX_WEAPON_GROUPS) {
            if (getWeaponCompositionPresetKey(target, index) != sourceWeaponKey) continue
            // Overwrite applies to the normal active tag storage, not just the preset store.
            // Re-sanitize for the target group so cross-ship overwrite cannot persist tags
            // that are disabled for the destination weapon group.
            val targetTags = sanitizeTagsForWeaponGroup(
                member = target,
                groupIndex = index,
                tags = tags,
                importSupportedCustomTags = true
            )
            val context = ShipEditorPersistenceContext(target)
            loadoutIndexes.forEach { loadoutIndex ->
                context.saveWeaponTags(index, loadoutIndex, targetTags)
                count++
            }
        }
    }
    return count
}

fun previewWeaponPresetOverwriteTargetMembers(
    sourceMember: FleetMemberAPI,
    scope: WeaponPresetScope,
): List<FleetMemberAPI> {
    val sourceShipId = exactSinglePresetShipId(sourceMember)
    return overwriteTargetMembers()
        ?.filter { target -> scope.matchesTarget(sourceMember, sourceShipId, target) }
        ?: emptyList()
}

private fun overwriteTargetMembers(): List<FleetMemberAPI>? {
    val sector = Global.getSector() ?: return null
    val membersById = linkedMapOf<String, FleetMemberAPI>()
    fun addMembers(members: List<FleetMemberAPI>?) {
        members
            ?.filterNot { it.isFighterWing }
            ?.forEach { member ->
                val id = member.id ?: return@forEach
                membersById.putIfAbsent(id, member)
            }
    }
    addMembers(sector.playerFleet?.membersWithFightersCopy)
    sector.economy?.marketsCopy
        ?.mapNotNull { market -> market.getSubmarket(Submarkets.SUBMARKET_STORAGE) }
        ?.forEach { storage ->
            addMembers(storage.cargoNullOk?.mothballedShips?.membersListCopy)
        }
    return membersById.values.toList()
}

private fun overwriteMatchingRuntimeGroups(
    sourceMember: FleetMemberAPI,
    runtimeShip: ShipAPI?,
    sourceWeaponKey: String,
    sourceShipId: String,
    loadoutIndexes: List<Int>,
    scope: WeaponPresetScope,
    tags: List<String>,
): Int {
    if (runtimeShip == null || !scope.matchesTarget(sourceMember, sourceShipId, sourceMember)) return 0
    var count = 0
    val context = ShipEditorPersistenceContext(sourceMember, runtimeShip)
    for (index in 0 until Values.MAX_WEAPON_GROUPS) {
        if (getWeaponCompositionPresetKey(sourceMember, index) != sourceWeaponKey) continue
        val targetTags = sanitizeTagsForWeaponGroup(
            member = sourceMember,
            groupIndex = index,
            tags = tags,
            runtimeShip = runtimeShip,
            importSupportedCustomTags = true
        )
        loadoutIndexes.forEach { loadoutIndex ->
            context.saveWeaponTags(index, loadoutIndex, targetTags)
            count++
        }
    }
    return count
}

private fun WeaponPresetScope.matchesTarget(
    sourceMember: FleetMemberAPI,
    sourceShipId: String,
    target: FleetMemberAPI,
): Boolean {
    return when (this) {
        WeaponPresetScope.SINGLE -> target.id == sourceShipId
        WeaponPresetScope.CLASS -> target.hullId == sourceMember.hullId
        WeaponPresetScope.GLOBAL -> true
        WeaponPresetScope.SUGGESTED -> false
    }
}

private fun loadPresetData(file: String): WeaponCompositionPresetFileData {
    return try {
        val root = JSONUtils.loadCommonJSON(file)
        val schemaVersion = root.optInt("schemaVersion", PRESET_SCHEMA_VERSION)
        val loadoutsObject = root.optJSONObject("loadouts")
        val presetsByLoadout = mutableMapOf<Int, MutableMap<String, List<String>>>()
        if (loadoutsObject != null) {
            loadoutsObject.keys().forEach { loadoutKeyAny ->
                val loadoutKey = loadoutKeyAny as? String ?: return@forEach
                val loadoutIndex = loadoutKey.toIntOrNull() ?: return@forEach
                val perLoadoutObject = loadoutsObject.optJSONObject(loadoutKey) ?: return@forEach
                val perLoadout = mutableMapOf<String, List<String>>()
                perLoadoutObject.keys().forEach { weaponKeyAny ->
                    val weaponKey = weaponKeyAny as? String ?: return@forEach
                    val tagsJson = perLoadoutObject.optJSONArray(weaponKey)
                    if (tagsJson == null) {
                        logPresetWarn("Ignoring malformed preset entry for key=$weaponKey in loadout=$loadoutIndex (not an array).")
                        return@forEach
                    }
                    perLoadout[weaponKey] = jsonArrayToStringList(tagsJson)
                }
                if (perLoadout.isNotEmpty()) {
                    presetsByLoadout[loadoutIndex] = perLoadout
                }
            }
        }
        WeaponCompositionPresetFileData(schemaVersion = schemaVersion, presetsByLoadout = presetsByLoadout)
    } catch (ex: Exception) {
        logPresetWarn("Failed reading preset file '$file'. Falling back to empty presets.", ex)
        WeaponCompositionPresetFileData()
    }
}

private fun savePresetData(file: String, fileData: WeaponCompositionPresetFileData) {
    val root = JSONUtils.loadCommonJSON(file)
    clearJsonObject(root)
    root.put("schemaVersion", fileData.schemaVersion)
    val loadoutsObject = JSONObject()
    fileData.presetsByLoadout.forEach { (loadoutIndex, presets) ->
        val loadoutObject = JSONObject()
        presets.forEach { (weaponKey, tags) ->
            loadoutObject.put(weaponKey, JSONArray(canonicalizeWeaponTagNames(tags)))
        }
        loadoutsObject.put(loadoutIndex.toString(), loadoutObject)
    }
    root.put("loadouts", loadoutsObject)
    root.save()
}

private fun clearJsonObject(jsonObject: JSONObject) {
    val keys = mutableListOf<String>()
    jsonObject.keys().forEach { key ->
        (key as? String)?.let { keys.add(it) }
    }
    keys.forEach { jsonObject.remove(it) }
}

private fun jsonArrayToStringList(jsonArray: JSONArray): List<String> {
    val output = mutableListOf<String>()
    for (i in 0 until jsonArray.length()) {
        val value = jsonArray.opt(i) as? String ?: continue
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) output.add(trimmed)
    }
    return output
}

private fun logPresetWarn(message: String, throwable: Throwable? = null) {
    val logger = Global.getLogger(WeaponCompositionPresetFileData::class.java)
    if (throwable == null) logger.warn(message) else logger.warn(message, throwable)
}
