package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.gui.actions.CycleTagListModeAction
import com.dp.advancedgunnerycontrol.gui.actions.GUIAction
import com.dp.advancedgunnerycontrol.gui.actions.NextShipAction
import com.dp.advancedgunnerycontrol.gui.actions.shipActionButtonKind
import com.dp.advancedgunnerycontrol.settings.Settings
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import java.awt.Color
import kotlin.math.max
import org.lwjgl.input.Keyboard

data class CampaignOptionRow(
    val label: String,
    val tooltip: String,
    val shortcut: Int? = null,
    val activationShortcut: Int? = shortcut,
    val kind: CampaignActionButtonKind = CampaignActionButtonKind.UNCOLOURED,
    val rebuildAfter: Boolean = true,
    val confirmationKey: String? = null,
    val confirmationTitle: String? = null,
    val confirmationDescription: String? = null,
    val confirmationTone: CampaignConfirmationTone = CampaignConfirmationTone.CAUTION,
    val rightClickCallback: (() -> Unit)? = null,
    val textColor: Color = CampaignGuiStyle.DEFAULT_TEXT_COLOUR,
    val callback: () -> Unit,
)

data class CampaignOptionsRenderResult(
    val rowOffset: Int,
    val maxRowOffset: Int,
)

data class CampaignOptionsLayout(
    val rows: List<CampaignActionRowLayout<CampaignOptionRow>>,
    val requiredHeight: Float,
)

/**
 * Shared Options panel controller component.
 * Owns option-row state, confirmation arming, right-click handling, shortcut
 * dispatch, and button polling for campaign and direct/refit hosts.
 */
class CampaignOptionPanelControllerSupport(
    private val capabilities: ShipEditorCapabilities,
    private val requestRebuild: () -> Unit,
    private val requestConfirmationRefresh: () -> Unit = requestRebuild,
    private val extraRowsProvider: () -> List<CampaignOptionRow> = { emptyList() },
    private val minHeight: Float = 0f,
    private val bindListener: ((ButtonAPI) -> Unit)? = null,
    private val afterRightClick: (CampaignOptionRow) -> Unit = { row ->
        if (row.rebuildAfter) requestRebuild()
    },
) {
    private val optionButtons = mutableListOf<ButtonBase<*>>()
    private val buttonCallbackPoller = CampaignButtonCallbackPoller(optionButtons)
    private var lastModifierKeys = GUIAction.modifierKeys()
    private var currentBaseRows: List<CampaignOptionRow> = emptyList()
    private var currentRows: List<CampaignOptionRow> = emptyList()
    private val confirmationController = CampaignOptionConfirmationController(requestRebuild)
    private val layoutCache = CampaignOptionLayoutCache()
    private var suppressionSnapshot: CampaignButtonSuppressionSnapshot? = null

    fun updateRows(baseRows: List<CampaignOptionRow>) {
        layoutCache.clear()
        currentBaseRows = baseRows
        confirmationController.clearIfUnavailable(baseRows)
        currentRows = confirmationController.armRows(baseRows)
    }

    fun advanceButtons() {
        buttonCallbackPoller.processIfRequested()
    }

    fun consumeModifierChange(): Boolean {
        val currentModifierKeys = GUIAction.modifierKeys()
        if (lastModifierKeys == currentModifierKeys) return false
        lastModifierKeys = currentModifierKeys
        return true
    }

    fun processInput(events: MutableList<InputEventAPI>?): Boolean {
        buttonCallbackPoller.requestPollFromInput(events)
        return optionButtons.processCampaignOptionButtonInput(events)
    }

    fun finishRowExecution(row: CampaignOptionRow) {
        if (row.confirmationKey != null) {
            requestConfirmationRefresh()
        } else if (row.rebuildAfter) {
            requestRebuild()
        }
    }

    fun handleButtonId(
        buttonId: Any?,
        executeRow: (CampaignOptionRow) -> Unit,
    ): Boolean {
        return executeCampaignOptionButtonId(buttonId, executeRow)
    }

    fun handleShortcut(
        shortcut: Int,
        executeRow: (CampaignOptionRow) -> Unit,
    ): Boolean {
        val row = findCampaignOptionShortcutRow(currentRows, extraRowsProvider, shortcut) ?: return false
        executeRow(row)
        return true
    }

    fun buildPanel(
        panel: CustomPanelAPI,
        executeRow: (CampaignOptionRow) -> Unit,
        renderHeading: Boolean = true,
        rowOffset: Int = 0,
        visibleBodyHeight: Float? = null,
        onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
    ): CampaignOptionsRenderResult {
        suppressionSnapshot = null
        val rows = campaignOptionRowsWithExtra(currentRows, extraRowsProvider())
        return renderBoundCampaignOptionsPanel(
            panel = panel,
            layout = layoutCache.layout(width = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING, rows = rows),
            optionButtons = optionButtons,
            executeRow = executeRow,
            renderHeading = renderHeading,
            rowOffset = rowOffset,
            visibleBodyHeight = visibleBodyHeight,
            onScrollIndicator = onScrollIndicator,
            bindListener = bindListener,
            afterRightClick = afterRightClick,
        )
    }

    fun confirmationRequest(): CampaignConfirmationModalRequest? {
        return confirmationController.request(currentBaseRows)
    }

    fun buildModifiersPanel(panel: CustomPanelAPI) {
        buildCampaignOptionModifiersPanel(panel, capabilities)
    }

    fun suppressButtonHover() {
        if (suppressionSnapshot == null) {
            suppressionSnapshot = optionButtons.suppressCampaignOptionButtonHover()
        }
    }

    fun restoreButtonHover() {
        suppressionSnapshot?.restore()
        suppressionSnapshot = null
    }

    fun estimateHeight(width: Float): Float {
        return layoutCache.layout(
            width = width,
            rows = campaignOptionRowsWithExtra(currentRows, extraRowsProvider()),
            minHeight = minHeight
        ).requiredHeight
    }

    fun estimateStableHeight(width: Float): Float {
        return layoutCache.layout(
            width = width,
            rows = stableAdvancedOptionRows(capabilities),
            minHeight = minHeight
        ).requiredHeight
    }

    fun estimateModifiersHeight(): Float {
        return estimateCampaignOptionModifiersHeight(capabilities)
    }
}

