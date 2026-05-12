package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.CustomWeaponTagListStore
import com.dp.advancedgunnerycontrol.typesandvalues.EditableShipModeDefinitions
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinitions
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinition
import com.dp.advancedgunnerycontrol.typesandvalues.fullShipModeNames
import com.dp.advancedgunnerycontrol.typesandvalues.WeaponTagCategory
import com.dp.advancedgunnerycontrol.typesandvalues.WeaponTagListMode
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagName
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagNames
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeName
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeNames
import com.dp.advancedgunnerycontrol.typesandvalues.shipModeDisplayName

internal object CustomTagManagerRows {
    private const val TAG_SECTION_LABEL = "Tags"
    private const val SHIP_MODE_SECTION_LABEL = "Ship Modes"

    fun status(row: CustomTagManagerRow.TagEntry): CustomTagManagerTagStatus {
        return when {
            row.pendingAddition -> CustomTagManagerTagStatus.ADDING
            row.pendingEdit && !row.markedForRemoval -> CustomTagManagerTagStatus.EDITING
            row.markedForRemoval -> CustomTagManagerTagStatus.REMOVING
            else -> CustomTagManagerTagStatus.NORMAL
        }
    }

    fun shipModeStatus(row: CustomTagManagerRow.ShipModeEntry): CustomTagManagerTagStatus {
        return when {
            row.pendingAddition -> CustomTagManagerTagStatus.ADDING
            row.pendingEdit && !row.markedForRemoval -> CustomTagManagerTagStatus.EDITING
            row.markedForRemoval -> CustomTagManagerTagStatus.REMOVING
            else -> CustomTagManagerTagStatus.NORMAL
        }
    }

    fun tagLabel(tag: String, status: CustomTagManagerTagStatus): String {
        val displayTag = EditableWeaponTagDefinitions.displayName(tag)
        return if (status.suffix.isBlank()) displayTag else "$displayTag ${status.suffix}"
    }

    fun shipModeLabel(mode: String, status: CustomTagManagerTagStatus): String {
        val displayMode = shipModeDisplayName(mode)
        return if (status.suffix.isBlank()) displayMode else "$displayMode ${status.suffix}"
    }

    fun highlightTokens(status: CustomTagManagerTagStatus): List<String> {
        return status.suffix.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    }

    fun highlightTokens(label: String, status: CustomTagManagerTagStatus): List<String> {
        return hiddenChangeHighlightTokens(label, highlightTokens(status))
    }

    fun tagTooltip(
        row: CustomTagManagerRow.TagEntry,
        status: CustomTagManagerTagStatus,
        editSourceTag: String?,
    ): String {
        return when (status) {
            CustomTagManagerTagStatus.ADDING -> "Remove pending addition ${row.tag}."
            CustomTagManagerTagStatus.EDITING -> "Revert edit to ${editSourceTag ?: row.tag}."
            CustomTagManagerTagStatus.REMOVING -> "Unmark ${row.tag} for removal."
            CustomTagManagerTagStatus.NORMAL -> "Mark ${row.tag} for removal."
        }
    }

    fun shipModeTooltip(
        row: CustomTagManagerRow.ShipModeEntry,
        status: CustomTagManagerTagStatus,
    ): String {
        val displayMode = shipModeDisplayName(row.mode)
        return when (status) {
            CustomTagManagerTagStatus.ADDING -> "Remove pending ship-mode addition $displayMode."
            CustomTagManagerTagStatus.EDITING -> "Revert edit to $displayMode."
            CustomTagManagerTagStatus.REMOVING -> "Unmark $displayMode for removal."
            CustomTagManagerTagStatus.NORMAL -> "Mark $displayMode for removal."
        }
    }

    fun completeTagListArchetypes(): List<CustomTagArchetype> {
        return archetypes(addCandidates())
    }

