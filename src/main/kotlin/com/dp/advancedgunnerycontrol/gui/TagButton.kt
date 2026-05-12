package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinitions
import com.dp.advancedgunnerycontrol.typesandvalues.TagListView
import com.dp.advancedgunnerycontrol.typesandvalues.disabledTagsForGroup
import com.dp.advancedgunnerycontrol.typesandvalues.getTagTooltip
import com.dp.advancedgunnerycontrol.typesandvalues.isIncompatibleWithExistingTags
import com.dp.advancedgunnerycontrol.typesandvalues.shouldTagBeDisabled
import com.dp.advancedgunnerycontrol.typesandvalues.tagUnavailableReasonForWeaponGroup
import com.dp.advancedgunnerycontrol.utils.agcStableShipId
import com.dp.advancedgunnerycontrol.utils.loadPersistentTags
import com.dp.advancedgunnerycontrol.utils.ShipEditorPersistenceContext
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

/**
 * Visible weapon-tag row component.
 * Used in weapon-group tag lists and legacy tooltip paths to toggle one tag for
 * one weapon group.
 */
class TagButton(
    var ship: FleetMemberAPI,
    var group: Int,
    tag: String,
    button: ButtonAPI,
    private val runtimeShip: ShipAPI? = null,
    private val visibleTagsForSanitization: List<String> = Settings.getCurrentWeaponTagList(),
    private val onSelectionChanged: ((List<String>) -> Unit)? = null,
    private val selectedTagsSnapshot: List<String>? = null,
    private val unavailableTagsSnapshot: Set<String>? = null,
    private val persistenceContext: ShipEditorPersistenceContext? = null,
    useMomentaryVisualState: Boolean = false,
) :
    ButtonBase<String>(tag, button, false, useMomentaryVisualState) {

    private val visualState = CampaignToggleVisualState()

    companion object {
        var campaignTagSelectionVersion = 0
            private set

        fun notifyCampaignTagSelectionChanged() {
            campaignTagSelectionVersion++
        }

        private data class CampaignTagButtonId(val group: Int, val tag: String)

        private fun loadTagsForContext(
            ship: FleetMemberAPI,
            group: Int,
            runtimeShip: ShipAPI?,
            persistenceContext: ShipEditorPersistenceContext? = null,
        ): List<String> {
            return (persistenceContext ?: ShipEditorPersistenceContext(ship, runtimeShip))
                .loadWeaponTags(group, AGCGUI.storageIndex)
        }

        private fun saveTagsForContext(
            ship: FleetMemberAPI,
            group: Int,
            runtimeShip: ShipAPI?,
            tags: List<String>,
            persistenceContext: ShipEditorPersistenceContext? = null,
        ) {
            (persistenceContext ?: ShipEditorPersistenceContext(ship, runtimeShip))
                .saveWeaponTags(group, AGCGUI.storageIndex, tags)
        }

        fun repairAndPersistSelectedTags(
            ship: FleetMemberAPI,
            group: Int,
            runtimeShip: ShipAPI? = null,
            visibleTags: List<String> = Settings.getCurrentWeaponTagList(),
            loadedTagsOverride: List<String>? = null,
            persistenceContext: ShipEditorPersistenceContext? = null,
        ): MutableList<String> {
            val loaded = loadedTagsOverride ?: loadTagsForContext(ship, group, runtimeShip, persistenceContext)
            val sanitized = selectedTagsAllowedForDisplay(ship, group, visibleTags, loaded)
            val shouldPersist = sanitized != loaded
            if (shouldPersist) {
                saveTagsForContext(ship, group, runtimeShip, sanitized, persistenceContext)
            }
            return sanitized
        }

        fun selectedTagsAllowedForDisplay(
            ship: FleetMemberAPI,
            group: Int,
            visibleTags: List<String> = Settings.getCurrentWeaponTagList(),
            loadedTags: List<String>,
        ): MutableList<String> {
            val visibleTagSet = visibleTags.toSet()
            val sanitized = loadedTags
                .filter { it in visibleTagSet }
                .toMutableList()
            var changed = true
            while (changed) {
                changed = false
                sanitized.toList().forEach { persistedTag ->
                    if (isTagUnavailable(ship, group, persistedTag, sanitized)) {
                        sanitized.remove(persistedTag)
                        changed = true
                    }
                }
            }
            return sanitized
        }

        private fun isTagUnavailable(
            ship: FleetMemberAPI,
            group: Int,
            tag: String,
            currentTags: List<String>
        ): Boolean {
            val otherTags = currentTags.toMutableList().apply { remove(tag) }
            return isIncompatibleWithExistingTags(tag, otherTags) || shouldTagBeDisabled(group, ship, tag)
        }

        fun unavailableTagsForSelection(
            ship: FleetMemberAPI,
            group: Int,
            candidateTags: List<String>,
            currentTags: List<String>,
        ): Set<String> {
            val disabledTags = disabledTagsForGroup(group, ship, candidateTags)
            return candidateTags
                .filter { tag -> tag in disabledTags || isIncompatibleWithExistingTags(tag, currentTags) }
                .toSet()
        }

        private fun tooltipForTag(
            ship: FleetMemberAPI,
            group: Int,
            tag: String,
            unavailable: Boolean,
            selectedTags: List<String>,
        ): String {
            val baseTooltip = getTagTooltip(tag)
            if (!unavailable) return baseTooltip
            val reason = tagUnavailableReasonForWeaponGroup(group, ship, tag, selectedTags) ?: return baseTooltip
            return "$baseTooltip\n\nUnavailable: $reason"
        }

        fun createLegacyTooltipTagButtonGroup(
            ship: FleetMemberAPI,
            group: Int,
            tooltip: TooltipMakerAPI,
            tagView: TagListView
        ): List<TagButton> {
            val tagButtons = mutableListOf<TagButton>()
            val persistedTags = loadPersistentTags(agcStableShipId(ship), group, AGCGUI.storageIndex)
            val persistedTagSet = persistedTags.toSet()
            tagView.view().forEach {
                tagButtons.add(
                    TagButton(
                        ship, group, it, addLegacyAgcTooltipCheckbox(
                            tooltip = tooltip,
                            label = it,
                            data = it,
                            tooltipText = getTagTooltip(it)
                        )
                    )
                )
                if (it in persistedTagSet) {
                    tagButtons.last().setCheckedFromPersistence(true)
                }
            }
            tagButtons.forEach {
                it.connectSameGroupButtons(tagButtons)
            }
            tagButtons.firstOrNull()?.updateDisabledButtons(persistedTags)
            return tagButtons
        }

        fun createCampaignTagButtonGroup(
            ship: FleetMemberAPI,
            group: Int,
            panel: CustomPanelAPI,
            renderedTags: List<String> = Settings.getCurrentWeaponTagList(),
            pinned: Boolean = false,
            runtimeShip: ShipAPI? = null,
            visibleTagsForSanitization: List<String> = renderedTags,
            onSelectionChanged: ((List<String>) -> Unit)? = null,
            sanitizedTagsOverride: List<String>? = null,
            unavailableTagsOverride: Set<String>? = null,
            persistenceContext: ShipEditorPersistenceContext? = null,
        ): List<TagButton> {
            val tags = renderedTags
            val sanitizedTags = sanitizedTagsOverride
                ?: repairAndPersistSelectedTags(
                    ship = ship,
                    group = group,
                    runtimeShip = runtimeShip,
                    visibleTags = visibleTagsForSanitization,
                    persistenceContext = persistenceContext
                )
            val sanitizedTagSet = sanitizedTags.toSet()
            val unavailableTags = unavailableTagsOverride
                ?: unavailableTagsForSelection(ship, group, tags.filterNot { it in sanitizedTagSet }, sanitizedTags)
            val metrics = CampaignGuiStyle.campaignTagGridMetrics(tags, panel.position.width, panel.position.height)
            val tagButtons = mutableListOf<TagButton>()
            tags.forEachIndexed { index, tag ->
                val unavailable = !pinned && tag in unavailableTags
                val shell = addCampaignTagToggleButton(
                    parent = panel,
                    data = CampaignTagButtonId(group, tag),
                    tag = tag,
                    index = index,
                    metrics = metrics,
                    tooltip = tooltipForTag(ship, group, tag, unavailable, sanitizedTags),
                    unavailable = unavailable,
                    selected = tag in sanitizedTagSet
                )
                tagButtons.add(
                    TagButton(
                        ship,
                        group,
                        tag,
                        shell.button,
                        runtimeShip,
                        visibleTagsForSanitization,
                        onSelectionChanged = onSelectionChanged,
                        selectedTagsSnapshot = sanitizedTags,
                        unavailableTagsSnapshot = unavailableTags,
                        persistenceContext = persistenceContext,
                        useMomentaryVisualState = true,
                    )
                )
                if (tag in sanitizedTagSet) {
                    tagButtons.last().setCheckedFromPersistence(true)
                }
            }
            tagButtons.forEach {
                it.connectSameGroupButtons(tagButtons)
            }
            return tagButtons
        }
    }

    private fun loadCurrentTags(): List<String> = loadTagsForContext(ship, group, runtimeShip, persistenceContext)

    private fun saveCurrentTags(tags: List<String>) = saveTagsForContext(ship, group, runtimeShip, tags, persistenceContext)

    private fun currentTagsForClick(): MutableList<String> =
        selectedTagsSnapshot?.toMutableList()
            ?: selectedTagsAllowedForDisplay(
                ship = ship,
                group = group,
                visibleTags = visibleTagsForSanitization,
                loadedTags = loadCurrentTags(),
            )

    private fun isUnavailableForClick(tags: List<String>): Boolean =
        if (unavailableTagsSnapshot != null) {
            associatedValue in unavailableTagsSnapshot
        } else {
            isTagUnavailable(ship, group, associatedValue, tags)
        }

    fun isEditableTag(): Boolean = EditableWeaponTagDefinitions.isEditable(associatedValue)

    private fun setCheckedFromPersistence(checked: Boolean) {
        setCheckedFromPersistenceVisual(checked, visualState)
    }

    private fun applyToggleableVisualState(force: Boolean = false) {
        visualState.apply(button, force)
    }

    private fun setUnavailableState() {
        disableAsUnavailable(visualState)
    }

    private fun rejectUnavailableClick() {
        setUnavailableState()
    }

    private fun afterPersistedSelectionChanged(tags: List<String>) {
        if (onSelectionChanged == null) {
            updateDisabledButtons(tags)
            notifyCampaignTagSelectionChanged()
        } else {
            onSelectionChanged.invoke(tags)
        }
    }

    private fun updateDisabledButtons(tags: List<String>) {
        if (isTagUnavailable(ship, group, associatedValue, tags)) {
            // TODO: If Starsector exposes a stable disabled-click sound API, trigger it when unavailable tags are clicked.
            setUnavailableState()
        } else {
            setAvailableState()
        }
        sameGroupButtons.forEach {
            val tagButton = it as? TagButton ?: return@forEach
            if (isTagUnavailable(ship, group, it.associatedValue, tags)) {
                tagButton.setUnavailableState()
            } else {
                tagButton.setAvailableState()
            }
        }
    }

    private fun setAvailableState() {
        setAvailableForConfiguredVisualState(visualState)
    }

    override fun executeCallbackIfChecked(): Boolean {
        if (useMomentaryVisualState) {
            return executeMomentaryVisualCallbackIfClicked()
        }
        if (!active && button.isChecked) {
            val tags = currentTagsForClick()
            if (!button.isEnabled || isUnavailableForClick(tags)) {
                rejectUnavailableClick()
                return false
            }
            val updatedTags = (tags + associatedValue).distinct()
            saveCurrentTags(updatedTags)
            setActiveChecked(true)
            afterPersistedSelectionChanged(updatedTags)
            syncButtonCheckedToActive()
            applyToggleableVisualState()
            return true
        } else if (active && !button.isChecked) {
            val tags = (selectedTagsSnapshot ?: loadCurrentTags()).filterNot { it == associatedValue }
            saveCurrentTags(tags)
            uncheck()
            afterPersistedSelectionChanged(tags)
            syncButtonCheckedToActive()
            applyToggleableVisualState()
            return true
        }
        syncButtonCheckedToActive()
        applyToggleableVisualState()
        return false
    }

    private fun executeMomentaryVisualCallbackIfClicked(): Boolean {
        return executeMomentaryVisualToggleIfClicked(
            currentState = { currentTagsForClick().toList() },
            isUnavailable = ::isUnavailableForClick,
            rejectUnavailable = ::rejectUnavailableClick,
            updatedState = { currentlyActive, tags ->
                if (currentlyActive) {
                    (selectedTagsSnapshot ?: loadCurrentTags()).filterNot { it == associatedValue }
                } else {
                    (tags + associatedValue).distinct()
                }
            },
            saveState = ::saveCurrentTags,
            afterStateChanged = ::afterPersistedSelectionChanged,
        )
    }

    override fun onActivate() {
    }
}
