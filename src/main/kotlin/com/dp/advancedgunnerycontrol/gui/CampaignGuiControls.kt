package com.dp.advancedgunnerycontrol.gui

import java.awt.Color
import com.dp.advancedgunnerycontrol.utils.invokeMethodByName
import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinitions
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipLocation
import com.fs.starfarer.api.util.Misc
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

data class StyledCampaignButtonShell(
    val panel: CustomPanelAPI,
    val button: ButtonAPI,
)

// Hit testing
fun campaignButtonContainsEvent(button: ButtonAPI, event: InputEventAPI): Boolean {
    if (event.isConsumed) return false
    val position = button.position ?: return false
    if (position.containsEvent(event)) return true
    val x = event.x.toFloat()
    val y = event.y.toFloat()
    return x >= position.x &&
        x <= position.x + position.width &&
        y >= position.y &&
        y <= position.y + position.height
}

fun consumeUnhandledAgcEditorInput(events: MutableList<InputEventAPI>) {
    events.forEach { event ->
        if (!event.isConsumed && (event.isKeyboardEvent || event.isMouseEvent)) {
            event.consume()
        }
    }
}

// Button sounds and suppression
fun playCampaignButtonPressedSound() {
    Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f)
}

fun restoreCampaignButtonSounds(button: ButtonAPI) {
    button.setMouseOverSound("ui_button_mouseover")
    button.setButtonPressedSound("ui_button_pressed")
    button.setButtonDisabledPressedSound("ui_button_disabled_pressed")
}

class CampaignButtonSuppressionSnapshot private constructor(
    private val states: List<State>,
) {
    private data class State(
        val button: ButtonAPI,
        val wasEnabled: Boolean,
    )

    fun restore() {
        states.forEach { state ->
            state.button.isEnabled = state.wasEnabled
            if (state.wasEnabled) {
                restoreCampaignButtonSounds(state.button)
            } else {
                muteCampaignButtonSounds(state.button)
                state.button.setShowTooltipWhileInactive(true)
            }
        }
    }

    companion object {
        fun suppress(controls: Iterable<ButtonBase<*>>): CampaignButtonSuppressionSnapshot {
            controls.forEach { control -> control.syncVisualCheckedToActive() }
            return suppressButtons(controls.map { control -> control.button })
        }

        fun suppressButtons(buttons: Iterable<ButtonAPI>): CampaignButtonSuppressionSnapshot {
            val uniqueButtons = uniqueCampaignButtons(buttons)
            val states = uniqueButtons.map { button ->
                State(button = button, wasEnabled = button.isEnabled)
            }
            uniqueButtons.forEach(::suppressCampaignButtonHover)
            return CampaignButtonSuppressionSnapshot(states)
        }
    }
}

fun Iterable<ButtonBase<*>>.suppressCampaignButtonHoverSnapshot(): CampaignButtonSuppressionSnapshot {
    return CampaignButtonSuppressionSnapshot.suppress(this)
}

fun Iterable<ButtonAPI>.suppressCampaignRawButtonHoverSnapshot(): CampaignButtonSuppressionSnapshot {
    return CampaignButtonSuppressionSnapshot.suppressButtons(this)
}

private fun uniqueCampaignButtons(buttons: Iterable<ButtonAPI>): List<ButtonAPI> {
    val seen = Collections.newSetFromMap(IdentityHashMap<ButtonAPI, Boolean>())
    return buttons.filter { button -> seen.add(button) }
}

fun ButtonBase<*>.applyDisabledCampaignButtonTemplate(
    showTooltipWhileInactive: Boolean = false,
): ButtonBase<*> {
    disable()
    muteCampaignButtonSounds(button)
    button.setShowTooltipWhileInactive(showTooltipWhileInactive)
    return this
}

fun ButtonBase<*>.suppressCampaignButtonHover(): ButtonBase<*> {
    syncVisualCheckedToActive()
    suppressCampaignButtonHover(button)
    return this
}

