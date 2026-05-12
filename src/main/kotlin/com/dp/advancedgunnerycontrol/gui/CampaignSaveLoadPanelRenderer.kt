package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.utils.WeaponCompositionPresetLoadStatus
import com.dp.advancedgunnerycontrol.utils.WeaponCompositionPresetPeekStatus
import com.dp.advancedgunnerycontrol.utils.WeaponCompositionPresetSaveStatus
import com.dp.advancedgunnerycontrol.utils.WeaponPresetBackend
import com.dp.advancedgunnerycontrol.utils.WeaponPresetScope
import com.dp.advancedgunnerycontrol.utils.ShipEditorPersistenceContext
import com.dp.advancedgunnerycontrol.utils.getWeaponCompositionPresetKey
import com.dp.advancedgunnerycontrol.utils.getVariantWeaponGroup
import com.dp.advancedgunnerycontrol.utils.loadWeaponPreset
import com.dp.advancedgunnerycontrol.utils.saveWeaponPreset
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.ui.CustomPanelAPI

enum class PendingPresetAction {
    SAVE,
    LOAD,
}

data class PresetControlState(
    val scope: WeaponPresetScope = WeaponPresetScope.SINGLE,
    val backend: WeaponPresetBackend = WeaponPresetBackend.CAMPAIGN,
    val overwrite: Boolean = false,
    val pendingAction: PendingPresetAction? = null,
    val cleanTags: List<String>? = null,
    val backendManuallySelected: Boolean = false,
)

data class PresetActionExecutionResult(
    val executed: Boolean,
    val action: PendingPresetAction?,
    val affectedGroupIndexes: Set<Int> = emptySet(),
)

private data class PresetControlAvailability(
    val enabled: Boolean,
    val disabledTooltip: String? = null,
)

/**
 * Save/Load preset action component.
 * Renders the per-group and all-groups Save/Load buttons, validates modal
 * choices, and executes preset save/load actions after confirmation.
 */
object CampaignSaveLoadPanelRenderer {
    // The left-column panel reuses the per-group controls via this sentinel so
    // scope/backend/overwrite behavior cannot drift into a second save system.
    const val ALL_WEAPON_GROUPS_INDEX = -1

    private const val SAVE_LOAD_BUTTON_HEIGHT = 18f
    private const val SAVE_LOAD_BUTTON_HGAP = 1f
    private const val SAVE_TOOLTIP = "Open the Save preset popup for this weapon group."
    private const val LOAD_TOOLTIP = "Open the Load preset popup for this weapon group."

    const val PANEL_HEIGHT =
        SAVE_LOAD_BUTTON_HEIGHT
    const val COMPACT_PANEL_HEIGHT = SAVE_LOAD_BUTTON_HEIGHT

    private val log = Global.getLogger(CampaignSaveLoadPanelRenderer::class.java)

    fun renderActionButtonsOnly(
        panel: CustomPanelAPI,
        groupIndex: Int,
        top: Float,
        width: Float,
        state: PresetControlState,
        onStateChanged: (PresetControlState) -> Unit,
        onRefreshRequested: () -> Unit,
    ): List<ButtonBase<*>> {
        val effectiveState = state.normalized()
        if (effectiveState != state) {
            onStateChanged(effectiveState)
        }
        return mutableListOf<ButtonBase<*>>().apply {
            addPresetActionButtons(
                panel = panel,
                groupIndex = groupIndex,
                top = top,
                width = width,
                state = effectiveState,
                onStateChanged = onStateChanged,
                onRefreshRequested = onRefreshRequested,
            )
        }
    }

    fun allGroupsOptionRows(
        ship: FleetMemberAPI,
        state: PresetControlState,
        skipAvailabilityChecks: Boolean,
        onStateChanged: (PresetControlState) -> Unit,
        onRefreshRequested: () -> Unit,
    ): List<CampaignOptionRow> {
        return listOf(
            allGroupsOptionRow(
                ship,
                PendingPresetAction.SAVE,
                state,
                skipAvailabilityChecks,
                onStateChanged,
                onRefreshRequested,
            ),
            allGroupsOptionRow(
                ship,
                PendingPresetAction.LOAD,
                state,
                skipAvailabilityChecks,
                onStateChanged,
                onRefreshRequested,
            ),
        )
    }

