package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.dp.advancedgunnerycontrol.utils.ShipEditorPersistenceContext
import com.dp.advancedgunnerycontrol.utils.WeaponCompositionPresetPeekStatus
import com.dp.advancedgunnerycontrol.utils.WeaponPresetBackend
import com.dp.advancedgunnerycontrol.utils.WeaponPresetScope
import com.dp.advancedgunnerycontrol.utils.agcShortShipId
import com.dp.advancedgunnerycontrol.utils.getWeaponCompositionPresetKey
import com.dp.advancedgunnerycontrol.utils.getWeaponCompositionPresetWeaponNames
import com.dp.advancedgunnerycontrol.utils.previewWeaponPresetOverwriteTargetMembers
import com.dp.advancedgunnerycontrol.utils.sanitizeWeaponCompositionPresetTagsForGroup
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI

internal object PresetConfirmationCopy {
    private const val NO_TAGS_SELECTED = PRESET_NO_TAGS_SELECTED_LABEL
    private const val NO_SAVED_TAGS_FOUND = "(No saved tags found)"

    fun body(
        groupIndex: Int,
        state: PresetControlState,
        ship: FleetMemberAPI?,
        runtimeShip: ShipAPI?,
        activePersistenceContext: ShipEditorPersistenceContext?,
        loadoutIndex: Int,
        presetPeekCache: PresetPeekCache? = null,
    ): List<CampaignHighlightedText> {
        // Protected user-workshopped preset modal copy. Do not rewrite, shorten,
        // remove, or bypass these templates unless the user explicitly asks, or
        // a behavior change makes the wording wrong and the user is told why.
        val paragraphs = when (state.pendingAction) {
            PendingPresetAction.SAVE -> saveBody(groupIndex, state, ship, runtimeShip, activePersistenceContext, loadoutIndex)
            PendingPresetAction.LOAD -> loadBody(groupIndex, state, ship, loadoutIndex, presetPeekCache)
            null -> listOf(CampaignHighlightedText("No preset action is currently pending."))
        }
        return paragraphs + confirmationFooterText()
    }

    fun reviewBody(
        groupIndex: Int,
        state: PresetControlState,
        ship: FleetMemberAPI?,
        runtimeShip: ShipAPI?,
        activePersistenceContext: ShipEditorPersistenceContext?,
        loadoutIndex: Int,
        presetPeekCache: PresetPeekCache? = null,
    ): PresetActionReviewBody? {
        if (groupIndex == CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) return null
        return when (state.pendingAction) {
            PendingPresetAction.SAVE -> saveReviewBody(
                groupIndex,
                state,
                ship,
                runtimeShip,
                activePersistenceContext,
                loadoutIndex,
            )
            PendingPresetAction.LOAD -> loadReviewBody(groupIndex, state, ship, loadoutIndex, presetPeekCache)
            null -> null
        }
    }

    private fun saveReviewBody(
        groupIndex: Int,
        state: PresetControlState,
        ship: FleetMemberAPI?,
        runtimeShip: ShipAPI?,
        activePersistenceContext: ShipEditorPersistenceContext?,
        loadoutIndex: Int,
    ): PresetActionReviewBody {
        val preset = presetDescriptor(state.scope, state.backend)
        val eligibleGroups = saveReviewGroupIndexes(ship, groupIndex, state.scope)
        val extra = mutableListOf<CampaignHighlightedText>()
        if (state.overwrite && state.scope != WeaponPresetScope.SUGGESTED) {
            extra.add(overwriteScopeParagraph(ship, groupIndex, state.scope))
        }
        return PresetActionReviewBody(
            intro = highlightPresetParagraph("Confirming will, for the following set of weapon types:"),
            weaponRows = reviewWeaponRows(ship, eligibleGroups),
            tagIntro = CampaignHighlightedText("Save the following tags:"),
            tagRows = saveReviewTagRows(ship, runtimeShip, activePersistenceContext, eligibleGroups, loadoutIndex),
            closing = highlightPresetParagraph("as the associated $preset tag preset."),
            extraParagraphs = extra,
            footer = confirmationFooterText(),
        )
    }