fun suppressCampaignButtonHover(button: ButtonAPI) {
    button.isEnabled = false
    muteCampaignButtonSounds(button)
    button.setShowTooltipWhileInactive(false)
}

fun ButtonAPI.bindCampaignButtonListener(
    listenerPanel: CustomPanelAPI?,
    narrativeContext: String,
): ButtonAPI {
    setShowTooltipWhileInactive(true)
    listenerPanel?.let {
        invokeMethodByName("setListener", this, it, narrativeContext = narrativeContext)
    }
    return this
}

private val registeredCampaignButtons = mutableListOf<ButtonAPI>()

fun registerCampaignButton(button: ButtonAPI): ButtonAPI {
    registeredCampaignButtons.add(button)
    return button
}

fun clearRegisteredCampaignButtons() {
    registeredCampaignButtons.clear()
}

fun suppressRegisteredCampaignButtonHoverSnapshot(): CampaignButtonSuppressionSnapshot {
    return registeredCampaignButtons.suppressCampaignRawButtonHoverSnapshot()
}

// Legacy checkbox helpers
fun addLegacyAgcTooltipCheckbox(
    tooltip: TooltipMakerAPI,
    label: String?,
    data: Any?,
    tooltipText: String? = null,
    width: Float = 160f,
    height: Float = 18f,
    pad: Float = 3f,
): ButtonAPI {
    val button = tooltip.addAreaCheckbox(
        label,
        data,
        Misc.getBasePlayerColor(),
        Misc.getDarkPlayerColor(),
        Misc.getBrightPlayerColor(),
        width,
        height,
        pad
    )
    registerCampaignButton(button)
    if (!tooltipText.isNullOrBlank()) {
        tooltip.addTooltipToPrevious(
            AGCGUI.makeTooltip(tooltipText),
            TooltipLocation.BELOW
        )
    }
    return button
}

// Toggle visual state
class CampaignToggleVisualState {
    private var visualStateChecked: Boolean? = null

    fun apply(button: ButtonAPI, force: Boolean = false) {
        if (!force && visualStateChecked == button.isChecked) return
        CampaignGuiStyle.applyToggleableCheckboxVisualState(button)
        visualStateChecked = button.isChecked
    }

    fun applyUnavailable(button: ButtonAPI) {
        CampaignGuiStyle.applyUnavailableCheckboxVisualState(button)
        visualStateChecked = null
    }
}

// Button control wrappers
class CampaignMomentaryButton(
    button: ButtonAPI,
    private val callback: () -> Unit,
) : ButtonBase<Unit>(Unit, button, false) {
    override fun executeCallbackIfChecked(): Boolean {
        if (!button.isEnabled) {
            button.isChecked = false
            active = false
            return false
        }
        if (!button.isChecked) return false
        callback()
        button.isChecked = false
        active = false
        return true
    }

    override fun onActivate() {}
}

// Modal button registration
fun MutableList<ButtonBase<*>>.addRenderedConfirmationModalButtons(
    modal: RenderedCampaignConfirmationModal,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    modal.backdropButtons.forEach { backdropButton ->
        addCampaignButtonControl(button = backdropButton) {}
    }
    addCampaignButtonControl(button = modal.confirmButton) { onConfirm() }
    addCampaignButtonControl(button = modal.cancelButton) { onCancel() }
}

fun MutableList<ButtonBase<*>>.clearRenderedConfirmationModalButtons(
    buttonStartIndex: Int,
    buttonEndIndex: Int,
) {
    val start = buttonStartIndex.coerceIn(0, size)
    val end = buttonEndIndex.coerceIn(start, size)
    subList(start, end).clear()
}

fun MutableList<ButtonBase<*>>.addBidirectionalCampaignMomentaryButton(
    button: ButtonAPI,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
): ButtonBase<*> {
    val control = addCampaignButtonControl(button = button) { onLeftClick() }
        .onRightClick { onRightClick() }
    return control
}

