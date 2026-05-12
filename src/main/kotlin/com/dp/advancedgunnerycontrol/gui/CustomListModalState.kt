package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.typesandvalues.ChoiceParameter
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinition
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinitions
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagName
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeName
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagNames
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeNames
import java.awt.Color

internal object CustomListDraftKeys {
    // Safe for AGC-generated tag and ship-mode strings; neither format emits these separators.
    const val STATE_SEPARATOR = "|"
    const val EDIT_PAIR_SEPARATOR = "=>"

    object Tags {
        const val ADDITIONS = "customTagManagerAdditions"
        const val EDITS = "customTagManagerEdits"
        const val EXPANDED_CATEGORIES = "customTagManagerExpandedCategories"
        const val EXPANDED_ARCHETYPES = "customTagManagerExpandedArchetypes"
    }

    object ShipModes {
        const val ADDITIONS = "customShipModeManagerAdditions"
        const val REMOVALS = "customShipModeManagerRemovals"
        const val EDITS = "customShipModeManagerEdits"
    }

    object ListSections {
        const val TAGS = "tags"
        const val SHIP_MODES = "shipModes"
        const val EXPANDED_MANAGER_SECTIONS = "customListManagerExpandedSections"
    }

    object ChangeReview {
        const val EXPANDED_SECTIONS = "customTagChangeReviewExpandedSections"
    }

    object Edit {
        const val KIND = "customTagEditKind"
        const val KIND_TAG = "tag"
        const val KIND_SHIP_MODE = "shipMode"
        const val RETURN = "customTagEditReturn"
        const val RETURN_MANAGER = "manager"
        const val PENDING_ADDITION = "customTagEditPendingAddition"
        const val FIXED_TAG = "customTagEditFixedTag"
    }

    object DebugColor {
        const val INDEX = "debugColorIndex"
        const val RED = "debugColorR"
        const val GREEN = "debugColorG"
        const val BLUE = "debugColorB"
        const val PERSISTENCE = "debugColorPersistence"
    }

    object LoadoutRename {
        const val INDEX = "loadoutIndex"
        const val NAME = "loadoutName"
    }
}

internal data class CustomTagManagerStagedState(
    val additions: List<String>,
    val removals: Set<String>,
    val edits: Map<String, String>,
)

internal data class CustomShipModeManagerStagedState(
    val additions: List<String>,
    val removals: Set<String>,
    val edits: Map<String, String> = emptyMap(),
)

internal fun stagedCustomTags(
    currentTags: List<String>,
    pendingAdditions: List<String>,
    pendingRemovals: Set<String>,
    pendingEdits: Map<String, String>,
): List<String> {
    val canonicalRemovals = canonicalizeWeaponTagNames(pendingRemovals.toList()).toSet()
    val editSources = canonicalizeWeaponTagNames(pendingEdits.keys.toList()).toSet()
    return canonicalizeWeaponTagNames(
        currentTags
            .filterNot {
                val canonical = canonicalizeWeaponTagName(it)
                canonical in canonicalRemovals || canonical in editSources
            } +
            pendingEdits.values +
            pendingAdditions
    )
}

internal fun stagedCustomShipModes(
    currentModes: List<String>,
    pendingAdditions: List<String>,
    pendingRemovals: Set<String>,
    pendingEdits: Map<String, String>,
): List<String> {
    val canonicalRemovals = canonicalizeShipModeNames(pendingRemovals.toList()).toSet()
    val editSources = canonicalizeShipModeNames(pendingEdits.keys.toList()).toSet()
    return canonicalizeShipModeNames(
        currentModes
            .filterNot {
                val canonical = canonicalizeShipModeName(it)
                canonical in canonicalRemovals || canonical in editSources
            } +
            pendingEdits.values +
            pendingAdditions
    )
}

internal interface CustomListModalChangeHandler {
    fun onCustomListModalStateChanged()
}

