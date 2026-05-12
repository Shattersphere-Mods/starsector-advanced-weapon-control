package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.gui.CampaignGuiStyle
import com.dp.advancedgunnerycontrol.gui.PendingConfirmationState
import com.dp.advancedgunnerycontrol.gui.VerticalScrollRegion
import com.dp.advancedgunnerycontrol.gui.handleVerticalScrollInput
import com.dp.advancedgunnerycontrol.gui.processCampaignKeyboardShortcutInput
import com.dp.advancedgunnerycontrol.gui.replaceCampaignRootContentPanel
import com.dp.advancedgunnerycontrol.gui.usefulVerticalScrollOffset
import com.dp.advancedgunnerycontrol.gui.usefulVerticalScrollOffsetByDelta
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.CustomWeaponTagListStore
import com.dp.advancedgunnerycontrol.typesandvalues.WeaponTagListMode
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.PositionAPI

data class SuggestedTagEditorContent(
    val panel: CustomPanelAPI,
    val view: SuggestedTagGuiView?,
)

class SuggestedTagEditorState {
    companion object {
        private val SUGGESTED_TAG_LIST_MODES = SuggestedTagEditorPreferences.listModes()
    }

    private val weaponListView = WeaponListView(5)
    private var suggestedTagListMode: WeaponTagListMode = SuggestedTagEditorPreferences.activeListMode()

    var sessionState = SuggestedTagSessionState()
        private set

    private var currentActions: List<SuggestedTagUiAction> = emptyList()

    private val pendingDangerousAction = PendingConfirmationState<SuggestedTagDangerousAction>()
    private val collapsedFilterCategories = WeaponFilter.filterCategories.toMutableSet()
    private var actionScrollRegion: VerticalScrollRegion<Unit>? = null
    private var filterScrollRegion: VerticalScrollRegion<Unit>? = null

    init {
        weaponListView.replaceFilters(SuggestedTagEditorPreferences.activeFilters())
    }

    fun ensureSuggestedTagsInitialized() {
        if (Settings.customSuggestedTags.isEmpty()) {
            Settings.customSuggestedTags = Settings.defaultSuggestedTags
        }
    }

    fun captureFrom(view: SuggestedTagGuiView?) {
        sessionState = sessionState.captureFrom(view)
    }

    fun refreshActions() {
        currentActions = buildSuggestedTagActionRows(
            weaponListView = weaponListView,
            suggestedTagListMode = suggestedTagListMode,
            suggestedTagListModeCount = SUGGESTED_TAG_LIST_MODES.size,
            suggestedTagListModeIndex = SUGGESTED_TAG_LIST_MODES.indexOf(suggestedTagListMode).coerceAtLeast(0),
            cycleSuggestedTagListMode = ::cycleSuggestedTagListMode,
            clearFilters = ::clearFilters,
            armDangerousAction = pendingDangerousAction::arm,
        )
    }

    fun visibleSuggestedTagList(): List<String> {
        return when (suggestedTagListMode) {
            WeaponTagListMode.CUSTOM_GLOBAL -> CustomWeaponTagListStore.getGlobalSupportedCustomTags()
            WeaponTagListMode.NOVICE,
            WeaponTagListMode.CLASSIC,
            WeaponTagListMode.COMPLETE -> Settings.getWeaponTagListForMode(suggestedTagListMode)
            WeaponTagListMode.CUSTOM -> Settings.getWeaponTagListForMode(WeaponTagListMode.CLASSIC)
        }
    }

    private fun cycleSuggestedTagListMode(delta: Int) {
        val currentIndex = SUGGESTED_TAG_LIST_MODES.indexOf(suggestedTagListMode).coerceAtLeast(0)
        val nextIndex = (currentIndex + delta + SUGGESTED_TAG_LIST_MODES.size) % SUGGESTED_TAG_LIST_MODES.size
        suggestedTagListMode = SUGGESTED_TAG_LIST_MODES[nextIndex]
        SuggestedTagEditorPreferences.saveListMode(suggestedTagListMode)
    }

    private fun toggleFilter(filter: WeaponFilter) {
        weaponListView.toggleFilter(filter)
        SuggestedTagEditorPreferences.saveFilters(weaponListView.activeFilters())
    }

    private fun clearFilters() {
        weaponListView.clearFilters()
        SuggestedTagEditorPreferences.saveFilters(emptyList())
    }

    fun shouldRegenerate(view: SuggestedTagGuiView?): Boolean {
        return view?.shouldRegenerate() == true || weaponListView.hasChanged()
    }

    fun rebuildContent(
        root: CustomPanelAPI,
        content: CustomPanelAPI?,
        view: SuggestedTagGuiView?,
        onCleared: () -> Unit,
        buildOptionsPanel: (CustomPanelAPI) -> Unit,
        renderConfirmationModal: (CustomPanelAPI) -> Unit,
    ): SuggestedTagEditorContent {
        ensureSuggestedTagsInitialized()
        captureFrom(view)
        refreshActions()

        val nextContent = replaceCampaignRootContentPanel(
            root = root,
            content = content,
            plugin = SuggestedTagGuiView(
                weaponListView,
                sessionState.tagScrollOffsets,
                sessionState.tagExpandedCategoryTitles,
                sessionState.collapsedWeaponInfoSections,
                sessionState.expandedAdvancedWeaponInfoSections,
                visibleTagList = visibleSuggestedTagList(),
            ),
            onCleared = onCleared,
        )
        val nextView = nextContent.plugin as? SuggestedTagGuiView
        nextView?.buildIn(nextContent, buildOptionsPanel)
        weaponListView.markRendered()
        renderConfirmationModal(nextContent)
        return SuggestedTagEditorContent(nextContent, nextView)
    }