class CampaignStateButton(
    button: ButtonAPI,
    initiallyChecked: Boolean,
    private val callback: () -> Unit,
) : ButtonBase<Unit>(Unit, button, false) {
    init {
        setActiveChecked(initiallyChecked)
    }

    override fun executeCallbackIfChecked(): Boolean {
        if (!button.isEnabled) {
            syncButtonCheckedToActive()
            return false
        }
        if (button.isChecked == active) return false
        callback()
        // Action rows rebuild after callbacks; keep the visual state stable for
        // the remainder of this frame so checked rows do not flicker off.
        syncButtonCheckedToActive()
        return true
    }

    override fun onActivate() {}
}

// Button polling
fun MutableList<ButtonBase<*>>.processCampaignButtonCallbacks(): Boolean {
    return processCampaignButtonCallbacksFrom(this)
}

private fun processCampaignButtonCallbacksFrom(buttons: List<ButtonBase<*>>): Boolean {
    val initialSize = buttons.size
    var index = 0
    while (index < initialSize && index < buttons.size) {
        if (buttons[index].executeCallbackIfChecked()) return true
        index++
    }
    return false
}

class CampaignButtonCallbackPoller(
    private val buttons: MutableList<ButtonBase<*>>,
) {
    companion object {
        private const val CLICK_SETTLE_POLL_FRAMES = 3
    }

    private var pollFramesRemaining = CLICK_SETTLE_POLL_FRAMES
    private var targetedPollButtons: List<ButtonBase<*>>? = null

    fun requestPoll() {
        pollFramesRemaining = CLICK_SETTLE_POLL_FRAMES
        targetedPollButtons = null
    }

    fun requestPollFromInput(events: MutableList<InputEventAPI>?) {
        val clickEvents = events?.filter(::isCampaignLeftClickPollEvent).orEmpty()
        if (clickEvents.isEmpty()) return
        val candidates = buttons.filter { button ->
            clickEvents.any(button::containsEvent)
        }
        pollFramesRemaining = CLICK_SETTLE_POLL_FRAMES
        targetedPollButtons = candidates.ifEmpty { null }
    }

    fun clearTargetedPoll() {
        targetedPollButtons = null
    }

    fun processIfRequested(): Boolean {
        if (pollFramesRemaining <= 0) return false
        pollFramesRemaining--
        val handled = processCurrentTarget()
        if (handled || pollFramesRemaining <= 0) {
            targetedPollButtons = null
        }
        return handled
    }

    private fun processCurrentTarget(): Boolean {
        return liveTargetedButtons()
            ?.let(::processCampaignButtonCallbacksFrom)
            ?: buttons.processCampaignButtonCallbacks()
    }

    private fun liveTargetedButtons(): List<ButtonBase<*>>? {
        return targetedPollButtons?.takeIf { candidates ->
            candidates.all { candidate -> buttons.contains(candidate) }
        }
    }

    private fun isCampaignLeftClickPollEvent(event: InputEventAPI): Boolean {
        return !event.isConsumed && (event.isLMBDownEvent || event.isLMBUpEvent)
    }
}

// Right-click dispatch
fun MutableList<ButtonBase<*>>.processCampaignButtonRightClickInput(events: MutableList<InputEventAPI>?): Boolean {
    events?.forEach { event ->
        if (processCampaignButtonRightClickEvent(event)) return true
    }
    return false
}

fun MutableList<ButtonBase<*>>.processCampaignButtonRightClickEvent(event: InputEventAPI): Boolean {
    val initialSize = size
    var index = 0
    while (index < initialSize && index < size) {
        if (this[index].processRightClickEvent(event)) return true
        index++
    }
    return false
}

// Button registration
fun MutableList<ButtonBase<*>>.addCampaignButtonControl(
    button: ButtonAPI,
    active: Boolean = false,
    stateful: Boolean = false,
    callback: () -> Unit,
): ButtonBase<*> {
    button.isChecked = active
    val control = if (stateful) {
        CampaignStateButton(button, active) { callback() }
    } else {
        CampaignMomentaryButton(button) { callback() }
    }
    add(control)
    return control
}