    private fun loadReviewBody(
        groupIndex: Int,
        state: PresetControlState,
        ship: FleetMemberAPI?,
        loadoutIndex: Int,
        presetPeekCache: PresetPeekCache?,
    ): PresetActionReviewBody {
        val preset = presetDescriptor(state.scope, state.backend)
        val groups = loadReviewGroupIndexes(ship, groupIndex)
        val loadout = loadoutPhraseOrNull(state.scope, loadoutIndex)
        return PresetActionReviewBody(
            intro = highlightPresetParagraph("Confirming will, for the following set of weapon types:"),
            weaponRows = reviewWeaponRows(ship, groups),
            tagIntro = highlightPresetParagraph("Load the associated $preset tag preset:"),
            tagRows = loadReviewTagRows(ship, groups, state, loadoutIndex, presetPeekCache),
            closing = highlightPresetParagraph("into this weapon group (#${groupIndex + 1})${loadoutTargetSuffix(loadout)}."),
            extraParagraphs = emptyList(),
            footer = confirmationFooterText(),
        )
    }

    private fun saveBody(
        groupIndex: Int,
        state: PresetControlState,
        ship: FleetMemberAPI?,
        runtimeShip: ShipAPI?,
        activePersistenceContext: ShipEditorPersistenceContext?,
        loadoutIndex: Int,
    ): List<CampaignHighlightedText> {
        val weapons = weaponSummary(ship, groupIndex)
        val tags = currentTagSummary(ship, runtimeShip, activePersistenceContext, groupIndex, loadoutIndex)
        val context = saveContextPhrase(ship, weapons, state.scope, loadoutIndex)
        val preset = presetDescriptor(state.scope, state.backend)
        val loadout = loadoutPhraseOrNull(state.scope, loadoutIndex)
        val paragraph = if (groupIndex == CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            allGroupsSaveText(ship, state, preset, loadout)
        } else {
            weaponGroupSaveText(context, tags, preset)
        }
        val body = mutableListOf(highlightPresetParagraph(paragraph))
        if (state.overwrite && state.scope != WeaponPresetScope.SUGGESTED) {
            body.add(overwriteScopeParagraph(ship, groupIndex, state.scope))
        }
        return body
    }

    private fun saveReviewGroupIndexes(
        ship: FleetMemberAPI?,
        groupIndex: Int,
        scope: WeaponPresetScope,
    ): List<Int> {
        if (ship == null) return emptyList()
        if (groupIndex != CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            return listOf(groupIndex)
        }
        return if (scope == WeaponPresetScope.SUGGESTED) {
            singleWeaponPresetGroupIndexes(ship)
        } else {
            weaponPresetGroupIndexes(ship)
        }
    }

    private fun loadReviewGroupIndexes(
        ship: FleetMemberAPI?,
        groupIndex: Int,
    ): List<Int> {
        if (ship == null) return emptyList()
        if (groupIndex != CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            return listOf(groupIndex)
        }
        return weaponPresetGroupIndexes(ship)
    }

    private fun reviewWeaponRows(
        ship: FleetMemberAPI?,
        groupIndexes: List<Int>,
    ): List<PresetReviewListRow> {
        if (ship == null || groupIndexes.isEmpty()) {
            return listOf(PresetReviewListRow(PRESET_UNKNOWN_WEAPON_COMBINATION_LABEL))
        }
        val includeGroupPrefix = groupIndexes.size > 1
        val rows = mutableListOf<PresetReviewListRow>()
        groupIndexes.forEach { groupIndex ->
            val weaponIds = weaponIdsForGroup(ship, groupIndex)
            if (weaponIds.isEmpty()) {
                rows.add(PresetReviewListRow(groupPrefix(groupIndex, includeGroupPrefix) + PRESET_UNKNOWN_WEAPON_COMBINATION_LABEL))
            } else {
                weaponIds.forEach { weaponId ->
                    val spec = runCatching { Global.getSettings().getWeaponSpec(weaponId) }.getOrNull()
                    rows.add(PresetReviewListRow(
                        label = groupPrefix(groupIndex, includeGroupPrefix) + (spec?.weaponName ?: weaponId),
                        sprite = spec?.turretSpriteName,
                    ))
                }
            }
        }
        return rows
    }