    private fun allGroupsOptionRow(
        ship: FleetMemberAPI,
        action: PendingPresetAction,
        state: PresetControlState,
        skipAvailabilityChecks: Boolean,
        onStateChanged: (PresetControlState) -> Unit,
        onRefreshRequested: () -> Unit,
    ): CampaignOptionRow {
        val enabled = skipAvailabilityChecks || nonEmptyWeaponGroupIndexes(ship).isNotEmpty()
        val disabledTooltip = "There are no non-empty weapon groups to ${action.label().lowercase()}."
        return CampaignOptionRow(
            label = action.label(),
            tooltip = if (enabled) action.allGroupsTooltip() else disabledTooltip,
            kind = action.kind(),
            textColor = if (enabled) CampaignGuiStyle.DEFAULT_TEXT_COLOUR else CampaignGuiStyle.DISABLED_TAG_TEXT_COLOR,
            rebuildAfter = false,
            callback = {
                if (enabled) {
                    onStateChanged(state.copy(pendingAction = action))
                    onRefreshRequested()
                }
            }
        )
    }

    private fun MutableList<ButtonBase<*>>.addPresetActionButtons(
        panel: CustomPanelAPI,
        groupIndex: Int,
        top: Float,
        width: Float,
        state: PresetControlState,
        onStateChanged: (PresetControlState) -> Unit,
        onRefreshRequested: () -> Unit,
    ) {
        val buttonWidth = (width - SAVE_LOAD_BUTTON_HGAP) / 2f

        addPresetActionButton(
            panel = panel,
            groupIndex = groupIndex,
            top = top,
            buttonWidth = buttonWidth,
            action = PendingPresetAction.SAVE,
            state = state,
            onStateChanged = onStateChanged,
            onRefreshRequested = onRefreshRequested,
        )
        addPresetActionButton(
            panel = panel,
            groupIndex = groupIndex,
            top = top,
            buttonWidth = buttonWidth,
            action = PendingPresetAction.LOAD,
            state = state,
            onStateChanged = onStateChanged,
            onRefreshRequested = onRefreshRequested,
        )
    }

    fun normalizedState(state: PresetControlState): PresetControlState {
        return state.normalized()
    }

    private fun PresetControlState.normalized(): PresetControlState {
        var normalized = this
        if (!Settings.isAdvancedMode) {
            normalized = normalized.copy(
                scope = if (normalized.scope in simpleModeScopes()) normalized.scope else WeaponPresetScope.SINGLE,
                backend = WeaponPresetBackend.CAMPAIGN,
                overwrite = false,
            )
        }
        if (normalized.scope == WeaponPresetScope.SINGLE && normalized.backend == WeaponPresetBackend.EXTERNAL) {
            normalized = normalized.copy(backend = WeaponPresetBackend.CAMPAIGN)
        }
        if (normalized.scope == WeaponPresetScope.SUGGESTED && normalized.overwrite) {
            normalized = normalized.copy(overwrite = false)
        }
        return normalized
    }

    private fun MutableList<ButtonBase<*>>.addPresetActionButton(
        panel: CustomPanelAPI,
        groupIndex: Int,
        top: Float,
        buttonWidth: Float,
        action: PendingPresetAction,
        state: PresetControlState,
        onStateChanged: (PresetControlState) -> Unit,
        onRefreshRequested: () -> Unit,
    ) {
        addPresetControlButton(
            panel = panel,
            groupIndex = groupIndex,
            top = top,
            buttonWidth = buttonWidth,
            height = SAVE_LOAD_BUTTON_HEIGHT,
            slot = action.slot(),
            dataPrefix = action.dataPrefix(),
            label = action.label(),
            kind = action.kind(),
            tooltip = action.tooltip(),
        ) {
            onStateChanged(state.copy(pendingAction = action))
            onRefreshRequested()
        }
    }

    private fun presetActionAvailability(
        ship: FleetMemberAPI,
        groupIndex: Int,
        action: PendingPresetAction,
        state: PresetControlState,
        presetPeekCache: PresetPeekCache? = null,
    ): PresetControlAvailability {
        val nonEmptyGroups = nonEmptyWeaponGroupIndexes(ship)
        if (groupIndex == ALL_WEAPON_GROUPS_INDEX && nonEmptyGroups.isEmpty()) {
            return disabled("There are no non-empty weapon groups to ${action.label().lowercase()}.")
        }
        return when (action) {
            PendingPresetAction.SAVE -> saveActionAvailability(ship, groupIndex, state)
            PendingPresetAction.LOAD -> loadActionAvailability(ship, groupIndex, state, presetPeekCache)
        }
    }