// Modifier panel rendering
const val CAMPAIGN_MODIFIERS_PANEL_HEIGHT = 48f

fun buildCampaignModifiersPanel(panel: CustomPanelAPI, modifiersText: String) {
    val width = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
    val infoPanel = panel.createUIElement(width, CAMPAIGN_MODIFIERS_PANEL_HEIGHT, false)
    infoPanel.addAgcHighlightedText(
        modifiersText,
        0f,
        CampaignGuiStyle.MODIFIER_TEXT_COLOUR,
        "[SHIFT]",
        "[CTRL]"
    )
    panel.addUIElement(infoPanel).inTL(CampaignGuiStyle.PANEL_PADDING, 0f)
}

/**
 * Keeps confirm/cancel flows bound to an exact action key. This avoids the
 * modifier-key bug where a visible confirm row could drift into a different
 * action variant after Shift/Ctrl changed.
 */
class PendingConfirmationState<K : Any> {
    var key: K? = null
        private set

    fun arm(key: K) {
        this.key = key
    }

    fun clear() {
        key = null
    }

    fun clearIfUnavailable(validKeys: Iterable<K>) {
        val pending = key ?: return
        if (validKeys.none { it == pending }) clear()
    }

    fun isPending(candidate: K?): Boolean {
        return candidate != null && key == candidate
    }
}

private fun addRegisteredControlPanel(
    parent: CustomPanelAPI,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    panelType: CampaignPanelType = CampaignPanelType.CONTROL_PANEL,
    fillColor: Color? = null,
    borderColor: Color? = null,
): CustomPanelAPI {
    val itemPanel = parent.createCustomPanel(
        width,
        height,
        CampaignPanelPlugin(
            panelType,
            fillColor = fillColor,
            borderColor = borderColor
        )
    )
    parent.addComponent(itemPanel)
    itemPanel.position.inTL(x, y)
    return itemPanel
}

fun addStyledCampaignButtonShell(
    parent: CustomPanelAPI,
    data: Any,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    colors: CampaignGuiStyle.ButtonStateColors,
    tooltip: String? = null,
    fillIdle: Boolean = true,
    panelType: CampaignPanelType = CampaignPanelType.CONTROL_PANEL,
    fillColorOverride: Color? = null,
    borderColor: Color? = null,
): StyledCampaignButtonShell {
    val itemPanel = addRegisteredControlPanel(
        parent = parent,
        x = x,
        y = y,
        width = width,
        height = height,
        panelType = panelType,
        fillColor = fillColorOverride ?: if (fillIdle) colors.idle else null,
        borderColor = borderColor,
    )

    val inner = itemPanel.createUIElement(width, height, false)
    val checkboxColors = CampaignGuiStyle.checkboxColorsForButton(colors)
    val button = inner.addAreaCheckbox(
        "",
        data,
        checkboxColors.base,
        checkboxColors.bg,
        checkboxColors.bright,
        width,
        height,
        0f
    )
    registerCampaignButton(button)
    if (!tooltip.isNullOrBlank()) {
        inner.addTooltipToPrevious(
            AGCGUI.makeTooltip(tooltip),
            TooltipLocation.BELOW
        )
    }
    itemPanel.addUIElement(inner).inTL(CampaignGuiStyle.ITEM_HIGHLIGHT_X_OFFSET, 0f)
    return StyledCampaignButtonShell(itemPanel, button)
}

fun addCampaignActionLabel(
    panel: TooltipMakerAPI,
    labelText: String,
    highlightTokens: List<String>,
    textColor: Color = CampaignGuiStyle.DEFAULT_TEXT_COLOUR,
) {
    val label = panel.addAgcText(labelText, 0f, textColor)
    val highlights = highlightTokens.filter { labelText.contains(it) }
    if (highlights.isNotEmpty()) {
        label.setHighlight(*highlights.toTypedArray())
        label.setHighlightColors(*Array(highlights.size) { CampaignGuiStyle.MODIFIER_TEXT_COLOUR })
    }
}