    fun addCandidates(): List<CustomTagAddCandidate> {
        val candidates = mutableListOf<CustomTagAddCandidate>()
        val seenDefinitions = mutableSetOf<String>()
        val seenDirectTags = mutableSetOf<String>()

        fun addDefinition(definition: EditableWeaponTagDefinition) {
            if (!seenDefinitions.add(definition.id)) return
            candidates += CustomTagAddCandidate(
                id = "definition:${definition.id}",
                label = definition.templateTag,
                definition = definition,
                directTag = null,
            )
        }

        Settings.getWeaponTagListForMode(WeaponTagListMode.COMPLETE).forEach { tag ->
            val definition = EditableWeaponTagDefinitions.definitionForTemplate(tag)
            if (definition != null) {
                addDefinition(definition)
                return@forEach
            }

            val canonicalTag = canonicalizeWeaponTagName(tag)
            if (!CustomWeaponTagListStore.isSupportedTag(canonicalTag) || !seenDirectTags.add(canonicalTag)) return@forEach
            candidates += CustomTagAddCandidate(
                id = "tag:$canonicalTag",
                label = EditableWeaponTagDefinitions.displayName(canonicalTag),
                definition = null,
                directTag = canonicalTag,
            )
        }

        EditableWeaponTagDefinitions.definitions.forEach(::addDefinition)
        return candidates
    }

    fun changeReviewRows(
        additions: List<String>,
        edits: Map<String, String>,
        removals: List<String>,
        shipModeAdditions: List<String> = emptyList(),
        shipModeEdits: Map<String, String> = emptyMap(),
        shipModeRemovals: List<String> = emptyList(),
        expandedSections: Set<String>,
    ): List<CustomTagChangeReviewRow> {
        val rows = mutableListOf<CustomTagChangeReviewRow>()

        fun addSection(
            section: CustomTagChangeReviewSection,
            labels: List<String>,
        ) {
            val isExpanded = section.id in expandedSections
            rows += CustomTagChangeReviewRow.Heading(section, labels.size, isExpanded)
            if (isExpanded) {
                labels.forEach { label ->
                    rows += CustomTagChangeReviewRow.Entry(label, section.kind)
                }
            }
        }

        val tagChangeCount = additions.size + edits.size + removals.size
        val tagsExpanded = CustomListDraftKeys.ListSections.TAGS in expandedSections
        rows += CustomTagChangeReviewRow.ListHeading(
            id = CustomListDraftKeys.ListSections.TAGS,
            label = TAG_SECTION_LABEL,
            count = tagChangeCount,
            expanded = tagsExpanded,
        )
        if (tagsExpanded) {
            addSection(CustomTagChangeReviewSection.ADDED, sortedDisplayTags(additions))
            addSection(
                CustomTagChangeReviewSection.MODIFIED,
                sortedDisplayEdits(edits)
            )
            addSection(CustomTagChangeReviewSection.REMOVED, sortedDisplayTags(removals))
        }

        val shipModeChangeCount = shipModeAdditions.size + shipModeEdits.size + shipModeRemovals.size
        val shipModesExpanded = CustomListDraftKeys.ListSections.SHIP_MODES in expandedSections
        rows += CustomTagChangeReviewRow.ListHeading(
            id = CustomListDraftKeys.ListSections.SHIP_MODES,
            label = SHIP_MODE_SECTION_LABEL,
            count = shipModeChangeCount,
            expanded = shipModesExpanded,
        )
        if (shipModesExpanded) {
            addSection(CustomTagChangeReviewSection.ADDED, sortedDisplayShipModes(shipModeAdditions))
            addSection(
                CustomTagChangeReviewSection.MODIFIED,
                sortedDisplayShipModeEdits(shipModeEdits)
            )
            addSection(CustomTagChangeReviewSection.REMOVED, sortedDisplayShipModes(shipModeRemovals))
        }
        return rows
    }