    fun canOpenPresetAction(
        ship: FleetMemberAPI,
        groupIndex: Int,
        action: PendingPresetAction,
        state: PresetControlState,
        presetPeekCache: PresetPeekCache? = null,
    ): Boolean = presetActionAvailability(ship, groupIndex, action, state.normalized(), presetPeekCache).enabled

    fun presetActionDisabledTooltip(
        ship: FleetMemberAPI,
        groupIndex: Int,
        action: PendingPresetAction,
        state: PresetControlState,
        presetPeekCache: PresetPeekCache? = null,
    ): String? = presetActionAvailability(ship, groupIndex, action, state.normalized(), presetPeekCache).disabledTooltip

    private fun saveActionAvailability(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
    ): PresetControlAvailability {
        if (state.scope != WeaponPresetScope.SUGGESTED) {
            return enabled()
        }
        if (groupIndex == ALL_WEAPON_GROUPS_INDEX) {
            return if (suggestedSaveEligibleGroupIndexes(ship).isNotEmpty()) {
                enabled()
            } else {
                disabled(
                    "Suggested presets are saved per weapon type, and this ship has no single-weapon groups to save."
                )
            }
        }
        return if (isSuggestedSaveEligibleGroup(ship, groupIndex)) {
            enabled()
        } else {
            disabled(
                "Suggested presets are saved per weapon type. This group has multiple weapon types, so it cannot be saved as a Suggested preset."
            )
        }
    }

    private fun loadActionAvailability(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
        presetPeekCache: PresetPeekCache? = null,
    ): PresetControlAvailability {
        if (
            state.pendingAction != PendingPresetAction.LOAD &&
            loadPresetExistsForAnySelectableBackend(ship, groupIndex, state, presetPeekCache)
        ) {
            return enabled()
        }
        return if (loadPresetExists(ship, groupIndex, state, presetPeekCache)) {
            enabled()
        } else {
            disabled(noPresetTooltip(ship, groupIndex, state))
        }
    }

    private fun enabled(): PresetControlAvailability = PresetControlAvailability(enabled = true)

    private fun disabled(tooltip: String): PresetControlAvailability {
        return PresetControlAvailability(enabled = false, disabledTooltip = tooltip)
    }

    private fun loadPresetExists(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
        presetPeekCache: PresetPeekCache? = null,
    ): Boolean {
        if (groupIndex != ALL_WEAPON_GROUPS_INDEX) {
            return PresetPeekCache.peek(
                presetPeekCache,
                ship,
                groupIndex,
                AGCGUI.storageIndex,
                state.scope,
                state.backend,
            )
                .status == WeaponCompositionPresetPeekStatus.FOUND
        }
        return nonEmptyWeaponGroupIndexes(ship).any { index ->
            PresetPeekCache.peek(
                presetPeekCache,
                ship,
                index,
                AGCGUI.storageIndex,
                state.scope,
                state.backend,
            )
                .status == WeaponCompositionPresetPeekStatus.FOUND
        }
    }

    private fun loadPresetExistsForAnySelectableBackend(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
        presetPeekCache: PresetPeekCache? = null,
    ): Boolean {
        return selectableBackends(state.scope).any { backend ->
            loadPresetExists(ship, groupIndex, state.copy(backend = backend), presetPeekCache)
        }
    }

    fun selectableBackends(scope: WeaponPresetScope): List<WeaponPresetBackend> {
        return if (Settings.isAdvancedMode && scope != WeaponPresetScope.SINGLE) {
            listOf(WeaponPresetBackend.CAMPAIGN, WeaponPresetBackend.EXTERNAL)
        } else {
            listOf(WeaponPresetBackend.CAMPAIGN)
        }
    }

    fun canToggleBackend(state: PresetControlState): Boolean = selectableBackends(state.scope).size > 1

    fun canToggleOverwrite(state: PresetControlState): Boolean = overwriteAvailability(state).enabled

    fun canExecutePendingAction(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
        presetPeekCache: PresetPeekCache? = null,
    ): Boolean {
        val effectiveState = state.normalized()
        val action = effectiveState.pendingAction ?: return false
        return presetActionAvailability(ship, groupIndex, action, effectiveState, presetPeekCache).enabled
    }

    private fun noPresetTooltip(ship: FleetMemberAPI, groupIndex: Int, state: PresetControlState): String {
        return CampaignPresetTerminology.noPresetTooltip(ship, groupIndex, state, ALL_WEAPON_GROUPS_INDEX)
    }