fun hiddenChangeHighlightTokens(labelText: String, baseTokens: List<String> = emptyList()): List<String> {
    if (!labelText.startsWith("*")) return baseTokens
    if ("*" in baseTokens) return baseTokens
    return listOf("*") + baseTokens
}

fun addStyledCampaignActionRow(
    parent: CustomPanelAPI,
    data: Any,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    kind: CampaignActionButtonKind,
    labelText: String,
    highlightTokens: List<String>,
    tooltip: String? = null,
    textPadding: Float = CampaignGuiStyle.ACTION_ROW_PADDING,
    textColor: Color = CampaignGuiStyle.DEFAULT_TEXT_COLOUR,
    centerConfirmCancelText: Boolean = true,
    centerText: Boolean = false,
): StyledCampaignButtonShell {
    val shell = addStyledCampaignButtonShell(
        parent = parent,
        data = data,
        x = x,
        y = y,
        width = width,
        height = height,
        colors = CampaignGuiStyle.colorsForActionButton(kind),
        tooltip = tooltip,
        fillIdle = CampaignGuiStyle.shouldFillActionButtonIdle(kind)
    )
    if (centerText || (centerConfirmCancelText && (kind == CampaignActionButtonKind.CONFIRM || kind == CampaignActionButtonKind.CANCEL))) {
        renderCenteredControlLabel(
            panel = shell.panel,
            text = labelText,
            width = width,
            height = height - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
            centerRegionOffsetX = CampaignGuiStyle.ITEM_HIGHLIGHT_X_OFFSET,
            centerRegionWidth = width,
            textColor = textColor,
        )
    } else {
        val textPanel = shell.panel.createUIElement(
            width - 2f * textPadding,
            height - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
            false
        )
        addCampaignActionLabel(textPanel, labelText, highlightTokens, textColor)
        shell.panel.addUIElement(textPanel).inTL(textPadding, CampaignGuiStyle.ITEM_TEXT_TOP_PADDING)
    }
    return shell
}

fun addTemplatedCampaignActionRow(
    parent: CustomPanelAPI,
    data: Any,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    template: CampaignGuiStyle.ActionButtonTemplate,
    labelText: String,
    highlightTokens: List<String>,
    tooltip: String? = null,
    textPadding: Float = CampaignGuiStyle.ACTION_ROW_PADDING,
    centerConfirmCancelText: Boolean = true,
    centerText: Boolean = false,
): StyledCampaignButtonShell {
    return addStyledCampaignActionRow(
        parent = parent,
        data = data,
        x = x,
        y = y,
        width = width,
        height = height,
        kind = template.kind,
        labelText = labelText,
        highlightTokens = highlightTokens,
        tooltip = tooltip,
        textPadding = textPadding,
        textColor = template.textColor,
        centerConfirmCancelText = centerConfirmCancelText,
        centerText = centerText,
    )
}

fun addTemplatedCampaignMomentaryActionButton(
    parent: CustomPanelAPI,
    data: Any,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    template: CampaignGuiStyle.ActionButtonTemplate,
    labelText: String,
    highlightTokens: List<String> = emptyList(),
    tooltip: String? = null,
    textPadding: Float = CampaignGuiStyle.ACTION_ROW_PADDING,
    showTooltipWhileInactive: Boolean = false,
    centerConfirmCancelText: Boolean = true,
    centerText: Boolean = false,
    callback: () -> Unit,
): CampaignMomentaryButton {
    val shell = addTemplatedCampaignActionRow(
        parent = parent,
        data = data,
        x = x,
        y = y,
        width = width,
        height = height,
        template = template,
        labelText = labelText,
        highlightTokens = highlightTokens,
        tooltip = tooltip,
        textPadding = textPadding,
        centerConfirmCancelText = centerConfirmCancelText,
        centerText = centerText,
    )
    val button = CampaignMomentaryButton(shell.button) {
        if (template.enabled) {
            callback()
        }
    }
    if (!template.enabled) {
        button.applyDisabledCampaignButtonTemplate(showTooltipWhileInactive)
    }
    return button
}

