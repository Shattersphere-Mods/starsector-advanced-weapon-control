package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.typesandvalues.TagListView
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.ui.CustomPanelAPI

data class ShipViewSessionState(
    val tagScrollOffsets: Map<Int, Int> = emptyMap(),
    val tagExpandedCategoryTitles: Map<Int, Set<String>> = emptyMap(),
    val optionsScrollOffset: Int = 0,
    val shipModeScrollOffset: Int = 0,
    val presetControlStates: Map<Int, PresetControlState> = emptyMap(),
    val collapsedPanelIds: Set<String> = emptySet(),
) {
    fun captureFrom(shipView: ShipView?): ShipViewSessionState {
        return if (shipView == null) {
            this
        } else {
            copy(
                tagScrollOffsets = shipView.captureTagScrollOffsets(),
                tagExpandedCategoryTitles = shipView.captureTagExpandedCategoryTitles(),
                optionsScrollOffset = shipView.captureOptionsScrollOffset(),
                shipModeScrollOffset = shipView.captureShipModeScrollOffset(),
                presetControlStates = shipView.capturePresetControlStates(),
                collapsedPanelIds = shipView.captureCollapsedPanelIds(),
            )
        }
    }

    fun withPresetControlState(groupIndex: Int, state: PresetControlState): ShipViewSessionState {
        return copy(
            presetControlStates = updatePresetControlStateMap(presetControlStates, groupIndex, state)
        )
    }
}

data class ShipEditorShipViewContent(
    val panel: CustomPanelAPI,
    val view: ShipView?,
    val sessionState: ShipViewSessionState,
)

fun rebuildShipEditorShipViewContent(
    root: CustomPanelAPI,
    content: CustomPanelAPI?,
    shipView: ShipView?,
    ship: FleetMemberAPI,
    tagView: TagListView,
    sessionState: ShipViewSessionState,
    onSessionStateUpdate: (ShipViewSessionState) -> Unit,
    customListState: GUIAttributes,
    runtimeShip: ShipAPI? = null,
    externalConfirmationModalProvider: (() -> CampaignConfirmationModalRequest?)? = null,
    suppressExternalOptionHover: (() -> Unit)? = null,
    restoreExternalOptionHover: (() -> Unit)? = null,
    onCleared: () -> Unit,
    buildOptionsPanel: (CustomPanelAPI, Boolean, Int, Float?, (Int, Int) -> Unit) -> CampaignOptionsRenderResult,
    optionsPreferredHeightProvider: (Float) -> Float,
    stableOptionsPreferredHeightProvider: (Float) -> Float,
    buildModifiersPanel: (CustomPanelAPI) -> Unit,
    modifiersPreferredHeightProvider: () -> Float,
    beforeBuild: (ShipView) -> Unit = {},
    afterBuild: (ShipView) -> Unit = {},
): ShipEditorShipViewContent {
    var activeSessionState = sessionState.captureFrom(shipView)
    onSessionStateUpdate(activeSessionState)

    val nextContent = replaceCampaignRootContentPanel(
        root = root,
        content = content,
        plugin = createShipEditorShipView(
            tagView = tagView,
            sessionStateProvider = { activeSessionState },
            onSessionStateUpdate = { state ->
                activeSessionState = state
                onSessionStateUpdate(state)
            },
            runtimeShip = runtimeShip,
            externalConfirmationModalProvider = externalConfirmationModalProvider,
            suppressExternalOptionHover = suppressExternalOptionHover,
            restoreExternalOptionHover = restoreExternalOptionHover,
            customListState = customListState,
        ),
        onCleared = onCleared,
    )
    val nextView = nextContent.plugin as? ShipView
    nextView?.let(beforeBuild)
    nextView?.buildIn(
        panel = nextContent,
        ship = ship,
        buildOptionsPanel = buildOptionsPanel,
        optionsPreferredHeightProvider = optionsPreferredHeightProvider,
        stableOptionsPreferredHeightProvider = stableOptionsPreferredHeightProvider,
        buildModifiersPanel = buildModifiersPanel,
        modifiersPreferredHeightProvider = modifiersPreferredHeightProvider,
    )
    nextView?.let(afterBuild)
    return ShipEditorShipViewContent(nextContent, nextView, activeSessionState)
}

fun createShipEditorShipView(
    tagView: TagListView,
    sessionStateProvider: () -> ShipViewSessionState,
    onSessionStateUpdate: (ShipViewSessionState) -> Unit,
    customListState: GUIAttributes,
    runtimeShip: ShipAPI? = null,
    externalConfirmationModalProvider: (() -> CampaignConfirmationModalRequest?)? = null,
    suppressExternalOptionHover: (() -> Unit)? = null,
    restoreExternalOptionHover: (() -> Unit)? = null,
): ShipView {
    val sessionState = sessionStateProvider()
    return ShipView(
        tagView = tagView,
        config = ShipViewConfig(
            enableTagScroll = false,
            drawFrame = false,
            runtimeShip = runtimeShip,
        ),
        initialState = ShipViewInitialState(
            tagScrollOffsets = sessionState.tagScrollOffsets,
            tagExpandedCategoryTitles = sessionState.tagExpandedCategoryTitles,
            optionsScrollOffset = sessionState.optionsScrollOffset,
            shipModeScrollOffset = sessionState.shipModeScrollOffset,
            presetControlStates = sessionState.presetControlStates,
            collapsedPanelIds = sessionState.collapsedPanelIds,
        ),
        presetBindings = ShipViewPresetBindings(
            onPresetControlStateUpdate = { groupIndex, state ->
                onSessionStateUpdate(sessionStateProvider().withPresetControlState(groupIndex, state))
            },
        ),
        externalBindings = ShipViewExternalBindings(
            confirmationModalProvider = externalConfirmationModalProvider,
            suppressOptionHover = suppressExternalOptionHover,
            restoreOptionHover = restoreExternalOptionHover,
        ),
        customListBindings = CustomListModalBindings(
            modalModeProvider = { customListState.customListModalMode },
            onModalModeUpdate = { mode -> customListState.customListModalMode = mode },
            draftDefinitionIdProvider = { customListState.customListDraftDefinitionId },
            onDraftDefinitionIdUpdate = { id -> customListState.customListDraftDefinitionId = id },
            draftValuesProvider = { customListState.customListDraftValues },
            onDraftValuesUpdate = { values ->
                customListState.customListDraftValues = values.toMutableMap()
            },
            editSourceGroupProvider = { customListState.customListEditSourceGroup },
            onEditSourceGroupUpdate = { group -> customListState.customListEditSourceGroup = group },
            editSourceTagProvider = { customListState.customListEditSourceTag },
            onEditSourceTagUpdate = { tag -> customListState.customListEditSourceTag = tag },
            managerScrollOffsetProvider = { customListState.customListManagerScrollOffset },
            onManagerScrollOffsetUpdate = { offset -> customListState.customListManagerScrollOffset = offset },
            changeReviewScrollOffsetProvider = { customListState.customListChangeReviewScrollOffset },
            onChangeReviewScrollOffsetUpdate = { offset ->
                customListState.customListChangeReviewScrollOffset = offset
            },
            markedForRemovalProvider = { customListState.customTagsMarkedForRemoval },
            onMarkedForRemovalUpdate = { tags ->
                customListState.customTagsMarkedForRemoval = tags.toMutableSet()
            },
        ),
    )
}
