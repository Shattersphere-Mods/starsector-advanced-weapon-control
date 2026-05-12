package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.gui.ButtonBase
import com.dp.advancedgunnerycontrol.gui.CampaignGuiStyle
import com.dp.advancedgunnerycontrol.gui.CampaignToggleVisualState
import com.dp.advancedgunnerycontrol.gui.addLegacyAgcTooltipCheckbox
import com.dp.advancedgunnerycontrol.gui.addCampaignTagToggleButton
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.TagListView
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagName
import com.dp.advancedgunnerycontrol.typesandvalues.getSuggestedModesForWeaponId
import com.dp.advancedgunnerycontrol.typesandvalues.getTagTooltip
import com.dp.advancedgunnerycontrol.typesandvalues.isIncompatibleWithExistingTags
import com.dp.advancedgunnerycontrol.typesandvalues.tagIncompatibilityReason
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

/**
 * Visible suggested-tag row component.
 * Used in Customize Suggested Tags to toggle one tag for one weapon id.
 */
class SuggestedTagButton(
    private val weaponId: String,
    tag: String,
    button: ButtonAPI,
    private val selectedTagsSnapshot: List<String>? = null,
    private val unavailableTagsSnapshot: Set<String>? = null,
    private val onSelectionChanged: ((List<String>) -> Unit)? = null,
    useMomentaryVisualState: Boolean = false,
) : ButtonBase<String>(tag, button, false, useMomentaryVisualState) {
    private val visualState = CampaignToggleVisualState()

    companion object{
        var suggestedTagSelectionVersion = 0
            private set

        private data class SuggestedTagButtonId(val weaponId: String, val tag: String)

        private fun isTagUnavailable(tag: String, currentTags: List<String>): Boolean {
            val otherTags = currentTags.toMutableList().apply { remove(tag) }
            return isIncompatibleWithExistingTags(tag, otherTags)
        }

        fun unavailableTagsForSelection(candidateTags: List<String>, currentTags: List<String>): Set<String> {
            return candidateTags
                .filter { tag -> isTagUnavailable(tag, currentTags) }
                .toSet()
        }

        private fun tooltipForTag(tag: String, unavailable: Boolean, selectedTags: List<String>): String {
            val baseTooltip = getTagTooltip(tag)
            if (!unavailable) return baseTooltip
            val reason = tagIncompatibilityReason(tag, selectedTags) ?: return baseTooltip
            return "$baseTooltip\n\nUnavailable: $reason"
        }

        fun supportedSuggestedTagsForWeapon(
            weaponId: String,
            supportedTags: Set<String> = Settings.getCurrentWeaponTagList().toSet(),
        ): List<String> {
            return getSuggestedModesForWeaponId(weaponId, supportedTags)
        }

        fun createButtonGroup(weaponId: String, tooltip: TooltipMakerAPI, tagView: TagListView) : List<SuggestedTagButton>
        {
            val tagButtons = mutableListOf<SuggestedTagButton>()
            val selectedTags = supportedSuggestedTagsForWeapon(weaponId)
            val selectedTagSet = selectedTags.toSet()
            tagView.view().forEach { tag ->
                tagButtons.add(
                    SuggestedTagButton(
                        weaponId,
                        tag,
                        addLegacyAgcTooltipCheckbox(
                            tooltip = tooltip,
                            label = tag,
                            data = tag,
                            tooltipText = getTagTooltip(tag)
                        ),
                        selectedTagsSnapshot = selectedTags,
                    )
                )
                if(tag in selectedTagSet){
                    tagButtons.last().setCheckedFromPersistence(true)
                }
            }
            tagButtons.forEach {
                it.connectSameGroupButtons(tagButtons)
            }
            tagButtons.firstOrNull()?.updateDisabledButtons(selectedTags)
            return tagButtons
        }

        fun createCampaignButtonGroup(
            weaponId: String,
            panel: CustomPanelAPI,
            visibleTags: List<String>,
            pinned: Boolean = false,
            selectedTagsOverride: List<String>? = null,
            unavailableTagsOverride: Set<String>? = null,
            onSelectionChanged: ((List<String>) -> Unit)? = null,
        ): List<SuggestedTagButton> {
            val tags = visibleTags
            val metrics = CampaignGuiStyle.campaignTagGridMetrics(tags, panel.position.width, panel.position.height)
            val selectedTags = selectedTagsOverride ?: supportedSuggestedTagsForWeapon(weaponId)
            val selectedTagSet = selectedTags.toSet()
            val unavailableTags = unavailableTagsOverride
                ?: unavailableTagsForSelection(tags.filterNot { it in selectedTagSet }, selectedTags)
            val tagButtons = mutableListOf<SuggestedTagButton>()

            tags.forEachIndexed { index, tag ->
                val unavailable = !pinned && tag in unavailableTags
                val shell = addCampaignTagToggleButton(
                    parent = panel,
                    data = SuggestedTagButtonId(weaponId, tag),
                    tag = tag,
                    index = index,
                    metrics = metrics,
                    tooltip = tooltipForTag(tag, unavailable, selectedTags),
                    unavailable = unavailable,
                    selected = tag in selectedTagSet
                )
                val suggestedButton = SuggestedTagButton(
                    weaponId,
                    tag,
                    shell.button,
                    selectedTagsSnapshot = selectedTags,
                    unavailableTagsSnapshot = unavailableTags,
                    onSelectionChanged = onSelectionChanged,
                    useMomentaryVisualState = true,
                )
                tagButtons.add(suggestedButton)

                if (tag in selectedTagSet) {
                    suggestedButton.setCheckedFromPersistence(true)
                }
            }

            tagButtons.forEach { it.connectSameGroupButtons(tagButtons) }
            tagButtons.firstOrNull()?.updateDisabledButtons(selectedTags)
            return tagButtons
        }
    }

    fun isForWeapon(weaponId: String): Boolean = this.weaponId == weaponId

    private fun setCheckedFromPersistence(checked: Boolean) {
        setCheckedFromPersistenceVisual(checked, visualState)
    }

    private fun applyToggleableVisualState(force: Boolean = false) {
        visualState.apply(button, force)
    }

    private fun setUnavailableState() {
        disableAsUnavailable(visualState)
    }

    private fun currentSuggestedTagsForWeapon(): List<String> {
        return supportedSuggestedTagsForWeapon(weaponId)
    }

    private fun currentSuggestedTagsForClick(): List<String> {
        return selectedTagsSnapshot ?: currentSuggestedTagsForWeapon()
    }

    private fun saveSuggestedTags(tags: List<String>) {
        val st = Settings.getCurrentSuggestedTags().toMutableMap()
        st[weaponId] = tags
        Settings.customSuggestedTags = st
    }

    private fun isUnavailableForCurrentSelection(tags: List<String>): Boolean {
        return unavailableTagsSnapshot?.let { associatedValue in it }
            ?: isTagUnavailable(associatedValue, tags)
    }

    private fun rejectUnavailableClick() {
        setUnavailableState()
    }

    override fun onActivate() {
    }

    private fun afterSuggestedTagsChanged(tags: List<String>) {
        if (onSelectionChanged == null) {
            updateDisabledButtons(tags)
            suggestedTagSelectionVersion++
        } else {
            onSelectionChanged.invoke(tags)
        }
    }

    override fun executeCallbackIfChecked(): Boolean {
        if (useMomentaryVisualState) {
            return executeMomentaryVisualCallbackIfClicked()
        }
        if (!active && button.isChecked) {
            val tags = currentSuggestedTagsForClick()
            if (!button.isEnabled || isUnavailableForCurrentSelection(tags)) {
                rejectUnavailableClick()
                return false
            }
            val updatedTags = (tags + associatedValue).distinct()
            saveSuggestedTags(updatedTags)
            setActiveChecked(true)
            afterSuggestedTagsChanged(updatedTags)
            syncButtonCheckedToActive()
            applyToggleableVisualState()
            return true
        } else if (active && !button.isChecked) {
            val updatedTags = currentSuggestedTagsForClick().filter { it != associatedValue }
            saveSuggestedTags(updatedTags)
            uncheck()
            afterSuggestedTagsChanged(updatedTags)
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
            currentState = ::currentSuggestedTagsForClick,
            isUnavailable = ::isUnavailableForCurrentSelection,
            rejectUnavailable = ::rejectUnavailableClick,
            updatedState = { currentlyActive, tags ->
                if (currentlyActive) {
                    tags.filter { it != associatedValue }
                } else {
                    (tags + associatedValue).distinct()
                }
            },
            saveState = ::saveSuggestedTags,
            afterStateChanged = ::afterSuggestedTagsChanged,
        )
    }

    private fun updateDisabledButtons() {
        updateDisabledButtons(currentSuggestedTagsForWeapon())
    }

    private fun updateDisabledButtons(tags: List<String>){
        if (isTagUnavailable(associatedValue, tags)) {
            setUnavailableState()
        } else {
            setAvailableState()
        }
        sameGroupButtons.forEach {
            val tagButton = it as? SuggestedTagButton ?: return@forEach
            if (isTagUnavailable(it.associatedValue, tags)) {
                tagButton.setUnavailableState()
            } else {
                tagButton.setAvailableState()
            }
        }
    }

    private fun setAvailableState() {
        setAvailableForConfiguredVisualState(visualState)
    }
}