fun addCampaignTagModeToggleShell(
    parent: CustomPanelAPI,
    data: Any,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    tooltip: String,
    unavailable: Boolean = false,
    fillIdle: Boolean = false,
    fillSelected: Boolean = false,
): StyledCampaignButtonShell {
    val colors = when {
        unavailable -> CampaignGuiStyle.DISABLED_BUTTON_COLORS
        fillSelected -> CampaignGuiStyle.ACTIVE_WEAPON_TAG_SHIP_MODE_BUTTON_COLORS
        else -> CampaignGuiStyle.UNCOLOURED_BUTTON_COLORS
    }
    val shell = addStyledCampaignButtonShell(
        parent = parent,
        data = data,
        x = x,
        y = y,
        width = width,
        height = height,
        colors = colors,
        tooltip = tooltip,
        fillIdle = fillIdle || fillSelected || unavailable,
        fillColorOverride = CampaignGuiStyle.DISABLED_TAG_BACKGROUND_COLOR.takeIf { unavailable },
    )
    if (unavailable) {
        shell.button.isEnabled = false
        shell.button.setShowTooltipWhileInactive(true)
        muteCampaignButtonSounds(shell.button)
        CampaignGuiStyle.applyUnavailableCheckboxVisualState(shell.button)
    }
    return shell
}

fun addCampaignTagToggleButton(
    parent: CustomPanelAPI,
    data: Any,
    tag: String,
    index: Int,
    metrics: WrapGridMetrics,
    tooltip: String,
    unavailable: Boolean = false,
    selected: Boolean = false,
): StyledCampaignButtonShell {
    val labelWidth = metrics.itemWidth - 2f * CampaignGuiStyle.ITEM_TEXT_HORIZONTAL_PADDING
    val displayTag = EditableWeaponTagDefinitions.displayName(tag)
    val labelText = computeTextFitLayout(
        text = displayTag,
        availableWidth = labelWidth,
        availableHeight = metrics.itemHeight - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
        minRowHeight = metrics.itemHeight,
        horizontalPadding = 0f,
        verticalPadding = 0f,
        maxLines = 1,
        canGrowHeight = false
    ).wrappedText
    val shell = addCampaignTagModeToggleShell(
        parent = parent,
        data = data,
        x = metrics.xFor(index),
        y = metrics.yFor(index),
        width = metrics.itemWidth,
        height = metrics.itemHeight,
        tooltip = tooltip,
        unavailable = unavailable,
        fillIdle = !selected && !unavailable,
        fillSelected = selected
    )
    renderCenteredControlLabel(
        panel = shell.panel,
        text = labelText,
        width = metrics.itemWidth,
        height = metrics.itemHeight - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
        centerRegionOffsetX = CampaignGuiStyle.ITEM_HIGHLIGHT_X_OFFSET,
        centerRegionWidth = metrics.itemWidth,
        textColor = if (unavailable) CampaignGuiStyle.DISABLED_TAG_TEXT_COLOR else null,
        highlightTokens = hiddenChangeHighlightTokens(labelText),
    )
    return shell
}

fun addTagScrollIndicatorButton(
    parent: CustomPanelAPI,
    top: Float,
    symbol: String,
    data: Any,
    height: Float = CampaignGuiStyle.TAG_ITEM_HEIGHT,
    x: Float = 0f,
    width: Float = parent.position.width,
): StyledCampaignButtonShell {
    val shell = addStyledCampaignButtonShell(
        parent = parent,
        data = data,
        x = x,
        y = top,
        width = width,
        height = height,
        colors = CampaignGuiStyle.UNCOLOURED_BUTTON_COLORS,
        fillIdle = false
    )
    renderCenteredControlLabel(
        panel = shell.panel,
        text = symbol,
        width = width,
        height = height - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
        centerRegionOffsetX = CampaignGuiStyle.ITEM_HIGHLIGHT_X_OFFSET,
    )
    return shell
}