internal class CustomListModalStateController(
    bindings: CustomListModalBindings,
    private val changeHandler: CustomListModalChangeHandler,
) {
    private val modalModeProvider = bindings.modalModeProvider
    private val onModalModeUpdate = bindings.onModalModeUpdate
    private val onDraftDefinitionIdUpdate = bindings.onDraftDefinitionIdUpdate
    private val draftValuesProvider = bindings.draftValuesProvider
    private val onDraftValuesUpdate = bindings.onDraftValuesUpdate
    private val onEditSourceGroupUpdate = bindings.onEditSourceGroupUpdate
    private val onEditSourceTagUpdate = bindings.onEditSourceTagUpdate
    private val onManagerScrollOffsetUpdate = bindings.onManagerScrollOffsetUpdate
    private val onChangeReviewScrollOffsetUpdate = bindings.onChangeReviewScrollOffsetUpdate
    private val markedForRemovalProvider = bindings.markedForRemovalProvider
    private val onMarkedForRemovalUpdate = bindings.onMarkedForRemovalUpdate

    fun isManagerReturnEdit(): Boolean {
        return draftValue(CustomListDraftKeys.Edit.RETURN) == CustomListDraftKeys.Edit.RETURN_MANAGER
    }

    fun managerEditSourceTag(sourceGroupIndex: Int?, sourceTag: String?): String? {
        if (!isManagerReturnEdit()) return null
        if (sourceGroupIndex != null) return null
        return sourceTag
            ?.takeIf { it.isNotBlank() }
            ?.let { if (isShipModeEdit()) canonicalShipMode(it) else canonicalTag(it) }
    }

    fun isShipModeEdit(): Boolean =
        draftValue(CustomListDraftKeys.Edit.KIND) == CustomListDraftKeys.Edit.KIND_SHIP_MODE

    fun managerEditIsPendingAddition(): Boolean {
        return draftValuesProvider?.invoke()
            ?.get(CustomListDraftKeys.Edit.PENDING_ADDITION)
            ?.toBooleanStrictOrNull()
            ?: false
    }

    fun additions(): MutableList<String> =
        canonicalDraftList(
            key = CustomListDraftKeys.Tags.ADDITIONS,
            canonicalize = ::canonicalTag,
        ).toMutableList()

    fun setAdditions(tags: List<String>) {
        writeCanonicalDraftList(
            key = CustomListDraftKeys.Tags.ADDITIONS,
            values = tags,
            canonicalize = ::canonicalTag,
        )
    }

    fun edits(): Map<String, String> =
        canonicalEditMap(
            key = CustomListDraftKeys.Tags.EDITS,
            canonicalize = ::canonicalTag,
        )

    fun setEdits(edits: Map<String, String>) {
        writeCanonicalEditMap(
            key = CustomListDraftKeys.Tags.EDITS,
            edits = edits,
            canonicalize = ::canonicalTag,
        )
    }

    fun currentMarkedForRemoval(): MutableSet<String> {
        return markedForRemovalProvider?.invoke()?.toMutableSet() ?: mutableSetOf()
    }

    fun toggleMarkedForRemoval(tag: String) {
        val marked = currentMarkedForRemoval()
        if (!marked.add(tag)) {
            marked.remove(tag)
        }
        updateMarkedForRemoval(marked)
    }

    fun normalizeStagedState(currentTags: List<String>): CustomTagManagerStagedState {
        val currentSet = canonicalizeWeaponTagNames(currentTags).toSet()
        val rawAdditions = additions()
        val additions = rawAdditions.filterNot { it in currentSet }.distinct()
        if (additions != rawAdditions) {
            setAdditions(additions)
        }

        val rawRemovals = currentMarkedForRemoval()
        val removalSources = rawRemovals.filter { it in currentSet }.toSet()
        val rawEdits = edits()
        val edits = rawEdits
            .filter { (source, edited) ->
                source in currentSet &&
                    source !in removalSources &&
                    source != edited &&
                    edited !in additions &&
                    edited !in (currentSet - source)
            }
        if (edits != rawEdits) {
            setEdits(edits)
        }

        val editSources = edits.keys
        val editTargets = edits.values.toSet()
        val removals = rawRemovals
            .filter { it in currentSet && (it !in editSources || it in removalSources) && it !in editTargets }
            .toMutableSet()
        if (removals != rawRemovals) {
            updateMarkedForRemoval(removals)
        }
        return CustomTagManagerStagedState(additions, removals, edits)
    }

    fun stagedTags(
        currentTags: List<String>,
        pendingAdditions: List<String>,
        pendingRemovals: Set<String>,
        pendingEdits: Map<String, String>,
    ): List<String> =
        stagedCustomTags(currentTags, pendingAdditions, pendingRemovals, pendingEdits)

    fun stageAddition(tag: String, currentTags: List<String>) {
        val canonicalTag = canonicalTag(tag)
        val currentSet = canonicalizeWeaponTagNames(currentTags).toSet()
        val additions = additions()
        val removals = currentMarkedForRemoval()
        removals.remove(canonicalTag)
        updateMarkedForRemoval(removals)
        if (canonicalTag !in currentSet && canonicalTag !in additions) {
            additions += canonicalTag
            setAdditions(additions)
        } else {
            changeHandler.onCustomListModalStateChanged()
        }
    }

    fun removeAddition(tag: String) {
        val canonicalTag = canonicalTag(tag)
        setAdditions(additions().filterNot { it == canonicalTag })
    }

    fun replaceAddition(sourceTag: String, editedTag: String) {
        val canonicalSource = canonicalTag(sourceTag)
        val canonicalEdited = canonicalTag(editedTag)
        val additions = additions()
            .filterNot { it == canonicalSource || it == canonicalEdited }
            .toMutableList()
        additions += canonicalEdited
        setAdditions(additions)
    }

    fun stageEdit(sourceTag: String, editedTag: String) {
        val canonicalSource = canonicalTag(sourceTag)
        val canonicalEdited = canonicalTag(editedTag)
        val edits = edits().toMutableMap()
        if (canonicalSource == canonicalEdited) {
            edits.remove(canonicalSource)
        } else {
            edits[canonicalSource] = canonicalEdited
        }
        val removals = currentMarkedForRemoval()
        removals.remove(canonicalSource)
        removals.remove(canonicalEdited)
        updateMarkedForRemoval(removals)
        setEdits(edits)
    }

    fun sourceForEdit(editedTag: String): String? {
        val canonicalEdited = canonicalTag(editedTag)
        return edits().entries.firstOrNull { it.value == canonicalEdited }?.key
    }

    fun expandedCategories(): MutableSet<String> = draftValueList(CustomListDraftKeys.Tags.EXPANDED_CATEGORIES).toMutableSet()

    fun expandedArchetypes(): MutableSet<String> = draftValueList(CustomListDraftKeys.Tags.EXPANDED_ARCHETYPES).toMutableSet()

    fun changeReviewExpandedSections(defaultSections: Collection<String>): MutableSet<String> {
        val saved = draftValuesProvider?.invoke()
            ?.get(CustomListDraftKeys.ChangeReview.EXPANDED_SECTIONS)
            ?.split(CustomListDraftKeys.STATE_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.toMutableSet()
        return saved ?: defaultSections.toMutableSet()
    }

    fun toggleManagerCategory(categoryTitle: String) {
        toggleDraftSet(CustomListDraftKeys.Tags.EXPANDED_CATEGORIES, categoryTitle)
    }

    fun toggleManagerArchetype(archetypeId: String) {
        toggleDraftSet(CustomListDraftKeys.Tags.EXPANDED_ARCHETYPES, archetypeId)
    }

    fun expandedListSections(): MutableSet<String> {
        return draftValueList(CustomListDraftKeys.ListSections.EXPANDED_MANAGER_SECTIONS).toMutableSet()
    }

    fun toggleListSection(sectionId: String) {
        toggleDraftSet(CustomListDraftKeys.ListSections.EXPANDED_MANAGER_SECTIONS, sectionId)
    }

    fun shipModeAdditions(): MutableList<String> =
        canonicalDraftList(
            key = CustomListDraftKeys.ShipModes.ADDITIONS,
            canonicalize = ::canonicalShipMode,
        ).toMutableList()

    fun setShipModeAdditions(modes: List<String>) {
        writeCanonicalDraftList(
            key = CustomListDraftKeys.ShipModes.ADDITIONS,
            values = modes,
            canonicalize = ::canonicalShipMode,
        )
    }

    fun shipModeRemovals(): MutableSet<String> =
        canonicalDraftList(
            key = CustomListDraftKeys.ShipModes.REMOVALS,
            canonicalize = ::canonicalShipMode,
        ).toMutableSet()

    fun setShipModeRemovals(modes: Set<String>) {
        writeCanonicalDraftList(
            key = CustomListDraftKeys.ShipModes.REMOVALS,
            values = modes.toList(),
            canonicalize = ::canonicalShipMode,
        )
    }

    fun shipModeEdits(): Map<String, String> =
        canonicalEditMap(
            key = CustomListDraftKeys.ShipModes.EDITS,
            canonicalize = ::canonicalShipMode,
        )

    fun setShipModeEdits(edits: Map<String, String>) {
        writeCanonicalEditMap(
            key = CustomListDraftKeys.ShipModes.EDITS,
            edits = edits,
            canonicalize = ::canonicalShipMode,
        )
    }

    fun toggleShipModeMarkedForRemoval(mode: String) {
        val canonicalMode = canonicalShipMode(mode)
        val removals = shipModeRemovals()
        if (!removals.add(canonicalMode)) removals.remove(canonicalMode)
        setShipModeRemovals(removals)
    }

    fun stageShipModeAddition(mode: String, currentModes: List<String>) {
        val canonicalMode = canonicalShipMode(mode)
        val currentSet = currentModes.map(::canonicalShipMode).toSet()
        val additions = shipModeAdditions()
        val removals = shipModeRemovals()
        removals.remove(canonicalMode)
        setShipModeRemovals(removals)
        if (canonicalMode !in currentSet && canonicalMode !in additions) {
            additions += canonicalMode
            setShipModeAdditions(additions)
        } else {
            changeHandler.onCustomListModalStateChanged()
        }
    }

    fun removeShipModeAddition(mode: String) {
        val canonicalMode = canonicalShipMode(mode)
        setShipModeAdditions(shipModeAdditions().filterNot { it == canonicalMode })
    }

    fun normalizeShipModeStagedState(currentModes: List<String>): CustomShipModeManagerStagedState {
        val currentSet = currentModes.map(::canonicalShipMode).toSet()
        val rawAdditions = shipModeAdditions()
        val additions = rawAdditions.filterNot { it in currentSet }.distinct()
        if (additions != rawAdditions) {
            setShipModeAdditions(additions)
        }

        val rawRemovals = shipModeRemovals()
        val rawEdits = shipModeEdits()
        val edits = rawEdits
            .filter { (source, edited) ->
                source in currentSet &&
                    source !in rawRemovals &&
                    source != edited &&
                    edited !in additions &&
                    edited !in (currentSet - source)
            }
        if (edits != rawEdits) {
            setShipModeEdits(edits)
        }
        val editTargets = edits.values.toSet()
        // Removal wins over edit: an edited source marked for removal is shown
        // and applied as a single removal, not as both an edit and a removal.
        val removals = rawRemovals
            .filter { it in currentSet && it !in editTargets }
            .toSet()
        if (removals != rawRemovals) {
            setShipModeRemovals(removals)
        }
        return CustomShipModeManagerStagedState(additions, removals, edits)
    }

    fun stagedShipModes(
        currentModes: List<String>,
        pendingAdditions: List<String>,
        pendingRemovals: Set<String>,
        pendingEdits: Map<String, String>,
    ): List<String> =
        stagedCustomShipModes(currentModes, pendingAdditions, pendingRemovals, pendingEdits)

    fun stageShipModeEdit(sourceMode: String, editedMode: String) {
        val canonicalSource = canonicalShipMode(sourceMode)
        val canonicalEdited = canonicalShipMode(editedMode)
        val edits = shipModeEdits().toMutableMap()
        if (canonicalSource == canonicalEdited) {
            edits.remove(canonicalSource)
        } else {
            edits[canonicalSource] = canonicalEdited
        }
        val removals = shipModeRemovals()
        removals.remove(canonicalSource)
        removals.remove(canonicalEdited)
        setShipModeRemovals(removals)
        setShipModeEdits(edits)
    }

    fun replaceShipModeAddition(sourceMode: String, editedMode: String) {
        val canonicalSource = canonicalShipMode(sourceMode)
        val canonicalEdited = canonicalShipMode(editedMode)
        val additions = shipModeAdditions()
            .filterNot { it == canonicalSource || it == canonicalEdited }
            .toMutableList()
        additions += canonicalEdited
        setShipModeAdditions(additions)
    }

    fun sourceForShipModeEdit(editedMode: String): String? {
        val canonicalEdited = canonicalShipMode(editedMode)
        return shipModeEdits().entries.firstOrNull { it.value == canonicalEdited }?.key
    }

    fun toggleChangeReviewSection(sectionId: String, defaultSections: Collection<String>) {
        val expanded = changeReviewExpandedSections(defaultSections)
        if (!expanded.add(sectionId)) expanded.remove(sectionId)
        writeDraftValue(CustomListDraftKeys.ChangeReview.EXPANDED_SECTIONS, expanded.joinToString(CustomListDraftKeys.STATE_SEPARATOR))
    }

    fun fixedEditTag(): String? {
        return draftValue(CustomListDraftKeys.Edit.FIXED_TAG)
            ?.takeIf { it.isNotBlank() }
            ?.let(::canonicalTag)
    }

    fun draftValuesFor(definition: EditableWeaponTagDefinition): MutableMap<String, String> {
        val current = draftValuesProvider?.invoke()?.toMutableMap() ?: mutableMapOf()
        EditableWeaponTagDefinitions.defaultValuesFor(definition).forEach { (key, value) ->
            current.putIfAbsent(key, value)
        }
        return current
    }

    fun updateDraftValue(definition: EditableWeaponTagDefinition, parameterId: String, value: String) {
        val values = draftValuesFor(definition)
        values[parameterId] = value
        writeDraftValues(values)
    }

    fun resetDraftValuesToDefaults(definition: EditableWeaponTagDefinition) {
        val values = draftValuesProvider?.invoke()?.toMutableMap() ?: mutableMapOf()
        for (parameter in definition.parameters) {
            values.remove(parameter.id)
        }
        val defaults = EditableWeaponTagDefinitions.defaultValuesFor(definition)
        for (parameter in definition.parameters) {
            defaults[parameter.id]?.let { value -> values[parameter.id] = value }
        }
        writeDraftValues(values)
    }

    fun cycleDraftChoice(definition: EditableWeaponTagDefinition, parameter: ChoiceParameter, delta: Int = 1) {
        val values = draftValuesFor(definition)
        val current = values[parameter.id] ?: parameter.defaultOptionId
        val currentIndex = parameter.options.indexOfFirst { it.id == current }.coerceAtLeast(0)
        val next = parameter.options[(currentIndex + delta + parameter.options.size) % parameter.options.size]
        updateDraftValue(definition, parameter.id, next.id)
    }

    fun beginStandaloneEdit(definition: EditableWeaponTagDefinition) {
        val values = EditableWeaponTagDefinitions.defaultValuesFor(definition).toMutableMap()
        values.remove(CustomListDraftKeys.Edit.FIXED_TAG)
        openEditModal(definition.id, values, sourceGroupIndex = null, sourceTag = null)
    }

    fun beginManagerEdit(definition: EditableWeaponTagDefinition) {
        val values = managerEditDraftValues(definition, CustomListDraftKeys.Edit.KIND_TAG)
        openEditModal(definition.id, values, sourceGroupIndex = null, sourceTag = null)
    }

    fun beginManagerEditFromTag(
        definition: EditableWeaponTagDefinition,
        sourceTag: String,
        pendingAddition: Boolean,
        parameterValues: Map<String, String> = emptyMap(),
    ) {
        val values = managerEditDraftValues(
            definition = definition,
            editKind = CustomListDraftKeys.Edit.KIND_TAG,
            parameterValues = parameterValues,
            pendingAddition = pendingAddition,
        )
        openEditModal(definition.id, values, sourceGroupIndex = null, sourceTag = canonicalTag(sourceTag))
    }

    fun beginManagerEditShipMode(definition: EditableWeaponTagDefinition) {
        val values = managerEditDraftValues(definition, CustomListDraftKeys.Edit.KIND_SHIP_MODE)
        openEditModal(definition.id, values, sourceGroupIndex = null, sourceTag = null)
    }

    fun beginManagerEditFromShipMode(
        definition: EditableWeaponTagDefinition,
        sourceMode: String,
        pendingAddition: Boolean,
        parameterValues: Map<String, String> = emptyMap(),
    ) {
        val values = managerEditDraftValues(
            definition = definition,
            editKind = CustomListDraftKeys.Edit.KIND_SHIP_MODE,
            parameterValues = parameterValues,
            pendingAddition = pendingAddition,
        )
        openEditModal(definition.id, values, sourceGroupIndex = null, sourceTag = canonicalShipMode(sourceMode))
    }

    fun beginRightClickEdit(
        definition: EditableWeaponTagDefinition?,
        tag: String,
        sourceGroupIndex: Int,
        parameterValues: Map<String, String> = emptyMap(),
    ) {
        val canonicalTag = canonicalTag(tag)
        val values = if (definition == null) {
            mutableMapOf(CustomListDraftKeys.Edit.FIXED_TAG to canonicalTag)
        } else {
            (if (parameterValues.isEmpty()) EditableWeaponTagDefinitions.defaultValuesFor(definition) else parameterValues)
                .toMutableMap()
                .also { it.remove(CustomListDraftKeys.Edit.FIXED_TAG) }
        }
        openEditModal(definition?.id, values, sourceGroupIndex = sourceGroupIndex, sourceTag = canonicalTag)
    }

    fun beginRightClickEditShipMode(
        definition: EditableWeaponTagDefinition,
        mode: String,
        parameterValues: Map<String, String> = emptyMap(),
    ) {
        val canonicalMode = canonicalShipMode(mode)
        val values = (if (parameterValues.isEmpty()) EditableWeaponTagDefinitions.defaultValuesFor(definition) else parameterValues)
            .toMutableMap()
        values[CustomListDraftKeys.Edit.KIND] = CustomListDraftKeys.Edit.KIND_SHIP_MODE
        values.remove(CustomListDraftKeys.Edit.RETURN)
        values.remove(CustomListDraftKeys.Edit.PENDING_ADDITION)
        values.remove(CustomListDraftKeys.Edit.FIXED_TAG)
        openEditModal(definition.id, values, sourceGroupIndex = -1, sourceTag = canonicalMode)
    }

    fun returnToManagerFromEdit() {
        val values = draftValuesProvider?.invoke()?.toMutableMap() ?: mutableMapOf()
        values.remove(CustomListDraftKeys.Edit.RETURN)
        values.remove(CustomListDraftKeys.Edit.KIND)
        values.remove(CustomListDraftKeys.Edit.PENDING_ADDITION)
        values.remove(CustomListDraftKeys.Edit.FIXED_TAG)
        onDraftValuesUpdate?.invoke(values)
        clearEditSource()
        onModalModeUpdate?.invoke(CustomListModalMode.MANAGE_TAGS)
        changeHandler.onCustomListModalStateChanged()
    }

    private fun managerEditDraftValues(
        definition: EditableWeaponTagDefinition,
        editKind: String,
        parameterValues: Map<String, String> = emptyMap(),
        pendingAddition: Boolean? = null,
    ): MutableMap<String, String> {
        val values = draftValuesProvider?.invoke()?.toMutableMap() ?: mutableMapOf()
        EditableWeaponTagDefinitions.defaultValuesFor(definition).forEach { (key, value) -> values[key] = value }
        parameterValues.forEach { (key, value) -> values[key] = value }
        values[CustomListDraftKeys.Edit.RETURN] = CustomListDraftKeys.Edit.RETURN_MANAGER
        values[CustomListDraftKeys.Edit.KIND] = editKind
        if (pendingAddition == null) {
            values.remove(CustomListDraftKeys.Edit.PENDING_ADDITION)
        } else {
            values[CustomListDraftKeys.Edit.PENDING_ADDITION] = pendingAddition.toString()
        }
        values.remove(CustomListDraftKeys.Edit.FIXED_TAG)
        return values
    }

    fun closeModal() {
        val closingMode = modalModeProvider?.invoke()
        onModalModeUpdate?.invoke(null)
        onDraftDefinitionIdUpdate?.invoke(null)
        if (closingMode != CustomListModalMode.DEBUG_COLORS) {
            onDraftValuesUpdate?.invoke(emptyMap())
        }
        clearEditSource()
        onManagerScrollOffsetUpdate?.invoke(0)
        onChangeReviewScrollOffsetUpdate?.invoke(0)
        onMarkedForRemovalUpdate?.invoke(emptySet())
        changeHandler.onCustomListModalStateChanged()
    }

    fun debugColorIndex(maxIndex: Int): Int {
        return (draftValue(CustomListDraftKeys.DebugColor.INDEX)?.toIntOrNull() ?: 0).coerceIn(0, maxIndex.coerceAtLeast(0))
    }

    fun setDebugColorIndex(index: Int) {
        writeDraftValue(CustomListDraftKeys.DebugColor.INDEX, index.toString())
    }

    fun debugColorDraft(fallback: Color): Color {
        return Color(
            (draftValue(CustomListDraftKeys.DebugColor.RED)?.toIntOrNull() ?: fallback.red).coerceIn(0, 255),
            (draftValue(CustomListDraftKeys.DebugColor.GREEN)?.toIntOrNull() ?: fallback.green).coerceIn(0, 255),
            (draftValue(CustomListDraftKeys.DebugColor.BLUE)?.toIntOrNull() ?: fallback.blue).coerceIn(0, 255),
            fallback.alpha
        )
    }

    fun hasDebugColorDraftRgb(): Boolean {
        val values = draftValuesProvider?.invoke() ?: return false
        return values.containsKey(CustomListDraftKeys.DebugColor.RED) &&
            values.containsKey(CustomListDraftKeys.DebugColor.GREEN) &&
            values.containsKey(CustomListDraftKeys.DebugColor.BLUE)
    }

    fun setDebugColorDraft(color: Color) {
        val values = draftValuesProvider?.invoke()?.toMutableMap() ?: mutableMapOf()
        values[CustomListDraftKeys.DebugColor.RED] = color.red.coerceIn(0, 255).toString()
        values[CustomListDraftKeys.DebugColor.GREEN] = color.green.coerceIn(0, 255).toString()
        values[CustomListDraftKeys.DebugColor.BLUE] = color.blue.coerceIn(0, 255).toString()
        writeDraftValues(values)
    }

    fun debugColorPersistent(): Boolean {
        return draftValue(CustomListDraftKeys.DebugColor.PERSISTENCE)?.toBooleanStrictOrNull() ?: false
    }

    fun setDebugColorPersistent(persistent: Boolean) {
        writeDraftValue(CustomListDraftKeys.DebugColor.PERSISTENCE, persistent.toString())
    }

    fun loadoutRenameIndex(defaultIndex: Int, maxLoadouts: Int): Int {
        return (draftValue(CustomListDraftKeys.LoadoutRename.INDEX)?.toIntOrNull() ?: defaultIndex)
            .coerceIn(0, maxLoadouts.coerceAtLeast(1) - 1)
    }

    fun loadoutRenameDraft(defaultIndex: Int, maxLoadouts: Int, displayName: (Int) -> String): String {
        val index = loadoutRenameIndex(defaultIndex, maxLoadouts)
        return draftValue(CustomListDraftKeys.LoadoutRename.NAME) ?: displayName(index)
    }

    fun setLoadoutRenameDraft(index: Int, name: String) {
        val values = draftValuesProvider?.invoke()?.toMutableMap() ?: mutableMapOf()
        values[CustomListDraftKeys.LoadoutRename.INDEX] = index.toString()
        values[CustomListDraftKeys.LoadoutRename.NAME] = name.take(24)
        writeDraftValues(values)
    }

    private fun toggleDraftSet(key: String, value: String) {
        val expanded = draftValueList(key).toMutableSet()
        if (!expanded.add(value)) expanded.remove(value)
        writeDraftValue(key, expanded.joinToString(CustomListDraftKeys.STATE_SEPARATOR))
    }

    private fun draftValue(key: String): String? {
        return draftValuesProvider?.invoke()?.get(key)
    }

    private fun draftValueList(key: String): List<String> {
        return draftValuesProvider?.invoke()
            ?.get(key)
            ?.split(CustomListDraftKeys.STATE_SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun canonicalDraftList(
        key: String,
        canonicalize: (String) -> String,
    ): List<String> =
        draftValueList(key)
            .map(canonicalize)
            .filter { it.isNotBlank() }
            .distinct()

    private fun writeCanonicalDraftList(
        key: String,
        values: List<String>,
        canonicalize: (String) -> String,
    ) {
        writeDraftValue(
            key,
            values
                .map(canonicalize)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(CustomListDraftKeys.STATE_SEPARATOR),
        )
    }

    private fun canonicalEditMap(
        key: String,
        canonicalize: (String) -> String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        draftValueList(key).forEach { pair ->
            val split = pair.split(CustomListDraftKeys.EDIT_PAIR_SEPARATOR, limit = 2)
            if (split.size != 2) return@forEach
            val source = canonicalize(split[0])
            val edited = canonicalize(split[1])
            if (source.isNotBlank() && edited.isNotBlank()) {
                result[source] = edited
            }
        }
        return result
    }

    private fun writeCanonicalEditMap(
        key: String,
        edits: Map<String, String>,
        canonicalize: (String) -> String,
    ) {
        val serialized = edits.entries
            .mapNotNull { (source, edited) ->
                val canonicalSource = canonicalize(source)
                val canonicalEdited = canonicalize(edited)
                if (
                    canonicalSource.isBlank() ||
                    canonicalEdited.isBlank() ||
                    canonicalSource == canonicalEdited
                ) {
                    null
                } else {
                    "$canonicalSource${CustomListDraftKeys.EDIT_PAIR_SEPARATOR}$canonicalEdited"
                }
            }
            .joinToString(CustomListDraftKeys.STATE_SEPARATOR)
        writeDraftValue(key, serialized)
    }

    private fun canonicalTag(tag: String): String = canonicalizeWeaponTagName(tag)

    private fun canonicalShipMode(mode: String): String =
        com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeName(mode)

    private fun writeDraftValue(key: String, value: String) {
        val values = draftValuesProvider?.invoke()?.toMutableMap() ?: mutableMapOf()
        values[key] = value
        writeDraftValues(values)
    }

    private fun writeDraftValues(values: Map<String, String>) {
        onDraftValuesUpdate?.invoke(values)
        changeHandler.onCustomListModalStateChanged()
    }

    private fun updateMarkedForRemoval(tags: Set<String>) {
        onMarkedForRemovalUpdate?.invoke(tags)
        changeHandler.onCustomListModalStateChanged()
    }

    private fun openEditModal(
        definitionId: String?,
        values: Map<String, String>,
        sourceGroupIndex: Int?,
        sourceTag: String?,
    ) {
        onDraftDefinitionIdUpdate?.invoke(definitionId)
        onDraftValuesUpdate?.invoke(values)
        onEditSourceGroupUpdate?.invoke(sourceGroupIndex)
        onEditSourceTagUpdate?.invoke(sourceTag)
        onModalModeUpdate?.invoke(CustomListModalMode.EDIT_TAG)
        changeHandler.onCustomListModalStateChanged()
    }

    private fun clearEditSource() {
        onDraftDefinitionIdUpdate?.invoke(null)
        onEditSourceGroupUpdate?.invoke(null)
        onEditSourceTagUpdate?.invoke(null)
    }
}