    fun refreshContent(
        view: SuggestedTagGuiView?,
        buildOptionsPanel: (CustomPanelAPI) -> Unit,
        rebuild: () -> Unit,
    ) {
        ensureSuggestedTagsInitialized()
        captureFrom(view)
        refreshActions()
        if (view?.refreshInPlace(buildOptionsPanel, visibleSuggestedTagList()) != true) {
            rebuild()
            return
        }
        weaponListView.markRendered()
    }

    fun renderOptionsPanel(
        panel: CustomPanelAPI,
        bindButton: (SuggestedTagUiAction, ButtonAPI, Boolean) -> Unit,
    ) {
        val result = renderSuggestedOptionsPanel(
            panel = panel,
            actions = currentActions,
            filterRows = buildSuggestedTagFilterActions(
                weaponListView,
                collapsedFilterCategories,
                onToggleFilter = ::toggleFilter,
            ),
            pageString = weaponListView.pageString,
            actionScrollOffset = sessionState.actionScrollOffset,
            filterScrollOffset = sessionState.filterScrollOffset,
            bindButton = bindButton,
            onActionScrollIndicator = { delta, maxOffset ->
                val offset = usefulVerticalScrollOffsetByDelta(sessionState.actionScrollOffset, delta, maxOffset)
                sessionState = sessionState.withActionScrollOffset(offset)
            },
            onFilterScrollIndicator = { delta, maxOffset ->
                val offset = usefulVerticalScrollOffsetByDelta(sessionState.filterScrollOffset, delta, maxOffset)
                sessionState = sessionState.withFilterScrollOffset(offset)
            },
        )
        sessionState = sessionState.withActionScrollOffset(result.actionScrollOffset)
        sessionState = sessionState.withFilterScrollOffset(result.filterScrollOffset)
        actionScrollRegion = result.actionScrollRegion
        filterScrollRegion = result.filterScrollRegion
    }

    private fun processOptionScrollInput(
        events: MutableList<InputEventAPI>,
        panelPos: PositionAPI?,
        onScrolled: () -> Unit,
        regionProvider: () -> VerticalScrollRegion<Unit>?,
        currentOffsetProvider: () -> Int,
        updateOffset: (Int) -> Unit,
    ): Boolean {
        val region = regionProvider() ?: return false
        return handleVerticalScrollInput(
            events = events,
            panelPos = panelPos,
            regions = listOf(region),
            currentOffset = { currentOffsetProvider() },
            setOffset = { _, offset ->
                updateOffset(offset)
            },
            onScrolled = { onScrolled() },
            scrollStep = CampaignGuiStyle.SUGGESTED_FILTER_SCROLL_STEP,
            normalizeOffset = { _, requested, maxOffset, delta ->
                usefulVerticalScrollOffset(requested, maxOffset, delta)
            },
        )
    }

    fun processInput(
        events: MutableList<InputEventAPI>,
        view: SuggestedTagGuiView?,
        panelPos: PositionAPI?,
        closeKeys: Set<Int>,
        onClose: () -> Unit,
        rebuild: () -> Unit,
        refreshContent: () -> Unit = rebuild,
        requestConfirmationRefresh: () -> Unit = rebuild,
        closeConfirmationTargeted: () -> Unit = rebuild,
    ) {
        if (
            processSuggestedConfirmationModalInput(
                events,
                ::pendingConfirmationRequest,
                rebuild,
                afterCancel = closeConfirmationTargeted,
            )
        ) return
        if (processOptionScrollInput(
                events = events,
                panelPos = panelPos,
                onScrolled = refreshContent,
                regionProvider = { actionScrollRegion },
                currentOffsetProvider = { sessionState.actionScrollOffset },
                updateOffset = { offset -> sessionState = sessionState.withActionScrollOffset(offset) },
            )
        ) return
        if (processOptionScrollInput(
                events = events,
                panelPos = panelPos,
                onScrolled = refreshContent,
                regionProvider = { filterScrollRegion },
                currentOffsetProvider = { sessionState.filterScrollOffset },
                updateOffset = { offset -> sessionState = sessionState.withFilterScrollOffset(offset) },
            )
        ) return
        view?.processInput(events)
        processCampaignKeyboardShortcutInput(
            events = events,
            closeKeys = closeKeys,
            onClose = onClose,
            onShortcut = { key ->
                val action = currentActions.firstOrNull { key in it.shortcuts }
                    ?: return@processCampaignKeyboardShortcutInput false
                executeAction(
                    action,
                    rebuild,
                    refreshContent = refreshContent,
                    requestConfirmationRefresh = requestConfirmationRefresh,
                )
                true
            },
        )
    }

    fun executeAction(
        action: SuggestedTagUiAction,
        rebuild: () -> Unit,
        refreshContent: () -> Unit = rebuild,
        requestConfirmationRefresh: () -> Unit = rebuild,
    ) {
        val hadPendingConfirmation = pendingConfirmationRequest() != null
        action.callback()
        val hasPendingConfirmation = pendingConfirmationRequest() != null
        when {
            action.rebuildAfter -> refreshContent()
            !hadPendingConfirmation && hasPendingConfirmation -> requestConfirmationRefresh()
        }
    }

    fun pendingConfirmationRequest() = pendingSuggestedDangerousActionRequest(pendingDangerousAction)

    fun clearPendingConfirmation() {
        pendingDangerousAction.clear()
    }
}