fun addTagScrollIndicatorMomentaryButton(
    parent: CustomPanelAPI,
    top: Float,
    symbol: String,
    data: Any,
    height: Float = CampaignGuiStyle.TAG_ITEM_HEIGHT,
    x: Float = 0f,
    width: Float = parent.position.width,
    callback: () -> Unit,
): Pair<StyledCampaignButtonShell, CampaignMomentaryButton> {
    val shell = addTagScrollIndicatorButton(
        parent = parent,
        top = top,
        symbol = symbol,
        data = data,
        height = height,
        x = x,
        width = width,
    )
    return shell to CampaignMomentaryButton(shell.button) { callback() }
}

fun renderTagLabel(
    panel: com.fs.starfarer.api.ui.CustomPanelAPI,
    text: String,
    width: Float,
    height: Float,
    x: Float,
    y: Float,
    textColor: Color? = null,
    alignment: Alignment? = null,
    highlightTokens: List<String> = emptyList(),
) {
    val element = panel.createUIElement(width, height, false)
    val label = element.addAgcText(text, 0f, textColor ?: CampaignGuiStyle.DEFAULT_TEXT_COLOUR)
    val highlights = highlightTokens.filter { text.contains(it) }
    if (highlights.isNotEmpty()) {
        label.setHighlight(*highlights.toTypedArray())
        label.setHighlightColors(*Array(highlights.size) { CampaignGuiStyle.MODIFIER_TEXT_COLOUR })
    }
    alignment?.let(label::setAlignment)
    panel.addUIElement(element).inTL(x, y)
}

/**
 * Shared label-centering path for AGC-owned button overlays. The fitter decides
 * what text can fit, while LabelAPI alignment handles centering inside the true
 * highlighted/control region. This avoids tuning x-offsets by eye per button.
 */
fun renderCenteredControlLabel(
    panel: CustomPanelAPI,
    text: String,
    width: Float,
    height: Float,
    top: Float = CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
    centerRegionOffsetX: Float = 0f,
    centerRegionWidth: Float = width,
    horizontalPadding: Float = CampaignGuiStyle.ITEM_TEXT_HORIZONTAL_PADDING,
    approxCharWidthPx: Float = CampaignGuiStyle.CENTERED_LABEL_CHAR_WIDTH_ESTIMATE,
    textColor: Color? = null,
    highlightTokens: List<String> = hiddenChangeHighlightTokens(text),
) {
    val fitted = computeTextFitLayout(
        text = text,
        availableWidth = centerRegionWidth,
        availableHeight = height,
        minRowHeight = height,
        horizontalPadding = 2f * horizontalPadding,
        verticalPadding = 0f,
        approxCharWidthPx = approxCharWidthPx,
        maxLines = 1,
        canGrowHeight = false
    )
    // Negative checkbox offsets expand the glow left of the panel; labels need
    // the clipped visible region or they read left-biased in game.
    val visibleRegionLeft = max(0f, centerRegionOffsetX)
    val left = visibleRegionLeft + horizontalPadding
    val renderWidth = min(
        centerRegionWidth - 2f * horizontalPadding,
        width - left - horizontalPadding
    ).coerceAtLeast(16f)
    renderTagLabel(
        panel = panel,
        text = fitted.wrappedText,
        width = renderWidth,
        height = height,
        x = left,
        y = top,
        textColor = textColor,
        alignment = Alignment.MID,
        highlightTokens = hiddenChangeHighlightTokens(fitted.wrappedText, highlightTokens)
    )
}