    fun archetypes(candidates: List<CustomTagAddCandidate>): List<CustomTagArchetype> {
        return candidates.map { candidate ->
            val category = WeaponTagCategory.categoryFor(candidate.definition?.templateTag ?: candidate.directTag ?: candidate.label)
            CustomTagArchetype(
                id = candidate.id,
                label = candidate.definition?.templateTag ?: candidate.label,
                definition = candidate.definition,
                directTag = candidate.directTag,
                category = category,
            )
        }
    }

    fun build(
        currentTags: List<String>,
        pendingAdditions: List<String>,
        pendingRemovals: Set<String>,
        pendingEdits: Map<String, String>,
        currentShipModes: List<String> = emptyList(),
        pendingShipModeAdditions: List<String> = emptyList(),
        pendingShipModeRemovals: Set<String> = emptySet(),
        pendingShipModeEdits: Map<String, String> = emptyMap(),
        expandedListSections: Set<String> = setOf(CustomListDraftKeys.ListSections.TAGS),
        expandedCategories: Set<String>,
        expandedArchetypes: Set<String>,
        archetypes: List<CustomTagArchetype>,
    ): List<CustomTagManagerRow> {
        val rows = mutableListOf<CustomTagManagerRow>()
        val stagedTags = stagedCustomTags(currentTags, pendingAdditions, pendingRemovals, pendingEdits)
        val visibleTags = canonicalizeWeaponTagNames(stagedTags + pendingRemovals)
        val visibleTagSet = visibleTags.toSet()
        val tagState = CustomTagManagerTagState(
            pendingRemovals = pendingRemovals,
            pendingAdditions = pendingAdditions.toSet(),
            editedTargets = pendingEdits.values.toSet(),
        )

        val tagsExpanded = CustomListDraftKeys.ListSections.TAGS in expandedListSections
        rows += CustomTagManagerRow.ListSectionHeading(
            id = CustomListDraftKeys.ListSections.TAGS,
            label = TAG_SECTION_LABEL,
            expanded = tagsExpanded,
            active = visibleTags.isNotEmpty(),
            count = visibleTags.size,
        )
        if (!tagsExpanded) {
            addShipModeSectionRows(
                rows = rows,
                currentShipModes = currentShipModes,
                pendingShipModeAdditions = pendingShipModeAdditions,
                pendingShipModeRemovals = pendingShipModeRemovals,
                pendingShipModeEdits = pendingShipModeEdits,
                expandedListSections = expandedListSections,
            )
            return rows
        }

        val archetypesByCategory = archetypesByCategory(archetypes)
        val visibleTagsByArchetypeId = visibleTagsByArchetypeId(visibleTags)

        WeaponTagCategory.DISPLAY_CATEGORIES.forEach { category ->
            val categoryArchetypes = archetypesByCategory[category].orEmpty()
            if (categoryArchetypes.isEmpty()) return@forEach
            val categoryActive = categoryArchetypes.any { archetype ->
                visibleTagsByArchetypeId[archetype.id].orEmpty().isNotEmpty()
            }
            val categoryExpanded = category.title in expandedCategories
            rows += CustomTagManagerRow.CategoryHeading(category, categoryExpanded, categoryActive)
            if (!categoryExpanded) return@forEach

            categoryArchetypes.forEach { archetype ->
                val archetypeTags = visibleTagsByArchetypeId[archetype.id].orEmpty()
                val archetypeActive = archetypeTags.isNotEmpty()
                val archetypeExpanded = archetype.id in expandedArchetypes
                rows += CustomTagManagerRow.ArchetypeHeading(archetype, archetypeExpanded, archetypeActive)
                if (!archetypeExpanded) return@forEach

                sortedTags(archetypeTags, tagState).forEach { tag ->
                    rows += CustomTagManagerRow.TagEntry(
                        archetype = archetype,
                        tag = tag,
                        markedForRemoval = tagState.isMarkedForRemoval(tag),
                        pendingAddition = tagState.isPendingAddition(tag),
                        pendingEdit = tagState.isPendingEdit(tag),
                    )
                }
                if (archetype.definition != null || archetype.directTag?.let { it !in visibleTagSet } == true) {
                    rows += CustomTagManagerRow.AddTagEntry(archetype = archetype)
                }
            }
        }
        addShipModeSectionRows(
            rows = rows,
            currentShipModes = currentShipModes,
            pendingShipModeAdditions = pendingShipModeAdditions,
            pendingShipModeRemovals = pendingShipModeRemovals,
            pendingShipModeEdits = pendingShipModeEdits,
            expandedListSections = expandedListSections,
        )
        return rows
    }

