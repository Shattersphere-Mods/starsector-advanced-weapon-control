package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.gui.CampaignActionButtonKind
import com.dp.advancedgunnerycontrol.gui.CampaignConfirmationModalRequest
import com.dp.advancedgunnerycontrol.gui.CampaignConfirmationTone
import com.dp.advancedgunnerycontrol.gui.CampaignGuiStyle
import com.dp.advancedgunnerycontrol.gui.CampaignTooltipCopy
import com.dp.advancedgunnerycontrol.gui.CampaignToggleHeading
import com.dp.advancedgunnerycontrol.gui.warningBodyWithFooter
import com.dp.advancedgunnerycontrol.settings.LunaSettingHandler
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.WeaponTagListMode
import org.lwjgl.input.Keyboard

data class SuggestedTagUiAction(
    val name: String,
    val shortcuts: List<Int> = emptyList(),
    val tooltip: String = "",
    val rebuildAfter: Boolean = true,
    val active: Boolean = false,
    val kind: CampaignActionButtonKind = CampaignActionButtonKind.UNCOLOURED,
    val activeKind: CampaignActionButtonKind = CampaignActionButtonKind.ACTIVE,
    val indent: Float = 0f,
    val onRightClick: (() -> Unit)? = null,
    val callback: () -> Unit,
) {
    fun effectiveKind(): CampaignActionButtonKind {
        return if (active) activeKind else kind
    }

    fun usesCheckedVisualState(requestedStateful: Boolean): Boolean {
        if (!requestedStateful) return false
        return effectiveKind() != CampaignActionButtonKind.SUGGESTED_FILTER_ACTIVE
    }
}

enum class SuggestedTagDangerousAction {
    RESET,
    BACKUP,
    RESTORE;

    fun actionName(): String {
        return when (this) {
            RESET -> "Reset"
            BACKUP -> "Backup"
            RESTORE -> "Restore"
        }
    }

    fun warningTitle(): String {
        return when (this) {
            RESET -> "Reset Suggested Tags Warning"
            BACKUP -> "Backup Suggested Tags Warning"
            RESTORE -> "Restore Suggested Tags Warning"
        }
    }

    fun confirmationBody(): String {
        return when (this) {
            RESET ->
                "Confirming will reset the customized suggested-tag list to the mod defaults. This replaces current custom suggestions."
            BACKUP ->
                "Confirming will save the current customized suggested-tag list to the cross-campaign backup file, overwriting the previous backup."
            RESTORE ->
                "Confirming will load the suggested-tag list from the cross-campaign backup file, replacing the current customized list."
        }
    }
}

fun suggestedPageNavigationActions(weaponListView: WeaponListView): List<SuggestedTagUiAction> {
    return listOf(
        SuggestedTagUiAction(
            "Next Page",
            listOf(Keyboard.KEY_D),
            CampaignTooltipCopy.NEXT_PAGE,
            onRightClick = { weaponListView.cycleBackwards() }
        ) { weaponListView.cycle() },
        SuggestedTagUiAction(
            "Prev Page",
            listOf(Keyboard.KEY_A),
            CampaignTooltipCopy.PREV_PAGE,
            onRightClick = { weaponListView.cycle() }
        ) { weaponListView.cycleBackwards() }
    )
}

fun suggestedTagListModeAction(
    mode: WeaponTagListMode,
    modeCount: Int,
    modeIndex: Int,
    onCycle: (Int) -> Unit,
): SuggestedTagUiAction {
    return SuggestedTagUiAction(
        "Tag list: ${mode.displayName} [${modeIndex + 1}/$modeCount]",
        tooltip = CampaignTooltipCopy.suggestedTagListMode(mode),
        onRightClick = { onCycle(-1) }
    ) {
        onCycle(1)
    }
}

fun suggestedDangerousActions(
    armAction: (SuggestedTagDangerousAction) -> Unit,
): List<SuggestedTagUiAction> {
    return listOf(
        suggestedBackupAction(armAction),
        suggestedResetAction(armAction),
        suggestedRestoreAction(armAction),
    )
}

private fun suggestedBackupAction(armAction: (SuggestedTagDangerousAction) -> Unit): SuggestedTagUiAction =
    SuggestedTagUiAction(
        "Backup Suggested Tags",
        tooltip = CampaignTooltipCopy.BACKUP_SUGGESTED_TAGS,
        kind = CampaignActionButtonKind.SAVE,
        rebuildAfter = false
    ) {
        armAction(SuggestedTagDangerousAction.BACKUP)
    }

private fun suggestedResetAction(armAction: (SuggestedTagDangerousAction) -> Unit): SuggestedTagUiAction =
    SuggestedTagUiAction(
        "Reset Suggested Tags",
        tooltip = CampaignTooltipCopy.RESET_SUGGESTED_TAGS,
        kind = CampaignActionButtonKind.LOAD,
        rebuildAfter = false
    ) {
        armAction(SuggestedTagDangerousAction.RESET)
    }