private data class CampaignOptionLayoutCacheKey(
    val width: Float,
    val minHeight: Float,
    val rows: List<CampaignOptionRowLayoutKey>,
)

private data class CampaignOptionRowLayoutKey(
    val label: String,
    val shortcut: Int?,
)

private data class CampaignOptionRowLayoutMetrics(
    val wrappedText: String,
    val rowHeight: Float,
)

private data class CachedCampaignOptionLayout(
    val key: CampaignOptionLayoutCacheKey,
    val metrics: List<CampaignOptionRowLayoutMetrics>,
    val requiredHeight: Float,
)

private class CampaignOptionLayoutCache {
    companion object {
        private const val MAX_ENTRIES = 8
    }

    private val entries = LinkedHashMap<CampaignOptionLayoutCacheKey, CachedCampaignOptionLayout>()

    fun clear() {
        entries.clear()
    }

    fun layout(
        width: Float,
        rows: List<CampaignOptionRow>,
        minHeight: Float = 0f,
    ): CampaignOptionsLayout {
        val key = CampaignOptionLayoutCacheKey(
            width = width,
            minHeight = minHeight,
            rows = rows.map { row -> CampaignOptionRowLayoutKey(row.label, row.shortcut) },
        )
        entries[key]?.let { cached -> return cached.toLayout(rows) }

        val computed = computeCampaignOptionsLayout(width, rows, minHeight)
        val cached = CachedCampaignOptionLayout(
            key = key,
            metrics = computed.rows.map { row ->
                CampaignOptionRowLayoutMetrics(
                    wrappedText = row.wrappedText,
                    rowHeight = row.rowHeight,
                )
            },
            requiredHeight = computed.requiredHeight,
        )
        entries[key] = cached
        trimOldest()
        return computed
    }

    private fun CachedCampaignOptionLayout.toLayout(rows: List<CampaignOptionRow>): CampaignOptionsLayout {
        return CampaignOptionsLayout(
            rows = metrics.zip(rows).map { (metric, row) ->
                CampaignActionRowLayout(
                    row = row,
                    wrappedText = metric.wrappedText,
                    rowHeight = metric.rowHeight,
                )
            },
            requiredHeight = requiredHeight,
        )
    }

    private fun trimOldest() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: return
            entries.remove(oldest)
        }
    }
}