    private fun isSuggestedSaveEligibleGroup(ship: FleetMemberAPI, groupIndex: Int): Boolean {
        val key = getWeaponCompositionPresetKey(ship, groupIndex)
            ?: return false
        return key.split("|").size == 1
    }

    private fun suggestedSaveEligibleGroupIndexes(ship: FleetMemberAPI): List<Int> {
        return nonEmptyWeaponGroupIndexes(ship).filter { index ->
            isSuggestedSaveEligibleGroup(ship, index)
        }
    }

    private fun overwriteAvailability(state: PresetControlState): PresetControlAvailability {
        if (!Settings.isAdvancedMode) {
            return disabled("Overwrite is only available in advanced mode.")
        }
        return if (state.scope == WeaponPresetScope.SUGGESTED) {
            disabled("Overwrite does not apply to Suggested presets because they are weapon-type recommendations, not ship/class/global active presets.")
        } else {
            enabled()
        }
    }

    fun scopeControlLabel(scope: WeaponPresetScope): String {
        val scopes = availableScopes()
        val index = scopes.indexOf(scope).coerceAtLeast(0)
        return "${scope.label()} [${index + 1}/${scopes.size}]"
    }

    fun nextAvailableScope(scope: WeaponPresetScope): WeaponPresetScope {
        return adjacentAvailableScope(scope, 1)
    }

    fun previousAvailableScope(scope: WeaponPresetScope): WeaponPresetScope {
        return adjacentAvailableScope(scope, -1)
    }

    private fun adjacentAvailableScope(scope: WeaponPresetScope, delta: Int): WeaponPresetScope {
        val scopes = availableScopes()
        val index = scopes.indexOf(scope).takeIf { it >= 0 } ?: 0
        return scopes[(index + delta + scopes.size) % scopes.size]
    }

    private fun availableScopes(): List<WeaponPresetScope> {
        return if (Settings.isAdvancedMode) {
            WeaponPresetScope.values().toList()
        } else {
            simpleModeScopes()
        }
    }

    private fun simpleModeScopes(): List<WeaponPresetScope> {
        return listOf(WeaponPresetScope.SINGLE, WeaponPresetScope.CLASS)
    }

    private fun buttonX(slot: Int, buttonWidth: Float): Float {
        return CampaignGuiStyle.PANEL_PADDING + slot * (buttonWidth + SAVE_LOAD_BUTTON_HGAP)
    }

    private fun PendingPresetAction.slot(): Int {
        return when (this) {
            PendingPresetAction.SAVE -> 0
            PendingPresetAction.LOAD -> 1
        }
    }

    private fun PendingPresetAction.dataPrefix(): String {
        return when (this) {
            PendingPresetAction.SAVE -> "save_preset"
            PendingPresetAction.LOAD -> "load_preset"
        }
    }

    private fun PendingPresetAction.label(): String {
        return when (this) {
            PendingPresetAction.SAVE -> "Save"
            PendingPresetAction.LOAD -> "Load"
        }
    }

    private fun PendingPresetAction.kind(): CampaignActionButtonKind {
        return when (this) {
            PendingPresetAction.SAVE -> CampaignActionButtonKind.SAVE
            PendingPresetAction.LOAD -> CampaignActionButtonKind.LOAD
        }
    }

    private fun PendingPresetAction.tooltip(): String {
        return when (this) {
            PendingPresetAction.SAVE -> SAVE_TOOLTIP
            PendingPresetAction.LOAD -> LOAD_TOOLTIP
        }
    }

    private fun PendingPresetAction.allGroupsTooltip(): String {
        return "${label()} presets for all non-empty weapon groups on this ship."
    }

    private fun MutableList<ButtonBase<*>>.addPresetControlButton(
        panel: CustomPanelAPI,
        groupIndex: Int,
        top: Float,
        buttonWidth: Float,
        height: Float,
        slot: Int,
        dataPrefix: String,
        label: String,
        kind: CampaignActionButtonKind,
        tooltip: String? = null,
        disabledTooltip: String? = null,
        centeredLabel: Boolean = true,
        enabled: Boolean = true,
        callback: () -> Unit,
    ): ButtonBase<*> {
        val template = CampaignGuiStyle.actionButtonTemplate(kind, enabled)
        val button = addTemplatedCampaignMomentaryActionButton(
            parent = panel,
            data = "${dataPrefix}_$groupIndex",
            x = buttonX(slot, buttonWidth),
            y = top,
            width = buttonWidth,
            height = height,
            tooltip = if (enabled) tooltip else disabledTooltip,
            template = template,
            labelText = label,
            showTooltipWhileInactive = !enabled && !disabledTooltip.isNullOrBlank(),
            centerConfirmCancelText = centeredLabel,
            centerText = centeredLabel,
            textPadding = CampaignGuiStyle.ITEM_TEXT_HORIZONTAL_PADDING,
        ) { callback() }
        add(button)
        return button
    }