private fun suggestedRestoreAction(armAction: (SuggestedTagDangerousAction) -> Unit): SuggestedTagUiAction =
    SuggestedTagUiAction(
        "Restore Suggested Tags",
        tooltip = CampaignTooltipCopy.RESTORE_SUGGESTED_TAGS,
        kind = CampaignActionButtonKind.LOAD,
        rebuildAfter = false
    ) {
        armAction(SuggestedTagDangerousAction.RESTORE)
    }

fun suggestedAutoApplyAction(): SuggestedTagUiAction {
    return if (Settings.autoApplySuggestedTags) {
        SuggestedTagUiAction("Disable Auto-apply", tooltip = CampaignTooltipCopy.autoApplyToggle(enabled = true)) {
            Settings.autoApplySuggestedTags = false
        }
    } else {
        SuggestedTagUiAction("Enable Auto-apply", tooltip = CampaignTooltipCopy.autoApplyToggle(enabled = false)) {
            Settings.autoApplySuggestedTags = true
        }
    }
}

fun buildSuggestedTagActionRows(
    weaponListView: WeaponListView,
    suggestedTagListMode: WeaponTagListMode,
    suggestedTagListModeCount: Int,
    suggestedTagListModeIndex: Int,
    cycleSuggestedTagListMode: (Int) -> Unit,
    clearFilters: () -> Unit,
    armDangerousAction: (SuggestedTagDangerousAction) -> Unit,
): List<SuggestedTagUiAction> {
    val actions = mutableListOf<SuggestedTagUiAction>()
    actions.addAll(suggestedPageNavigationActions(weaponListView))
    actions.add(
        suggestedTagListModeAction(
            mode = suggestedTagListMode,
            modeCount = suggestedTagListModeCount,
            modeIndex = suggestedTagListModeIndex,
            onCycle = cycleSuggestedTagListMode,
        )
    )
    actions.add(suggestedBackupAction(armDangerousAction))
    actions.add(
        SuggestedTagUiAction(
            "Reset Filters",
            tooltip = CampaignTooltipCopy.RESET_FILTERS,
            kind = CampaignActionButtonKind.LOAD
        ) {
            clearFilters()
        }
    )
    if (!LunaSettingHandler.isLunaLibPresent) {
        actions.add(suggestedAutoApplyAction())
    }
    actions.add(suggestedResetAction(armDangerousAction))
    actions.add(suggestedRestoreAction(armDangerousAction))
    return actions
}

fun suggestedDangerousActionConfirmationRequest(
    pending: SuggestedTagDangerousAction,
    onClear: () -> Unit,
): CampaignConfirmationModalRequest {
    return CampaignConfirmationModalRequest(
        title = pending.warningTitle(),
        body = "",
        richBody = warningBodyWithFooter(pending.confirmationBody()),
        tone = CampaignConfirmationTone.WARNING,
        onConfirm = {
            when (pending) {
                SuggestedTagDangerousAction.RESET -> Settings.customSuggestedTags = Settings.defaultSuggestedTags
                SuggestedTagDangerousAction.BACKUP -> backupSuggestedTagsToJson()
                SuggestedTagDangerousAction.RESTORE -> restoreSuggestedTagsFromJson()
            }
            onClear()
        },
        onCancel = onClear
    )
}

fun buildSuggestedTagFilterActions(
    weaponListView: WeaponListView,
    collapsedFilterCategories: MutableSet<WeaponFilter.FilterCategory>,
    onToggleFilter: (WeaponFilter) -> Unit,
): List<SuggestedTagUiAction> {
    val actions = mutableListOf<SuggestedTagUiAction>()
    weaponListView.activeFilters().forEach { filter ->
        actions.add(
            SuggestedTagUiAction(
                filter.name(),
                tooltip = CampaignTooltipCopy.filterToggle(active = true),
                active = true,
                kind = CampaignActionButtonKind.SUGGESTED_FILTER_ACTIVE,
                activeKind = CampaignActionButtonKind.SUGGESTED_FILTER_ACTIVE
            ) {
                onToggleFilter(filter)
            }
        )
    }
    WeaponFilter.filterCategories.forEach { category ->
        val categoryFilters = WeaponFilter.filtersIn(category)
        val expanded = !collapsedFilterCategories.contains(category)
        val heading = CampaignToggleHeading(
            title = category.title,
            expanded = expanded,
            active = false,
            subject = "filters"
        )
        actions.add(
            SuggestedTagUiAction(
                heading.label,
                tooltip = heading.tooltip,
                active = false,
                kind = heading.kind,
                activeKind = heading.kind
            ) {
                if (expanded) {
                    collapsedFilterCategories.add(category)
                } else {
                    collapsedFilterCategories.remove(category)
                }
            }
        )
        if (expanded) {
            categoryFilters.forEach { filter ->
                val filterActive = weaponListView.containsFilter(filter)
                if (filterActive) return@forEach
                actions.add(
                    SuggestedTagUiAction(
                        filter.name(),
                        tooltip = CampaignTooltipCopy.filterToggle(filterActive),
                        active = filterActive,
                        indent = CampaignGuiStyle.CHILD_ROW_INDENT
                    ) {
                        onToggleFilter(filter)
                    }
                )
            }
        }
    }
    return actions
}