fun addCampaignPanelHeading(
    panel: CustomPanelAPI,
    title: String,
    top: Float = CampaignGuiStyle.PANEL_PADDING,
    horizontalPadding: Float = CampaignGuiStyle.PANEL_PADDING,
    fillColor: Color = CampaignGuiStyle.PANEL_HEADING_COLOUR,
    textColor: Color? = null,
    headingHeight: Float = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT,
): CustomPanelAPI {
    val headerPanel = panel.createCustomPanel(
        panel.position.width - 2f * horizontalPadding,
        headingHeight,
        CampaignPanelPlugin(CampaignPanelType.PANEL_HEADING, fillColor = fillColor)
    )
    panel.addComponent(headerPanel)
    headerPanel.position.inTL(horizontalPadding, top)

    val estimatedLabelWidth = title.length * CampaignGuiStyle.HEADING_CHAR_WIDTH_ESTIMATE
    val left = ((headerPanel.position.width - estimatedLabelWidth) / 2f).coerceAtLeast(CampaignGuiStyle.ITEM_TEXT_HORIZONTAL_PADDING)
    val width = (headerPanel.position.width - left - CampaignGuiStyle.ITEM_TEXT_HORIZONTAL_PADDING).coerceAtLeast(16f)
    renderTagLabel(
        headerPanel,
        title,
        width,
        headingHeight - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
        left,
        CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
        textColor = textColor
    )
    return headerPanel
}

fun addCollapsibleCampaignPanelHeading(
    panel: CustomPanelAPI,
    title: String,
    collapsed: Boolean,
    data: Any,
    top: Float = CampaignGuiStyle.PANEL_PADDING,
    fillColor: Color = CampaignGuiStyle.COLLAPSIBLE_HEADING_COLOUR,
    textColor: Color? = null,
    headingHeight: Float = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT,
    statusSuffix: String = "",
): StyledCampaignButtonShell {
    val headerWidth = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
    val headerPanel = panel.createCustomPanel(
        headerWidth,
        headingHeight,
        CampaignPanelPlugin(CampaignPanelType.PANEL_HEADING, fillColor = fillColor)
    )
    panel.addComponent(headerPanel)
    headerPanel.position.inTL(CampaignGuiStyle.PANEL_PADDING, top)

    val element = headerPanel.createUIElement(headerWidth, headingHeight, false)
    val colors = CampaignGuiStyle.checkboxColorsForButton(CampaignGuiStyle.sameColorButtonState(fillColor))
    val button = element.addAreaCheckbox(
        "",
        data,
        colors.base,
        colors.bg,
        colors.bright,
        headerWidth,
        headingHeight,
        0f
    )
    registerCampaignButton(button)
    element.addTooltipToPrevious(
        AGCGUI.makeTooltip("${if (collapsed) "Expand" else "Collapse"} $title panel."),
        TooltipLocation.BELOW
    )
    headerPanel.addUIElement(element).inTL(CampaignGuiStyle.ITEM_HIGHLIGHT_X_OFFSET, 0f)
    renderCenteredControlLabel(
        panel = headerPanel,
        text = "$title (${if (collapsed) "+" else "-"})$statusSuffix",
        width = headerWidth,
        height = headingHeight - CampaignGuiStyle.ITEM_TEXT_TOP_PADDING,
        centerRegionOffsetX = CampaignGuiStyle.ITEM_HIGHLIGHT_X_OFFSET,
        centerRegionWidth = headerWidth,
        textColor = textColor,
    )
    return StyledCampaignButtonShell(headerPanel, button)
}

fun updatePresetControlStateMap(
    currentStates: Map<Int, PresetControlState>,
    groupIndex: Int,
    state: PresetControlState,
): Map<Int, PresetControlState> {
    val updated = currentStates.toMutableMap()
    if (state == PresetControlState()) {
        updated.remove(groupIndex)
    } else {
        updated[groupIndex] = state
    }
    return updated
}