    private fun saveReviewTagRows(
        ship: FleetMemberAPI?,
        runtimeShip: ShipAPI?,
        activePersistenceContext: ShipEditorPersistenceContext?,
        groupIndexes: List<Int>,
        loadoutIndex: Int,
    ): List<PresetReviewListRow> {
        if (ship == null || groupIndexes.isEmpty()) {
            return listOf(PresetReviewListRow(NO_TAGS_SELECTED))
        }
        val context = activePersistenceContext ?: ShipEditorPersistenceContext(ship, runtimeShip)
        val includeGroupPrefix = groupIndexes.size > 1
        val rows = mutableListOf<PresetReviewListRow>()
        groupIndexes.forEach { groupIndex ->
            val tags = sanitizeWeaponCompositionPresetTagsForGroup(
                ship,
                groupIndex,
                context.loadWeaponTags(groupIndex, loadoutIndex)
            )
            if (tags.isEmpty()) {
                rows.add(PresetReviewListRow(groupPrefix(groupIndex, includeGroupPrefix) + NO_TAGS_SELECTED))
            } else {
                tags.forEach { tag ->
                    rows.add(PresetReviewListRow(groupPrefix(groupIndex, includeGroupPrefix) + tag))
                }
            }
        }
        return rows
    }

    private fun loadReviewTagRows(
        ship: FleetMemberAPI?,
        groupIndexes: List<Int>,
        state: PresetControlState,
        loadoutIndex: Int,
        presetPeekCache: PresetPeekCache?,
    ): List<PresetReviewListRow> {
        if (ship == null || groupIndexes.isEmpty()) {
            return listOf(PresetReviewListRow(NO_SAVED_TAGS_FOUND))
        }
        val includeGroupPrefix = groupIndexes.size > 1
        val rows = mutableListOf<PresetReviewListRow>()
        groupIndexes.forEach { groupIndex ->
            val tags = PresetPeekCache.peek(
                presetPeekCache,
                ship,
                groupIndex,
                loadoutIndex,
                state.scope,
                state.backend,
            )
                .takeIf { it.status == WeaponCompositionPresetPeekStatus.FOUND }
                ?.tags
                .orEmpty()
            if (tags.isEmpty()) {
                rows.add(PresetReviewListRow(groupPrefix(groupIndex, includeGroupPrefix) + NO_TAGS_SELECTED))
            } else {
                tags.forEach { tag ->
                    rows.add(PresetReviewListRow(groupPrefix(groupIndex, includeGroupPrefix) + tag))
                }
            }
        }
        return rows
    }

    private fun groupPrefix(groupIndex: Int, include: Boolean): String {
        return if (include) "Group ${groupIndex + 1}: " else ""
    }

    private fun loadBody(
        groupIndex: Int,
        state: PresetControlState,
        ship: FleetMemberAPI?,
        loadoutIndex: Int,
        presetPeekCache: PresetPeekCache? = null,
    ): List<CampaignHighlightedText> {
        val weapons = weaponSummary(ship, groupIndex)
        val tags = savedTagSummary(ship, groupIndex, state, loadoutIndex, presetPeekCache)
        val context = loadContextPhrase(ship, weapons, state.scope, loadoutIndex)
        val preset = presetDescriptor(state.scope, state.backend)
        val loadout = loadoutPhraseOrNull(state.scope, loadoutIndex)
        val paragraph = if (groupIndex == CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            allGroupsLoadText(ship, state, preset, loadout)
        } else {
            weaponGroupLoadText(context, tags, preset, groupIndex, loadout)
        }
        return listOf(highlightPresetParagraph(paragraph))
    }

    private fun weaponGroupSaveText(
        context: String,
        tags: String,
        preset: String,
    ): String {
        return "Confirming will, for $context, save this weapon group's current tags ($tags) as the associated $preset tag preset."
    }

    private fun weaponGroupLoadText(
        context: String,
        tags: String,
        preset: String,
        groupIndex: Int,
        loadout: String?,
    ): String {
        return "Confirming will, for $context, load the associated $preset tag preset ($tags) " +
            "into this weapon group (#${groupIndex + 1})${loadoutTargetSuffix(loadout)}."
    }

    private fun allGroupsSaveText(
        ship: FleetMemberAPI?,
        state: PresetControlState,
        preset: String,
        loadout: String?,
    ): String {
        return when (state.scope) {
            WeaponPresetScope.SUGGESTED ->
                "Confirming will, for all eligible single-weapon groups, save each weapon group's current tags as the associated $preset tag preset for that weapon type. Groups with multiple weapon types will be skipped."
            else ->
                "Confirming will, for ${allGroupsContextPhrase(ship, state.scope, loadout)}, save each weapon group's current tags as the associated $preset tag preset for that group's weapon combination."
        }
    }

