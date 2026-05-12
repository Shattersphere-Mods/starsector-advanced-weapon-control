package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.gui.CampaignGuiStyle
import com.dp.advancedgunnerycontrol.gui.VerticalScrollRegion
import com.dp.advancedgunnerycontrol.gui.addAgcText
import com.dp.advancedgunnerycontrol.gui.addCampaignPanelHeading
import com.dp.advancedgunnerycontrol.gui.campaignActionRowsHeight
import com.dp.advancedgunnerycontrol.gui.computeCampaignActionRowLayouts
import com.dp.advancedgunnerycontrol.gui.renderCampaignScrollableActionRows
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import kotlin.math.max
import kotlin.math.min

data class SuggestedOptionsPanelResult(
    val actionScrollOffset: Int,
    val actionScrollRegion: VerticalScrollRegion<Unit>?,
    val filterScrollOffset: Int,
    val filterScrollRegion: VerticalScrollRegion<Unit>,
)

/**
 * Suggested-tags left-column component.
 * Renders Backup/Reset/Restore, page controls, tag-list selector, Reset Filters,
 * the Filter panel, and page/status text.
 */
fun renderSuggestedOptionsPanel(
    panel: CustomPanelAPI,
    actions: List<SuggestedTagUiAction>,
    filterRows: List<SuggestedTagUiAction>,
    pageString: String,
    actionScrollOffset: Int,
    filterScrollOffset: Int,
    bindButton: (SuggestedTagUiAction, ButtonAPI, Boolean) -> Unit,
    onActionScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
    onFilterScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
): SuggestedOptionsPanelResult {
    val width = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
    val headingHeight = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT
    addCampaignPanelHeading(panel, "Options", headingHeight = headingHeight)

    val actionLayouts = computeCampaignActionRowLayouts(
        rows = actions,
        width = width,
        maxLines = CampaignGuiStyle.SUGGESTED_ACTION_LABEL_MAX_LINES,
        label = { action -> action.name },
        shortcuts = { action -> action.shortcuts },
    )
    val actionRowsTop = CampaignGuiStyle.PANEL_PADDING + headingHeight
    val pageStatusHeight = CampaignGuiStyle.SUGGESTED_FILTER_INFO_HEIGHT
    val filterMinimumHeight = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT + CampaignGuiStyle.TAG_ITEM_HEIGHT
    val maxActionRowsHeight = (panel.position.height -
        actionRowsTop -
        filterMinimumHeight -
        pageStatusHeight -
        3f * CampaignGuiStyle.PANEL_PADDING
    ).coerceAtLeast(0f)
    val desiredActionRowsHeight = min(
        campaignActionRowsHeight(actionLayouts),
        panel.position.height * 0.35f
    )
    val actionVisibleHeight = min(desiredActionRowsHeight, maxActionRowsHeight).coerceAtLeast(0f)
    val actionRenderResult = if (actionVisibleHeight > 0f) {
        renderCampaignScrollableActionRows(
            panel = panel,
            width = width,
            top = actionRowsTop,
            layouts = actionLayouts,
            visibleHeight = actionVisibleHeight,
            currentOffset = actionScrollOffset,
            maxLines = CampaignGuiStyle.SUGGESTED_ACTION_LABEL_MAX_LINES,
            scrollIndicatorRow = { up, visiblePageSize, maxOffset ->
                suggestedActionScrollIndicatorAction(up, visiblePageSize, maxOffset, onActionScrollIndicator)
            },
            label = { action -> action.name },
            shortcuts = { action -> action.shortcuts },
            kind = { action -> action.effectiveKind() },
            tooltip = { action -> action.tooltip },
            bindButton = { action, button ->
                bindButton(action, button, false)
            },
            bindScrollIndicatorButton = { action, button ->
                bindButton(action, button, false)
            },
        )
    } else {
        com.dp.advancedgunnerycontrol.gui.CampaignScrollableActionRowsRenderResult(0, 0)
    }
    val actionScrollRegion = if (actionRenderResult.maxOffset > 0 && actionVisibleHeight > 0f) {
        val actionBottom = panel.position.height - actionRowsTop - actionVisibleHeight
        VerticalScrollRegion(
            key = Unit,
            left = CampaignGuiStyle.PANEL_PADDING,
            right = CampaignGuiStyle.PANEL_PADDING + width,
            bottom = actionBottom,
            top = actionBottom + actionVisibleHeight,
            maxOffset = actionRenderResult.maxOffset,
        )
    } else {
        null
    }

    val filterTop = actionRowsTop +
        actionVisibleHeight +
        if (actionLayouts.isEmpty()) 0f else CampaignGuiStyle.PANEL_PADDING
    val filterMaxVisibleHeight = (panel.position.height -
        filterTop -
        headingHeight -
        pageStatusHeight -
        CampaignGuiStyle.PANEL_PADDING
    ).coerceAtLeast(0f)
    val filterResult = renderSuggestedFilterPanel(
        panel = panel,
        width = width,
        top = filterTop,
        maxVisibleHeight = filterMaxVisibleHeight,
        filterRows = filterRows,
        scrollOffset = filterScrollOffset,
        bindButton = bindButton,
        onScrollIndicator = onFilterScrollIndicator,
    )
    val infoTop = filterResult.bottom
    val infoHeight = max(0f, panel.position.height - infoTop - CampaignGuiStyle.PANEL_PADDING)
    if (infoHeight > 0f) {
        val infoPanel = panel.createUIElement(width, infoHeight, false)
        infoPanel.addAgcText(pageString, 4f)
        panel.addUIElement(infoPanel).inTL(CampaignGuiStyle.PANEL_PADDING, infoTop)
    }

    return SuggestedOptionsPanelResult(
        actionScrollOffset = actionRenderResult.offset,
        actionScrollRegion = actionScrollRegion,
        filterScrollOffset = filterResult.scrollOffset,
        filterScrollRegion = filterResult.scrollRegion,
    )
}

private fun suggestedActionScrollIndicatorAction(
    up: Boolean,
    visiblePageSize: Int,
    maxOffset: Int,
    onScrollIndicator: (delta: Int, maxOffset: Int) -> Unit,
): SuggestedTagUiAction {
    val label = if (up) CampaignGuiStyle.SCROLL_INDICATOR_ABOVE else CampaignGuiStyle.SCROLL_INDICATOR_BELOW
    val tooltip = if (up) "Show earlier option rows." else "Show later option rows."
    return SuggestedTagUiAction(label, tooltip = tooltip) {
        val delta = if (up) -visiblePageSize else visiblePageSize
        onScrollIndicator(delta, maxOffset)
    }
}
