package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.CustomWeaponTagListStore
import com.dp.advancedgunnerycontrol.typesandvalues.CustomShipModeListStore
import com.dp.advancedgunnerycontrol.typesandvalues.ChoiceParameter
import com.dp.advancedgunnerycontrol.typesandvalues.DecimalParameter
import com.dp.advancedgunnerycontrol.typesandvalues.EditableTagParameterDefinition
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinition
import com.dp.advancedgunnerycontrol.typesandvalues.EditableShipModeDefinitions
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinitions
import com.dp.advancedgunnerycontrol.typesandvalues.NumberParameter
import com.dp.advancedgunnerycontrol.typesandvalues.TagListView
import com.dp.advancedgunnerycontrol.typesandvalues.TextParameter
import com.dp.advancedgunnerycontrol.typesandvalues.ToggleParameter
import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.dp.advancedgunnerycontrol.typesandvalues.WeaponTagCategory
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagName
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeWeaponTagNames
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeName
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeNames
import com.dp.advancedgunnerycontrol.typesandvalues.tagNameToRegexName
import com.dp.advancedgunnerycontrol.utils.WeaponCompositionPresetPeekStatus
import com.dp.advancedgunnerycontrol.utils.ShipEditorPersistenceContext
import com.dp.advancedgunnerycontrol.utils.WeaponPresetBackend
import com.dp.advancedgunnerycontrol.utils.WeaponPresetScope
import com.dp.advancedgunnerycontrol.utils.getVariantWeaponGroup
import com.dp.advancedgunnerycontrol.utils.sanitizeWeaponCompositionPresetTagsForGroup
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.PositionAPI
import java.awt.Color
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.lwjgl.input.Keyboard

/**
 * Main ship-editor component used by both campaign and direct/refit AGC screens.
 * Renders the ship panel, options panel, ship modes, weapon groups, tag lists,
 * Save/Load modals, custom-list manager, edit tag/mode popups, and debug-color popup.
 *
 * Lifecycle and ownership map:
 * - session/root state: current ship, root panel, persistence context, shared buttons
 * - input routing: button polling, scroll regions, keyboard shortcuts, modal shields
 * - options panel: row builder callback, targeted row refresh, options scroll state
 * - ship modes: effective-mode cache, mode scroll state, targeted mode-panel refresh
 * - weapon groups: weapon-group panels/headings plus WeaponGroupTagListRenderer state
 * - preset modal: save/load control state, review previews, preset peek/dirty caches
 * - custom-list modal: staged tag/mode state, list scrolling, modal shell lifecycle
 * - external confirmation: host-provided warning modal rendering and cleanup
 * - persistence/cache helpers: active ShipEditorPersistenceContext, preset caches,
 *   custom-list archetype cache, and targeted refresh invalidation
 */