    private fun allGroupsLoadText(
        ship: FleetMemberAPI?,
        state: PresetControlState,
        preset: String,
        loadout: String?,
    ): String {
        return when (state.scope) {
            WeaponPresetScope.SUGGESTED ->
                "Confirming will, for all weapon groups, load all matching associated $preset tag presets into the appropriate weapon groups. Groups without matching saved presets will be left unchanged."
            else ->
                "Confirming will, for ${allGroupsContextPhrase(ship, state.scope, loadout)}, load all matching associated $preset tag presets into the appropriate weapon groups. Groups without matching saved presets will be left unchanged."
        }
    }

    private fun overwriteScopeParagraph(
        ship: FleetMemberAPI?,
        groupIndex: Int,
        scope: WeaponPresetScope,
    ): CampaignHighlightedText {
        val groupText = if (groupIndex == CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            "all matching weapon combinations"
        } else {
            "all instances of this weapon combination"
        }
        val target = overwriteTargetPhrase(ship, scope)
        val text = "This will also overwrite the active tags of $groupText across all loadouts for $target."
        return CampaignHighlightedText(
            text,
            listOf(
                CampaignTextHighlight(text, CampaignGuiStyle.ALERT_RED_COLOR),
                CampaignTextHighlight("overwrite", CampaignGuiStyle.ALERT_RED_COLOR),
                CampaignTextHighlight("all loadouts", CampaignGuiStyle.ALERT_RED_COLOR),
            )
        )
    }

    private fun highlightPresetParagraph(text: String): CampaignHighlightedText {
        val highlights = bracketedTextHighlights(text).toMutableList()
        if (text.contains("overwrit", ignoreCase = true)) {
            highlights.add(CampaignTextHighlight("overwriting", CampaignGuiStyle.ALERT_RED_COLOR))
            highlights.add(CampaignTextHighlight("overwrite", CampaignGuiStyle.ALERT_RED_COLOR))
        }
        return CampaignHighlightedText(text, highlights)
    }