class CampaignOptionConfirmationController(
    private val requestRebuild: () -> Unit,
) {
    private val pendingConfirmation = PendingConfirmationState<String>()

    fun clearIfUnavailable(baseRows: List<CampaignOptionRow>) {
        pendingConfirmation.clearIfUnavailable(baseRows.mapNotNull { it.confirmationKey })
    }

    fun armRows(baseRows: List<CampaignOptionRow>): List<CampaignOptionRow> {
        return baseRows.map { row ->
            val key = row.confirmationKey ?: return@map row
            row.copy(callback = { pendingConfirmation.arm(key) })
        }
    }

    fun request(baseRows: List<CampaignOptionRow>): CampaignConfirmationModalRequest? {
        val pendingKey = pendingConfirmation.key ?: return null
        val row = baseRows.firstOrNull { it.confirmationKey == pendingKey } ?: return null
        return campaignConfirmationRequestWithFooter(
            title = row.confirmationTitle ?: "Confirm ${row.label}",
            description = row.confirmationDescription ?: "Confirming will execute ${row.label}.",
            tone = row.confirmationTone,
            onConfirm = {
                row.callback()
                pendingConfirmation.clear()
                if (row.rebuildAfter) {
                    requestRebuild()
                }
            },
            onCancel = {
                pendingConfirmation.clear()
            }
        )
    }
}

fun computeCampaignOptionsLayout(
    width: Float,
    rows: List<CampaignOptionRow>,
    minHeight: Float = 0f,
): CampaignOptionsLayout {
    val rowLayouts = computeCampaignActionRowLayouts(
        rows = rows,
        width = width,
        maxLines = CampaignGuiStyle.ACTION_LABEL_MAX_LINES,
        label = { row -> row.label },
        shortcuts = { row -> row.shortcut?.let(::listOf) ?: emptyList() },
    )
    val requiredHeight = max(
        minHeight,
        CampaignGuiStyle.PANEL_PADDING +
            CampaignGuiStyle.CONTAINER_HEADING_HEIGHT +
            campaignActionRowsHeight(rowLayouts) +
            CampaignGuiStyle.PANEL_PADDING
    )
    return CampaignOptionsLayout(
        rows = rowLayouts,
        requiredHeight = requiredHeight
    )
}

fun renderCampaignOptionsPanel(
    panel: CustomPanelAPI,
    rows: List<CampaignOptionRow>,
    renderHeading: Boolean,
    rowOffset: Int,
    visibleBodyHeight: Float?,
    onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
    bindButton: (CampaignOptionRow, ButtonAPI) -> Unit,
): CampaignOptionsRenderResult {
    val width = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
    val layout = computeCampaignOptionsLayout(width, rows)
    return renderCampaignOptionsPanelLayout(
        panel = panel,
        layout = layout,
        renderHeading = renderHeading,
        rowOffset = rowOffset,
        visibleBodyHeight = visibleBodyHeight,
        onScrollIndicator = onScrollIndicator,
        bindButton = bindButton,
    )
}

fun renderCampaignOptionsPanelLayout(
    panel: CustomPanelAPI,
    layout: CampaignOptionsLayout,
    renderHeading: Boolean,
    rowOffset: Int,
    visibleBodyHeight: Float?,
    onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
    bindButton: (CampaignOptionRow, ButtonAPI) -> Unit,
): CampaignOptionsRenderResult {
    val width = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
    val bodyHeight = visibleBodyHeight ?: panel.position.height
    if (renderHeading) {
        addCampaignPanelHeading(
            panel = panel,
            title = "Options",
            headingHeight = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT,
        )
    }
    val rendered = renderCampaignScrollableActionRows(
        panel = panel,
        width = width,
        top = CampaignGuiStyle.PANEL_PADDING + CampaignGuiStyle.CONTAINER_HEADING_HEIGHT,
        layouts = layout.rows,
        visibleHeight = bodyHeight,
        currentOffset = rowOffset,
        maxLines = CampaignGuiStyle.ACTION_LABEL_MAX_LINES,
        scrollIndicatorRow = { up, visiblePageSize, maxOffset ->
            campaignOptionScrollIndicatorRow(up, visiblePageSize, maxOffset, onScrollIndicator)
        },
        label = { row -> row.label },
        shortcuts = { row -> row.shortcut?.let(::listOf) ?: emptyList() },
        kind = { row -> row.kind },
        tooltip = { row -> row.tooltip },
        bindButton = bindButton,
        bindScrollIndicatorButton = bindButton,
        textColor = { row -> row.textColor },
        textPadding = CampaignGuiStyle.ACTION_ROW_PADDING,
    )
    return CampaignOptionsRenderResult(
        rowOffset = rendered.offset,
        maxRowOffset = rendered.maxOffset
    )
}

