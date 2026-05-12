package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.CustomShipModeListStore
import com.dp.advancedgunnerycontrol.typesandvalues.canonicalizeShipModeNames
import com.dp.advancedgunnerycontrol.typesandvalues.defaultShipMode
import com.dp.advancedgunnerycontrol.typesandvalues.isSupportedShipModeName
import com.dp.advancedgunnerycontrol.typesandvalues.shipModeDescription
import com.dp.advancedgunnerycontrol.typesandvalues.shipModeDisplayName
import com.dp.advancedgunnerycontrol.utils.ShipEditorPersistenceContext
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIComponentAPI

/**
 * Visible ship-mode row component.
 * Used in the Ship Modes panel to toggle, pin, and right-click edit modes for
 * the current ship/loadout.
 */
class ShipModeButton(
    var ship: FleetMemberAPI,
    mode: String,
    button: ButtonAPI,
    private val runtimeShip: ShipAPI? = null,
    private val onSelectionChanged: (() -> Unit)? = null,
    private val currentModesSnapshot: List<String>? = null,
    private val persistenceContext: ShipEditorPersistenceContext? = null,
    useMomentaryVisualState: Boolean = false,
) :
    ButtonBase<String>(mode, button, false, useMomentaryVisualState) {

    private val visualState = CampaignToggleVisualState()

    data class CampaignModeButtonGroupResult(
        val buttons: List<ButtonBase<*>>,
        val maxRowOffset: Int,
        val effectiveRowOffset: Int,
    )

    data class CampaignModeButtonGroupPlan(
        val sectionKey: String,
        val columns: Int,
        val itemWidth: Float,
        val activeModeSet: Set<String>,
        val layout: PinnedVerticalScrollLayout<CampaignModeListRow>,
    )

    sealed class CampaignModeListRow {
        data class Heading(
            val title: String,
            val expanded: Boolean,
            val active: Boolean,
        ) : CampaignModeListRow()

        data class Modes(
            val modes: List<String>,
        ) : CampaignModeListRow()
    }

    data class PreparedCampaignModeButtonGroup(
        val persistedModeList: List<String>,
        val plan: CampaignModeButtonGroupPlan,
    ) {
        val tightHeight: Float
            get() = computeVerticalItemsHeight(
                renderedRowCount = plan.layout.scrollSlice.items.size +
                    (if (plan.layout.scrollSlice.hasAbove) 1 else 0) +
                    (if (plan.layout.scrollSlice.hasBelow) 1 else 0)
            )

        private fun computeVerticalItemsHeight(renderedRowCount: Int): Float {
            return com.dp.advancedgunnerycontrol.gui.computeVerticalItemsHeight(
                renderedRowCount,
                CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT,
                CampaignGuiStyle.SHIP_MODE_ITEM_VGAP,
            )
        }
    }

    companion object {
        var campaignShipModeSelectionVersion = 0
            private set

        private const val ACTIVE_MODE_SECTION = "Active Ship Modes"
        private const val INACTIVE_MODE_SECTION = "Inactive Ship Modes"
        private val collapsedCampaignModeSectionsByKey = mutableMapOf<String, MutableSet<String>>()

        fun notifyCampaignShipModeSelectionChanged() {
            campaignShipModeSelectionVersion++
        }

        fun createModeButtonGroup(
            ship: FleetMemberAPI,
            panel: CustomPanelAPI,
            position: UIComponentAPI
        ): List<ShipModeButton> {
            val modeButtons = mutableListOf<ShipModeButton>()
            val elementList = mutableListOf<TooltipMakerAPI>()
            Settings.getCurrentShipModeNames().forEach { configuredMode ->
                val modeName = configuredMode.ifBlank { defaultShipMode }
                val tooltip = panel.createUIElement(130f, 30f, false)
                modeButtons.add(
                    ShipModeButton(
                        ship, modeName, addLegacyAgcTooltipCheckbox(
                            tooltip = tooltip,
                            label = shipModeDisplayName(modeName),
                            data = modeName,
                            tooltipText = shipModeDescription(modeName),
                            width = 130f
                        )
                    )
                )
                if (elementList.isEmpty()) {
                    panel.addUIElement(tooltip).belowLeft(position, 5f)
                } else {
                    panel.addUIElement(tooltip).rightOfTop(elementList.last(), 12f)
                }
                elementList.add(tooltip)
            }

            modeButtons.forEach {
                it.connectSameGroupButtons(modeButtons)
            }
            applyPersistedModes(modeButtons, ship, null)
            return modeButtons
        }

        fun createCampaignModeButtonGroup(
            ship: FleetMemberAPI,
            panel: CustomPanelAPI,
            runtimeShip: ShipAPI? = null,
            rowOffset: Int = 0,
            onScrollRows: (Int) -> Unit = {},
            layoutContainerHeight: Float = panel.position.height,
            onSelectionChanged: (() -> Unit)? = null,
            preparedGroup: PreparedCampaignModeButtonGroup? = null,
            persistenceContext: ShipEditorPersistenceContext? = null,
        ): CampaignModeButtonGroupResult {
            val prepared = preparedGroup ?: prepareCampaignModeButtonGroup(
                ship = ship,
                width = panel.position.width,
                availableHeight = layoutContainerHeight,
                runtimeShip = runtimeShip,
                rowOffset = rowOffset,
                persistenceContext = persistenceContext,
            )
            val persistedModeList = prepared.persistedModeList
            val persistedModes = persistedModeList.toSet()
            val plan = prepared.plan
            val layout = plan.layout
            val modeButtons = mutableListOf<ShipModeButton>()
            val allButtons = mutableListOf<ButtonBase<*>>()

            var nextTop = 0f
            val pageDelta = visibleScrollPageDelta(layout.scrollSlice)

            if (layout.scrollSlice.hasAbove) {
                allButtons.add(renderCampaignModeScrollIndicator(
                    panel = panel,
                    top = nextTop,
                    symbol = CampaignGuiStyle.SCROLL_INDICATOR_ABOVE,
                    onClick = { onScrollRows(-pageDelta) },
                ))
                nextTop += CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT + CampaignGuiStyle.SHIP_MODE_ITEM_VGAP
            }

            renderCampaignModeRows(
                ship = ship,
                panel = panel,
                runtimeShip = runtimeShip,
                persistenceContext = persistenceContext,
                plan = plan,
                rows = layout.scrollSlice.items,
                top = nextTop,
                persistedModeList = persistedModeList,
                onSelectionChanged = onSelectionChanged,
                modeButtons = modeButtons,
                allButtons = allButtons,
            )
            if (layout.scrollSlice.items.isNotEmpty()) {
                nextTop += layout.renderedScrollItemsHeight
            }
            allButtons.addAll(modeButtons)

            if (layout.scrollSlice.hasBelow) {
                if (layout.scrollSlice.items.isNotEmpty()) {
                    nextTop += CampaignGuiStyle.SHIP_MODE_ITEM_VGAP
                }
                allButtons.add(renderCampaignModeScrollIndicator(
                    panel = panel,
                    top = nextTop,
                    symbol = CampaignGuiStyle.SCROLL_INDICATOR_BELOW,
                    onClick = { onScrollRows(pageDelta) },
                ))
            }

            modeButtons.forEach {
                it.connectSameGroupButtons(modeButtons)
            }
            updateButtonsFromModes(modeButtons, persistedModes.toList())
            return CampaignModeButtonGroupResult(allButtons, layout.scrollSlice.maxOffset, layout.scrollSlice.offset)
        }

        private fun renderCampaignModeRows(
            ship: FleetMemberAPI,
            panel: CustomPanelAPI,
            runtimeShip: ShipAPI?,
            persistenceContext: ShipEditorPersistenceContext?,
            plan: CampaignModeButtonGroupPlan,
            rows: List<CampaignModeListRow>,
            top: Float,
            persistedModeList: List<String>,
            onSelectionChanged: (() -> Unit)?,
            modeButtons: MutableList<ShipModeButton>,
            allButtons: MutableList<ButtonBase<*>>,
        ) {
            rows.forEachIndexed { rowIndex, row ->
                val rowTop = top + rowIndex * (CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT + CampaignGuiStyle.SHIP_MODE_ITEM_VGAP)
                when (row) {
                    is CampaignModeListRow.Heading -> {
                        allButtons.add(renderCampaignModeSectionHeading(panel, plan.sectionKey, row, rowTop, onSelectionChanged))
                    }
                    is CampaignModeListRow.Modes -> renderCampaignModeButtonRow(
                        ship = ship,
                        panel = panel,
                        runtimeShip = runtimeShip,
                        persistenceContext = persistenceContext,
                        plan = plan,
                        row = row,
                        rowTop = rowTop,
                        persistedModeList = persistedModeList,
                        onSelectionChanged = onSelectionChanged,
                        modeButtons = modeButtons,
                    )
                }
            }
        }

        private fun renderCampaignModeSectionHeading(
            panel: CustomPanelAPI,
            sectionKey: String,
            row: CampaignModeListRow.Heading,
            top: Float,
            onSelectionChanged: (() -> Unit)?,
        ): ButtonBase<*> {
            val heading = CampaignToggleHeading(
                title = row.title,
                expanded = row.expanded,
                active = row.active,
                subject = "section",
                inactiveKind = CampaignActionButtonKind.TAG_TOP_LEVEL_SECTION,
                activeKind = CampaignActionButtonKind.TAG_TOP_LEVEL_SECTION,
            )
            return addTemplatedCampaignMomentaryActionButton(
                parent = panel,
                data = "ship_mode_section:$sectionKey:${row.title}",
                x = 0f,
                y = top,
                width = panel.position.width,
                height = CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT,
                template = CampaignGuiStyle.actionButtonTemplate(heading.kind),
                labelText = heading.label,
                tooltip = heading.tooltip,
                centerText = true,
            ) {
                val collapsedSections = collapsedCampaignModeSectionsByKey.getOrPut(sectionKey) { mutableSetOf() }
                if (row.expanded) {
                    collapsedSections.add(row.title)
                } else {
                    collapsedSections.remove(row.title)
                }
                onSelectionChanged?.invoke() ?: notifyCampaignShipModeSelectionChanged()
            }
        }

        private fun renderCampaignModeButtonRow(
            ship: FleetMemberAPI,
            panel: CustomPanelAPI,
            runtimeShip: ShipAPI?,
            persistenceContext: ShipEditorPersistenceContext?,
            plan: CampaignModeButtonGroupPlan,
            row: CampaignModeListRow.Modes,
            rowTop: Float,
            persistedModeList: List<String>,
            onSelectionChanged: (() -> Unit)?,
            modeButtons: MutableList<ShipModeButton>,
        ) {
            row.modes.forEachIndexed { columnIndex, modeName ->
                renderCampaignModeButton(
                    ship = ship,
                    panel = panel,
                    runtimeShip = runtimeShip,
                    persistenceContext = persistenceContext,
                    plan = plan,
                    columnIndex = columnIndex,
                    rowTop = rowTop,
                    modeName = modeName,
                    persistedModeList = persistedModeList,
                    onSelectionChanged = onSelectionChanged,
                    modeButtons = modeButtons,
                )
            }
        }

        private fun renderCampaignModeButton(
            ship: FleetMemberAPI,
            panel: CustomPanelAPI,
            runtimeShip: ShipAPI?,
            persistenceContext: ShipEditorPersistenceContext?,
            plan: CampaignModeButtonGroupPlan,
            columnIndex: Int,
            rowTop: Float,
            modeName: String,
            persistedModeList: List<String>,
            onSelectionChanged: (() -> Unit)?,
            modeButtons: MutableList<ShipModeButton>,
        ) {
            val itemWidth = plan.itemWidth
            val shell = addCampaignTagModeToggleShell(
                parent = panel,
                data = modeName,
                x = columnIndex * (itemWidth + CampaignGuiStyle.SHIP_MODE_ITEM_HGAP),
                y = rowTop,
                width = itemWidth,
                height = CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT,
                tooltip = shipModeDescription(modeName),
                fillIdle = modeName !in plan.activeModeSet,
                fillSelected = modeName in plan.activeModeSet
            )
            modeButtons.add(
                ShipModeButton(
                    ship,
                    modeName,
                    shell.button,
                    runtimeShip,
                    onSelectionChanged,
                    persistedModeList,
                    persistenceContext,
                    useMomentaryVisualState = true,
                )
            )
            modeButtons.last().updateCheckedFromModes(persistedModeList)
            renderCenteredControlLabel(
                panel = shell.panel,
                text = shipModeDisplayName(modeName),
                width = itemWidth,
                height = CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
                centerRegionOffsetX = CampaignGuiStyle.ITEM_HIGHLIGHT_X_OFFSET,
                centerRegionWidth = itemWidth,
                horizontalPadding = 0f,
                approxCharWidthPx = CampaignGuiStyle.SHIP_MODE_LABEL_CHAR_WIDTH_ESTIMATE
            )
        }

        private fun renderCampaignModeScrollIndicator(
            panel: CustomPanelAPI,
            top: Float,
            symbol: String,
            onClick: () -> Unit,
        ): ButtonBase<*> {
            val (_, button) = addTagScrollIndicatorMomentaryButton(
                parent = panel,
                top = top,
                symbol = symbol,
                data = "ship_modes_$symbol",
                height = CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT,
                callback = onClick,
            )
            return button
        }

        fun prepareCampaignModeButtonGroup(
            ship: FleetMemberAPI,
            width: Float,
            availableHeight: Float,
            runtimeShip: ShipAPI? = null,
            rowOffset: Int = 0,
            configuredModesOverride: List<String>? = null,
            persistenceContext: ShipEditorPersistenceContext? = null,
            forceActiveSectionExpanded: Boolean = false,
        ): PreparedCampaignModeButtonGroup {
            val context = persistenceContext ?: ShipEditorPersistenceContext(ship, runtimeShip)
            val persistedModeList = canonicalizeShipModeNames(context.loadModes(AGCGUI.storageIndex))
            val configuredModes = configuredModesOverride
                ?: currentModeNamesForContext(context)
            return PreparedCampaignModeButtonGroup(
                persistedModeList = persistedModeList,
                plan = campaignModeButtonGroupPlan(
                    sectionKey = context.shipId,
                    configuredModes = configuredModes,
                    width = width,
                    containerHeight = availableHeight,
                    rowOffset = rowOffset,
                    persistedModes = persistedModeList.toSet(),
                    forceActiveSectionExpanded = forceActiveSectionExpanded,
                )
            )
        }

        private fun currentModeNamesForContext(
            context: ShipEditorPersistenceContext,
        ): List<String> =
            CustomShipModeListStore.effectiveModeNamesForShipOrDefault(
                    context.shipId,
                    Settings.getCurrentShipModeNames()
                )

        fun estimateCampaignModeButtonGroupTightHeight(
            ship: FleetMemberAPI,
            width: Float,
            availableHeight: Float,
            runtimeShip: ShipAPI? = null,
            rowOffset: Int = 0,
            preparedGroup: PreparedCampaignModeButtonGroup? = null,
        ): Float {
            val prepared = preparedGroup ?: prepareCampaignModeButtonGroup(
                ship = ship,
                width = width,
                availableHeight = availableHeight,
                runtimeShip = runtimeShip,
                rowOffset = rowOffset,
            )
            return prepared.tightHeight
                .coerceAtLeast(CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT)
                .coerceAtMost(availableHeight)
        }

        private fun campaignModeButtonGroupPlan(
            sectionKey: String,
            configuredModes: List<String>,
            width: Float,
            containerHeight: Float,
            rowOffset: Int,
            persistedModes: Set<String>,
            forceActiveSectionExpanded: Boolean,
        ): CampaignModeButtonGroupPlan {
            val activeOffListModes = persistedModes
                .filter(::isSupportedShipModeName)
                .filterNot { mode -> mode in configuredModes }
            val modes = configuredModes + activeOffListModes
            val columns = CampaignGuiStyle.SHIP_MODE_COLUMN_COUNT
            val itemWidth =
                (width - CampaignGuiStyle.SHIP_MODE_ITEM_HGAP * (columns - 1)) / columns
            val activeModes = modes.filter { it in persistedModes }
            val activeModeSet = activeModes.toSet()
            val inactiveModes = modes.filterNot { it in activeModeSet }
            val rows = campaignModeRows(
                sectionKey = sectionKey,
                activeModes = activeModes,
                inactiveModes = inactiveModes,
                columns = columns,
                forceActiveSectionExpanded = forceActiveSectionExpanded,
            )
            return CampaignModeButtonGroupPlan(
                sectionKey = sectionKey,
                columns = columns,
                itemWidth = itemWidth,
                activeModeSet = activeModeSet,
                layout = computePinnedVerticalScrollLayout(
                    pinnedCandidates = emptyList(),
                    scrollCandidates = rows,
                    containerHeight = containerHeight,
                    currentOffset = rowOffset,
                    itemHeight = CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT,
                    verticalGap = CampaignGuiStyle.SHIP_MODE_ITEM_VGAP,
                    indicatorHeight = CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT,
                )
            )
        }

        private fun campaignModeRows(
            sectionKey: String,
            activeModes: List<String>,
            inactiveModes: List<String>,
            columns: Int,
            forceActiveSectionExpanded: Boolean,
        ): List<CampaignModeListRow> {
            if (forceActiveSectionExpanded) {
                collapsedCampaignModeSectionsByKey[sectionKey]?.remove(ACTIVE_MODE_SECTION)
            }
            val collapsedSections = collapsedCampaignModeSectionsByKey[sectionKey].orEmpty()
            val activeExpanded = ACTIVE_MODE_SECTION !in collapsedSections
            val inactiveExpanded = INACTIVE_MODE_SECTION !in collapsedSections
            return buildList {
                add(CampaignModeListRow.Heading(ACTIVE_MODE_SECTION, activeExpanded, activeModes.isNotEmpty()))
                if (activeExpanded) {
                    activeModes.chunked(columns).forEach { add(CampaignModeListRow.Modes(it)) }
                }
                add(CampaignModeListRow.Heading(INACTIVE_MODE_SECTION, inactiveExpanded, active = false))
                if (inactiveExpanded) {
                    inactiveModes.chunked(columns).forEach { add(CampaignModeListRow.Modes(it)) }
                }
            }
        }

        private fun applyPersistedModes(
            buttons: List<ShipModeButton>,
            ship: FleetMemberAPI,
            runtimeShip: ShipAPI?,
        ) {
            val modes = ShipEditorPersistenceContext(ship, runtimeShip).loadModes(AGCGUI.storageIndex)
            updateButtonsFromModes(buttons, modes)
        }

        private fun updateButtonsFromModes(
            buttons: List<ShipModeButton>,
            modes: List<String>,
        ) {
            buttons.forEach { it.updateCheckedFromModes(modes) }
        }
    }

    override fun executeCallbackIfChecked(): Boolean {
        if (useMomentaryVisualState) {
            return executeMomentaryVisualCallbackIfClicked()
        }
        if (!active && button.isChecked) {
            val modes = currentModesForClick()
            val modeName = associatedValue
            val updatedModes = if (modeName == defaultShipMode) {
                listOf(defaultShipMode)
            } else {
                (modes.filterNot { it == defaultShipMode } + modeName).distinct()
            }
            saveCurrentModes(updatedModes)
            setActiveChecked(true)
            onSelectionChanged?.invoke() ?: run {
                campaignShipModeSelectionVersion++
                updateSameGroupFromModes(updatedModes)
            }
            syncButtonCheckedToActive()
            applyToggleableVisualState()
            return true
        } else if (active && !button.isChecked) {
            val modeName = associatedValue
            val modes = currentModesForClick().filterNot { it == modeName }
            saveCurrentModes(modes)
            uncheck()
            onSelectionChanged?.invoke() ?: run {
                campaignShipModeSelectionVersion++
                updateSameGroupFromModes(modes)
            }
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
            currentState = ::currentModesForClick,
            updatedState = { currentlyActive, modes ->
                val modeName = associatedValue
                if (currentlyActive) {
                    modes.filterNot { it == modeName }
                } else if (modeName == defaultShipMode) {
                    listOf(defaultShipMode)
                } else {
                    (modes.filterNot { it == defaultShipMode } + modeName).distinct()
                }
            },
            saveState = ::saveCurrentModes,
            afterStateChanged = ::afterModesChanged,
        )
    }

    private fun applyToggleableVisualState(force: Boolean = false) {
        visualState.apply(button, force)
    }

    private fun updateSameGroupFromPersistedModes() {
        updateSameGroupFromModes(loadCurrentModes())
    }

    private fun updateSameGroupFromModes(modes: List<String>) {
        sameGroupButtons.forEach { (it as? ShipModeButton)?.updateCheckedFromModes(modes) }
    }

    private fun updateCheckedFromModes(modes: List<String>) {
        if (useMomentaryVisualState) {
            setActiveForConfiguredVisualState(modes.contains(associatedValue))
            return
        }
        if (modes.contains(associatedValue)) {
            check()
        } else {
            uncheck()
        }
        applyToggleableVisualState()
    }

    private fun loadCurrentModes(): List<String> {
        return (persistenceContext ?: ShipEditorPersistenceContext(ship, runtimeShip)).loadModes(AGCGUI.storageIndex)
    }

    private fun currentModesForClick(): List<String> {
        return currentModesSnapshot ?: loadCurrentModes()
    }

    private fun saveCurrentModes(modes: List<String>) {
        (persistenceContext ?: ShipEditorPersistenceContext(ship, runtimeShip)).saveModes(AGCGUI.storageIndex, modes)
    }

    private fun afterModesChanged(modes: List<String>) {
        onSelectionChanged?.invoke() ?: run {
            campaignShipModeSelectionVersion++
            updateSameGroupFromModes(modes)
        }
    }

    override fun onActivate() {
    }
}
