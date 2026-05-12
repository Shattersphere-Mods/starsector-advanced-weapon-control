package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.gui.CampaignGuiStyle
import com.dp.advancedgunnerycontrol.gui.VerticalScrollRegion
import com.dp.advancedgunnerycontrol.gui.addCampaignPanelHeading
import com.dp.advancedgunnerycontrol.gui.computeCampaignActionRowLayouts
import com.dp.advancedgunnerycontrol.gui.renderCampaignScrollableActionRows
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import kotlin.math.max

data class SuggestedFilterPanelResult(
    val bottom: Float,
    val scrollOffset: Int,
    val scrollRegion: VerticalScrollRegion<Unit>,
)

/**
 * Suggested-tags Filter panel component.
 * Renders active filters, filter category headings, and scroll indicators in
 * the left column of Customize Suggested Tags.
 */
fun renderSuggestedFilterPanel(
    panel: CustomPanelAPI,
    width: Float,
    top: Float,
    maxVisibleHeight: Float,
    filterRows: List<SuggestedTagUiAction>,
    scrollOffset: Int,
    bindButton: (SuggestedTagUiAction, ButtonAPI, Boolean) -> Unit,
    onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
): SuggestedFilterPanelResult {
    addCampaignPanelHeading(panel, "Filter", top = top, headingHeight = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT)

    val layouts = computeCampaignActionRowLayouts(
        rows = filterRows,
        width = width,
        maxLines = CampaignGuiStyle.SUGGESTED_ACTION_LABEL_MAX_LINES,
        label = { action -> action.name },
        shortcuts = { action -> action.shortcuts },
        rowIndent = { action -> action.indent },
    )
    val rowsTop = top + CampaignGuiStyle.CONTAINER_HEADING_HEIGHT
    val visibleHeight = max(
        CampaignGuiStyle.TAG_ITEM_HEIGHT,
        panel.position.height - rowsTop - CampaignGuiStyle.SUGGESTED_FILTER_INFO_HEIGHT - CampaignGuiStyle.PANEL_PADDING
    ).coerceAtMost(maxVisibleHeight.coerceAtLeast(0f))
    val relativeBottom = panel.position.height - rowsTop - visibleHeight
    val renderedRows = renderCampaignScrollableActionRows(
        panel = panel,
        width = width,
        top = rowsTop,
        layouts = layouts,
        visibleHeight = visibleHeight,
        currentOffset = scrollOffset,
        maxLines = CampaignGuiStyle.SUGGESTED_ACTION_LABEL_MAX_LINES,
        scrollIndicatorRow = { up, visiblePageSize, maxOffset ->
            suggestedFilterScrollIndicatorAction(up, visiblePageSize, maxOffset, onScrollIndicator)
        },
        label = { action -> action.name },
        shortcuts = { action -> action.shortcuts },
        kind = { action -> action.effectiveKind() },
        tooltip = { action -> action.tooltip },
        bindButton = { action, button ->
            bindButton(action, button, true)
        },
        bindScrollIndicatorButton = { action, button ->
            bindButton(action, button, false)
        },
        textPadding = CampaignGuiStyle.ACTION_ROW_PADDING,
    )
    val scrollRegion = VerticalScrollRegion(
        key = Unit,
        left = CampaignGuiStyle.PANEL_PADDING,
        right = CampaignGuiStyle.PANEL_PADDING + width,
        bottom = relativeBottom,
        top = relativeBottom + visibleHeight,
        maxOffset = renderedRows.maxOffset,
    )

    return SuggestedFilterPanelResult(
        bottom = rowsTop + visibleHeight + CampaignGuiStyle.ACTION_ROW_GAP,
        scrollOffset = renderedRows.offset,
        scrollRegion = scrollRegion,
    )
}

private fun suggestedFilterScrollIndicatorAction(
    up: Boolean,
    visiblePageSize: Int,
    maxOffset: Int,
    onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
): SuggestedTagUiAction {
    val label = if (up) CampaignGuiStyle.SCROLL_INDICATOR_ABOVE else CampaignGuiStyle.SCROLL_INDICATOR_BELOW
    val tooltip = if (up) "Show earlier filter rows." else "Show later filter rows."
    return SuggestedTagUiAction(label, tooltip = tooltip) {
        val delta = if (up) -visiblePageSize else visiblePageSize
        onScrollIndicator(delta, maxOffset)
    }
}