private fun campaignOptionScrollIndicatorRow(
    up: Boolean,
    visiblePageSize: Int,
    maxOffset: Int,
    onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
): CampaignOptionRow {
    val label = if (up) CampaignGuiStyle.SCROLL_INDICATOR_ABOVE else CampaignGuiStyle.SCROLL_INDICATOR_BELOW
    val tooltip = if (up) "Show earlier option rows." else "Show later option rows."
    return CampaignOptionRow(
        label = label,
        tooltip = tooltip,
        kind = CampaignActionButtonKind.UNCOLOURED,
        rebuildAfter = false,
    ) {
        val delta = if (up) -visiblePageSize else visiblePageSize
        onScrollIndicator(delta, maxOffset)
    }
}

fun campaignOptionRowForAction(
    action: GUIAction,
    useStableLabel: Boolean = false,
    kind: CampaignActionButtonKind? = null,
    rebuildAfter: Boolean = true,
    confirmationKey: String? = null,
    rightClickCallback: (() -> Unit)? = null,
    callback: () -> Unit,
): CampaignOptionRow {
    return CampaignOptionRow(
        label = if (useStableLabel) action.getStableLayoutName() else action.getName(),
        tooltip = action.getTooltip(),
        shortcut = action.getDisplayShortcut(),
        activationShortcut = action.getShortcut(),
        kind = kind ?: shipActionButtonKind(action),
        rebuildAfter = rebuildAfter,
        confirmationKey = confirmationKey,
        confirmationTitle = action.getConfirmationTitle(),
        confirmationDescription = action.getConfirmationDescription(),
        confirmationTone = action.getConfirmationTone(),
        rightClickCallback = rightClickCallback,
        callback = callback
    )
}

fun buildCampaignOptionRowsFromActions(
    actions: List<GUIAction>,
    useStableLabels: Boolean,
    confirmationKey: (GUIAction) -> String? = { null },
    rightClickCallback: (GUIAction) -> (() -> Unit)? = { null },
    rebuildAfter: (GUIAction) -> Boolean = { true },
    callback: (GUIAction) -> () -> Unit,
): List<CampaignOptionRow> {
    return actions.map { action ->
        campaignOptionRowForAction(
            action = action,
            useStableLabel = useStableLabels,
            confirmationKey = confirmationKey(action),
            rightClickCallback = rightClickCallback(action),
            rebuildAfter = rebuildAfter(action),
            callback = callback(action),
        )
    }
}

fun MutableList<ButtonBase<*>>.bindCampaignOptionRowButton(
    row: CampaignOptionRow,
    button: ButtonAPI,
    executeRow: (CampaignOptionRow) -> Unit,
    afterRightClick: (CampaignOptionRow) -> Unit = {},
): ButtonBase<*> {
    val control = addCampaignButtonControl(button = button) { executeRow(row) }
    row.rightClickCallback?.let { callback ->
        control.onRightClick {
            callback()
            afterRightClick(row)
        }
    }
    button.setShowTooltipWhileInactive(true)
    return control
}

fun MutableList<ButtonBase<*>>.processCampaignOptionButtonInput(events: MutableList<InputEventAPI>?): Boolean {
    return processCampaignButtonRightClickInput(events)
}

fun MutableList<ButtonBase<*>>.suppressCampaignOptionButtonHover(): CampaignButtonSuppressionSnapshot {
    return suppressCampaignButtonHoverSnapshot()
}

fun findCampaignOptionShortcutRow(
    rows: List<CampaignOptionRow>,
    extraRowsProvider: () -> List<CampaignOptionRow>,
    shortcut: Int,
): CampaignOptionRow? {
    return campaignOptionRowsWithExtra(rows, extraRowsProvider()).firstOrNull { it.activationShortcut == shortcut }
}

fun executeCampaignOptionButtonId(
    buttonId: Any?,
    executeRow: (CampaignOptionRow) -> Unit,
): Boolean {
    val row = buttonId as? CampaignOptionRow ?: return false
    executeRow(row)
    return true
}

fun renderBoundCampaignOptionsPanel(
    panel: CustomPanelAPI,
    layout: CampaignOptionsLayout,
    optionButtons: MutableList<ButtonBase<*>>,
    executeRow: (CampaignOptionRow) -> Unit,
    renderHeading: Boolean = true,
    rowOffset: Int = 0,
    visibleBodyHeight: Float? = null,
    onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
    bindListener: ((ButtonAPI) -> Unit)? = null,
    afterRightClick: (CampaignOptionRow) -> Unit = {},
): CampaignOptionsRenderResult {
    optionButtons.clear()
    return renderCampaignOptionsPanelLayout(
        panel = panel,
        layout = layout,
        renderHeading = renderHeading,
        rowOffset = rowOffset,
        visibleBodyHeight = visibleBodyHeight,
        onScrollIndicator = onScrollIndicator,
        bindButton = { row, button ->
            optionButtons.bindCampaignOptionRowButton(
                row = row,
                button = button,
                executeRow = executeRow,
                afterRightClick = afterRightClick,
            )
            bindListener?.invoke(button)
        },
    )
}