    private fun addShipModeSectionRows(
        rows: MutableList<CustomTagManagerRow>,
        currentShipModes: List<String>,
        pendingShipModeAdditions: List<String>,
        pendingShipModeRemovals: Set<String>,
        pendingShipModeEdits: Map<String, String>,
        expandedListSections: Set<String>,
    ) {
        val stagedModes = stagedCustomShipModes(
            currentModes = currentShipModes,
            pendingAdditions = pendingShipModeAdditions,
            pendingRemovals = pendingShipModeRemovals,
            pendingEdits = pendingShipModeEdits,
        )
        val visibleModes = canonicalizeShipModeNames(stagedModes + pendingShipModeRemovals)
        val expanded = CustomListDraftKeys.ListSections.SHIP_MODES in expandedListSections
        rows += CustomTagManagerRow.ListSectionHeading(
            id = CustomListDraftKeys.ListSections.SHIP_MODES,
            label = SHIP_MODE_SECTION_LABEL,
            expanded = expanded,
            active = visibleModes.isNotEmpty(),
            count = visibleModes.size,
        )
        if (!expanded) return

        val pendingAdditions = pendingShipModeAdditions.toSet()
        val pendingEdits = pendingShipModeEdits.values.toSet()
        visibleModes.sortedWith { left, right ->
            compareShipModes(left, right, pendingAdditions, pendingShipModeRemovals, pendingEdits)
        }.forEach { mode ->
            rows += CustomTagManagerRow.ShipModeEntry(
                mode = mode,
                markedForRemoval = mode in pendingShipModeRemovals,
                pendingAddition = mode in pendingAdditions,
                pendingEdit = mode in pendingEdits,
            )
        }

        val visibleSet = visibleModes.toSet()
        EditableShipModeDefinitions.definitions
            .filter { shouldOfferShipModeDefinitionAddRow(it, visibleSet) }
            .forEach { definition ->
            rows += CustomTagManagerRow.AddShipModeEntry(
                mode = definition.templateTag,
                definition = definition,
            )
        }
        fullShipModeNames()
            .map(::canonicalizeShipModeName)
            .filter { shouldOfferDirectShipModeAddRow(it, visibleSet) }
            .forEach { mode -> rows += CustomTagManagerRow.AddShipModeEntry(mode) }
    }

    private fun archetypesByCategory(
        archetypes: List<CustomTagArchetype>,
    ): Map<WeaponTagCategory, List<CustomTagArchetype>> {
        val grouped = linkedMapOf<WeaponTagCategory, MutableList<CustomTagArchetype>>()
        archetypes.forEach { archetype ->
            var categoryArchetypes = grouped[archetype.category]
            if (categoryArchetypes == null) {
                categoryArchetypes = mutableListOf()
                grouped[archetype.category] = categoryArchetypes
            }
            categoryArchetypes.add(archetype)
        }
        return grouped
    }

    private fun visibleTagsByArchetypeId(
        visibleTags: List<String>,
    ): Map<String, List<String>> {
        val grouped = linkedMapOf<String, MutableList<String>>()
        visibleTags.forEach { tag ->
            val archetypeId = archetypeIdForTag(tag)
            var archetypeTags = grouped[archetypeId]
            if (archetypeTags == null) {
                archetypeTags = mutableListOf()
                grouped[archetypeId] = archetypeTags
            }
            archetypeTags.add(tag)
        }
        return grouped
    }