    private fun bracketedTextHighlights(text: String): List<CampaignTextHighlight> {
        val highlights = mutableListOf<CampaignTextHighlight>()
        var depth = 0
        var start = -1
        text.forEachIndexed { index, char ->
            when (char) {
                '(' -> {
                    if (depth == 0) start = index
                    depth++
                }
                ')' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            highlights.add(
                                CampaignTextHighlight(
                                    text.substring(start, index + 1),
                                    CampaignGuiStyle.MODIFIER_TEXT_COLOUR
                                )
                            )
                            start = -1
                        }
                    }
                }
            }
        }
        return highlights
    }

    private fun storageFamilyPhrase(backend: WeaponPresetBackend): String {
        return CampaignPresetTerminology.bracketedStorageLabel(backend)
    }

    private fun loadContextPhrase(
        ship: FleetMemberAPI?,
        weapons: String,
        scope: WeaponPresetScope,
        loadoutIndex: Int,
    ): String {
        return naturalJoin(contextParts(ship, weapons, scope, includeLoadout = scope != WeaponPresetScope.SUGGESTED, loadoutIndex = loadoutIndex))
    }

    private fun saveContextPhrase(
        ship: FleetMemberAPI?,
        weapons: String,
        scope: WeaponPresetScope,
        loadoutIndex: Int,
    ): String {
        val weaponPart = if (scope == WeaponPresetScope.SUGGESTED) {
            "this weapon type ($weapons)"
        } else {
            "this combination of weapons ($weapons)"
        }
        return naturalJoin(
            contextParts(
                ship,
                weapons,
                scope,
                includeLoadout = scope != WeaponPresetScope.SUGGESTED,
                weaponPart = weaponPart,
                loadoutIndex = loadoutIndex,
            )
        )
    }

    private fun contextParts(
        ship: FleetMemberAPI?,
        weapons: String,
        scope: WeaponPresetScope,
        includeLoadout: Boolean,
        weaponPart: String = "this combination of weapons ($weapons)",
        loadoutIndex: Int,
    ): List<String> {
        val parts = mutableListOf(weaponPart)
        scopeIdentityPhrase(ship, scope)?.let(parts::add)
        if (includeLoadout) parts.add("loadout (${loadoutPhrase(loadoutIndex)})")
        return parts
    }

    private fun allGroupsContextPhrase(ship: FleetMemberAPI?, scope: WeaponPresetScope, loadout: String?): String {
        val parts = mutableListOf("all weapon groups")
        scopeIdentityPhrase(ship, scope)?.let(parts::add)
        parts.add("loadout (${loadout ?: "unknown"})")
        return naturalJoin(parts)
    }

    private fun scopeIdentityPhrase(ship: FleetMemberAPI?, scope: WeaponPresetScope): String? {
        return when (scope) {
            WeaponPresetScope.SINGLE -> "ship ID (${shortShipId(ship)})"
            WeaponPresetScope.CLASS -> "ship class (${ship?.hullSpec?.hullName ?: "unknown"})"
            WeaponPresetScope.GLOBAL,
            WeaponPresetScope.SUGGESTED -> null
        }
    }

    private fun presetDescriptor(
        scope: WeaponPresetScope,
        backend: WeaponPresetBackend,
    ): String {
        val storage = storageFamilyPhrase(backend)
        val scopeText = CampaignPresetTerminology.presetDescriptorScopeLabel(scope)
        return "$storage, $scopeText"
    }

    private fun overwriteTargetPhrase(ship: FleetMemberAPI?, scope: WeaponPresetScope): String {
        return when (scope) {
            WeaponPresetScope.SINGLE -> "this specific ship"
            WeaponPresetScope.CLASS -> {
                val className = ship?.hullSpec?.hullName ?: "this"
                "$className class ships in your fleet and storage${overwriteTargetListSuffix(ship, scope, maxEntries = null)}"
            }
            WeaponPresetScope.GLOBAL ->
                "all ships in your fleet and storage${overwriteTargetListSuffix(ship, scope, maxEntries = 10)}"
            WeaponPresetScope.SUGGESTED -> "suggested presets"
        }
    }

    private fun overwriteTargetListSuffix(
        ship: FleetMemberAPI?,
        scope: WeaponPresetScope,
        maxEntries: Int?,
    ): String {
        val source = ship ?: return ""
        val entries = sortedOverwriteTargetMembers(previewWeaponPresetOverwriteTargetMembers(source, scope))
            .map { member ->
                val name = member.shipName.ifBlank { member.hullSpec.hullName }
                "$name (${shortShipId(member)})"
            }
        if (entries.isEmpty()) return ""
        val visible = maxEntries?.let(entries::take) ?: entries
        val remaining = entries.size - visible.size
        val suffix = if (remaining > 0) {
            visible.joinToString(", ") + ", and $remaining more"
        } else {
            visible.joinToString(", ")
        }
        return " ($suffix)"
    }

    private fun sortedOverwriteTargetMembers(members: List<FleetMemberAPI>): List<FleetMemberAPI> {
        val sorted = members.toMutableList()
        for (i in 1 until sorted.size) {
            val value = sorted[i]
            var j = i - 1
            while (j >= 0 && compareOverwriteTargetMembers(sorted[j], value) > 0) {
                sorted[j + 1] = sorted[j]
                j--
            }
            sorted[j + 1] = value
        }
        return sorted
    }

    private fun compareOverwriteTargetMembers(left: FleetMemberAPI, right: FleetMemberAPI): Int {
        val leftName = left.shipName.ifBlank { left.hullSpec.hullName }
        val rightName = right.shipName.ifBlank { right.hullSpec.hullName }
        val nameComparison = leftName.compareTo(rightName)
        if (nameComparison != 0) return nameComparison
        return left.id.orEmpty().compareTo(right.id.orEmpty())
    }

    private fun shortShipId(ship: FleetMemberAPI?): String {
        return agcShortShipId(ship)
    }

    private fun loadoutPhrase(loadoutIndex: Int): String {
        return Settings.loadoutDisplayName(loadoutIndex)
    }

    private fun loadoutPhraseOrNull(scope: WeaponPresetScope, loadoutIndex: Int): String? {
        return if (scope == WeaponPresetScope.SUGGESTED) null else loadoutPhrase(loadoutIndex)
    }

    private fun loadoutTargetSuffix(loadout: String?): String {
        return loadout?.let { " in this loadout ($it)" }.orEmpty()
    }

    private fun naturalJoin(parts: List<String>): String {
        return when (parts.size) {
            0 -> ""
            1 -> parts.first()
            2 -> "${parts[0]} and ${parts[1]}"
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    private fun weaponSummary(ship: FleetMemberAPI?, groupIndex: Int): String {
        if (ship == null) return PRESET_UNKNOWN_WEAPON_COMBINATION_LABEL
        if (groupIndex != CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            return getWeaponCompositionPresetWeaponNames(ship, groupIndex)
                .joinToString(" + ")
                .ifBlank { PRESET_UNKNOWN_WEAPON_COMBINATION_LABEL }
        }
        val groups = mutableListOf<String>()
        weaponPresetGroupIndexes(ship).forEach { index ->
            val weapons = getWeaponCompositionPresetWeaponNames(ship, index)
                .joinToString(" + ")
            if (weapons.isNotBlank()) {
                groups.add("Group ${index + 1}: $weapons")
            }
        }
        return groups.joinToString("; ").ifBlank { "unknown weapon combinations" }
    }

    private fun currentTagSummary(
        ship: FleetMemberAPI?,
        runtimeShip: ShipAPI?,
        activePersistenceContext: ShipEditorPersistenceContext?,
        groupIndex: Int,
        loadoutIndex: Int,
    ): String {
        if (ship == null) return NO_TAGS_SELECTED
        val context = activePersistenceContext ?: ShipEditorPersistenceContext(ship, runtimeShip)
        if (groupIndex != CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            return sanitizeWeaponCompositionPresetTagsForGroup(
                ship,
                groupIndex,
                context.loadWeaponTags(groupIndex, loadoutIndex)
            ).joinToString(" + ").ifBlank { NO_TAGS_SELECTED }
        }
        val groups = mutableListOf<String>()
        weaponPresetGroupIndexes(ship).forEach { index ->
            val tags = sanitizeWeaponCompositionPresetTagsForGroup(
                ship,
                index,
                context.loadWeaponTags(index, loadoutIndex)
            ).joinToString(" + ").ifBlank { NO_TAGS_SELECTED }
            groups.add("Group ${index + 1}: $tags")
        }
        return groups.joinToString("; ").ifBlank { NO_TAGS_SELECTED }
    }

    private fun savedTagSummary(
        ship: FleetMemberAPI?,
        groupIndex: Int,
        state: PresetControlState,
        loadoutIndex: Int,
        presetPeekCache: PresetPeekCache? = null,
    ): String {
        if (ship == null) return NO_SAVED_TAGS_FOUND
        if (groupIndex != CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            return PresetPeekCache.peek(
                presetPeekCache,
                ship,
                groupIndex,
                loadoutIndex,
                state.scope,
                state.backend,
            )
                .takeIf { it.status == WeaponCompositionPresetPeekStatus.FOUND }
                ?.tags
                ?.joinToString(" + ")
                ?.ifBlank { NO_TAGS_SELECTED }
                ?: NO_SAVED_TAGS_FOUND
        }
        val groups = mutableListOf<String>()
        weaponPresetGroupIndexes(ship).forEach { index ->
            val tags = PresetPeekCache.peek(
                presetPeekCache,
                ship,
                index,
                loadoutIndex,
                state.scope,
                state.backend,
            )
                .takeIf { it.status == WeaponCompositionPresetPeekStatus.FOUND }
                ?.tags
                ?.joinToString(" + ")
                ?.ifBlank { NO_TAGS_SELECTED }
                ?: NO_SAVED_TAGS_FOUND
            groups.add("Group ${index + 1}: $tags")
        }
        return groups.joinToString("; ").ifBlank { NO_SAVED_TAGS_FOUND }
    }

    private fun weaponPresetGroupIndexes(ship: FleetMemberAPI): List<Int> {
        val indexes = mutableListOf<Int>()
        for (index in 0 until Values.MAX_WEAPON_GROUPS) {
            if (getWeaponCompositionPresetKey(ship, index) != null) {
                indexes.add(index)
            }
        }
        return indexes
    }

    private fun singleWeaponPresetGroupIndexes(ship: FleetMemberAPI): List<Int> {
        val indexes = mutableListOf<Int>()
        for (index in 0 until Values.MAX_WEAPON_GROUPS) {
            if (weaponIdsForGroup(ship, index).size == 1) {
                indexes.add(index)
            }
        }
        return indexes
    }

    private fun weaponIdsForGroup(ship: FleetMemberAPI, groupIndex: Int): List<String> {
        val key = getWeaponCompositionPresetKey(ship, groupIndex) ?: return emptyList()
        val ids = mutableListOf<String>()
        key.split("|").forEach { weaponId ->
            if (weaponId.isNotBlank()) {
                ids.add(weaponId)
            }
        }
        return ids
    }
}