fun estimateCampaignOptionsHeight(
    width: Float,
    rows: List<CampaignOptionRow>,
    extraRowsProvider: () -> List<CampaignOptionRow> = { emptyList() },
    minHeight: Float = 0f,
): Float {
    return computeCampaignOptionsLayout(
        width = width,
        rows = campaignOptionRowsWithExtra(rows, extraRowsProvider()),
        minHeight = minHeight
    ).requiredHeight
}

fun estimateStableCampaignOptionsHeight(
    width: Float,
    capabilities: ShipEditorCapabilities,
    minHeight: Float = 0f,
): Float {
    return computeCampaignOptionsLayout(
        width = width,
        rows = stableAdvancedOptionRows(capabilities),
        minHeight = minHeight
    ).requiredHeight
}

fun buildCampaignOptionModifiersPanel(
    panel: CustomPanelAPI,
    capabilities: ShipEditorCapabilities,
) {
    val modifiersText = capabilities.modifierText() ?: return
    buildCampaignModifiersPanel(panel, modifiersText)
}

fun estimateCampaignOptionModifiersHeight(capabilities: ShipEditorCapabilities): Float {
    return if (capabilities.modifierText() == null) 0f else CAMPAIGN_MODIFIERS_PANEL_HEIGHT
}

fun campaignOptionRowsWithExtra(
    baseRows: List<CampaignOptionRow>,
    extraRows: List<CampaignOptionRow>,
): List<CampaignOptionRow> {
    if (extraRows.isEmpty()) return baseRows
    val rows = baseRows.toMutableList()
    val manageTagsIndex = rows.indexOfFirst { it.label == "Manage Tags and Ship Modes" || it.label == "Manage Tags" }
    val insertIndex = if (manageTagsIndex >= 0) {
        manageTagsIndex + 1
    } else {
        val toggleIndex = rows.indexOfFirst { it.label.startsWith("Switch to ") }
        if (toggleIndex >= 0) toggleIndex + 1 else rows.size
    }
    rows.addAll(insertIndex.coerceIn(0, rows.size), extraRows)
    return rows
}

fun stableAdvancedOptionRows(capabilities: ShipEditorCapabilities): List<CampaignOptionRow> {
    val rows = mutableListOf<CampaignOptionRow>()
    if (capabilities.canSelectOtherShips) {
        rows.add(stableLayoutRow(NextShipAction.STABLE_LAYOUT_NAME, Keyboard.KEY_TAB))
    }
    if (capabilities.canCycleLoadout) {
        rows.add(stableLayoutRow("Cycle loadout [1 / ${Settings.maxLoadouts()}] <Normal>"))
    }
    rows.add(stableLayoutRow(CycleTagListModeAction.STABLE_LAYOUT_NAME))
    if (capabilities.canCustomizeSuggestedTags) {
        rows.add(stableLayoutRow("Customize suggested tags"))
    }
    rows.add(stableLayoutRow("Switch to simple mode"))
    rows.add(stableLayoutRow("Manage Tags and Ship Modes", kind = CampaignActionButtonKind.SAVE))
    rows.add(stableLayoutRow("Save", kind = CampaignActionButtonKind.SAVE))
    rows.add(stableLayoutRow("Load", kind = CampaignActionButtonKind.LOAD))
    rows.add(stableLayoutRow("Reset \"All Ships\" List", kind = CampaignActionButtonKind.LOAD))
    rows.add(stableLayoutRow("Reset tags and ship modes", kind = CampaignActionButtonKind.LOAD))
    if (capabilities.canReloadSettings) {
        rows.add(stableLayoutRow("Reload Settings.EDITME", kind = CampaignActionButtonKind.LOAD))
    }
    return rows
}

private fun stableLayoutRow(
    label: String,
    shortcut: Int? = null,
    kind: CampaignActionButtonKind = CampaignActionButtonKind.UNCOLOURED,
): CampaignOptionRow {
    return CampaignOptionRow(
        label = label,
        tooltip = "",
        shortcut = shortcut,
        kind = kind,
        confirmationDescription = null,
        callback = {}
    )
}