    private fun shouldOfferDirectShipModeAddRow(mode: String, visibleModes: Set<String>): Boolean {
        return canonicalizeShipModeName(mode) !in visibleModes
    }

    private fun shouldOfferShipModeDefinitionAddRow(
        definition: EditableWeaponTagDefinition,
        visibleModes: Set<String>,
    ): Boolean {
        if (definition.parameters.isNotEmpty()) return true
        val canonicalDefault = definition.buildCanonicalTag(EditableWeaponTagDefinitions.defaultValuesFor(definition)).canonicalTag
            ?.let(::canonicalizeShipModeName)
            ?: return true
        return canonicalDefault !in visibleModes
    }

    private fun compareShipModes(
        left: String,
        right: String,
        pendingAdditions: Set<String>,
        pendingRemovals: Set<String>,
        pendingEdits: Set<String>,
    ): Int {
        fun rank(mode: String): Int = when {
            mode in pendingAdditions -> CustomTagManagerTagStatus.ADDING.sortRank
            mode in pendingEdits -> CustomTagManagerTagStatus.EDITING.sortRank
            mode in pendingRemovals -> CustomTagManagerTagStatus.REMOVING.sortRank
            else -> CustomTagManagerTagStatus.NORMAL.sortRank
        }
        val leftRank = rank(left)
        val rightRank = rank(right)
        if (leftRank != rightRank) return leftRank.compareTo(rightRank)
        return left.compareTo(right)
    }

    private fun archetypeIdForTag(tag: String): String {
        val canonicalTag = canonicalizeWeaponTagName(tag)
        val definitionId = EditableWeaponTagDefinitions.parse(canonicalTag)?.definitionId
        return if (definitionId != null) "definition:$definitionId" else "tag:$canonicalTag"
    }

    private fun displayTag(tag: String): String = EditableWeaponTagDefinitions.displayName(tag)

    private fun sortedDisplayTags(tags: List<String>): List<String> {
        val labels = mutableListOf<String>()
        for (tag in tags) {
            labels += displayTag(tag)
        }
        labels.sort()
        return labels
    }

    private fun sortedDisplayEdits(edits: Map<String, String>): List<String> {
        val labels = mutableListOf<String>()
        for ((source, edited) in edits) {
            labels += "${displayTag(source)} -> ${displayTag(edited)}"
        }
        labels.sort()
        return labels
    }

    private fun sortedDisplayShipModes(modes: List<String>): List<String> {
        val labels = mutableListOf<String>()
        for (mode in modes) {
            labels += shipModeDisplayName(mode)
        }
        labels.sort()
        return labels
    }

    private fun sortedDisplayShipModeEdits(edits: Map<String, String>): List<String> {
        val labels = mutableListOf<String>()
        for ((source, edited) in edits) {
            labels += "${shipModeDisplayName(source)} -> ${shipModeDisplayName(edited)}"
        }
        labels.sort()
        return labels
    }

    private fun sortedTags(
        tags: List<String>,
        tagState: CustomTagManagerTagState,
    ): List<String> {
        val sorted = tags.toMutableList()
        for (i in 1 until sorted.size) {
            val value = sorted[i]
            var j = i - 1
            while (j >= 0 && compareTags(sorted[j], value, tagState) > 0) {
                sorted[j + 1] = sorted[j]
                j--
            }
            sorted[j + 1] = value
        }
        return sorted
    }

    private fun compareTags(
        left: String,
        right: String,
        tagState: CustomTagManagerTagState,
    ): Int {
        val leftStatus = tagState.statusFor(left)
        val rightStatus = tagState.statusFor(right)
        if (leftStatus != rightStatus) return leftStatus.sortRank.compareTo(rightStatus.sortRank)
        return left.compareTo(right)
    }
}