    fun requiresOverwriteWarning(state: PresetControlState): Boolean {
        val effectiveState = state.normalized()
        return effectiveState.pendingAction == PendingPresetAction.SAVE &&
            effectiveState.overwrite &&
            effectiveState.scope != WeaponPresetScope.SUGGESTED
    }

    fun requiresConfirmation(state: PresetControlState): Boolean {
        return state.normalized().pendingAction != null
    }

    fun executePendingAction(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
        runtimeShip: ShipAPI?,
        presetPeekCache: PresetPeekCache? = null,
        persistenceContext: ShipEditorPersistenceContext? = null,
    ): PresetActionExecutionResult {
        val effectiveState = state.normalized()
        val action = effectiveState.pendingAction
        if (action != null && !presetActionAvailability(ship, groupIndex, action, effectiveState, presetPeekCache).enabled) {
            log.info("Ignored unavailable ${action.label()} preset action for scope=${effectiveState.scope}, backend=${effectiveState.backend}.")
            return PresetActionExecutionResult(executed = false, action = action)
        }
        return when (action) {
            PendingPresetAction.SAVE -> {
                val affected = executeSave(ship, groupIndex, effectiveState, runtimeShip, persistenceContext)
                PresetActionExecutionResult(
                    executed = affected.isNotEmpty(),
                    action = action,
                    affectedGroupIndexes = affected,
                )
            }
            PendingPresetAction.LOAD -> {
                val affected = executeLoad(ship, groupIndex, effectiveState, runtimeShip, persistenceContext)
                PresetActionExecutionResult(
                    executed = affected.isNotEmpty(),
                    action = action,
                    affectedGroupIndexes = affected,
                )
            }
            null -> PresetActionExecutionResult(executed = false, action = null)
        }
    }

    private fun executeSave(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
        runtimeShip: ShipAPI?,
        persistenceContext: ShipEditorPersistenceContext?,
    ): Set<Int> {
        if (groupIndex == ALL_WEAPON_GROUPS_INDEX) {
            return executeSaveAllGroups(ship, state, runtimeShip, persistenceContext)
        }
        val result = saveWeaponPreset(
            ship,
            groupIndex,
            AGCGUI.storageIndex,
            state.scope,
            state.backend,
            state.overwrite,
            runtimeShip = runtimeShip,
            persistenceContext = persistenceContext,
        )
        when (result.status) {
            WeaponCompositionPresetSaveStatus.FAILED -> {
                log.warn("Failed to save weapon preset. See log.")
            }
            WeaponCompositionPresetSaveStatus.NO_WEAPON_GROUP_KEY -> {
                log.info("No weapons in this group.")
            }
            WeaponCompositionPresetSaveStatus.SUGGESTED_REQUIRES_SINGLE_WEAPON -> {
                log.info("Suggested presets can only be saved from groups with one weapon type.")
            }
            WeaponCompositionPresetSaveStatus.SAVED -> {
                log.info("Saved ${state.scope.label()} ${state.backend.label()} preset; overwritten groups=${result.overwrittenGroups}.")
            }
        }
        return if (result.status == WeaponCompositionPresetSaveStatus.SAVED) setOf(groupIndex) else emptySet()
    }