class ShipView(
    private val tagView: TagListView,
    private val config: ShipViewConfig = ShipViewConfig(),
    initialState: ShipViewInitialState = ShipViewInitialState(),
    private val presetBindings: ShipViewPresetBindings = ShipViewPresetBindings(),
    private val externalBindings: ShipViewExternalBindings = ShipViewExternalBindings(),
    private val customListBindings: CustomListModalBindings = CustomListModalBindings(),
) : CustomView(), CustomListModalChangeHandler {
    companion object {
        private const val CUSTOM_TAG_MODAL_PADDING = CampaignGuiStyle.MODAL_PADDING
        private const val CUSTOM_TAG_MODAL_ROW_HEIGHT = CampaignGuiStyle.MODAL_ROW_HEIGHT
        private const val CUSTOM_TAG_MODAL_ROW_GAP = CampaignGuiStyle.MODAL_ROW_GAP
        private const val CUSTOM_TAG_EDIT_MODAL_WIDTH = CampaignGuiStyle.EDIT_TAG_MODAL_WIDTH
        private const val CUSTOM_TAG_EDIT_TITLE_HEIGHT = CampaignGuiStyle.MODAL_HEADING_HEIGHT
        private const val CUSTOM_TAG_EDIT_VERTICAL_SECTION_GAP = CampaignGuiStyle.MODAL_TITLE_BODY_GAP
        private const val CUSTOM_TAG_EDIT_TITLE_TO_COMPONENT_GAP = CampaignGuiStyle.MODAL_TITLE_BODY_GAP
        private const val CUSTOM_TAG_EDIT_COMPONENT_TO_BUTTON_GAP = CampaignGuiStyle.MODAL_BODY_ACTION_GAP
    }

    // Session/root state and input routing.
    private val buttons: MutableList<ButtonBase<*>> = mutableListOf()
    private val log = Global.getLogger(ShipView::class.java)
    private val buttonCallbackPoller = CampaignButtonCallbackPoller(buttons)

    // Weapon-group tag lists and group heading refresh ownership.
    private val weaponGroupTagListRenderer = WeaponGroupTagListRenderer(
        initialState.tagScrollOffsets,
        initialState.tagExpandedCategoryTitles,
        buttons,
        config.runtimeShip,
        onMissingRefreshContext = { campaignScrollDirty = true },
        onGroupTagsChanged = ::handleWeaponGroupTagsChanged,
    )
    private val presetControlStateByGroup = initialState.presetControlStates.toMutableMap()
    private val collapsedPanelIds = initialState.collapsedPanelIds.toMutableSet()
    private var campaignScrollDirty = false
    private var observedTagSelectionVersion = TagButton.campaignTagSelectionVersion
    private var observedShipModeSelectionVersion = ShipModeButton.campaignShipModeSelectionVersion
    private var activePersistenceContext: ShipEditorPersistenceContext? = null
    private var activeShip: FleetMemberAPI? = null
    private var rootPanel: CustomPanelAPI? = null
    private var nonModalSuppressionSnapshot: CampaignButtonSuppressionSnapshot? = null
    private val weaponGroupPanelsByIndex = mutableMapOf<Int, CustomPanelAPI>()
    private val weaponGroupHeadingPanelsByIndex = mutableMapOf<Int, CustomPanelAPI>()

    // Custom-list modal staged state and shell lifecycle.
    private val customListState = CustomListModalStateController(
        customListBindings,
        changeHandler = this,
    )

    private var confirmationDialogPosition: PositionAPI? = null
    private var customTagDialogPosition: PositionAPI? = null
    private var customListModalRootPanel: CustomPanelAPI? = null
    private var customListModalShell: ShipViewModalShell? = null
    private var customListModalButtonStartIndex: Int = -1
    private var customListModalButtonEndIndex: Int = -1
    private var renderingCustomListModal = false
    private var customListModalRefreshPending = false

    // Options panel targeted refresh and scroll state.
    private var optionsScrollOffset = initialState.optionsScrollOffset.coerceAtLeast(0)
    private var optionsScrollMaxOffset = 0
    private var optionsScrollRegion: VerticalScrollRegion<Unit>? = null
    private var optionsPanel: CustomPanelAPI? = null
    private var optionsRowsPanel: CustomPanelAPI? = null
    private var optionsBuildPanel: ((CustomPanelAPI, Boolean, Int, Float?, (Int, Int) -> Unit) -> CampaignOptionsRenderResult)? = null
    private var optionsPanelWidth: Float = 0f
    private var optionsPanelHeight: Float = 0f
    private var optionsPanelTop: Float = 0f
    private var optionsPanelRootHeight: Float = 0f
    private var optionsPanelBodyTop: Float = 0f
    private var optionsPanelBodyHeight: Float = 0f

    // Ship-mode effective-list cache, scroll state, and targeted refresh state.
    private var shipModeScrollOffset = initialState.shipModeScrollOffset.coerceAtLeast(0)
    private var shipModeScrollMaxOffset = 0
    private var shipModeScrollRegion: VerticalScrollRegion<Unit>? = null
    private var shipModeParentPanel: CustomPanelAPI? = null
    private var shipModeItemPanel: CustomPanelAPI? = null
    private var shipModePanelShip: FleetMemberAPI? = null
    private var shipModePanelInnerWidth: Float = 0f
    private var shipModePanelBodyTop: Float = 0f
    private var shipModePanelBodyHeight: Float = 0f
    private var shipModePanelTop: Float = 0f
    private var shipModePanelRootHeight: Float = 0f
    private var shipModeControls: List<ButtonBase<*>> = emptyList()
    private var effectiveShipModeCacheKey: String? = null
    private var effectiveShipModeCache: List<String>? = null
    private var customListModalScrollRegion: CustomListModalScrollRegion? = null
    private var customTagArchetypeCache: List<CustomTagArchetype>? = null

    // Preset Save/Load modal, review previews, and external confirmation state.
    private var presetModalRootPanel: CustomPanelAPI? = null
    private var presetModalShell: ShipViewModalShell? = null
    private var presetModalButtonStartIndex: Int = -1
    private var presetModalButtonEndIndex: Int = -1
    private var presetScopeWheelRegion: VerticalScrollRegion<Unit>? = null
    private var externalConfirmationRootPanel: CustomPanelAPI? = null
    private var externalConfirmationModal: RenderedCampaignConfirmationModal? = null
    private var externalConfirmationButtonStartIndex: Int = -1
    private var externalConfirmationButtonEndIndex: Int = -1
    private val presetPeekCache = PresetPeekCache()
    // Preset peeks can touch campaign or external storage; cache by concrete
    // ship/group/loadout/scope/backend and clear after mutating save/load actions.
    private val presetDirtyByGroup = mutableMapOf<Int, Boolean>()

    private fun addPanel(
        parent: CustomPanelAPI,
        width: Float,
        height: Float,
        type: CampaignPanelType,
        x: Float,
        y: Float,
    ): CustomPanelAPI {
        val child = createChildPanel(parent, width, height, type)
        child.position.inTL(x, y)
        return child
    }

    private fun addPanelBelow(
        parent: CustomPanelAPI,
        anchor: CustomPanelAPI,
        width: Float,
        height: Float,
        type: CampaignPanelType,
        gap: Float = 0f,
    ): CustomPanelAPI {
        val child = createChildPanel(parent, width, height, type)
        child.position.belowLeft(anchor, gap)
        return child
    }

    private fun addBlackSpacerBelow(
        parent: CustomPanelAPI,
        anchor: CustomPanelAPI,
        width: Float,
        height: Float,
    ): CustomPanelAPI? {
        if (height <= 0.5f) return null
        val child = parent.createCustomPanel(
            width,
            height,
            CampaignPanelPlugin(
                CampaignPanelType.SHIP_MODES_PANEL,
                fillColor = CampaignGuiStyle.BLACK_PANEL_FILL_COLOR,
            )
        )
        parent.addComponent(child)
        child.position.belowLeft(anchor, 0f)
        return child
    }

    private fun addPanelRightOf(
        parent: CustomPanelAPI,
        anchor: CustomPanelAPI,
        width: Float,
        height: Float,
        type: CampaignPanelType,
        gap: Float = 0f,
    ): CustomPanelAPI {
        val child = createChildPanel(parent, width, height, type)
        child.position.rightOfTop(anchor, gap)
        return child
    }

    private fun createChildPanel(
        parent: CustomPanelAPI,
        width: Float,
        height: Float,
        type: CampaignPanelType,
    ): CustomPanelAPI {
        val child = parent.createCustomPanel(width, height, CampaignPanelPlugin(type))
        parent.addComponent(child)
        return child
    }

    private fun addShipModeButtonGroup(
        ship: FleetMemberAPI,
        panel: CustomPanelAPI,
        layoutContainerHeight: Float = panel.position.height,
        onSelectionChanged: (() -> Unit)? = null,
        preparedGroup: ShipModeButton.PreparedCampaignModeButtonGroup? = null,
    ): List<ButtonBase<*>> {
        val rendered = ShipModeButton.createCampaignModeButtonGroup(
            ship = ship,
            panel = panel,
            runtimeShip = config.runtimeShip,
            rowOffset = shipModeScrollOffset,
            onScrollRows = ::scrollShipModesByRows,
            layoutContainerHeight = layoutContainerHeight,
            onSelectionChanged = onSelectionChanged,
            preparedGroup = preparedGroup,
            persistenceContext = activePersistenceContext,
        )
        shipModeScrollMaxOffset = rendered.maxRowOffset
        shipModeScrollOffset = rendered.effectiveRowOffset.coerceIn(0, shipModeScrollMaxOffset)
        buttons.addAll(rendered.buttons)
        return rendered.buttons
    }

    override fun advance(t: Float) {
        buttonCallbackPoller.processIfRequested()
        if (config.enableTagScroll) {
            tagView.advance()
        }
    }

    fun shouldRegenerate(): Boolean {
        return campaignScrollDirty ||
            observedTagSelectionVersion != TagButton.campaignTagSelectionVersion ||
            observedShipModeSelectionVersion != ShipModeButton.campaignShipModeSelectionVersion ||
            (config.enableTagScroll && tagView.hasChanged())
    }

    fun captureTagScrollOffsets(): Map<Int, Int> = weaponGroupTagListRenderer.captureScrollOffsets()
    fun captureTagExpandedCategoryTitles(): Map<Int, Set<String>> = weaponGroupTagListRenderer.captureExpandedCategoryTitles()
    fun captureOptionsScrollOffset(): Int = optionsScrollOffset
    fun captureShipModeScrollOffset(): Int = shipModeScrollOffset
    fun capturePresetControlStates(): Map<Int, PresetControlState> = presetControlStateByGroup.toMap()
    fun captureCollapsedPanelIds(): Set<String> = collapsedPanelIds.toSet()
    fun hasConfirmationModal(): Boolean = pendingConfirmationModalRequest() != null || customListBindings.modalModeProvider?.invoke() != null
    fun hasOverwriteWarningModal(): Boolean = hasConfirmationModal()
    fun allGroupsPresetOptionRows(): List<CampaignOptionRow> {
        val ship = activeShip ?: return emptyList()
        val state = presetControlStateByGroup[CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX] ?: PresetControlState()
        val skipAvailabilityChecks = pendingPresetConfirmation() != null
        return CampaignSaveLoadPanelRenderer.allGroupsOptionRows(
            ship = ship,
            state = state,
            skipAvailabilityChecks = skipAvailabilityChecks,
            onStateChanged = { nextState ->
                updatePresetControlState(
                    CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX,
                    nextState
                )
            },
            onRefreshRequested = {
                openPresetActionModal(
                    CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX,
                    presetControlStateByGroup[CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX]
                        ?: PresetControlState()
                )
            }
        )
    }

    fun markAllVisibleGroupsCleanToCurrentTags() {
        val ship = activeShip ?: return
        (0 until Values.MAX_WEAPON_GROUPS)
            .filter { index -> getVariantWeaponGroup(ship, index)?.slots?.isNotEmpty() == true }
            .forEach { index ->
                val current = presetControlStateByGroup[index] ?: PresetControlState()
                val cleanTags = currentSanitizedTagsForGroup(ship, index)
                PresetCleanBaselineStore.put(ship, index, AGCGUI.storageIndex, cleanTags)
                updatePresetControlState(index, current.copy(cleanTags = cleanTags))
            }
        presetDirtyByGroup.clear()
    }

    override fun processInput(events: MutableList<InputEventAPI>?) {
        buttonCallbackPoller.requestPollFromInput(events)
        if (processCustomListModalScrollInput(events)) return
        if (processCustomListModalInput(events)) return
        if (processConfirmationModalInput(events)) return
        if (processOptionsScrollInput(events)) return
        if (processCustomShipModeRightClickEditInput(events)) return
        if (processCustomTagRightClickEditInput(events)) return
        if (processButtonRightClickInput(events)) return
        if (processShipModeScrollInput(events)) return
        weaponGroupTagListRenderer.processInput(events, pos)
    }

    override fun buttonPressed(buttonId: Any?) {}

    private fun processOptionsScrollInput(events: MutableList<InputEventAPI>?): Boolean {
        val region = optionsScrollRegion ?: return false
        return handleVerticalScrollInput(
            events = events,
            panelPos = pos,
            regions = listOf(region),
            currentOffset = { optionsScrollOffset },
            setOffset = { _, offset -> optionsScrollOffset = offset },
            onScrolled = { refreshOptionsPanel() },
            scrollStep = 1,
            normalizeOffset = { _, requested, maxOffset, delta ->
                usefulVerticalScrollOffset(requested, maxOffset, delta)
            },
        )
    }

    private fun processShipModeScrollInput(events: MutableList<InputEventAPI>?): Boolean {
        val region = shipModeScrollRegion ?: return false
        return handleVerticalScrollInput(
            events = events,
            panelPos = pos,
            regions = listOf(region),
            currentOffset = { shipModeScrollOffset },
            setOffset = { _, offset -> shipModeScrollOffset = offset },
            onScrolled = { refreshShipModePanel() },
            scrollStep = 1,
            normalizeOffset = { _, requested, maxOffset, delta ->
                usefulVerticalScrollOffset(requested, maxOffset, delta)
            },
        )
    }

    private fun processCustomListModalScrollInput(events: MutableList<InputEventAPI>?): Boolean {
        val region = customListModalScrollRegion ?: return false
        return handleVerticalScrollInput(
            events = events,
            panelPos = customTagDialogPosition,
            regions = listOf(region.asVerticalScrollRegion()),
            currentOffset = ::customListModalScrollOffset,
            setOffset = ::setCustomListModalScrollOffset,
            onScrolled = { refreshCustomListModalOrDirty() },
            scrollStep = CampaignGuiStyle.WEAPON_TAG_SCROLL_STEP,
            normalizeOffset = { _, requested, maxOffset, delta ->
                usefulVerticalScrollOffset(requested, maxOffset, delta)
            },
        )
    }

    private fun scrollShipModesByRows(delta: Int) {
        val updated = usefulVerticalScrollOffsetByDelta(shipModeScrollOffset, delta, shipModeScrollMaxOffset)
        if (updated == shipModeScrollOffset) return
        shipModeScrollOffset = updated
        refreshShipModePanel()
    }

    private fun scrollOptionsByRows(delta: Int, maxOffset: Int) {
        val updated = usefulVerticalScrollOffsetByDelta(optionsScrollOffset, delta, maxOffset)
        if (updated == optionsScrollOffset) return
        optionsScrollOffset = updated
        refreshOptionsPanel()
    }

    private fun refreshOptionsPanel() {
        buttonCallbackPoller.clearTargetedPoll()
        val parent = optionsPanel ?: return
        val oldRows = optionsRowsPanel ?: return
        removePanelCatching(parent, oldRows, "options rows")
        buildOptionsRowsPanel(parent)
    }

    private fun pendingPresetConfirmation(): Pair<Int, PresetControlState>? {
        return presetControlStateByGroup.entries.firstOrNull { (_, state) ->
            CampaignSaveLoadPanelRenderer.requiresConfirmation(state)
        }?.let { it.key to CampaignSaveLoadPanelRenderer.normalizedState(it.value) }
    }

    private fun pendingConfirmationModalRequest(): CampaignConfirmationModalRequest? {
        return pendingPresetConfirmation()
            ?.let { (groupIndex, state) -> presetConfirmationRequest(groupIndex, state) }
            ?: externalBindings.confirmationModalProvider?.invoke()
    }

    private fun processConfirmationModalInput(events: MutableList<InputEventAPI>?): Boolean {
        val pending = pendingConfirmationModalRequest() ?: return false
        return processCampaignConfirmationModalInput(
            events = events,
            onCancel = {
                pending.onCancel()
                if (pendingPresetConfirmation() == null) {
                    closeExternalConfirmationModalTargeted()
                }
            },
            onConfirm = {
                val preset = pendingPresetConfirmation()
                if (preset == null || presetCanConfirm(preset.first, preset.second)) {
                    pending.onConfirm()
                }
            },
            onMouseEventBeforeConsume = ::processPresetModalMouseEventBeforeConsume,
        )
    }

    private fun processPresetModalMouseEventBeforeConsume(event: InputEventAPI): Boolean {
        return processPresetScopeWheelEvent(event) || processButtonRightClickEvent(event)
    }

    private fun processPresetScopeWheelEvent(event: InputEventAPI): Boolean {
        if (event.isConsumed || !event.isMouseScrollEvent) return false
        val eventValue = event.eventValue
        if (eventValue == 0) return false
        val (groupIndex, state) = pendingPresetConfirmation() ?: return false
        val region = presetScopeWheelRegion ?: return false
        if (!verticalScrollRegionContainsEvent(event, confirmationDialogPosition, region)) return false
        val nextScope = if (eventValue > 0) {
            CampaignSaveLoadPanelRenderer.previousAvailableScope(state.scope)
        } else {
            CampaignSaveLoadPanelRenderer.nextAvailableScope(state.scope)
        }
        event.consume()
        selectPresetModalScope(groupIndex, state, nextScope)
        return true
    }

    private fun processCustomListModalInput(events: MutableList<InputEventAPI>?): Boolean {
        val mode = customListBindings.modalModeProvider?.invoke() ?: return false
        events?.forEach { event ->
            if (event.isConsumed) return@forEach
            if (event.isKeyDownEvent) {
                if (mode == CustomListModalMode.RENAME_LOADOUT) {
                    processLoadoutRenameKeyEvent(event)
                    event.consume()
                    return@forEach
                }
                if (event.eventValue == Keyboard.KEY_ESCAPE) {
                    if (mode == CustomListModalMode.EDIT_TAG && customListState.isManagerReturnEdit()) {
                        returnToCustomTagManagerFromEdit()
                    } else {
                        closeCustomListModal()
                    }
                }
                event.consume()
                return@forEach
            }
            if (event.isMouseEvent) {
                if (processButtonRightClickEvent(event)) return@forEach
                event.consume()
            }
        }
        return true
    }

    private fun processButtonRightClickInput(events: MutableList<InputEventAPI>?): Boolean {
        return buttons.processCampaignButtonRightClickInput(events)
    }

    private fun processButtonRightClickEvent(event: InputEventAPI): Boolean {
        return buttons.processCampaignButtonRightClickEvent(event)
    }

    private fun processLoadoutRenameKeyEvent(event: InputEventAPI) {
        val index = currentLoadoutRenameIndex()
        val current = currentLoadoutRenameDraft()
        when (event.eventValue) {
            Keyboard.KEY_ESCAPE -> closeCustomListModal()
            Keyboard.KEY_RETURN,
            Keyboard.KEY_NUMPADENTER -> {
                if (Settings.renameLoadout(index, current)) {
                    closeCustomListModal()
                }
            }
            Keyboard.KEY_BACK -> setLoadoutRenameDraft(current.dropLast(1))
            else -> {
                val char = event.eventChar
                if (char.code in 32..126 && current.length < 24) {
                    setLoadoutRenameDraft(current + char)
                }
            }
        }
    }

    private fun processCustomTagRightClickEditInput(events: MutableList<InputEventAPI>?): Boolean {
        val ship = activeShip ?: return false
        val context = activePersistenceContext ?: return false
        if (context.shipId.isBlank()) return false
        if (!CustomWeaponTagListStore.getActiveModeOrDefault(context.shipId).isCustom) return false
        val rightClickEvents = events
            ?.filter { event -> !event.isConsumed && event.isRMBDownEvent }
            .orEmpty()
        if (rightClickEvents.isEmpty()) return false

        var customTags: Set<String>? = null
        rightClickEvents.forEach { event ->
            for (button in buttons) {
                val candidate = button as? TagButton ?: continue
                if (!candidate.containsEvent(event)) continue
                val canEdit = candidate.isEditableTag() || run {
                    val tags = customTags ?: currentCustomTagsForContext(context)
                        .toSet()
                        .also { customTags = it }
                    canonicalTag(candidate.associatedValue) in tags
                }
                if (!canEdit) return@forEach
                event.consume()
                startCustomTagEditFromTag(ship, context, candidate.group, candidate.associatedValue)
                return true
            }
        }
        return false
    }

    private fun processCustomShipModeRightClickEditInput(events: MutableList<InputEventAPI>?): Boolean {
        val ship = activeShip ?: return false
        val context = activePersistenceContext ?: return false
        if (context.shipId.isBlank()) return false
        if (!CustomWeaponTagListStore.getActiveModeOrDefault(context.shipId).isCustom) return false
        val rightClickEvents = events
            ?.filter { event -> !event.isConsumed && event.isRMBDownEvent }
            .orEmpty()
        if (rightClickEvents.isEmpty()) return false

        rightClickEvents.forEach { event ->
            for (button in buttons) {
                val candidate = button as? ShipModeButton ?: continue
                if (!candidate.containsEvent(event)) continue
                val mode = canonicalizeShipModeName(candidate.associatedValue)
                if (EditableShipModeDefinitions.definitionForMode(mode) == null) return@forEach
                event.consume()
                startCustomShipModeEditFromVisibleButton(ship, mode)
                return true
            }
        }
        return false
    }

    private fun isCollapsed(key: CollapsiblePanelKey): Boolean = isCollapsedPanelId(key.id)

    private fun weaponGroupWeaponsPanelId(groupIndex: Int): String = "weapon_group:$groupIndex:weapons"

    private fun isCollapsedPanelId(id: String): Boolean = id in collapsedPanelIds

    private fun toggleCollapsedPanelId(id: String) {
        if (!collapsedPanelIds.add(id)) {
            collapsedPanelIds.remove(id)
        }
        campaignScrollDirty = true
    }

    private fun addCollapsiblePanelHeading(
        panel: CustomPanelAPI,
        key: CollapsiblePanelKey,
    ): Boolean {
        return addCollapsiblePanelHeading(
            panel = panel,
            title = key.title,
            id = key.id,
        )
    }

    private fun addCollapsiblePanelHeading(
        panel: CustomPanelAPI,
        title: String,
        id: String,
        fillColor: Color = CampaignGuiStyle.COLLAPSIBLE_HEADING_COLOUR,
        statusSuffix: String = "",
    ): Boolean {
        val collapsed = isCollapsedPanelId(id)
        val shell = addCollapsibleCampaignPanelHeading(
            panel = panel,
            title = title,
            collapsed = collapsed,
            data = "collapse_panel:$id",
            fillColor = fillColor,
            statusSuffix = statusSuffix,
        )
        buttons.addCampaignButtonControl(button = shell.button) {
            toggleCollapsedPanelId(id)
        }
        return collapsed
    }

    private fun effectiveShipModesForEditor(ship: FleetMemberAPI): List<String> {
        val context = activePersistenceContext ?: ShipEditorPersistenceContext(ship, config.runtimeShip)
        val defaultModes = Settings.getCurrentShipModeNames()
        val customState = CustomWeaponTagListStore.getStateOrNull(context.shipId)
        val key = listOf(
            context.shipId,
            customState?.activeMode.orEmpty(),
            CustomWeaponTagListStore.currentListVersion().toString(),
            CustomShipModeListStore.currentListVersion().toString(),
            defaultModes.joinToString("|")
        ).joinToString("::")
        effectiveShipModeCache?.let { cached ->
            if (effectiveShipModeCacheKey == key) return cached
        }
        return CustomShipModeListStore.effectiveModeNamesForShipOrDefault(context.shipId, defaultModes)
            .also {
                effectiveShipModeCacheKey = key
                effectiveShipModeCache = it
            }
    }

    private fun buildShipPanel(panel: CustomPanelAPI, ship: FleetMemberAPI) {
        if (addCollapsiblePanelHeading(panel, CollapsiblePanelKey.SHIP)) return
        CampaignShipPanelRenderer.render(panel, ship, CampaignGuiStyle.CONTAINER_HEADING_HEIGHT, renderHeading = false)
    }

    private fun buildShipModesPanel(
        panel: CustomPanelAPI,
        ship: FleetMemberAPI,
        panelTop: Float,
        rootHeight: Float,
    ) {
        val bodyTop = ShipEditorLayoutCalculator.shipModePanelBodyTop()
        val innerWidth = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
        val bodyHeight = ShipEditorLayoutCalculator.shipModePanelBodyHeight(panel.position.height)
        shipModeScrollRegion = null

        val preparedModeGroup = ShipModeButton.prepareCampaignModeButtonGroup(
            ship = ship,
            width = innerWidth,
            availableHeight = bodyHeight,
            runtimeShip = config.runtimeShip,
            rowOffset = shipModeScrollOffset,
            configuredModesOverride = effectiveShipModesForEditor(ship),
            persistenceContext = activePersistenceContext,
            forceActiveSectionExpanded = true,
        )
        val itemPanelHeight = ShipModeButton.estimateCampaignModeButtonGroupTightHeight(
            ship = ship,
            width = innerWidth,
            availableHeight = bodyHeight,
            runtimeShip = config.runtimeShip,
            rowOffset = shipModeScrollOffset,
            preparedGroup = preparedModeGroup,
        )
        val itemPanelTop = bodyTop
        val itemPanel = panel.createCustomPanel(innerWidth, itemPanelHeight, null)
        panel.addComponent(itemPanel)
        itemPanel.position.inTL(CampaignGuiStyle.PANEL_PADDING, itemPanelTop)
        shipModeParentPanel = panel
        shipModeItemPanel = itemPanel
        shipModePanelShip = ship
        shipModePanelInnerWidth = innerWidth
        shipModePanelBodyTop = bodyTop
        shipModePanelBodyHeight = bodyHeight
        shipModePanelTop = panelTop
        shipModePanelRootHeight = rootHeight
        shipModeControls = addShipModeButtonGroup(
            ship,
            itemPanel,
            layoutContainerHeight = bodyHeight,
            onSelectionChanged = ::refreshShipModePanel,
            preparedGroup = preparedModeGroup,
        )
        shipModeScrollRegion = VerticalScrollRegion(
            key = Unit,
            left = CampaignGuiStyle.PANEL_PADDING,
            right = CampaignGuiStyle.PANEL_PADDING + innerWidth,
            bottom = rootHeight - panelTop - itemPanelTop - itemPanelHeight,
            top = rootHeight - panelTop - itemPanelTop,
            maxOffset = shipModeScrollMaxOffset,
        )
    }

    private fun refreshShipModePanel() {
        buttonCallbackPoller.clearTargetedPoll()
        val parent = shipModeParentPanel ?: return
        val ship = shipModePanelShip ?: return
        val oldPanel = shipModeItemPanel ?: return
        removePanelCatching(parent, oldPanel, "ship-mode rows")
        buttons.removeAll(shipModeControls.toSet())
        val preparedModeGroup = ShipModeButton.prepareCampaignModeButtonGroup(
            ship = ship,
            width = shipModePanelInnerWidth,
            availableHeight = shipModePanelBodyHeight,
            runtimeShip = config.runtimeShip,
            rowOffset = shipModeScrollOffset,
            configuredModesOverride = effectiveShipModesForEditor(ship),
            persistenceContext = activePersistenceContext,
        )
        val itemPanelHeight = ShipModeButton.estimateCampaignModeButtonGroupTightHeight(
            ship = ship,
            width = shipModePanelInnerWidth,
            availableHeight = shipModePanelBodyHeight,
            runtimeShip = config.runtimeShip,
            rowOffset = shipModeScrollOffset,
            preparedGroup = preparedModeGroup,
        )
        val nextPanel = parent.createCustomPanel(shipModePanelInnerWidth, itemPanelHeight, null)
        parent.addComponent(nextPanel)
        nextPanel.position.inTL(CampaignGuiStyle.PANEL_PADDING, shipModePanelBodyTop)
        shipModeItemPanel = nextPanel
        shipModeControls = addShipModeButtonGroup(
            ship,
            nextPanel,
            layoutContainerHeight = shipModePanelBodyHeight,
            onSelectionChanged = ::refreshShipModePanel,
            preparedGroup = preparedModeGroup,
        )
        shipModeScrollRegion = VerticalScrollRegion(
            key = Unit,
            left = CampaignGuiStyle.PANEL_PADDING,
            right = CampaignGuiStyle.PANEL_PADDING + shipModePanelInnerWidth,
            bottom = shipModePanelRootHeight - shipModePanelTop - shipModePanelBodyTop - itemPanelHeight,
            top = shipModePanelRootHeight - shipModePanelTop - shipModePanelBodyTop,
            maxOffset = shipModeScrollMaxOffset,
        )
    }

    private fun removePanelCatching(parent: CustomPanelAPI, child: CustomPanelAPI, context: String) {
        runCatching { parent.removeComponent(child) }
            .onFailure { ex ->
                Global.getLogger(ShipView::class.java)
                    .warn("[AGC_GUI] Failed to remove $context panel during refresh", ex)
            }
    }

    private fun buildWeaponGroupPanel(
        panel: CustomPanelAPI,
        ship: FleetMemberAPI,
        groupIndex: Int,
        relativeGroupLeft: Float,
        relativeGroupTopOffset: Float,
        contentHeight: Float,
    ) {
        val entries = weaponEntriesForGroup(ship, groupIndex)
        val isDirty = entries.isNotEmpty() && isPresetDirtyForGroup(ship, groupIndex)
        weaponGroupPanelsByIndex[groupIndex] = panel
        renderWeaponGroupHeading(panel, groupIndex, isDirty)
        if (entries.isEmpty()) {
            renderEmptyWeaponGroupBody(panel)
            return
        }

        val layout = computeWeaponGroupPanelLayout(panel, groupIndex)
        renderPresetControls(panel, ship, groupIndex, layout)
        addWeaponGroupSectionHeading(
            panel = panel,
            title = "Weapons",
            id = weaponGroupWeaponsPanelId(groupIndex),
            top = layout.weaponsHeadingTop,
        )
        renderWeaponPanel(panel, entries, layout)
        renderWeaponGroupTagListPanel(
            panel = panel,
            ship = ship,
            groupIndex = groupIndex,
            layout = layout,
            relativeGroupLeft = relativeGroupLeft,
            relativeGroupTopOffset = relativeGroupTopOffset,
            contentHeight = contentHeight
        )
    }

    private fun renderWeaponGroupHeading(
        panel: CustomPanelAPI,
        groupIndex: Int,
        isDirty: Boolean,
    ) {
        weaponGroupHeadingPanelsByIndex.remove(groupIndex)?.let(panel::removeComponent)
        val title = "Group ${groupIndex + 1}${if (isDirty) " (Unsaved)" else ""}"
        val heading = addCampaignPanelHeading(
            panel = panel,
            title = title,
            fillColor = if (isDirty) {
                CampaignGuiStyle.STALE_WEAPON_GROUP_PANEL_HEADING_COLOUR
            } else {
                CampaignGuiStyle.PANEL_HEADING_COLOUR
            },
        )
        weaponGroupHeadingPanelsByIndex[groupIndex] = heading
    }

    private fun addWeaponGroupSectionHeading(
        panel: CustomPanelAPI,
        title: String,
        id: String,
        top: Float,
        fillColor: Color = CampaignGuiStyle.COLLAPSIBLE_HEADING_COLOUR,
        statusSuffix: String = "",
    ): Pair<CustomPanelAPI, ButtonBase<*>> {
        val shell = addCollapsibleCampaignPanelHeading(
            panel = panel,
            title = title,
            collapsed = isCollapsedPanelId(id),
            data = "collapse_panel:$id",
            top = top,
            fillColor = fillColor,
            statusSuffix = statusSuffix,
        )
        val control = buttons.addCampaignButtonControl(button = shell.button) {
            toggleCollapsedPanelId(id)
        }
        return shell.panel to control
    }

    private fun weaponEntriesForGroup(ship: FleetMemberAPI, groupIndex: Int): List<CampaignWeaponPanelEntry> {
        return getVariantWeaponGroup(ship, groupIndex)
            ?.let { CampaignWeaponPanelRenderer.aggregateWeapons(it, ship) }
            ?: emptyList()
    }

    private fun computeWeaponGroupPanelLayout(
        panel: CustomPanelAPI,
        groupIndex: Int,
    ): WeaponGroupPanelLayout {
        val sectionHeadingHeight = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT
        val innerWidth = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
        val saveLoadPanelTop = CampaignGuiStyle.PANEL_PADDING + CampaignGuiStyle.CONTAINER_HEADING_HEIGHT
        val weaponPanelVisible = !isCollapsedPanelId(weaponGroupWeaponsPanelId(groupIndex))
        val weaponsHeadingTop = saveLoadPanelTop +
            CampaignSaveLoadPanelRenderer.COMPACT_PANEL_HEIGHT +
            CampaignWeaponPanelRenderer.WEAPON_TO_TAG_GAP
        val weaponPanelTop = weaponsHeadingTop + sectionHeadingHeight
        val baseWeaponPanelHeight = if (weaponPanelVisible) CampaignWeaponPanelRenderer.PANEL_HEIGHT else 0f
        val baseTagListPanelTop = weaponPanelTop + baseWeaponPanelHeight + CampaignWeaponPanelRenderer.WEAPON_TO_TAG_GAP
        val rawTagListPanelHeight = (panel.position.height - baseTagListPanelTop).coerceAtLeast(0f)
        // Align the tag panel to whole tag rows so the scroll indicator sits
        // at the bottom; any sub-row slack is more useful in the weapon panel.
        val alignedTagListPanelHeight = if (rawTagListPanelHeight >= CampaignGuiStyle.TAG_ITEM_HEIGHT) {
            val tagSlotCount = computeVerticalSlotCount(rawTagListPanelHeight)
            computeVerticalItemsHeight(tagSlotCount)
                .coerceAtMost(rawTagListPanelHeight)
        } else {
            0f
        }
        val reclaimedHeight = if (weaponPanelVisible) max(0f, rawTagListPanelHeight - alignedTagListPanelHeight) else 0f
        val weaponPanelHeight = if (weaponPanelVisible) baseWeaponPanelHeight + reclaimedHeight else 0f
        val tagListPanelTop = baseTagListPanelTop + reclaimedHeight
        val rawTagListPanelHeightAfterReclaim = (panel.position.height - tagListPanelTop).coerceAtLeast(0f)
        val tagListPanelHeight = if (rawTagListPanelHeightAfterReclaim >= CampaignGuiStyle.TAG_ITEM_HEIGHT) {
            rawTagListPanelHeightAfterReclaim
        } else {
            0f
        }
        return WeaponGroupPanelLayout(
            innerWidth = innerWidth,
            saveLoadPanelTop = saveLoadPanelTop,
            weaponsHeadingTop = weaponsHeadingTop,
            weaponPanelTop = weaponPanelTop,
            weaponPanelHeight = weaponPanelHeight,
            weaponPanelVisible = weaponPanelVisible,
            tagListPanelTop = tagListPanelTop,
            tagListPanelHeight = tagListPanelHeight,
        )
    }

    private fun renderPresetControls(
        panel: CustomPanelAPI,
        ship: FleetMemberAPI,
        groupIndex: Int,
        layout: WeaponGroupPanelLayout,
    ) {
        val presetControlState = presetControlStateByGroup[groupIndex] ?: PresetControlState()
        buttons.addAll(
            CampaignSaveLoadPanelRenderer.renderActionButtonsOnly(
                panel = panel,
                groupIndex = groupIndex,
                top = layout.saveLoadPanelTop,
                width = layout.innerWidth,
                state = presetControlState,
                onStateChanged = { state ->
                    updatePresetControlState(groupIndex, state)
                },
                onRefreshRequested = {
                    openPresetActionModal(
                        groupIndex,
                        presetControlStateByGroup[groupIndex] ?: PresetControlState()
                    )
                },
            )
        )
    }

    private fun updatePresetControlState(groupIndex: Int, state: PresetControlState) {
        val effectiveState = mergePresetActionWithRememberedSettings(groupIndex, state)
        if (effectiveState == PresetControlState()) {
            presetControlStateByGroup.remove(groupIndex)
        } else {
            presetControlStateByGroup[groupIndex] = effectiveState
        }
        activeShip?.let { ship ->
            PresetControlStateMemory.remember(ship, groupIndex, effectiveState)
        }
        presetBindings.onPresetControlStateUpdate?.invoke(groupIndex, effectiveState)
    }

    private fun mergePresetActionWithRememberedSettings(
        groupIndex: Int,
        state: PresetControlState,
    ): PresetControlState {
        val action = state.pendingAction ?: return state
        val existing = presetControlStateByGroup[groupIndex]
            ?: activeShip?.let { PresetControlStateMemory.stateFor(it, groupIndex) }
            ?: return state
        val actionOnly = state.copy(pendingAction = null) == PresetControlState()
        return if (actionOnly) {
            existing.copy(pendingAction = action)
        } else {
            state
        }
    }

    private fun restorePersistentPresetControlStates(ship: FleetMemberAPI) {
        PresetControlStateMemory.statesFor(ship).forEach { (groupIndex, storedState) ->
            if (groupIndex !in presetControlStateByGroup) {
                presetControlStateByGroup[groupIndex] = storedState
            }
        }
    }

    private fun renderWeaponPanel(
        panel: CustomPanelAPI,
        entries: List<CampaignWeaponPanelEntry>,
        layout: WeaponGroupPanelLayout,
    ) {
        if (!layout.weaponPanelVisible || layout.weaponPanelHeight <= 0f) return
        val weaponPanel = addPanel(
            parent = panel,
            width = layout.innerWidth,
            height = layout.weaponPanelHeight,
            type = CampaignPanelType.WEAPON_PANEL,
            x = CampaignGuiStyle.PANEL_PADDING,
            y = layout.weaponPanelTop
        )
        CampaignWeaponPanelRenderer.render(weaponPanel, entries)
        if (entries.isNotEmpty()) {
            CampaignWeaponPanelRenderer.addWeaponEntriesTooltip(weaponPanel, entries)
        }
    }

    private fun renderEmptyWeaponGroupBody(panel: CustomPanelAPI) {
        val bodyTop = CampaignGuiStyle.PANEL_PADDING + CampaignGuiStyle.CONTAINER_HEADING_HEIGHT
        val bodyHeight = max(0f, panel.position.height - bodyTop)
        if (bodyHeight <= 0f) return
        val body = panel.createCustomPanel(
            panel.position.width,
            bodyHeight,
            CampaignPanelPlugin(
                CampaignPanelType.WEAPON_PANEL,
                fillColor = Color(0, 0, 0, 235)
            )
        )
        panel.addComponent(body)
        body.position.inTL(0f, bodyTop)
    }

    private fun renderWeaponGroupTagListPanel(
        panel: CustomPanelAPI,
        ship: FleetMemberAPI,
        groupIndex: Int,
        layout: WeaponGroupPanelLayout,
        relativeGroupLeft: Float,
        relativeGroupTopOffset: Float,
        contentHeight: Float,
    ) {
        if (layout.tagListPanelHeight <= 0f) return
        val weaponGroupTagListPanel = addPanel(
            parent = panel,
            width = layout.innerWidth,
            height = layout.tagListPanelHeight,
            type = CampaignPanelType.WEAPON_GROUP_TAG_LIST_PANEL,
            x = CampaignGuiStyle.PANEL_PADDING,
            y = layout.tagListPanelTop
        )

        val relativeTopOffset = relativeGroupTopOffset + layout.tagListPanelTop
        val relativeBottom = contentHeight - relativeTopOffset - layout.tagListPanelHeight
        weaponGroupTagListRenderer.build(
            panel = weaponGroupTagListPanel,
            ship = ship,
            groupIndex = groupIndex,
            relativeScrollLeft = relativeGroupLeft + CampaignGuiStyle.PANEL_PADDING,
            relativeScrollRight = relativeGroupLeft + CampaignGuiStyle.PANEL_PADDING + layout.innerWidth,
            relativeScrollBottom = relativeBottom,
            relativeScrollTop = relativeBottom + layout.tagListPanelHeight,
        )
    }

    private fun buildOptionsRowsPanel(parent: CustomPanelAPI) {
        optionsScrollRegion = null
        val buildRows = optionsBuildPanel ?: run {
            optionsScrollMaxOffset = 0
            optionsScrollOffset = 0
            return
        }
        val rowsPanel = parent.createCustomPanel(optionsPanelWidth, optionsPanelHeight, null)
        parent.addComponent(rowsPanel)
        rowsPanel.position.inTL(0f, 0f)
        optionsRowsPanel = rowsPanel
        val result = buildRows(rowsPanel, false, optionsScrollOffset, optionsPanelBodyHeight, ::scrollOptionsByRows)
        optionsScrollMaxOffset = result.maxRowOffset
        optionsScrollOffset = result.rowOffset.coerceIn(0, optionsScrollMaxOffset)
        optionsScrollRegion = VerticalScrollRegion(
            key = Unit,
            left = CampaignGuiStyle.PANEL_PADDING,
            right = optionsPanelWidth - CampaignGuiStyle.PANEL_PADDING,
            bottom = optionsPanelRootHeight - optionsPanelTop - optionsPanelBodyTop - optionsPanelBodyHeight,
            top = optionsPanelRootHeight - optionsPanelTop - optionsPanelBodyTop,
            maxOffset = optionsScrollMaxOffset,
        )
    }

    private fun isPresetDirtyForGroup(ship: FleetMemberAPI, groupIndex: Int): Boolean {
        if (pendingPresetConfirmation() != null) {
            return presetDirtyByGroup[groupIndex] ?: false
        }
        return presetDirtyByGroup.getOrPut(groupIndex) {
            computePresetDirtyForGroup(ship, groupIndex, currentTags = null)
        }
    }

    private fun isPresetDirtyForGroup(
        ship: FleetMemberAPI,
        groupIndex: Int,
        currentTags: List<String>,
    ): Boolean {
        if (pendingPresetConfirmation() != null) {
            return presetDirtyByGroup[groupIndex] ?: false
        }
        return computePresetDirtyForGroup(ship, groupIndex, currentTags).also { isDirty ->
            presetDirtyByGroup[groupIndex] = isDirty
        }
    }

    private fun computePresetDirtyForGroup(
        ship: FleetMemberAPI,
        groupIndex: Int,
        currentTags: List<String>?,
    ): Boolean {
        val context = activePersistenceContext ?: ShipEditorPersistenceContext(ship, config.runtimeShip)
        val presetState = presetControlStateByGroup[groupIndex] ?: PresetControlState()
        val currentSanitized = if (currentTags == null) {
            currentSanitizedTagsForGroup(ship, groupIndex, context)
        } else {
            sanitizeWeaponCompositionPresetTagsForGroup(ship, groupIndex, currentTags)
        }.toSet()
        (presetState.cleanTags ?: PresetCleanBaselineStore.get(ship, groupIndex, AGCGUI.storageIndex))
            ?.let { return currentSanitized != it.toSet() }
        val presetResult = presetPeekCache.peek(
            member = ship,
            groupIndex = groupIndex,
            loadoutIndex = AGCGUI.storageIndex,
            scope = presetState.scope,
            backend = presetState.backend,
        )
        return when (presetResult.status) {
            WeaponCompositionPresetPeekStatus.FOUND -> {
                PresetCleanBaselineStore.put(ship, groupIndex, AGCGUI.storageIndex, presetResult.tags)
                currentSanitized != presetResult.tags.toSet()
            }
            WeaponCompositionPresetPeekStatus.NO_PRESET_FOUND,
            WeaponCompositionPresetPeekStatus.NO_WEAPON_GROUP_KEY -> {
                PresetCleanBaselineStore.put(ship, groupIndex, AGCGUI.storageIndex, emptyList())
                currentSanitized.isNotEmpty()
            }
            WeaponCompositionPresetPeekStatus.FAILED -> true
        }
    }

    private fun handleWeaponGroupTagsChanged(groupIndex: Int, currentTags: List<String>) {
        val ship = activeShip ?: return
        val panel = weaponGroupPanelsByIndex[groupIndex] ?: return
        val isDirty = isPresetDirtyForGroup(ship, groupIndex, currentTags)
        renderWeaponGroupHeading(panel, groupIndex, isDirty)
    }

    private fun currentSanitizedTagsForGroup(
        ship: FleetMemberAPI,
        groupIndex: Int,
        context: ShipEditorPersistenceContext = activePersistenceContext ?: ShipEditorPersistenceContext(ship, config.runtimeShip),
    ): List<String> {
        return sanitizeWeaponCompositionPresetTagsForGroup(
            ship,
            groupIndex,
            context.loadWeaponTags(groupIndex, AGCGUI.storageIndex)
        )
    }

    private fun buildWeaponGroupsPanel(panel: CustomPanelAPI, ship: FleetMemberAPI, contentHeight: Float, leftColumnWidth: Float) {
        val innerWidth = panel.position.width
        val innerHeight = panel.position.height
        val cardWidth = innerWidth / Values.MAX_WEAPON_GROUPS

        repeat(Values.MAX_WEAPON_GROUPS) { index ->
            val effectiveWidth = if (index == Values.MAX_WEAPON_GROUPS - 1) {
                innerWidth - cardWidth * (Values.MAX_WEAPON_GROUPS - 1)
            } else {
                cardWidth
            }
            val groupPanel = addPanel(
                parent = panel,
                width = effectiveWidth,
                height = innerHeight,
                type = CampaignPanelType.WEAPON_GROUP_PANEL,
                x = index * cardWidth,
                y = 0f
            )
            buildWeaponGroupPanel(
                groupPanel,
                ship,
                index,
                relativeGroupLeft = leftColumnWidth + index * cardWidth,
                relativeGroupTopOffset = 0f,
                contentHeight = contentHeight
            )
        }
    }

    private fun renderConfirmationModal(panel: CustomPanelAPI) {
        val preset = pendingPresetConfirmation()
        if (preset != null) {
            renderPresetActionModal(panel, preset.first, preset.second)
            return
        }
        renderExternalConfirmationModal(panel)
    }

    fun openExternalConfirmationModalOrDirty() {
        val panel = rootPanel ?: run {
            campaignScrollDirty = true
            return
        }
        if (externalConfirmationModal != null) {
            closeExternalConfirmationModalTargeted()
        }
        renderExternalConfirmationModal(panel)
    }

    private fun renderExternalConfirmationModal(panel: CustomPanelAPI) {
        val request = pendingConfirmationModalRequest() ?: run {
            confirmationDialogPosition = null
            return
        }
        val firstModalButtonIndex = buttons.size
        suppressNonModalButtonHover(firstModalButtonIndex)
        val modal = renderCampaignConfirmationModal(
            root = panel,
            request = request,
            confirmData = "confirmation_modal_confirm",
            cancelData = "confirmation_modal_cancel",
            backdropData = "confirmation_modal_backdrop"
        )
        externalConfirmationRootPanel = panel
        externalConfirmationModal = modal
        externalConfirmationButtonStartIndex = firstModalButtonIndex
        confirmationDialogPosition = modal.dialogPosition
        buttons.addRenderedConfirmationModalButtons(
            modal = modal,
            onConfirm = request.onConfirm,
            onCancel = {
                request.onCancel()
                closeExternalConfirmationModalTargeted()
            },
        )
        externalConfirmationButtonEndIndex = buttons.size
    }

    private fun renderPresetActionModal(panel: CustomPanelAPI, groupIndex: Int, state: PresetControlState) {
        val request = presetConfirmationRequest(groupIndex, state)
        val firstModalButtonIndex = buttons.size
        suppressNonModalButtonHover(firstModalButtonIndex)
        val overwriteWarning = CampaignSaveLoadPanelRenderer.requiresOverwriteWarning(state)
        val accentColor = if (overwriteWarning) CampaignGuiStyle.ALERT_RED_COLOR else CampaignGuiStyle.LOAD_BUTTON_HOVER_COLOR
        val titleColor = if (overwriteWarning) CampaignGuiStyle.ALERT_RED_COLOR else CampaignGuiStyle.LOAD_BUTTON_HOVER_COLOR
        val dialogWidth = min(CUSTOM_TAG_EDIT_MODAL_WIDTH, panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING)
        val reviewBody = presetActionReviewBody(groupIndex, state)
        val layout = PresetActionModalLayoutBuilder.build(
            screenHeight = panel.position.height,
            dialogWidth = dialogWidth,
            state = state,
            request = request,
            reviewBody = reviewBody,
        )
        val dialogHeight = layout.dialogHeight
        val shell = ShipViewModalFactory.createShell(
            panel = panel,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            borderColor = accentColor,
        )
        val dialog = shell.dialog
        confirmationDialogPosition = dialog.position
        presetModalRootPanel = panel
        presetModalShell = shell
        presetModalButtonStartIndex = firstModalButtonIndex

        renderCustomListModalTitle(dialog, dialogWidth, request.title, titleColor)
        val bodyTop = CUSTOM_TAG_MODAL_PADDING + CUSTOM_TAG_EDIT_TITLE_HEIGHT + CUSTOM_TAG_EDIT_TITLE_TO_COMPONENT_GAP
        renderPresetModalBody(dialog, layout, bodyTop)
        var y = bodyTop +
            layout.bodyHeight +
            PresetActionModalLayoutBuilder.BODY_TO_CONTROLS_GAP
        setPresetScopeWheelRegion(y)
        renderPresetScopeToggle(dialog, groupIndex, state, y)
        y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
        renderPresetBackendToggle(dialog, groupIndex, state, y)
        y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
        if (state.pendingAction == PendingPresetAction.SAVE) {
            renderPresetOverwriteToggle(dialog, groupIndex, state, y)
        }

        val canConfirm = presetCanConfirm(groupIndex, state)
        addEdgeCustomTagFooterButtons(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            left = ModalFooterButtonSpec(
                data = "preset_action_modal_confirm",
                kind = CampaignActionButtonKind.CONFIRM,
                enabled = canConfirm,
                labelText = "Confirm",
                tooltip = if (canConfirm) "Apply this ${state.pendingAction?.name?.lowercase() ?: "preset"} action." else "No matching preset is available for these options.",
                showTooltipWhileInactive = true,
            ) {
                if (presetCanConfirm(groupIndex, state)) {
                    request.onConfirm()
                }
            },
            right = ModalFooterButtonSpec(
                data = "preset_action_modal_cancel",
                kind = CampaignActionButtonKind.CANCEL,
                labelText = "Cancel",
                tooltip = "Cancel this preset action.",
            ) { request.onCancel() }
        )
        presetModalButtonEndIndex = buttons.size
    }

    private fun openPresetActionModal(groupIndex: Int, state: PresetControlState) {
        val normalized = CampaignSaveLoadPanelRenderer.normalizedState(state)
        updatePresetControlState(groupIndex, normalized)
        val panel = rootPanel ?: run {
            campaignScrollDirty = true
            return
        }
        if (presetModalShell != null) {
            refreshPresetActionModal(groupIndex, normalized)
        } else {
            renderPresetActionModal(panel, groupIndex, normalized)
        }
    }

    private fun refreshPresetActionModal(groupIndex: Int, state: PresetControlState) {
        val panel = presetModalRootPanel ?: run {
            campaignScrollDirty = true
            return
        }
        val shell = presetModalShell ?: run {
            campaignScrollDirty = true
            return
        }
        detachShipViewModal(panel, shell, buttons, presetModalButtonStartIndex, presetModalButtonEndIndex)
        clearPresetModalRefs()
        renderPresetActionModal(panel, groupIndex, state)
    }

    private fun renderPresetModalBody(
        dialog: CustomPanelAPI,
        layout: PresetActionModalLayout,
        bodyTop: Float,
    ) {
        val reviewBody = layout.reviewBody
        if (reviewBody != null) {
            val body = dialog.createCustomPanel(layout.contentWidth, layout.bodyHeight, null)
            dialog.addComponent(body)
            body.position.inTL(CUSTOM_TAG_MODAL_PADDING, bodyTop)
            PresetActionReviewRenderer.render(
                body = body,
                review = reviewBody,
                contentWidth = layout.contentWidth,
                maxPreviewRows = layout.reviewMaxPreviewRows,
            )
        } else {
            val body = dialog.createUIElement(layout.contentWidth, layout.bodyHeight, layout.bodyScrollable)
            body.setParaFontDefault()
            renderCampaignHighlightedTextBody(body, layout.bodyParagraphs)
            dialog.addUIElement(body).inTL(CUSTOM_TAG_MODAL_PADDING, bodyTop)
        }
    }

    private fun refreshPresetActionModalForCurrentState() {
        val (groupIndex, state) = pendingPresetConfirmation() ?: run {
            campaignScrollDirty = true
            return
        }
        refreshPresetActionModal(groupIndex, state)
    }

    private fun renderPresetScopeToggle(
        dialog: CustomPanelAPI,
        groupIndex: Int,
        state: PresetControlState,
        y: Float,
    ) {
        CustomTagEditRowRenderer.addBidirectionalMomentaryRow(
            dialog = dialog,
            y = y,
            leftLabel = "Preset:",
            buttonText = CampaignSaveLoadPanelRenderer.scopeControlLabel(state.scope),
            kind = CampaignActionButtonKind.UNCOLOURED,
            tooltip = CampaignPresetTerminology.presetFamilyTooltip() +
                "\nLeft-click or mouse-wheel down for next; right-click or mouse-wheel up for previous.",
            buttons = buttons,
            onLeftClick = {
                selectPresetModalScope(groupIndex, state, CampaignSaveLoadPanelRenderer.nextAvailableScope(state.scope))
            },
            onRightClick = {
                selectPresetModalScope(groupIndex, state, CampaignSaveLoadPanelRenderer.previousAvailableScope(state.scope))
            },
        )
    }

    private fun selectPresetModalScope(
        groupIndex: Int,
        state: PresetControlState,
        nextScope: WeaponPresetScope,
    ) {
        val nextBackend = if (
            state.pendingAction == PendingPresetAction.SAVE &&
            !state.backendManuallySelected &&
            state.scope == WeaponPresetScope.SINGLE &&
            nextScope != WeaponPresetScope.SINGLE
        ) {
            WeaponPresetBackend.EXTERNAL
        } else {
            state.backend
        }
        updateAndRefreshPresetModal(
            groupIndex = groupIndex,
            state = state.copy(
                scope = nextScope,
                backend = nextBackend,
                overwrite = if (nextScope == WeaponPresetScope.SUGGESTED) false else state.overwrite,
            )
        )
    }

    private fun updateAndRefreshPresetModal(groupIndex: Int, state: PresetControlState) {
        val normalized = CampaignSaveLoadPanelRenderer.normalizedState(state)
        updatePresetControlState(groupIndex, normalized)
        refreshPresetActionModal(groupIndex, normalized)
    }

    private fun renderPresetBackendToggle(
        dialog: CustomPanelAPI,
        groupIndex: Int,
        state: PresetControlState,
        y: Float,
    ) {
        val external = state.backend == WeaponPresetBackend.EXTERNAL
        val canToggle = CampaignSaveLoadPanelRenderer.canToggleBackend(state)
        val actionText = if (state.pendingAction == PendingPresetAction.LOAD) "load" else "save"
        addPresetModalToggleRow(
            dialog = dialog,
            y = y,
            leftLabel = "Cross campaign $actionText:",
            buttonText = if (external) "Enabled" else "Disabled",
            kind = when {
                !canToggle -> CampaignActionButtonKind.UNCOLOURED
                external -> CampaignActionButtonKind.CONFIRM
                else -> CampaignActionButtonKind.UNCOLOURED
            },
            tooltip = if (canToggle) {
                "Toggle cross-campaign ${state.scope.label()} preset storage."
            } else {
                "Cross-campaign storage is not available for this preset scope."
            },
            canToggle = canToggle,
        ) {
            val nextBackend = if (external) WeaponPresetBackend.CAMPAIGN else WeaponPresetBackend.EXTERNAL
            updateAndRefreshPresetModal(groupIndex, state.copy(backend = nextBackend, backendManuallySelected = true))
        }
    }

    private fun renderPresetOverwriteToggle(
        dialog: CustomPanelAPI,
        groupIndex: Int,
        state: PresetControlState,
        y: Float,
    ) {
        val canToggle = CampaignSaveLoadPanelRenderer.canToggleOverwrite(state)
        addPresetModalToggleRow(
            dialog = dialog,
            y = y,
            leftLabel = "Overwrite:",
            buttonText = if (state.overwrite) "Enabled" else "Disabled",
            kind = when {
                !canToggle -> CampaignActionButtonKind.UNCOLOURED
                state.overwrite -> CampaignActionButtonKind.CANCEL
                else -> CampaignActionButtonKind.UNCOLOURED
            },
            tooltip = if (canToggle) {
                "Toggle whether saving also overwrites matching active weapon groups."
            } else {
                "Overwrite is unavailable for this preset scope."
            },
            canToggle = canToggle,
        ) {
            updateAndRefreshPresetModal(groupIndex, state.copy(overwrite = !state.overwrite))
        }
    }

    private fun addPresetModalToggleRow(
        dialog: CustomPanelAPI,
        y: Float,
        leftLabel: String,
        buttonText: String,
        kind: CampaignActionButtonKind,
        tooltip: String,
        canToggle: Boolean,
        onToggle: () -> Unit,
    ) {
        CustomTagEditRowRenderer.addMomentaryRow(
            dialog = dialog,
            y = y,
            leftLabel = leftLabel,
            buttonText = buttonText,
            kind = kind,
            buttons = buttons,
            tooltip = tooltip,
            enabled = canToggle,
            onClick = onToggle,
        )
    }

    private fun presetCanConfirm(groupIndex: Int, state: PresetControlState): Boolean {
        val ship = activeShip ?: return false
        return CampaignSaveLoadPanelRenderer.canExecutePendingAction(ship, groupIndex, state, presetPeekCache)
    }

    private fun renderCustomListModal(panel: CustomPanelAPI) {
        val mode = customListBindings.modalModeProvider?.invoke() ?: run {
            customTagDialogPosition = null
            customListModalScrollRegion = null
            return
        }
        val ship = activeShip ?: return
        val context = activePersistenceContext ?: return
        val firstModalButtonIndex = buttons.size
        suppressNonModalButtonHover(firstModalButtonIndex)
        customListModalScrollRegion = null
        renderingCustomListModal = true
        customListModalRefreshPending = false
        val nestedManagerEdit = mode == CustomListModalMode.EDIT_TAG && customListState.isManagerReturnEdit()
        val managerRows = if (mode == CustomListModalMode.MANAGE_TAGS || nestedManagerEdit) {
            customTagManagerRowsForCurrentContext(context)
        } else {
            emptyList()
        }
        val changeReviewRows = if (mode == CustomListModalMode.CONFIRM_TAG_CHANGES) {
            customTagChangeReviewRowsForCurrentContext(context)
        } else {
            emptyList()
        }

        val targetWidth = CustomListModalLayout.targetWidth(mode, nestedManagerEdit)
        val targetHeight = CustomListModalLayout.targetHeight(
            mode = mode,
            nestedManagerEdit = nestedManagerEdit,
            editParameterCount = currentCustomTagEditParameterCount(),
            managerRowCount = managerRows.size,
            changeReviewRowCount = changeReviewRows.size,
        )
        val dialogWidth = min(targetWidth, panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING)
        val dialogHeight = min(targetHeight, CustomListModalLayout.maxModalHeight(panel.position.height))
        val shell = ShipViewModalFactory.createShell(
            panel = panel,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            borderColor = CampaignGuiStyle.SAVE_BUTTON_HOVER_COLOR,
        )
        addCustomTagBackdropButtons(shell.backdrop, shell.bounds.x, shell.bounds.y, shell.bounds.width, shell.bounds.height)
        val dialog = shell.dialog
        customListModalRootPanel = panel
        customListModalShell = shell
        customListModalButtonStartIndex = firstModalButtonIndex
        customTagDialogPosition = dialog.position

        when (mode) {
            CustomListModalMode.MANAGE_TAGS -> renderCustomTagTemplateSelection(
                dialog,
                ship,
                context,
                dialogWidth,
                dialogHeight,
                managerRows,
            )
            CustomListModalMode.EDIT_TAG -> {
                if (nestedManagerEdit) {
                    renderNestedCustomTagManagerEdit(dialog, ship, context, dialogWidth, dialogHeight, managerRows)
                } else {
                    renderCustomTagParameterEditor(dialog, ship, context, dialogWidth, dialogHeight)
                }
            }
            CustomListModalMode.CONFIRM_TAG_CHANGES -> renderCustomTagChangeConfirmation(
                dialog,
                ship,
                context,
                dialogWidth,
                dialogHeight,
                changeReviewRows,
            )
            CustomListModalMode.DEBUG_COLORS -> renderDebugColorModal(dialog, dialogWidth, dialogHeight)
            CustomListModalMode.RENAME_LOADOUT -> renderLoadoutRenameModal(dialog, dialogWidth, dialogHeight)
        }
        customListModalButtonEndIndex = buttons.size
        renderingCustomListModal = false
        if (customListModalRefreshPending) {
            customListModalRefreshPending = false
            campaignScrollDirty = true
        }
    }

    override fun onCustomListModalStateChanged() {
        if (renderingCustomListModal) {
            customListModalRefreshPending = true
            return
        }
        val mode = customListBindings.modalModeProvider?.invoke()
        if (mode == null) {
            campaignScrollDirty = true
            return
        }
        refreshCustomListModalOrDirty()
    }

    private fun refreshCustomListModalOrDirty() {
        val panel = customListModalRootPanel
        val shell = customListModalShell
        if (panel == null || shell == null) {
            campaignScrollDirty = true
            return
        }
        detachShipViewModal(panel, shell, buttons, customListModalButtonStartIndex, customListModalButtonEndIndex)
        clearCustomListModalRefs()
        renderCustomListModal(panel)
    }

    private fun clearCustomListModalRefs() {
        customListModalRootPanel = null
        customListModalShell = null
        customListModalButtonStartIndex = -1
        customListModalButtonEndIndex = -1
    }

    private fun clearPresetModalRefs() {
        presetModalRootPanel = null
        presetModalShell = null
        presetModalButtonStartIndex = -1
        presetModalButtonEndIndex = -1
        clearPresetScopeWheelRegion()
    }

    private fun setPresetScopeWheelRegion(rowTopFromDialogTop: Float) {
        val dialogHeight = confirmationDialogPosition?.height ?: run {
            clearPresetScopeWheelRegion()
            return
        }
        val rowBottom = dialogHeight - rowTopFromDialogTop - CUSTOM_TAG_MODAL_ROW_HEIGHT
        presetScopeWheelRegion = VerticalScrollRegion(
            key = Unit,
            left = CUSTOM_TAG_MODAL_PADDING,
            right = CUSTOM_TAG_MODAL_PADDING + CustomTagEditRowRenderer.rowWidth(),
            bottom = rowBottom,
            top = rowBottom + CUSTOM_TAG_MODAL_ROW_HEIGHT,
            maxOffset = 1,
        )
    }

    private fun clearPresetScopeWheelRegion() {
        presetScopeWheelRegion = null
    }

    private fun clearExternalConfirmationModalRefs() {
        externalConfirmationRootPanel = null
        externalConfirmationModal = null
        externalConfirmationButtonStartIndex = -1
        externalConfirmationButtonEndIndex = -1
    }

    private fun renderNestedCustomTagManagerEdit(
        dialog: CustomPanelAPI,
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        dialogWidth: Float,
        dialogHeight: Float,
        rows: List<CustomTagManagerRow>,
    ) {
        val managerButtonStart = buttons.size
        renderCustomTagTemplateSelection(dialog, ship, context, dialogWidth, dialogHeight, rows)
        buttons.subList(managerButtonStart.coerceIn(0, buttons.size), buttons.size)
            .forEach { button -> button.suppressCampaignButtonHover() }
        addCustomTagBackdropButton(dialog, 0f, 0f, dialogWidth, dialogHeight)?.let { shield ->
            buttons.addCampaignButtonControl(button = shield) {}
        }

        val editorWidth = min(CUSTOM_TAG_EDIT_MODAL_WIDTH, dialogWidth - 2f * CUSTOM_TAG_MODAL_PADDING)
        val editorHeight = min(
            CustomListModalLayout.editModalHeight(currentCustomTagEditParameterCount()),
            dialogHeight - 2f * CUSTOM_TAG_MODAL_PADDING
        )
        val editor = ShipViewModalFactory.createDialog(
            parent = dialog,
            bounds = ShipViewModalFactory.centeredBounds(dialog, editorWidth, editorHeight),
            borderColor = CampaignGuiStyle.SAVE_BUTTON_HOVER_COLOR,
            fillColor = Color(34, 34, 34, 250),
        )
        renderCustomTagParameterEditor(editor, ship, context, editorWidth, editorHeight)
    }

    private fun renderCustomListModalTitle(
        dialog: CustomPanelAPI,
        dialogWidth: Float,
        title: String,
        color: Color = CampaignGuiStyle.SAVE_BUTTON_HOVER_COLOR,
    ) {
        val text = dialog.createUIElement(
            dialogWidth - 2f * CUSTOM_TAG_MODAL_PADDING,
            CUSTOM_TAG_EDIT_TITLE_HEIGHT,
            false
        )
        text.addAgcLargeHeading(title, color)
        dialog.addUIElement(text).inTL(CUSTOM_TAG_MODAL_PADDING, CUSTOM_TAG_MODAL_PADDING)
    }

    private fun renderCustomTagTemplateSelection(
        dialog: CustomPanelAPI,
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        dialogWidth: Float,
        dialogHeight: Float,
        rows: List<CustomTagManagerRow>? = null,
    ) {
        if (context.shipId.isBlank()) return
        val renderRows = rows ?: customTagManagerRowsForCurrentContext(context)
        val headerHeight = renderCustomTagManagerHeader(dialog, dialogWidth)

        val rowTop = CUSTOM_TAG_MODAL_PADDING + headerHeight + CUSTOM_TAG_EDIT_TITLE_TO_COMPONENT_GAP
        val buttonY = dialogHeight - CUSTOM_TAG_MODAL_PADDING - CUSTOM_TAG_MODAL_ROW_HEIGHT
        customListModalScrollRegion = CustomListModalListRenderer.render(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            rowTop = rowTop,
            buttonY = buttonY,
            rows = renderRows,
            target = CustomListModalScrollTarget.MANAGE_TAGS,
            scrollUpData = "custom_tag_manager_scroll_up",
            scrollDownData = "custom_tag_manager_scroll_down",
            currentOffset = customListModalScrollOffset(CustomListModalScrollTarget.MANAGE_TAGS),
            buttons = buttons,
            onScrollOffsetChanged = { setCustomListModalScrollOffset(CustomListModalScrollTarget.MANAGE_TAGS, it) },
            onDirty = ::refreshCustomListModalOrDirty,
        ) { listPanel, row, y, width ->
            renderCustomTagManagerRow(
                dialog = listPanel,
                ship = ship,
                context = context,
                row = row,
                y = y,
                width = width,
            )
        }

        val currentTags = currentCustomTagsForContext(context)
        val currentShipModes = currentCustomShipModesForContext(context)
        val stagedState = customListState.normalizeStagedState(currentTags)
        val shipModeStagedState = customListState.normalizeShipModeStagedState(currentShipModes)
        val hasPendingTagChanges = stagedState.additions.isNotEmpty() ||
            stagedState.removals.isNotEmpty() ||
            stagedState.edits.isNotEmpty()
        val hasPendingShipModeChanges = shipModeStagedState.additions.isNotEmpty() ||
            shipModeStagedState.removals.isNotEmpty() ||
            shipModeStagedState.edits.isNotEmpty()
        val hasPendingListChanges = hasPendingTagChanges || hasPendingShipModeChanges
        addEdgeCustomTagFooterButtons(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            left = ModalFooterButtonSpec(
                data = "custom_tag_manager_confirm_changes",
                kind = CampaignActionButtonKind.CONFIRM,
                enabled = hasPendingListChanges,
                labelText = "Confirm",
                tooltip = if (!hasPendingListChanges) {
                    "Add or remove at least one custom tag or ship mode before confirming."
                } else {
                    "Review the pending custom list changes before applying them."
                },
                showTooltipWhileInactive = true,
            ) {
                customListBindings.onModalModeUpdate?.invoke(CustomListModalMode.CONFIRM_TAG_CHANGES)
                refreshCustomListModalOrDirty()
            },
            right = ModalFooterButtonSpec(
                data = "custom_tag_add_cancel",
                kind = CampaignActionButtonKind.CANCEL,
                labelText = "Cancel",
            ) { closeCustomListModal() }
        )
    }

    private fun renderCustomTagManagerHeader(dialog: CustomPanelAPI, dialogWidth: Float): Float {
        val headerLayout = CustomListModalLayout.managerHeaderLayout(dialogWidth)
        val title = dialog.createUIElement(
            dialogWidth - 2f * CUSTOM_TAG_MODAL_PADDING,
            CUSTOM_TAG_EDIT_TITLE_HEIGHT,
            false
        )
        title.addAgcLargeHeading("Manage Tags and Ship Modes", CampaignGuiStyle.SAVE_BUTTON_HOVER_COLOR)
        dialog.addUIElement(title).inTL(CUSTOM_TAG_MODAL_PADDING, CUSTOM_TAG_MODAL_PADDING)

        val instructions = dialog.createUIElement(
            dialogWidth - 2f * CUSTOM_TAG_MODAL_PADDING,
            headerLayout.instructionLayout.renderHeight,
            false
        )
        instructions.setParaFontDefault()
        instructions.addAgcText(headerLayout.instructionLayout.wrappedText, 0f)
        dialog.addUIElement(instructions).inTL(
            CUSTOM_TAG_MODAL_PADDING,
            CUSTOM_TAG_MODAL_PADDING + headerLayout.instructionTop
        )
        return headerLayout.renderHeight
    }

    private fun renderCustomTagManagerRow(
        dialog: CustomPanelAPI,
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        row: CustomTagManagerRow,
        y: Float,
        width: Float,
    ) {
        CustomTagManagerRowRenderer.renderManagerRow(
            dialog = dialog,
            row = row,
            y = y,
            width = width,
            buttons = buttons,
            editSourceForTag = customListState::sourceForEdit,
            onToggleListSection = customListState::toggleListSection,
            onToggleCategory = customListState::toggleManagerCategory,
            onToggleArchetype = customListState::toggleManagerArchetype,
            onToggleTag = { tagRow, editSource ->
                when {
                    tagRow.pendingAddition -> customListState.removeAddition(tagRow.tag)
                    tagRow.pendingEdit -> customListState.toggleMarkedForRemoval(editSource ?: tagRow.tag)
                    else -> customListState.toggleMarkedForRemoval(tagRow.tag)
                }
            },
            onToggleShipMode = { modeRow ->
                when {
                    modeRow.pendingAddition -> customListState.removeShipModeAddition(modeRow.mode)
                    modeRow.pendingEdit -> customListState.toggleShipModeMarkedForRemoval(
                        customListState.sourceForShipModeEdit(modeRow.mode) ?: modeRow.mode
                    )
                    else -> customListState.toggleShipModeMarkedForRemoval(modeRow.mode)
                }
            },
            onEditShipMode = { mode, pendingAddition, sourceMode ->
                startCustomTagManagerEditFromShipMode(
                    mode = mode,
                    pendingAddition = pendingAddition,
                    sourceMode = sourceMode,
                )
            },
            onEditTag = { tag, pendingAddition, sourceTag ->
                startCustomTagManagerEditFromTag(
                    ship = ship,
                    context = context,
                    tag = tag,
                    pendingAddition = pendingAddition,
                    sourceTag = sourceTag,
                )
            },
            onAddDefinition = ::startCustomTagManagerEdit,
            onAddDirectTag = { tag -> customListState.stageAddition(tag, currentCustomTagsForActiveContext()) },
            onAddShipModeDefinition = ::startCustomTagManagerEditShipMode,
            onAddShipMode = { mode -> customListState.stageShipModeAddition(mode, currentCustomShipModesForContext(context)) },
        )
    }

    private fun customTagManagerRows(
        currentTags: List<String>,
        pendingAdditions: List<String>,
        pendingRemovals: Set<String>,
        pendingEdits: Map<String, String>,
        currentShipModes: List<String> = currentCustomShipModesForActiveContext(),
    ): List<CustomTagManagerRow> {
        return CustomTagManagerRows.build(
            currentTags = currentTags,
            pendingAdditions = pendingAdditions,
            pendingRemovals = pendingRemovals,
            pendingEdits = pendingEdits,
            currentShipModes = currentShipModes,
            pendingShipModeAdditions = customListState.shipModeAdditions(),
            pendingShipModeRemovals = customListState.shipModeRemovals(),
            pendingShipModeEdits = customListState.shipModeEdits(),
            expandedListSections = customListState.expandedListSections(),
            expandedCategories = customListState.expandedCategories(),
            expandedArchetypes = customListState.expandedArchetypes(),
            archetypes = customTagArchetypes(),
        )
    }

    private fun customTagArchetypes(): List<CustomTagArchetype> =
        customTagArchetypeCache ?: CustomTagManagerRows.completeTagListArchetypes()
            .also { customTagArchetypeCache = it }

    private fun canonicalTag(tag: String): String = canonicalizeWeaponTagName(tag)

    private fun renderCustomTagChangeConfirmation(
        dialog: CustomPanelAPI,
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        dialogWidth: Float,
        dialogHeight: Float,
        rows: List<CustomTagChangeReviewRow>? = null,
    ) {
        val currentTags = currentCustomTagsForContext(context)
        val currentShipModes = currentCustomShipModesForContext(context)
        val (additions, normalizedRemovals, edits) = customListState.normalizeStagedState(currentTags)
        val shipModeStagedState = customListState.normalizeShipModeStagedState(currentShipModes)
        val removals = normalizedRemovals.toList().sorted()
        val hasRemovals = removals.isNotEmpty() || shipModeStagedState.removals.isNotEmpty()
        renderCustomListModalTitle(
            dialog = dialog,
            dialogWidth = dialogWidth,
            title = if (hasRemovals) "Custom List Changes Warning" else "Custom List Changes",
            color = if (hasRemovals) CampaignGuiStyle.ALERT_RED_COLOR else CampaignGuiStyle.CONFIRM_BUTTON_HOVER_COLOR
        )

        val renderRows = rows ?: customTagChangeReviewRows(
            additions = additions,
            edits = edits,
            removals = removals,
            shipModeAdditions = shipModeStagedState.additions,
            shipModeEdits = shipModeStagedState.edits,
            shipModeRemovals = shipModeStagedState.removals.toList(),
        )
        val rowTop = CUSTOM_TAG_MODAL_PADDING + CUSTOM_TAG_EDIT_TITLE_HEIGHT + CUSTOM_TAG_EDIT_TITLE_TO_COMPONENT_GAP
        val buttonY = dialogHeight - CUSTOM_TAG_MODAL_PADDING - CUSTOM_TAG_MODAL_ROW_HEIGHT
        customListModalScrollRegion = CustomListModalListRenderer.render(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            rowTop = rowTop,
            buttonY = buttonY,
            rows = renderRows,
            target = CustomListModalScrollTarget.REVIEW_CHANGES,
            scrollUpData = "custom_tag_change_review_scroll_up",
            scrollDownData = "custom_tag_change_review_scroll_down",
            currentOffset = customListModalScrollOffset(CustomListModalScrollTarget.REVIEW_CHANGES),
            buttons = buttons,
            onScrollOffsetChanged = { setCustomListModalScrollOffset(CustomListModalScrollTarget.REVIEW_CHANGES, it) },
            onDirty = ::refreshCustomListModalOrDirty,
        ) { listPanel, row, y, width ->
            renderCustomTagChangeReviewRow(listPanel, row, y, width)
        }

        addEdgeCustomTagFooterButtons(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            left = ModalFooterButtonSpec(
                data = "custom_tag_change_confirm",
                kind = CampaignActionButtonKind.CONFIRM,
                labelText = "Confirm",
                tooltip = "Apply pending Custom tag and ship-mode additions, modifications, and removals.",
            ) {
                applyCustomListManagerChanges(ship, context)
            },
            right = ModalFooterButtonSpec(
                data = "custom_tag_change_go_back",
                kind = CampaignActionButtonKind.CANCEL,
                labelText = "Go Back",
                tooltip = "Return to the Custom list editor without applying changes.",
            ) {
                customListBindings.onModalModeUpdate?.invoke(CustomListModalMode.MANAGE_TAGS)
                refreshCustomListModalOrDirty()
            }
        )
    }

    private fun renderCustomTagChangeReviewRow(
        dialog: CustomPanelAPI,
        row: CustomTagChangeReviewRow,
        y: Float,
        width: Float,
    ) {
        CustomTagManagerRowRenderer.renderChangeReviewRow(
            dialog = dialog,
            row = row,
            y = y,
            width = width,
            buttons = buttons,
            onToggleSection = { sectionId ->
                customListState.toggleChangeReviewSection(sectionId, customTagChangeReviewSectionIds())
            }
        )
    }

    private fun customListModalScrollOffset(target: CustomListModalScrollTarget): Int {
        return when (target) {
            CustomListModalScrollTarget.MANAGE_TAGS -> customListBindings.managerScrollOffsetProvider?.invoke() ?: 0
            CustomListModalScrollTarget.REVIEW_CHANGES -> customListBindings.changeReviewScrollOffsetProvider?.invoke() ?: 0
        }
    }

    private fun setCustomListModalScrollOffset(target: CustomListModalScrollTarget, offset: Int) {
        when (target) {
            CustomListModalScrollTarget.MANAGE_TAGS -> customListBindings.onManagerScrollOffsetUpdate?.invoke(offset)
            CustomListModalScrollTarget.REVIEW_CHANGES -> customListBindings.onChangeReviewScrollOffsetUpdate?.invoke(offset)
        }
    }

    private fun currentCustomTagEditParameterCount(): Int = currentCustomTagDraftDefinition()?.parameters?.size ?: 0

    private fun customTagManagerRowsForCurrentContext(context: ShipEditorPersistenceContext): List<CustomTagManagerRow> {
        if (context.shipId.isBlank()) return emptyList()
        val currentTags = currentCustomTagsForContext(context)
        val (pendingAdditions, pendingRemovals, pendingEdits) = customListState.normalizeStagedState(currentTags)
        val currentShipModes = currentCustomShipModesForContext(context)
        customListState.normalizeShipModeStagedState(currentShipModes)
        return customTagManagerRows(
            currentTags = currentTags,
            pendingAdditions = pendingAdditions,
            pendingRemovals = pendingRemovals,
            pendingEdits = pendingEdits,
            currentShipModes = currentShipModes,
        )
    }

    private fun customTagChangeReviewRowsForCurrentContext(
        context: ShipEditorPersistenceContext,
    ): List<CustomTagChangeReviewRow> {
        return if (context.shipId.isNotBlank()) {
            val currentTags = currentCustomTagsForContext(context)
            val currentShipModes = currentCustomShipModesForContext(context)
            val (additions, removals, edits) = customListState.normalizeStagedState(currentTags)
            val shipModeStagedState = customListState.normalizeShipModeStagedState(currentShipModes)
            customTagChangeReviewRows(
                additions = additions,
                edits = edits,
                removals = removals.toList(),
                shipModeAdditions = shipModeStagedState.additions,
                shipModeEdits = shipModeStagedState.edits,
                shipModeRemovals = shipModeStagedState.removals.toList(),
            )
        } else {
            CustomTagChangeReviewSection.entries.map { section ->
                CustomTagChangeReviewRow.Heading(section, count = 0, expanded = true)
            }
        }
    }

    private fun renderLoadoutRenameModal(
        dialog: CustomPanelAPI,
        dialogWidth: Float,
        dialogHeight: Float,
    ) {
        val index = currentLoadoutRenameIndex()
        val draft = currentLoadoutRenameDraft()
        val validation = Settings.validateLoadoutName(index, draft)

        renderCustomListModalTitle(dialog, dialogWidth, "Rename Loadout")

        var y = CUSTOM_TAG_MODAL_PADDING + CUSTOM_TAG_EDIT_TITLE_HEIGHT + CUSTOM_TAG_EDIT_TITLE_TO_COMPONENT_GAP
        CustomTagEditRowRenderer.renderDisplay(
            dialog = dialog,
            y = y,
            leftLabel = "Loadout",
            value = "${index + 1} / ${Settings.maxLoadouts().coerceAtLeast(1)}",
        )
        y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
        CustomTagEditRowRenderer.renderDisplay(
            dialog = dialog,
            y = y,
            leftLabel = "Name",
            value = draft.ifBlank { "<empty>" },
            valueColor = if (validation == null) CampaignGuiStyle.DEFAULT_TEXT_COLOUR else CampaignGuiStyle.DISABLED_TAG_TEXT_COLOR,
        )
        y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
        val hint = validation ?: "Alt+right-click the loadout button to rename this value again later."
        val hintPanel = dialog.createUIElement(dialogWidth - 2f * CUSTOM_TAG_MODAL_PADDING, CUSTOM_TAG_MODAL_ROW_HEIGHT, false)
        hintPanel.addAgcText(hint, 0f, if (validation == null) CampaignGuiStyle.DEFAULT_TEXT_COLOUR else CampaignGuiStyle.ALERT_RED_COLOR)
        dialog.addUIElement(hintPanel).inTL(CUSTOM_TAG_MODAL_PADDING, y)

        val canConfirm = validation == null
        addEqualWidthCustomTagFooterButtons(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            specs = listOf(
                ModalFooterButtonSpec(
                    data = "loadout_rename_confirm",
                    kind = CampaignActionButtonKind.CONFIRM,
                    enabled = canConfirm,
                    labelText = "Confirm",
                    tooltip = validation ?: "Rename the current loadout.",
                    showTooltipWhileInactive = true,
                ) {
                    if (Settings.renameLoadout(index, currentLoadoutRenameDraft())) {
                        closeCustomListModal()
                        campaignScrollDirty = true
                    }
                },
                ModalFooterButtonSpec(
                    data = "loadout_rename_cancel",
                    kind = CampaignActionButtonKind.CANCEL,
                    labelText = "Cancel",
                    tooltip = "Close without renaming this loadout.",
                ) { closeCustomListModal() }
            )
        )
    }

    private fun renderDebugColorModal(
        dialog: CustomPanelAPI,
        dialogWidth: Float,
        dialogHeight: Float,
    ) {
        val targets = CampaignGuiStyle.debugColorTargets()
        if (targets.isEmpty()) {
            closeCustomListModal()
            return
        }
        val index = currentDebugColorIndex(targets)
        val target = targets[index]
        ensureDebugColorDraft(target)
        val draft = currentDebugColorDraft(target)
        val persistent = isDebugColorPersistent()

        DebugColorModalRenderer.render(
            dialog = dialog,
            buttons = buttons,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            target = target,
            targetIndex = index,
            targetCount = targets.size,
            draft = draft,
            persistent = persistent,
            renderTitle = ::renderCustomListModalTitle,
            onCycleTarget = { delta -> selectDebugColorTarget(targets, index, delta) },
            onDraftChanged = ::setDebugColorDraft,
            onPersistenceChanged = ::setDebugColorPersistent,
            onApply = { selectedTarget ->
                CampaignGuiStyle.setDebugColorOverride(
                    selectedTarget,
                    currentDebugColorDraft(selectedTarget),
                    persistent = isDebugColorPersistent()
                )
            },
            onClose = ::closeCustomListModal,
        )
    }

    private fun renderCustomTagParameterEditor(
        dialog: CustomPanelAPI,
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        dialogWidth: Float,
        dialogHeight: Float,
    ) {
        val state = currentCustomTagEditRenderState(context)
        if (state == null) {
            customListState.returnToManagerFromEdit()
            return
        }

        renderCustomListModalTitle(
            dialog,
            dialogWidth,
            if (customListState.isShipModeEdit()) "Edit Ship Mode" else "Edit Tag"
        )

        var y = CUSTOM_TAG_MODAL_PADDING + CUSTOM_TAG_EDIT_TITLE_HEIGHT + CUSTOM_TAG_EDIT_TITLE_TO_COMPONENT_GAP
        val widthMultiplier = CustomTagEditRowRenderer.widthMultiplierForDialogWidth(dialogWidth)
        CustomTagEditRowRenderer.renderDisplay(
            dialog,
            y,
            "Preview",
            state.preview,
            CampaignGuiStyle.MODIFIER_TEXT_COLOUR,
            widthMultiplier = widthMultiplier,
        )
        y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
        renderCustomTagEditParameterRows(dialog, state, y, widthMultiplier)
        renderCustomTagEditButtons(dialog, ship, context, state, dialogWidth, dialogHeight)
    }

    private fun currentCustomTagEditRenderState(context: ShipEditorPersistenceContext): CustomTagEditRenderState? {
        val definition = currentCustomTagDraftDefinition()
        val fixedTag = currentCustomTagFixedTag()
        val draftValues = definition?.let(::currentCustomTagDraftValues).orEmpty()
        if (customListState.isShipModeEdit()) {
            return currentCustomShipModeEditRenderState(context, definition, draftValues)
        }
        return CustomTagEditRenderStateBuilder.build(
            context = context,
            definition = definition,
            fixedTag = fixedTag,
            draftValues = draftValues,
            editSource = currentCustomTagEditSource(),
            editSourceGroupIndex = customListBindings.editSourceGroupProvider?.invoke(),
            editSourceTagProviderValue = customListBindings.editSourceTagProvider?.invoke(),
            modalState = customListState,
        )
    }

    private fun currentCustomShipModeEditRenderState(
        context: ShipEditorPersistenceContext,
        definition: EditableWeaponTagDefinition?,
        draftValues: Map<String, String>,
    ): CustomTagEditRenderState? {
        return CustomShipModeEditRenderStateBuilder.build(
            definition = definition,
            draftValues = draftValues,
            currentModes = currentCustomShipModesForContext(context),
            loadModes = context::loadModes,
            editSourceGroupIndex = customListBindings.editSourceGroupProvider?.invoke(),
            editSourceValue = customListBindings.editSourceTagProvider?.invoke(),
            modalState = customListState,
        )
    }

    private fun renderCustomTagEditParameterRows(
        dialog: CustomPanelAPI,
        state: CustomTagEditRenderState,
        startY: Float,
        widthMultiplier: Float,
    ) {
        var y = startY
        val definition = state.definition ?: return
        EditableWeaponTagDefinitions.visibleParameters(definition).forEach { parameter ->
            val disabledReason = disabledEditParameterReason(state.draftValues, parameter)
            val tooltip = editParameterTooltip(definition, parameter, disabledReason)
            when (parameter) {
                is ChoiceParameter -> {
                    val current = state.draftValues[parameter.id] ?: parameter.defaultOptionId
                    val label = parameter.options.firstOrNull { it.id == current }?.label ?: current
                    CustomTagEditRowRenderer.addBidirectionalMomentaryRow(
                        dialog = dialog,
                        y = y,
                        leftLabel = parameter.label,
                        buttonText = "$label [$current]",
                        kind = CampaignActionButtonKind.UNCOLOURED,
                        highlightTokens = listOf("[$current]"),
                        tooltip = tooltip ?: "Cycle ${parameter.label}. Left-click for next; right-click for previous.",
                        widthMultiplier = widthMultiplier,
                        buttons = buttons,
                        onLeftClick = {
                            cycleDraftChoice(definition, parameter, 1)
                        },
                        onRightClick = {
                            cycleDraftChoice(definition, parameter, -1)
                        },
                    )
                    y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
                }
                is NumberParameter -> {
                    renderEditTagIncrementor(
                        dialog,
                        definition,
                        parameter,
                        state.draftValues,
                        y,
                        tooltip,
                        disabledReason == null,
                        widthMultiplier,
                    )
                    y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
                }
                is DecimalParameter -> {
                    renderEditTagDecimalIncrementor(
                        dialog,
                        definition,
                        parameter,
                        state.draftValues,
                        y,
                        tooltip,
                        disabledReason == null,
                        widthMultiplier,
                    )
                    y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
                }
                is ToggleParameter -> {
                    val enabled = state.draftValues[parameter.id]?.toBooleanStrictOrNull() ?: parameter.defaultValue
                    val rowEnabled = disabledReason == null
                    CustomTagEditRowRenderer.addMomentaryRow(
                        dialog = dialog,
                        y = y,
                        leftLabel = parameter.label,
                        buttonText = if (enabled) "On" else "Off",
                        kind = if (enabled && rowEnabled) CampaignActionButtonKind.ACTIVE else CampaignActionButtonKind.UNCOLOURED,
                        buttons = buttons,
                        tooltip = tooltip ?: "Toggle ${parameter.label}.",
                        widthMultiplier = widthMultiplier,
                        enabled = rowEnabled,
                    ) {
                        updateDraftValue(parameter.id, (!enabled).toString())
                    }
                    y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
                }
                is TextParameter -> {
                    val value = state.draftValues[parameter.id] ?: parameter.defaultValue
                    CustomTagEditRowRenderer.renderDisplay(
                        dialog,
                        y,
                        parameter.label,
                        "$value (text editing pending)",
                        widthMultiplier = widthMultiplier,
                    )
                    y += CUSTOM_TAG_MODAL_ROW_HEIGHT + CUSTOM_TAG_MODAL_ROW_GAP
                }
            }
        }
    }

    private fun editParameterTooltip(
        definition: EditableWeaponTagDefinition,
        parameter: EditableTagParameterDefinition,
        disabledReason: String?,
    ): String? {
        val base = editableTagParameterTooltip(definition, parameter)
        return listOfNotNull(base, disabledReason).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun disabledEditParameterReason(
        draftValues: Map<String, String>,
        parameter: EditableTagParameterDefinition,
    ): String? {
        if (parameter.id == EditableWeaponTagDefinitions.PARAM_IGNORE_IF_BEAMED) {
            val metric = draftValues[EditableWeaponTagDefinitions.PARAM_FLUX_METRIC] ?: "TF"
            return if (metric == "SF") null else "Only available for soft-flux HoldFire."
        }
        if (parameter.id == EditableWeaponTagDefinitions.PARAM_BEAM_WINDOW) {
            val metric = draftValues[EditableWeaponTagDefinitions.PARAM_FLUX_METRIC] ?: "TF"
            if (metric != "SF") return "Only available for soft-flux HoldFire."
            val enabled = draftValues[EditableWeaponTagDefinitions.PARAM_IGNORE_IF_BEAMED]
                ?.toBooleanStrictOrNull() == true
            return if (enabled) null else "Enable Ignore if beamed to edit this value."
        }
        val thresholdIndex = parameter.id
            .takeIf { it.startsWith("threshold") && it.length > "threshold".length }
            ?.removePrefix("threshold")
            ?.toIntOrNull()
            ?: return null
        val enabledId = "enabledThreshold$thresholdIndex"
        val enabled = draftValues[enabledId]?.toBooleanStrictOrNull() ?: true
        return if (enabled) null else "Enable threshold $thresholdIndex to edit this value."
    }

    private fun editableTagParameterTooltip(
        definition: EditableWeaponTagDefinition,
        parameter: EditableTagParameterDefinition,
    ): String? {
        shipModeParameterTooltip(definition, parameter)?.let { return it }
        return when (parameter.id) {
            EditableWeaponTagDefinitions.PARAM_FLUX_METRIC ->
                "Choose total, soft, or hard flux to check."
            EditableWeaponTagDefinitions.PARAM_THRESHOLD ->
                editableTagThresholdTooltip(definition)
            EditableWeaponTagDefinitions.PARAM_TARGET_SHIELD_THRESHOLD ->
                editableTagTargetShieldThresholdTooltip(definition)
            EditableWeaponTagDefinitions.PARAM_TOTAL_FLUX_CAP ->
                "Safety cap: this tag stops applying above this total flux."
            EditableWeaponTagDefinitions.PARAM_PRIORITY_MULTIPLIER ->
                "Higher values make matching targets more preferred."
            EditableWeaponTagDefinitions.PARAM_KINETIC_THRESHOLD ->
                "Kinetic shots prefer shields above this shield factor. Values are percentages; 50 means 0.50."
            EditableWeaponTagDefinitions.PARAM_HIGH_EXPLOSIVE_THRESHOLD ->
                "HE/frag shots prefer shields below this shield factor. Values are percentages; 15 means 0.15."
            EditableWeaponTagDefinitions.PARAM_TRIGGER_HAPPINESS ->
                "Higher values make Opportunist more willing to fire."
            EditableWeaponTagDefinitions.PARAM_CLEANUP_DAMAGE_CAP ->
                "Ignore AvoidPD waste checks for weapons at or below this estimated attack-packet damage."
            EditableWeaponTagDefinitions.PARAM_IGNORE_IF_BEAMED ->
                "Soft-flux hold is ignored briefly after this ship is hit by an enemy beam. Max TF still applies."
            EditableWeaponTagDefinitions.PARAM_BEAM_WINDOW ->
                "How long the beam exception stays active after the last enemy beam hit."
            EditableWeaponTagDefinitions.PARAM_IGNORE_KINETIC_WEAPONS ->
                "When enabled, this tag does not affect kinetic weapons."
            EditableWeaponTagDefinitions.PARAM_IGNORE_HIGH_EXPLOSIVE_WEAPONS ->
                "When enabled, this tag does not affect high-explosive weapons."
            EditableWeaponTagDefinitions.PARAM_IGNORE_FRAGMENTATION_WEAPONS ->
                "When enabled, this tag does not affect fragmentation weapons."
            EditableWeaponTagDefinitions.PARAM_IGNORE_ENERGY_WEAPONS ->
                "When enabled, this tag does not affect energy weapons."
            EditableWeaponTagDefinitions.PARAM_IGNORE_BEAM_WEAPONS ->
                "When enabled, this tag does not affect beam or burst-beam weapons."
            EditableWeaponTagDefinitions.PARAM_IGNORE_MISSILE_WEAPONS ->
                "When enabled, this tag does not affect missile-slot weapons."
            EditableWeaponTagDefinitions.PARAM_IGNORE_PROJECTILE_WEAPONS ->
                "When enabled, this tag does not affect non-beam, non-missile projectile weapons."
            EditableWeaponTagDefinitions.PARAM_REQUIRE_SHIP_TARGET ->
                "When enabled, this tag only works while the ship has an active ship target."
            EditableWeaponTagDefinitions.PARAM_TRIGGER_AMMO_FEEDER ->
                syncSystemTriggerTooltip("Accelerated Ammo Feeder")
            EditableWeaponTagDefinitions.PARAM_TRIGGER_HIGH_ENERGY_FOCUS ->
                syncSystemTriggerTooltip("High Energy Focus")
            EditableWeaponTagDefinitions.PARAM_TRIGGER_LIDAR_ARRAY ->
                "Triggers Lidar Array as the volley is prepared. Volleys can still fire if Lidar Array is unavailable."
            EditableWeaponTagDefinitions.PARAM_TRIGGER_TEMPORAL_SHELL ->
                syncSystemTriggerTooltip("Temporal Shell")
            EditableWeaponTagDefinitions.PARAM_TRIGGER_ENTROPY_AMPLIFIER ->
                "Triggers Entropy Amplifier just before a volley at a ship target. Volleys can still fire if it is unavailable."
            else -> fallbackEditParameterTooltip(parameter)
        }
    }

    private fun shipModeParameterTooltip(
        definition: EditableWeaponTagDefinition,
        parameter: EditableTagParameterDefinition,
    ): String? = when (definition.id) {
        "ship_personality_override" -> "Choose the AI personality to use."
        "ship_low_shield_flux_threshold" -> "Lower shields when flux is above this percent."
        "ship_shield_up_flux_threshold" -> "Raise shields when flux is below this percent."
        "ship_shield_up_plus_flux_threshold" -> "Keep shields raised while flux is below this percent."
        "ship_vent" -> when (parameter.id) {
            EditableShipModeDefinitions.PARAM_THRESHOLD -> "Vent when flux is above this percent."
            "safetyFactor" -> "Higher values require safer venting opportunities."
            "aggressive" -> "Vent more aggressively and back off less while venting."
            else -> null
        }
        "ship_cr_retreat_thresholds" -> multiThresholdShipModeTooltip(parameter, "CR")
        "ship_hull_retreat_thresholds" -> multiThresholdShipModeTooltip(parameter, "hull")
        else -> null
    }

    private fun multiThresholdShipModeTooltip(
        parameter: EditableTagParameterDefinition,
        metric: String,
    ): String? = when {
        parameter.id.startsWith("enabledThreshold") -> "Toggle this $metric retreat threshold."
        parameter.id.startsWith("threshold") -> "Retreat when $metric falls below this percent."
        parameter.id == "directRetreat" -> "Order retreat directly when any enabled threshold is reached."
        else -> null
    }

    private fun fallbackEditParameterTooltip(parameter: EditableTagParameterDefinition): String? = when (parameter) {
        is ChoiceParameter -> "Choose ${parameter.label}."
        is ToggleParameter -> "Toggle ${parameter.label}."
        is NumberParameter -> "Adjust ${parameter.label}."
        is DecimalParameter -> "Adjust ${parameter.label}."
        is TextParameter -> null
    }

    private fun syncSystemTriggerTooltip(systemName: String): String {
        return "Triggers $systemName just before the synchronised volley fires. Volleys can still fire if $systemName is unavailable."
    }

    private fun editableTagThresholdTooltip(definition: EditableWeaponTagDefinition): String? = when (definition.id) {
        "hold_fire_flux_threshold" -> "Stops firing above this flux percent."
        "force_fire_flux_threshold" -> "Forces firing below this flux percent."
        "avoid_shield_flux_threshold" -> "Avoids shield targets above this flux percent."
        "target_shield_flux_threshold" -> "Prefers shield targets above this flux percent."
        "pd_flux_threshold" -> "PD mode activates above this flux percent."
        "opportunist_ammo_threshold" -> "Use Opportunist behaviour when ammo is below this percent."
        "pd_ammo_threshold" -> "Use PD-only targeting when ammo is below this percent."
        "no_pd_waste_threshold" -> "Avoids PD targets above this damage-waste percent."
        "no_pd_health_threshold" -> "Avoids PD targets below this hitpoint value."
        "avoid_armor_threshold" -> "Minimum armour effectiveness needed to fire at armour."
        "panic_hull_threshold" -> "Panic firing starts below this hull percent."
        "range_threshold" -> "Only targets within this percent of weapon range."
        "low_rof_threshold" -> "Higher values reduce rate of fire more."
        else -> null
    }

    private fun editableTagTargetShieldThresholdTooltip(definition: EditableWeaponTagDefinition): String? = when {
        definition.family == "AvoidShield" -> "Require target shield factor below this percent."
        definition.family == "TargetShield" -> "Require target shield factor above this percent."
        else -> "Adjust the target shield-factor threshold."
    }

    private fun renderCustomTagEditButtons(
        dialog: CustomPanelAPI,
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        state: CustomTagEditRenderState,
        dialogWidth: Float,
        dialogHeight: Float,
    ) {
        val confirmSpec = customTagEditConfirmSpec(ship, context, state)
        val specs = when {
            state.isManagerEdit -> managerCustomTagEditFooterSpecs(state, confirmSpec)
            customListState.isShipModeEdit() -> shipModeEditFooterSpecs(context, state, confirmSpec)
            else -> weaponTagEditFooterSpecs(ship, context, state, confirmSpec)
        }
        addEqualWidthCustomTagFooterButtons(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            specs = specs,
        )
    }

    private fun customTagEditConfirmSpec(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        state: CustomTagEditRenderState,
    ): ModalFooterButtonSpec {
        return ModalFooterButtonSpec(
            data = "custom_tag_edit_confirm",
            kind = CampaignActionButtonKind.CONFIRM,
            enabled = state.confirmAvailability.enabled,
            labelText = "Confirm",
            tooltip = state.confirmAvailability.tooltip,
            showTooltipWhileInactive = true,
        ) {
            state.canonicalTag?.let { tag ->
                if (state.isManagerEdit && customListState.isShipModeEdit()) {
                    when {
                        state.managerEditSource == null -> customListState.stageShipModeAddition(tag, currentCustomShipModesForActiveContext())
                        state.managerEditSourceIsPendingAddition -> customListState.replaceShipModeAddition(state.managerEditSource, tag)
                        else -> customListState.stageShipModeEdit(state.managerEditSource, tag)
                    }
                    returnToCustomTagManagerFromEdit()
                } else if (customListState.isShipModeEdit()) {
                    if (state.editSourceTag == null) {
                        addCustomShipMode(context, tag)
                    } else {
                        replaceCustomShipMode(context, state.editSourceTag, tag)
                    }
                } else if (state.isManagerEdit) {
                    when {
                        state.managerEditSource == null -> customListState.stageAddition(tag, currentCustomTagsForActiveContext())
                        state.managerEditSourceIsPendingAddition -> customListState.replaceAddition(state.managerEditSource, tag)
                        else -> customListState.stageEdit(state.managerEditSource, tag)
                    }
                    returnToCustomTagManagerFromEdit()
                } else if (state.editSource == null) {
                    addCustomTag(ship, context, tag)
                } else {
                    replaceCustomTag(ship, context, state.editSource.second, tag)
                }
            }
        }
    }

    private fun managerCustomTagEditFooterSpecs(
        state: CustomTagEditRenderState,
        confirmSpec: ModalFooterButtonSpec,
    ): List<ModalFooterButtonSpec> {
        return listOf(
            confirmSpec,
            defaultCustomTagEditFooterSpec(state),
            ModalFooterButtonSpec(
                data = "custom_tag_edit_cancel",
                kind = CampaignActionButtonKind.CANCEL,
                labelText = "Cancel",
            ) { returnToCustomTagManagerFromEdit() },
        )
    }

    private fun shipModeEditFooterSpecs(
        context: ShipEditorPersistenceContext,
        state: CustomTagEditRenderState,
        confirmSpec: ModalFooterButtonSpec,
    ): List<ModalFooterButtonSpec> {
        return listOf(
            confirmSpec,
            ModalFooterButtonSpec(
                data = "custom_ship_mode_edit_copy",
                kind = CampaignActionButtonKind.SAVE,
                enabled = state.copyAvailability.enabled,
                labelText = "Copy",
                tooltip = state.copyAvailability.tooltip,
                showTooltipWhileInactive = true,
            ) {
                state.canonicalTag?.let { mode -> addCustomShipMode(context, mode) }
            },
            defaultCustomTagEditFooterSpec(state),
            ModalFooterButtonSpec(
                data = "custom_ship_mode_edit_delete",
                kind = CampaignActionButtonKind.LOAD,
                enabled = state.deleteAvailability.enabled,
                labelText = "Delete",
                tooltip = state.deleteAvailability.tooltip,
                showTooltipWhileInactive = true,
            ) {
                state.editSourceTag?.let { mode -> removeCustomShipModes(context, setOf(mode)) }
            },
            ModalFooterButtonSpec(
                data = "custom_ship_mode_edit_cancel",
                kind = CampaignActionButtonKind.CANCEL,
                labelText = "Cancel",
            ) { closeCustomListModal() },
        )
    }

    private fun weaponTagEditFooterSpecs(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        state: CustomTagEditRenderState,
        confirmSpec: ModalFooterButtonSpec,
    ): List<ModalFooterButtonSpec> {
        return listOf(
            confirmSpec,
            ModalFooterButtonSpec(
                data = "custom_tag_edit_copy",
                kind = CampaignActionButtonKind.SAVE,
                enabled = state.copyAvailability.enabled,
                labelText = "Copy",
                tooltip = state.copyAvailability.tooltip,
                showTooltipWhileInactive = true,
            ) {
                state.canonicalTag?.let { tag -> addCustomTag(ship, context, tag, replaceEditSourceIfActive = false) }
            },
            defaultCustomTagEditFooterSpec(state),
            ModalFooterButtonSpec(
                data = "custom_tag_edit_delete",
                kind = CampaignActionButtonKind.LOAD,
                enabled = state.deleteAvailability.enabled,
                labelText = "Delete",
                tooltip = state.deleteAvailability.tooltip,
                showTooltipWhileInactive = true,
            ) {
                state.editSource?.second?.let { sourceTag ->
                    removeCustomTags(ship, context, setOf(sourceTag))
                }
            },
            ModalFooterButtonSpec(
                data = "custom_tag_edit_cancel",
                kind = CampaignActionButtonKind.CANCEL,
                labelText = "Cancel",
            ) { closeCustomListModal() },
        )
    }

    private fun defaultCustomTagEditFooterSpec(
        state: CustomTagEditRenderState,
    ): ModalFooterButtonSpec {
        return ModalFooterButtonSpec(
            data = if (customListState.isShipModeEdit()) "custom_ship_mode_edit_default" else "custom_tag_edit_default",
            kind = CampaignActionButtonKind.UNCOLOURED,
            enabled = state.definition != null,
            labelText = "Default",
            tooltip = "Reset editable values to this tag or ship mode's defaults.",
            showTooltipWhileInactive = true,
        ) {
            state.definition?.let(customListState::resetDraftValuesToDefaults)
        }
    }

    private fun addEqualWidthCustomTagFooterButtons(
        dialog: CustomPanelAPI,
        dialogWidth: Float,
        dialogHeight: Float,
        specs: List<ModalFooterButtonSpec>,
    ) {
        CustomListModalFooterRenderer.addEqualWidthButtons(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            buttons = buttons,
            specs = specs,
        )
    }

    private fun addEdgeCustomTagFooterButtons(
        dialog: CustomPanelAPI,
        dialogWidth: Float,
        dialogHeight: Float,
        left: ModalFooterButtonSpec,
        right: ModalFooterButtonSpec,
    ) {
        CustomListModalFooterRenderer.addEdgeButtons(
            dialog = dialog,
            dialogWidth = dialogWidth,
            dialogHeight = dialogHeight,
            buttons = buttons,
            left = left,
            right = right,
        )
    }

    private fun renderEditTagIncrementor(
        dialog: CustomPanelAPI,
        definition: EditableWeaponTagDefinition,
        parameter: NumberParameter,
        draftValues: Map<String, String>,
        y: Float,
        tooltip: String?,
        enabled: Boolean = true,
        widthMultiplier: Float = 1f,
    ) {
        val current = draftValues[parameter.id]?.toIntOrNull() ?: parameter.defaultValue
        val isPriorityMultiplier = parameter.id == EditableWeaponTagDefinitions.PARAM_PRIORITY_MULTIPLIER
        val label = "${parameter.label}: $current${parameter.suffix}"
        CustomTagEditRowRenderer.renderDeltaButtons(
            dialog = dialog,
            y = y,
            leftLabel = label,
            dataPrefix = "custom_tag_number:${definition.id}:${parameter.id}",
            tooltipLabel = parameter.label,
            tooltip = tooltip,
            buttons = buttons,
            widthMultiplier = widthMultiplier,
            deltas = if (isPriorityMultiplier) listOf(-1000, -100, 100, 1000) else listOf(-10, -1, 1, 10),
            enabled = enabled,
        ) { delta ->
            updateDraftValue(parameter.id, (current + delta).coerceIn(parameter.minValue, parameter.maxValue).toString())
        }
    }

    private fun renderEditTagDecimalIncrementor(
        dialog: CustomPanelAPI,
        definition: EditableWeaponTagDefinition,
        parameter: DecimalParameter,
        draftValues: Map<String, String>,
        y: Float,
        tooltip: String?,
        enabled: Boolean = true,
        widthMultiplier: Float = 1f,
    ) {
        val current = draftValues[parameter.id]?.toFloatOrNull() ?: parameter.defaultValue
        CustomTagEditRowRenderer.renderDecimalDeltaButtons(
            dialog = dialog,
            y = y,
            leftLabel = "${parameter.label}: ${formatDecimalParameterValue(current, parameter)}${parameter.suffix}",
            dataPrefix = "custom_tag_decimal:${definition.id}:${parameter.id}",
            tooltipLabel = parameter.label,
            tooltip = tooltip,
            buttons = buttons,
            widthMultiplier = widthMultiplier,
            minorStepLabel = formatDecimalParameterValue(parameter.minorStep, parameter),
            majorStepLabel = formatDecimalParameterValue(parameter.majorStep, parameter),
            minorStep = parameter.minorStep,
            majorStep = parameter.majorStep,
            enabled = enabled,
        ) { delta ->
            val updated = (current + delta).coerceIn(parameter.minValue, parameter.maxValue)
            updateDraftValue(parameter.id, formatDecimalParameterValue(updated, parameter))
        }
    }

    private fun formatDecimalParameterValue(value: Float, parameter: DecimalParameter): String {
        var rendered = String.format(Locale.US, "%.${parameter.decimalPlaces}f", value)
        if (rendered.contains('.')) {
            while (rendered.endsWith("0")) {
                rendered = rendered.dropLast(1)
            }
            if (rendered.endsWith(".")) {
                rendered = rendered.dropLast(1)
            }
        }
        return rendered
    }

    private fun currentDebugColorIndex(targets: List<CampaignGuiStyle.DebugColorTarget>): Int {
        return customListState.debugColorIndex(targets.lastIndex)
    }

    private fun setDebugColorDraftIndex(index: Int) {
        customListState.setDebugColorIndex(index)
    }

    private fun selectDebugColorTarget(
        targets: List<CampaignGuiStyle.DebugColorTarget>,
        currentIndex: Int,
        delta: Int,
    ) {
        if (targets.isEmpty()) return
        val nextIndex = (currentIndex + delta + targets.size) % targets.size
        setDebugColorDraftIndex(nextIndex)
        setDebugColorDraft(CampaignGuiStyle.currentDebugColor(targets[nextIndex]))
    }

    private fun ensureDebugColorDraft(target: CampaignGuiStyle.DebugColorTarget) {
        if (!customListState.hasDebugColorDraftRgb()) {
            setDebugColorDraft(CampaignGuiStyle.currentDebugColor(target))
        }
    }

    private fun currentDebugColorDraft(target: CampaignGuiStyle.DebugColorTarget): Color {
        return customListState.debugColorDraft(CampaignGuiStyle.currentDebugColor(target))
    }

    private fun setDebugColorDraft(color: Color) {
        customListState.setDebugColorDraft(color)
    }

    private fun isDebugColorPersistent(): Boolean {
        return customListState.debugColorPersistent()
    }

    private fun setDebugColorPersistent(persistent: Boolean) {
        customListState.setDebugColorPersistent(persistent)
    }

    private fun currentLoadoutRenameIndex(): Int {
        return customListState.loadoutRenameIndex(AGCGUI.storageIndex, Settings.maxLoadouts())
    }

    private fun currentLoadoutRenameDraft(): String {
        return customListState.loadoutRenameDraft(
            defaultIndex = AGCGUI.storageIndex,
            maxLoadouts = Settings.maxLoadouts(),
            displayName = Settings::loadoutDisplayName,
        )
    }

    private fun setLoadoutRenameDraft(name: String) {
        customListState.setLoadoutRenameDraft(currentLoadoutRenameIndex(), name)
    }

    private fun addCustomTagBackdropButtons(
        backdrop: CustomPanelAPI,
        dialogX: Float,
        dialogY: Float,
        dialogWidth: Float,
        dialogHeight: Float,
    ) {
        val rootWidth = backdrop.position.width
        val rootHeight = backdrop.position.height
        listOfNotNull(
            addCustomTagBackdropButton(backdrop, 0f, 0f, rootWidth, dialogY),
            addCustomTagBackdropButton(backdrop, 0f, dialogY + dialogHeight, rootWidth, rootHeight - dialogY - dialogHeight),
            addCustomTagBackdropButton(backdrop, 0f, dialogY, dialogX, dialogHeight),
            addCustomTagBackdropButton(backdrop, dialogX + dialogWidth, dialogY, rootWidth - dialogX - dialogWidth, dialogHeight),
        ).forEach { button ->
            buttons.addCampaignButtonControl(button = button) {}
        }
    }

    private fun addCustomTagBackdropButton(
        backdrop: CustomPanelAPI,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): ButtonAPI? {
        return addTransparentCampaignInputShield(
            parent = backdrop,
            data = "custom_tag_modal_backdrop",
            x = x,
            y = y,
            width = width,
            height = height,
        )
    }

    private fun startCustomTagEdit(definition: EditableWeaponTagDefinition) {
        customListState.beginStandaloneEdit(definition)
    }

    private fun startCustomTagManagerEdit(definition: EditableWeaponTagDefinition) {
        customListState.beginManagerEdit(definition)
    }

    private fun startCustomTagManagerEditShipMode(definition: EditableWeaponTagDefinition) {
        customListState.beginManagerEditShipMode(definition)
    }

    private fun startCustomTagManagerEditFromTag(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        tag: String,
        pendingAddition: Boolean,
        sourceTag: String = tag,
    ) {
        val canonicalTag = canonicalTag(tag)
        val canonicalSourceTag = canonicalTag(sourceTag)
        val parsed = EditableWeaponTagDefinitions.parse(canonicalTag)
        val definition = parsed
            ?.let { EditableWeaponTagDefinitions.definitionById(it.definitionId) }
            ?: EditableWeaponTagDefinitions.definitionForTemplate(canonicalTag)
            ?: return
        customListState.beginManagerEditFromTag(
            definition = definition,
            sourceTag = canonicalSourceTag,
            pendingAddition = pendingAddition,
            parameterValues = parsed?.parameterValues.orEmpty(),
        )
        activeShip = ship
    }

    private fun returnToCustomTagManagerFromEdit() {
        customListState.returnToManagerFromEdit()
    }

    private fun startCustomTagManagerEditFromShipMode(
        mode: String,
        pendingAddition: Boolean,
        sourceMode: String = mode,
    ) {
        val canonicalMode = canonicalizeShipModeName(mode)
        val canonicalSourceMode = canonicalizeShipModeName(sourceMode)
        val parsed = EditableShipModeDefinitions.parse(canonicalMode)
        val definition = parsed
            ?.let { EditableShipModeDefinitions.definitionById(it.definitionId) }
            ?: EditableShipModeDefinitions.definitionForMode(canonicalMode)
            ?: return
        customListState.beginManagerEditFromShipMode(
            definition = definition,
            sourceMode = canonicalSourceMode,
            pendingAddition = pendingAddition,
            parameterValues = parsed?.parameterValues.orEmpty(),
        )
    }

    private fun startCustomShipModeEditFromVisibleButton(
        ship: FleetMemberAPI,
        mode: String,
    ) {
        val canonicalMode = canonicalizeShipModeName(mode)
        val parsed = EditableShipModeDefinitions.parse(canonicalMode)
        val definition = parsed
            ?.let { EditableShipModeDefinitions.definitionById(it.definitionId) }
            ?: EditableShipModeDefinitions.definitionForMode(canonicalMode)
            ?: return
        customListState.beginRightClickEditShipMode(
            definition = definition,
            mode = canonicalMode,
            parameterValues = parsed?.parameterValues.orEmpty(),
        )
        activeShip = ship
    }

    private fun startCustomTagEditFromTag(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        groupIndex: Int,
        tag: String,
    ) {
        val canonicalTag = canonicalTag(tag)
        val parsed = EditableWeaponTagDefinitions.parse(canonicalTag)
        val definition = parsed
            ?.let { EditableWeaponTagDefinitions.definitionById(it.definitionId) }
            ?: EditableWeaponTagDefinitions.definitionForTemplate(canonicalTag)
        customListState.beginRightClickEdit(
            definition = definition,
            tag = canonicalTag,
            sourceGroupIndex = groupIndex,
            parameterValues = parsed?.parameterValues.orEmpty(),
        )
        activeShip = ship
    }

    private fun currentCustomTagDraftDefinition(): EditableWeaponTagDefinition? {
        val id = customListBindings.draftDefinitionIdProvider?.invoke() ?: return null
        return EditableWeaponTagDefinitions.definitions.firstOrNull { it.id == id }
            ?: EditableShipModeDefinitions.definitionById(id)
    }

    private fun currentCustomTagFixedTag(): String? {
        return customListState.fixedEditTag()
    }

    private fun currentCustomTagDraftValues(definition: EditableWeaponTagDefinition): MutableMap<String, String> {
        return customListState.draftValuesFor(definition)
    }

    private fun updateDraftValue(parameterId: String, value: String) {
        val definition = currentCustomTagDraftDefinition() ?: return
        customListState.updateDraftValue(definition, parameterId, value)
    }

    private fun cycleDraftChoice(definition: EditableWeaponTagDefinition, parameter: ChoiceParameter, delta: Int = 1) {
        customListState.cycleDraftChoice(definition, parameter, delta)
    }

    private fun currentCustomTagEditSource(): Pair<Int, String>? {
        val groupIndex = customListBindings.editSourceGroupProvider?.invoke() ?: return null
        val tag = customListBindings.editSourceTagProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return null
        return groupIndex to tag
    }

    private fun currentCustomTagsForActiveContext(): List<String> {
        val context = activePersistenceContext ?: return emptyList()
        return currentCustomTagsForContext(context)
    }

    private fun currentCustomTagsForContext(context: ShipEditorPersistenceContext): List<String> {
        val shipId = context.shipId.takeIf { it.isNotBlank() } ?: return emptyList()
        return CustomWeaponTagListStore.getSupportedCustomTags(shipId)
    }

    private fun currentCustomShipModesForActiveContext(): List<String> {
        val context = activePersistenceContext ?: return emptyList()
        return currentCustomShipModesForContext(context)
    }

    private fun currentCustomShipModesForContext(context: ShipEditorPersistenceContext): List<String> {
        val shipId = context.shipId.takeIf { it.isNotBlank() } ?: return emptyList()
        return CustomShipModeListStore.getCustomModeNamesForEditing(shipId)
    }

    private fun customTagChangeReviewRows(
        additions: List<String>,
        edits: Map<String, String>,
        removals: List<String>,
        shipModeAdditions: List<String> = emptyList(),
        shipModeEdits: Map<String, String> = emptyMap(),
        shipModeRemovals: List<String> = emptyList(),
    ): List<CustomTagChangeReviewRow> {
        return CustomTagManagerRows.changeReviewRows(
            additions = additions,
            edits = edits,
            removals = removals,
            shipModeAdditions = shipModeAdditions,
            shipModeEdits = shipModeEdits,
            shipModeRemovals = shipModeRemovals,
            expandedSections = customListState.changeReviewExpandedSections(customTagChangeReviewSectionIds()),
        )
    }

    private fun customTagChangeReviewSectionIds(): List<String> =
        listOf(CustomListDraftKeys.ListSections.TAGS, CustomListDraftKeys.ListSections.SHIP_MODES) +
            CustomTagChangeReviewSection.entries.map { it.id }

    private fun applyCustomListManagerChanges(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
    ) {
        if (context.shipId.isBlank()) return
        val currentTags = currentCustomTagsForContext(context)
        val currentShipModes = currentCustomShipModesForContext(context)
        val stagedState = customListState.normalizeStagedState(currentTags)
        val shipModeStagedState = customListState.normalizeShipModeStagedState(currentShipModes)
        if (CustomTagMutations.applyManagerChanges(ship, context, stagedState, shipModeStagedState)) {
            finishCustomTagMutation(ship)
        }
    }

    private fun addCustomTag(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        tag: String,
        replaceEditSourceIfActive: Boolean = true,
    ) {
        val editSource = currentCustomTagEditSource().takeIf { replaceEditSourceIfActive }
        if (CustomTagMutations.addCustomTag(ship, context, tag, editSource)) {
            finishCustomTagMutation(ship)
        }
    }

    private fun replaceCustomTag(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        sourceTag: String,
        editedTag: String,
    ) {
        if (CustomTagMutations.replaceCustomTag(ship, context, sourceTag, editedTag)) {
            finishCustomTagMutation(ship)
        }
    }

    private fun removeCustomTags(
        ship: FleetMemberAPI,
        context: ShipEditorPersistenceContext,
        tags: Set<String>,
    ) {
        if (CustomTagMutations.removeCustomTags(context, tags)) {
            finishCustomTagMutation(ship)
        }
    }

    private fun addCustomShipMode(
        context: ShipEditorPersistenceContext,
        mode: String,
    ) {
        val ship = activeShip ?: return
        if (CustomTagMutations.addCustomShipMode(context, mode)) {
            finishCustomTagMutation(ship)
        }
    }

    private fun replaceCustomShipMode(
        context: ShipEditorPersistenceContext,
        sourceMode: String,
        editedMode: String,
    ) {
        val ship = activeShip ?: return
        if (CustomTagMutations.replaceCustomShipMode(context, sourceMode, editedMode)) {
            finishCustomTagMutation(ship)
        }
    }

    private fun removeCustomShipModes(
        context: ShipEditorPersistenceContext,
        modes: Set<String>,
    ) {
        val ship = activeShip ?: return
        if (CustomTagMutations.removeCustomShipModes(context, modes)) {
            finishCustomTagMutation(ship)
        }
    }

    private fun finishCustomTagMutation(ship: FleetMemberAPI) {
        activeShip = ship
        tagView.reset()
        effectiveShipModeCacheKey = null
        effectiveShipModeCache = null
        ShipModeButton.notifyCampaignShipModeSelectionChanged()
        closeCustomListModal()
        campaignScrollDirty = true
    }

    private fun closeCustomListModal() {
        customListState.closeModal()
        customTagDialogPosition = null
        customListModalScrollRegion = null
        val panel = customListModalRootPanel
        val shell = customListModalShell
        if (panel != null && shell != null) {
            detachShipViewModal(panel, shell, buttons, customListModalButtonStartIndex, customListModalButtonEndIndex)
            clearCustomListModalRefs()
            restoreSuppressedNonModalButtonHover()
        } else {
            clearCustomListModalRefs()
            campaignScrollDirty = true
        }
    }

    private fun suppressNonModalButtonHover(firstModalButtonIndex: Int) {
        suppressButtonHoverRange(0, firstModalButtonIndex)
    }

    private fun suppressButtonHoverRange(startIndex: Int, endIndex: Int) {
        if (nonModalSuppressionSnapshot != null) return
        val start = startIndex.coerceIn(0, buttons.size)
        val end = endIndex.coerceIn(start, buttons.size)
        // Modal input consumption runs after Starsector has already had a
        // chance to update hover state. Disable and mute all already-rendered
        // AGC buttons through the shared registry, not just this view's
        // ButtonBase list, because options and raw helper buttons can live in
        // separate collections.
        buttons.subList(start, end).forEach { control -> control.syncVisualCheckedToActive() }
        nonModalSuppressionSnapshot = suppressRegisteredCampaignButtonHoverSnapshot()
    }

    private fun restoreSuppressedNonModalButtonHover() {
        nonModalSuppressionSnapshot?.restore()
        nonModalSuppressionSnapshot = null
        externalBindings.restoreOptionHover?.invoke()
    }

    private fun presetConfirmationRequest(groupIndex: Int, state: PresetControlState): CampaignConfirmationModalRequest {
        val isOverwrite = CampaignSaveLoadPanelRenderer.requiresOverwriteWarning(state)
        val action = state.pendingAction ?: PendingPresetAction.SAVE
        val title = when {
            isOverwrite -> "Overwrite Save Warning"
            action == PendingPresetAction.SAVE -> "Confirm Save"
            else -> "Confirm Load"
        }
        return CampaignConfirmationModalRequest(
            title = title,
            body = "",
            richBody = presetConfirmationBody(groupIndex, state),
            tone = if (isOverwrite) CampaignConfirmationTone.WARNING else CampaignConfirmationTone.CAUTION,
            onConfirm = { confirmPresetAction(groupIndex, state) },
            onCancel = { cancelPresetAction(groupIndex, state) }
        )
    }

    private fun presetConfirmationBody(groupIndex: Int, state: PresetControlState): List<CampaignHighlightedText> {
        return PresetConfirmationCopy.body(
            groupIndex = groupIndex,
            state = state,
            ship = activeShip,
            runtimeShip = config.runtimeShip,
            activePersistenceContext = activePersistenceContext,
            loadoutIndex = AGCGUI.storageIndex,
            presetPeekCache = presetPeekCache,
        )
    }

    private fun presetActionReviewBody(groupIndex: Int, state: PresetControlState): PresetActionReviewBody? {
        return PresetConfirmationCopy.reviewBody(
            groupIndex = groupIndex,
            state = state,
            ship = activeShip,
            runtimeShip = config.runtimeShip,
            activePersistenceContext = activePersistenceContext,
            loadoutIndex = AGCGUI.storageIndex,
            presetPeekCache = presetPeekCache,
        )
    }

    private fun confirmPresetAction(groupIndex: Int, state: PresetControlState) {
        val ship = activeShip ?: return
        val result = CampaignSaveLoadPanelRenderer.executePendingAction(
            ship,
            groupIndex,
            state,
            config.runtimeShip,
            presetPeekCache,
            activePersistenceContext,
        )
        presetPeekCache.clear()
        if (result.executed) {
            updateCleanPresetBaseline(ship, groupIndex, state)
            closePresetActionModalTargeted(groupIndex, state)
            refreshPresetActionResult(ship, groupIndex, result)
        } else {
            updatePresetControlState(groupIndex, state.copy(pendingAction = null))
            closePresetActionModalTargeted(groupIndex, state)
        }
    }

    private fun updateCleanPresetBaseline(ship: FleetMemberAPI, groupIndex: Int, state: PresetControlState) {
        if (groupIndex != CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            val cleanTags = currentSanitizedTagsForGroup(ship, groupIndex)
            PresetCleanBaselineStore.put(ship, groupIndex, AGCGUI.storageIndex, cleanTags)
            updatePresetControlState(
                groupIndex,
                state.copy(pendingAction = null, overwrite = false, cleanTags = cleanTags)
            )
            return
        }
        updatePresetControlState(groupIndex, state.copy(pendingAction = null, overwrite = false))
        (0 until Values.MAX_WEAPON_GROUPS)
            .filter { index -> getVariantWeaponGroup(ship, index)?.slots?.isNotEmpty() == true }
            .forEach { index ->
                val current = presetControlStateByGroup[index] ?: PresetControlState()
                val cleanTags = currentSanitizedTagsForGroup(ship, index)
                PresetCleanBaselineStore.put(ship, index, AGCGUI.storageIndex, cleanTags)
                updatePresetControlState(index, current.copy(cleanTags = cleanTags))
            }
    }

    private fun cancelPresetAction(groupIndex: Int, state: PresetControlState) {
        closePresetActionModalTargeted(groupIndex, state)
    }

    private fun closePresetActionModalTargeted(groupIndex: Int, state: PresetControlState) {
        updatePresetControlState(groupIndex, state.copy(pendingAction = null, overwrite = false))
        val panel = presetModalRootPanel
        val shell = presetModalShell
        if (panel != null && shell != null) {
            detachShipViewModal(panel, shell, buttons, presetModalButtonStartIndex, presetModalButtonEndIndex)
            clearPresetModalRefs()
        } else {
            campaignScrollDirty = true
        }
        confirmationDialogPosition = null
        restoreSuppressedNonModalButtonHover()
    }

    private fun closeExternalConfirmationModalTargeted() {
        val panel = externalConfirmationRootPanel
        val modal = externalConfirmationModal
        if (panel == null || modal == null) {
            campaignScrollDirty = true
            confirmationDialogPosition = null
            restoreSuppressedNonModalButtonHover()
            return
        }
        try {
            runCatching { panel.removeComponent(modal.backdrop) }
                .onFailure { ex ->
                    log.warn("[AGC_SHIP_VIEW] Failed to remove external confirmation backdrop", ex)
                }
            runCatching { panel.removeComponent(modal.dialog) }
                .onFailure { ex ->
                    log.warn("[AGC_SHIP_VIEW] Failed to remove external confirmation dialog", ex)
                }
            buttons.clearRenderedConfirmationModalButtons(
                externalConfirmationButtonStartIndex,
                externalConfirmationButtonEndIndex
            )
        } finally {
            clearExternalConfirmationModalRefs()
            confirmationDialogPosition = null
            restoreSuppressedNonModalButtonHover()
        }
    }

    private fun refreshPresetActionResult(
        ship: FleetMemberAPI,
        groupIndex: Int,
        result: PresetActionExecutionResult,
    ) {
        val groups = if (groupIndex == CampaignSaveLoadPanelRenderer.ALL_WEAPON_GROUPS_INDEX) {
            affectedNonEmptyGroups(ship, result.affectedGroupIndexes)
        } else {
            result.affectedGroupIndexes.ifEmpty { setOf(groupIndex) }
        }
        when (result.action) {
            PendingPresetAction.LOAD -> groups.forEach { index ->
                weaponGroupTagListRenderer.refreshGroup(index)
                handleWeaponGroupTagsChanged(index, currentSanitizedTagsForGroup(ship, index))
            }
            PendingPresetAction.SAVE -> groups.forEach { index ->
                handleWeaponGroupTagsChanged(index, currentSanitizedTagsForGroup(ship, index))
            }
            null -> Unit
        }
    }

    private fun affectedNonEmptyGroups(ship: FleetMemberAPI, affectedGroups: Set<Int>): Set<Int> {
        return affectedGroups.ifEmpty {
            (0 until Values.MAX_WEAPON_GROUPS)
                .filter { index -> getVariantWeaponGroup(ship, index)?.slots?.isNotEmpty() == true }
                .toSet()
        }
    }

    fun buildIn(
        panel: CustomPanelAPI,
        ship: FleetMemberAPI,
        buildOptionsPanel: ((CustomPanelAPI, Boolean, Int, Float?, (Int, Int) -> Unit) -> CampaignOptionsRenderResult)? = null,
        optionsPreferredHeightProvider: ((Float) -> Float)? = null,
        stableOptionsPreferredHeightProvider: ((Float) -> Float)? = null,
        buildModifiersPanel: ((CustomPanelAPI) -> Unit)? = null,
        modifiersPreferredHeightProvider: (() -> Float)? = null,
    ) {
        val startNs = System.nanoTime()
        optionsBuildPanel = buildOptionsPanel
        rootPanel = panel
        nonModalSuppressionSnapshot = null
        clearRegisteredCampaignButtons()
        buttons.clear()
        weaponGroupTagListRenderer.clear()
        weaponGroupPanelsByIndex.clear()
        weaponGroupHeadingPanelsByIndex.clear()
        shipModeParentPanel = null
        shipModeItemPanel = null
        shipModePanelShip = null
        shipModeControls = emptyList()
        effectiveShipModeCacheKey = null
        effectiveShipModeCache = null
        campaignScrollDirty = false
        presetDirtyByGroup.clear()
        activePersistenceContext = ShipEditorPersistenceContext(ship, config.runtimeShip)
        weaponGroupTagListRenderer.bindPersistenceContext(activePersistenceContext)
        activeShip = ship
        restorePersistentPresetControlStates(ship)
        confirmationDialogPosition = null
        customListModalScrollRegion = null
        clearCustomListModalRefs()
        renderingCustomListModal = false
        customListModalRefreshPending = false
        clearPresetModalRefs()
        clearExternalConfirmationModalRefs()
        observedTagSelectionVersion = TagButton.campaignTagSelectionVersion
        observedShipModeSelectionVersion = ShipModeButton.campaignShipModeSelectionVersion
        activePersistenceContext?.let(ShipViewHotTagCache::ensureLoaded)
        optionsPanel = null
        optionsRowsPanel = null

        val layout = computeShipEditorLayout(
            panel = panel,
            ship = ship,
            optionsPreferredHeightProvider = optionsPreferredHeightProvider,
            stableOptionsPreferredHeightProvider = stableOptionsPreferredHeightProvider,
            modifiersPreferredHeightProvider = modifiersPreferredHeightProvider
        )

        val leftColumnPanel = addPanel(
            parent = panel,
            width = layout.leftColumnWidth,
            height = panel.position.height,
            type = CampaignPanelType.LEFT_COLUMN_PANEL,
            x = 0f,
            y = 0f
        )

        val shipPanel = addPanel(
            parent = leftColumnPanel,
            width = layout.leftColumnWidth,
            height = layout.shipPanelHeight,
            type = CampaignPanelType.SHIP_PANEL,
            x = 0f,
            y = 0f
        )
        buildShipPanel(shipPanel, ship)

        val visibleSpacerHeight = if (isCollapsed(CollapsiblePanelKey.SHIP)) 0f else layout.spacerHeight
        val spacerPanel = addBlackSpacerBelow(
            parent = leftColumnPanel,
            anchor = shipPanel,
            width = layout.leftColumnWidth,
            height = visibleSpacerHeight,
        )

        val optionsPanel = addPanelBelow(
            parent = leftColumnPanel,
            anchor = spacerPanel ?: shipPanel,
            width = layout.leftColumnWidth,
            height = layout.optionsHeight,
            type = CampaignPanelType.OPTIONS_PANEL
        )
        this.optionsPanel = optionsPanel
        optionsPanelWidth = layout.leftColumnWidth
        optionsPanelHeight = layout.optionsHeight
        optionsPanelTop = layout.shipPanelHeight + visibleSpacerHeight
        optionsPanelRootHeight = panel.position.height
        optionsScrollRegion = null
        val optionsCollapsed = isCollapsed(CollapsiblePanelKey.OPTIONS)
        if (!optionsCollapsed) {
            val bodyTop = CampaignGuiStyle.PANEL_PADDING + CampaignGuiStyle.CONTAINER_HEADING_HEIGHT
            val bodyHeight = max(0f, layout.optionsHeight - bodyTop - CampaignGuiStyle.PANEL_PADDING)
            optionsPanelBodyTop = bodyTop
            optionsPanelBodyHeight = bodyHeight
            buildOptionsRowsPanel(optionsPanel)
        } else {
            optionsScrollMaxOffset = 0
            optionsScrollOffset = 0
            optionsRowsPanel = null
        }
        addCollapsiblePanelHeading(optionsPanel, CollapsiblePanelKey.OPTIONS)

        var leftColumnAnchor = optionsPanel
        if (buildModifiersPanel != null && layout.modifiersHeight > 0f) {
            val modifiersPanel = addPanelBelow(
                parent = leftColumnPanel,
                anchor = optionsPanel,
                width = layout.leftColumnWidth,
                height = layout.modifiersHeight,
                type = CampaignPanelType.OPTIONS_PANEL
            )
            buildModifiersPanel.invoke(modifiersPanel)
            leftColumnAnchor = modifiersPanel
        }

        val shipModesPanel = addPanelBelow(
            parent = leftColumnPanel,
            anchor = leftColumnAnchor,
            width = layout.leftColumnWidth,
            height = layout.shipModeHeight,
            type = CampaignPanelType.SHIP_MODES_PANEL
        )
        val shipModesPanelTop = layout.shipPanelHeight +
            visibleSpacerHeight +
            layout.optionsHeight +
            layout.modifiersHeight
        buildShipModesPanel(shipModesPanel, ship, shipModesPanelTop, panel.position.height)

        val weaponGroupsPanel = addPanelRightOf(
            parent = panel,
            anchor = leftColumnPanel,
            width = layout.weaponGroupsWidth,
            height = panel.position.height,
            type = CampaignPanelType.WEAPON_GROUPS_PANEL
        )
        buildWeaponGroupsPanel(weaponGroupsPanel, ship, panel.position.height, layout.leftColumnWidth)
        if (pendingConfirmationModalRequest() != null) {
            renderConfirmationModal(panel)
        } else {
            confirmationDialogPosition = null
            renderCustomListModal(panel)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        if (elapsedMs >= CampaignGuiStyle.GUI_PERF_LOG_THRESHOLD_MS) {
            log.info("[AGC_PERF] ShipView.buildIn took ${elapsedMs}ms")
        }
    }

    private fun computeShipEditorLayout(
        panel: CustomPanelAPI,
        ship: FleetMemberAPI,
        optionsPreferredHeightProvider: ((Float) -> Float)?,
        stableOptionsPreferredHeightProvider: ((Float) -> Float)?,
        modifiersPreferredHeightProvider: (() -> Float)?,
    ): ShipEditorLayout {
        return ShipEditorLayoutCalculator.compute(
            panelWidth = panel.position.width,
            panelHeight = panel.position.height,
            ship = ship,
            shipModes = effectiveShipModesForEditor(ship),
            optionsPreferredHeightProvider = optionsPreferredHeightProvider,
            stableOptionsPreferredHeightProvider = stableOptionsPreferredHeightProvider,
            modifiersPreferredHeightProvider = modifiersPreferredHeightProvider,
            isCollapsed = ::isCollapsed,
        )
    }

    fun showShipModes(attributes: GUIAttributes) {
        val ship = attributes.ship ?: return
        val screenWidthUi = Global.getSettings().screenWidthPixels / Global.getSettings().screenScaleMult
        val panelHeight = Global.getSettings().screenHeightPixels / Global.getSettings().screenScaleMult - 40f
        attributes.customPanel = attributes.visualPanel?.showCustomPanel(screenWidthUi, panelHeight, this)
        attributes.customPanel?.position?.inTL(0f, 20f)
        attributes.customPanel?.let {
            buildIn(it, ship)
        }
    }

    override fun render(alpha: Float) {
        if (!config.drawFrame) return
        super.render(alpha)
    }
}