    private fun executeLoad(
        ship: FleetMemberAPI,
        groupIndex: Int,
        state: PresetControlState,
        runtimeShip: ShipAPI?,
        persistenceContext: ShipEditorPersistenceContext?,
    ): Set<Int> {
        if (groupIndex == ALL_WEAPON_GROUPS_INDEX) {
            return executeLoadAllGroups(ship, state, runtimeShip, persistenceContext)
        }
        val result = loadWeaponPreset(
            ship,
            groupIndex,
            AGCGUI.storageIndex,
            state.scope,
            state.backend,
            runtimeShip = runtimeShip,
            persistenceContext = persistenceContext,
        )
        when (result.status) {
            WeaponCompositionPresetLoadStatus.FAILED -> {
                log.warn("Failed to load weapon preset. See log.")
            }
            WeaponCompositionPresetLoadStatus.NO_WEAPON_GROUP_KEY -> {
                log.info("No weapons in this group.")
            }
            WeaponCompositionPresetLoadStatus.NO_PRESET_FOUND -> {
                log.info("No ${state.scope.label()} ${state.backend.label()} preset saved for this weapon combination.")
            }
            WeaponCompositionPresetLoadStatus.LOADED -> {
                val imported = result.importedCustomTags.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "; imported custom tags=", separator = ", ")
                    ?: ""
                log.info("Loaded ${state.scope.label()} ${state.backend.label()} preset for this weapon combination$imported.")
            }
        }
        return if (result.status == WeaponCompositionPresetLoadStatus.LOADED) setOf(groupIndex) else emptySet()
    }

    private fun nonEmptyWeaponGroupIndexes(ship: FleetMemberAPI): List<Int> {
        return (0 until Values.MAX_WEAPON_GROUPS)
            .filter { index -> getVariantWeaponGroup(ship, index)?.slots?.isNotEmpty() == true }
    }

    private fun executeSaveAllGroups(
        ship: FleetMemberAPI,
        state: PresetControlState,
        runtimeShip: ShipAPI?,
        persistenceContext: ShipEditorPersistenceContext?,
    ): Set<Int> {
        val groupIndexes = nonEmptyWeaponGroupIndexes(ship)
        if (groupIndexes.isEmpty()) {
            log.info("No weapons in any group.")
            return emptySet()
        }
        val savedGroups = mutableSetOf<Int>()
        var saved = 0
        var skipped = 0
        var failed = 0
        var overwritten = 0
        groupIndexes.forEach { groupIndex ->
            val result = saveWeaponPreset(
                ship,
                groupIndex,
                AGCGUI.storageIndex,
                state.scope,
                state.backend,
                state.overwrite,
                runtimeShip = runtimeShip,
                persistenceContext = persistenceContext,
            )
            when (result.status) {
                WeaponCompositionPresetSaveStatus.SAVED -> {
                    saved++
                    savedGroups.add(groupIndex)
                    overwritten += result.overwrittenGroups
                }
                WeaponCompositionPresetSaveStatus.NO_WEAPON_GROUP_KEY,
                WeaponCompositionPresetSaveStatus.SUGGESTED_REQUIRES_SINGLE_WEAPON -> skipped++
                WeaponCompositionPresetSaveStatus.FAILED -> failed++
            }
        }
        log.info(
            "Saved all-groups ${state.scope.label()} ${state.backend.label()} presets; " +
                "saved=$saved skipped=$skipped failed=$failed overwrittenGroups=$overwritten."
        )
        return savedGroups
    }

    private fun executeLoadAllGroups(
        ship: FleetMemberAPI,
        state: PresetControlState,
        runtimeShip: ShipAPI?,
        persistenceContext: ShipEditorPersistenceContext?,
    ): Set<Int> {
        val groupIndexes = nonEmptyWeaponGroupIndexes(ship)
        if (groupIndexes.isEmpty()) {
            log.info("No weapons in any group.")
            return emptySet()
        }
        val loadedGroups = mutableSetOf<Int>()
        var loaded = 0
        var missing = 0
        var skipped = 0
        var failed = 0
        var importedCustomTags = 0
        groupIndexes.forEach { groupIndex ->
            val result = loadWeaponPreset(
                ship,
                groupIndex,
                AGCGUI.storageIndex,
                state.scope,
                state.backend,
                runtimeShip = runtimeShip,
                persistenceContext = persistenceContext,
            )
            when (result.status) {
                WeaponCompositionPresetLoadStatus.LOADED -> {
                    loaded++
                    loadedGroups.add(groupIndex)
                    importedCustomTags += result.importedCustomTags.size
                }
                WeaponCompositionPresetLoadStatus.NO_PRESET_FOUND -> missing++
                WeaponCompositionPresetLoadStatus.NO_WEAPON_GROUP_KEY -> skipped++
                WeaponCompositionPresetLoadStatus.FAILED -> failed++
            }
        }
        log.info(
            "Loaded all-groups ${state.scope.label()} ${state.backend.label()} presets; " +
                "loaded=$loaded missing=$missing skipped=$skipped failed=$failed importedCustomTags=$importedCustomTags."
        )
        return loadedGroups
    }
}
