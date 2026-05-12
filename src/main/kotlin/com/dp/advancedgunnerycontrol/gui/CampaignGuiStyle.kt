package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.typesandvalues.Values
import java.awt.Color
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.util.Misc
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils
import org.lwjgl.input.Keyboard
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min

private val NEUTRAL_UI_BORDER_COLOR: Color = Color(200, 200, 200)

enum class CampaignBorderMode {
    NONE,
    FULL,
    SIDES,
    SIDES_AND_BOTTOM,
}

enum class CampaignPanelType(val outlineColor: Color) {
    SHIP_WEAPON_GROUPS_PANEL(Color(0, 220, 120)), // debug outline: bright green
    LEFT_COLUMN_PANEL(Color.GRAY), // debug outline: gray
    WEAPON_GROUPS_PANEL(Color(70, 220, 220)), // debug outline: cyan
    WEAPON_GROUP_PANEL(Color.GRAY), // debug outline: gray
    WEAPON_PANEL(Color(255, 220, 120)), // debug outline: pale gold
    WEAPON_ENTRY_PANEL(Color(255, 190, 90)), // debug outline: orange gold
    WEAPON_GROUP_TAG_LIST_PANEL(Color(160, 255, 180)), // debug outline: pale green
    SUGGESTED_WEAPON_PANELS_PANEL(Color(70, 220, 220)), // debug outline: cyan
    SUGGESTED_WEAPON_PANEL(Color.GRAY), // debug outline: gray
    SUGGESTED_TAG_LIST_PANEL(Color(160, 255, 180)), // debug outline: pale green
    SHIP_MODES_PANEL(Color(90, 150, 255)), // debug outline: sky blue
    CONTROL_PANEL(NEUTRAL_UI_BORDER_COLOR), // neutral outline
    PANEL_HEADING(Color.GRAY), // debug outline: gray
    SHIP_PANEL(Color(190, 110, 255)), // debug outline: purple
    OPTIONS_PANEL(Color(255, 110, 110)), // debug outline: salmon
}

enum class CampaignActionButtonKind {
    UNCOLOURED,
    SAVE,
    LOAD,
    CONFIRM,
    CANCEL,
    ACTIVE,
    FILTER_CATEGORY,
    FILTER_CATEGORY_ACTIVE,
    SUGGESTED_FILTER_ACTIVE,
    TAG_TOP_LEVEL_SECTION,
    TAG_CATEGORY,
    TAG_CATEGORY_ACTIVE,
    ADD_TAG,
    DISABLED,
}

val campaignBorderModeByType = mapOf(
    CampaignPanelType.CONTROL_PANEL to CampaignBorderMode.FULL,
    CampaignPanelType.PANEL_HEADING to CampaignBorderMode.FULL,
    CampaignPanelType.LEFT_COLUMN_PANEL to CampaignBorderMode.SIDES_AND_BOTTOM,
    CampaignPanelType.WEAPON_GROUP_PANEL to CampaignBorderMode.SIDES,
    CampaignPanelType.SUGGESTED_WEAPON_PANEL to CampaignBorderMode.SIDES,
).withDefault { CampaignBorderMode.NONE }

object CampaignGuiStyle {
    private val log = Global.getLogger(CampaignGuiStyle::class.java)
    private const val CHECKED_IDLE_DIM_MULT = 0.55f

    // Debug-color and button-template models
    data class DebugColorTarget(
        val key: String,
        val label: String,
        val defaultColor: Color,
    )
    data class CheckboxColors(
        val base: Color,
        val bg: Color,
        val bright: Color,
    )
    data class ButtonStateColors(
        val idle: Color,
        val hover: Color,
    )
    data class ActionButtonTemplate(
        val kind: CampaignActionButtonKind,
        val textColor: Color,
        val enabled: Boolean,
    )

    // Core text and panel colors
    val DEFAULT_TEXT_COLOUR: Color = Color(220, 220, 220) // light gray
    val MODIFIER_TEXT_COLOUR: Color = Misc.getHighlightColor()
    val DEFAULT_TEXT_FONT: String? = null
    val TOOLTIP_TEXT_FONT: String? = null
    val BLACK_PANEL_FILL_COLOR: Color = Color(0, 0, 0, 225)
    val TRANSPARENT_PANEL_FILL_COLOR: Color = Color(0, 0, 0, 0)
    val MODAL_BACKDROP_FILL_COLOR: Color = Color(0, 0, 0, 175)
    val MODAL_DIALOG_FILL_COLOR: Color = Color(38, 38, 38, 245)
    val WHITE_PANEL_BORDER_COLOR: Color = NEUTRAL_UI_BORDER_COLOR

    val TOOLTIP_TEXT_COLOR: Color = Color(245, 230, 150) // pale gold
    val ALERT_RED_COLOR: Color get() = debugColor(ALERT_RED_COLOR_KEY, Color(245, 95, 85, 240)) // alert red
    val DISABLED_TAG_BACKGROUND_COLOR: Color get() = debugColor(DISABLED_TAG_BACKGROUND_COLOR_KEY, Color(28, 28, 28, 235)) // charcoal
    val DISABLED_TAG_DARK_COLOR: Color get() = debugColor(DISABLED_TAG_DARK_COLOR_KEY, Color(18, 18, 18, 235)) // near black
    val DISABLED_TAG_BRIGHT_COLOR: Color get() = ALERT_RED_COLOR // alert red
    val DISABLED_TAG_TEXT_COLOR: Color = Color(120, 120, 120) // medium gray
    val DISABLED_TAG_BORDER_COLOR: Color = Color(95, 95, 95) // dark gray
    val SAVE_BUTTON_IDLE_COLOR: Color get() = debugColor(SAVE_BUTTON_IDLE_COLOR_KEY, Color(200, 170, 225, 225)) // save/apply purple
    val SAVE_BUTTON_HOVER_COLOR: Color get() = SAVE_BUTTON_IDLE_COLOR
    val LOAD_BUTTON_IDLE_COLOR: Color get() = debugColor(LOAD_BUTTON_IDLE_COLOR_KEY, Color(205, 190, 105, 225)) // load/delete yellow
    val LOAD_BUTTON_HOVER_COLOR: Color get() = LOAD_BUTTON_IDLE_COLOR
    val CONFIRM_BUTTON_IDLE_COLOR: Color get() = debugColor(CONFIRM_BUTTON_IDLE_COLOR_KEY, Color(160, 225, 135, 225)) // confirm green
    val CONFIRM_BUTTON_HOVER_COLOR: Color get() = CONFIRM_BUTTON_IDLE_COLOR
    val EXTERNAL_TOGGLE_ACTIVE_IDLE_COLOR: Color get() = CONFIRM_BUTTON_IDLE_COLOR // muted green
    val EXTERNAL_TOGGLE_ACTIVE_HOVER_COLOR: Color get() = EXTERNAL_TOGGLE_ACTIVE_IDLE_COLOR
    val CANCEL_BUTTON_IDLE_COLOR: Color get() = debugColor(CANCEL_BUTTON_IDLE_COLOR_KEY, Color(225, 120, 120, 225)) // cancel red
    val CANCEL_BUTTON_HOVER_COLOR: Color get() = CANCEL_BUTTON_IDLE_COLOR
    val UNCOLOURED_BUTTON_IDLE_COLOR: Color get() = debugColor(UNCOLOURED_BUTTON_IDLE_COLOR_KEY, Color(120, 120, 120, 225)) // neutral gray
    val UNCOLOURED_BUTTON_HOVER_COLOR: Color get() = UNCOLOURED_BUTTON_IDLE_COLOR
    val PRESET_SCOPE_BUTTON_IDLE_COLOR: Color get() = debugColor(PRESET_SCOPE_BUTTON_IDLE_COLOR_KEY, Color(225, 155, 80, 225)) // preset orange
    val PRESET_SCOPE_BUTTON_HOVER_COLOR: Color get() = PRESET_SCOPE_BUTTON_IDLE_COLOR

    // Tag, ship-mode, and filter colors
    val INACTIVE_WEAPON_TAG_SHIP_MODE_COLOR: Color get() = UNCOLOURED_BUTTON_IDLE_COLOR
    val INACTIVE_WEAPON_TAG_SHIP_MODE_HOVER_COLOR: Color get() = UNCOLOURED_BUTTON_HOVER_COLOR
    val ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR: Color get() = debugColor(ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR_KEY, Color(160, 225, 135, 225)) // active green
    val ACTIVE_WEAPON_TAG_SHIP_MODE_HOVER_COLOR: Color get() = ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR
    val ACTIVE_WEAPON_TAG_SHIP_MODE_RENDERED_COLOR: Color get() = artificiallyDarkenedIdleColor(ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR)
    val ACTIVE_FILTER_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(
        ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR,
        ACTIVE_WEAPON_TAG_SHIP_MODE_HOVER_COLOR
    )
    val ACTIVE_WEAPON_TAG_SHIP_MODE_BUTTON_COLORS: ButtonStateColors get() = ACTIVE_FILTER_BUTTON_COLORS

    val PANEL_HEADING_COLOUR: Color get() = debugColor(PANEL_HEADING_COLOUR_KEY, Color(40, 40, 40, 225)) // dark gray
    val STALE_WEAPON_GROUP_PANEL_HEADING_COLOUR: Color get() = debugColor(STALE_WEAPON_GROUP_PANEL_HEADING_COLOUR_KEY, Color(130, 130, 130, 225)) // stale gray
    val COLLAPSIBLE_HEADING_COLOUR: Color get() = debugColor(COLLAPSIBLE_HEADING_COLOUR_KEY, Color(200, 200, 200, 225)) // collapsible heading
    val UNSAVED_COLLAPSIBLE_HEADING_COLOUR: Color get() = debugColor(UNSAVED_COLLAPSIBLE_HEADING_COLOUR_KEY, Color(200, 200, 200, 225)) // unsaved heading
    val FILTER_CATEGORY_INACTIVE_COLOR: Color get() = debugColor(SUGGESTED_FILTER_HEADING_COLOR_KEY, Color(225, 155, 80, 225)) // suggested filter heading
    val SUGGESTED_FILTER_ACTIVE_COLOR: Color get() = debugColor(SUGGESTED_FILTER_ACTIVE_COLOR_KEY, Color(160, 225, 135, 225)) // active suggested filter
    val TOP_LEVEL_TAG_SHIP_MODE_HEADING_COLOR: Color get() = debugColor(TOP_LEVEL_TAG_SHIP_MODE_HEADING_COLOR_KEY, Color(200, 200, 200, 225)) // top-level tag/mode heading
    val SPECIFIC_TAG_SHIP_MODE_HEADING_COLOR: Color get() = debugColor(SPECIFIC_TAG_SHIP_MODE_HEADING_COLOR_KEY, Color(80, 150, 220, 225)) // tag category heading
    const val CHILD_ROW_INDENT: Float = 28.5f
    const val FILTER_CHILD_ROW_INDENT: Float = CHILD_ROW_INDENT
    fun sameColorButtonState(color: Color): ButtonStateColors = ButtonStateColors(color, color)

    val FILTER_CATEGORY_BUTTON_COLORS: ButtonStateColors get() = sameColorButtonState(FILTER_CATEGORY_INACTIVE_COLOR)
    val FILTER_CATEGORY_ACTIVE_BUTTON_COLORS: ButtonStateColors get() = FILTER_CATEGORY_BUTTON_COLORS
    val SUGGESTED_FILTER_ACTIVE_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(
        SUGGESTED_FILTER_ACTIVE_COLOR,
        SUGGESTED_FILTER_ACTIVE_COLOR
    )
    val TAG_TOP_LEVEL_SECTION_BUTTON_COLORS: ButtonStateColors get() = sameColorButtonState(TOP_LEVEL_TAG_SHIP_MODE_HEADING_COLOR)
    val TAG_CATEGORY_BUTTON_COLORS: ButtonStateColors get() = sameColorButtonState(SPECIFIC_TAG_SHIP_MODE_HEADING_COLOR)
    val TAG_CATEGORY_ACTIVE_BUTTON_COLORS: ButtonStateColors get() = TAG_CATEGORY_BUTTON_COLORS
    val ADD_TAG_BUTTON_COLORS: ButtonStateColors get() = sameColorButtonState(STALE_WEAPON_GROUP_PANEL_HEADING_COLOUR)
    val MODAL_COMPONENT_ROW_FILL_COLOR: Color get() = BLACK_PANEL_FILL_COLOR
    val MODAL_COMPONENT_BORDER_COLOR: Color get() = NEUTRAL_UI_BORDER_COLOR
    val MODAL_SCROLL_LIST_FILL_COLOR: Color get() = BLACK_PANEL_FILL_COLOR
    val MODAL_SCROLL_LIST_BORDER_COLOR: Color get() = NEUTRAL_UI_BORDER_COLOR
    val MODAL_REVIEW_LIST_FILL_COLOR: Color get() = MODAL_DIALOG_FILL_COLOR
    val MODAL_COMPONENT_BUTTON_COLORS: ButtonStateColors get() = sameColorButtonState(DEFAULT_TEXT_COLOUR)

    // Derived action-button color families
    val SAVE_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(SAVE_BUTTON_IDLE_COLOR, SAVE_BUTTON_HOVER_COLOR)
    val LOAD_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(LOAD_BUTTON_IDLE_COLOR, LOAD_BUTTON_HOVER_COLOR)
    val CONFIRM_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(CONFIRM_BUTTON_IDLE_COLOR, CONFIRM_BUTTON_HOVER_COLOR)
    val EXTERNAL_TOGGLE_ACTIVE_COLORS: ButtonStateColors get() = ButtonStateColors(EXTERNAL_TOGGLE_ACTIVE_IDLE_COLOR, EXTERNAL_TOGGLE_ACTIVE_HOVER_COLOR)
    val CANCEL_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(CANCEL_BUTTON_IDLE_COLOR, CANCEL_BUTTON_HOVER_COLOR)
    val UNCOLOURED_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(UNCOLOURED_BUTTON_IDLE_COLOR, UNCOLOURED_BUTTON_HOVER_COLOR)
    val PRESET_SCOPE_BUTTON_COLORS: ButtonStateColors get() = ButtonStateColors(PRESET_SCOPE_BUTTON_IDLE_COLOR, PRESET_SCOPE_BUTTON_HOVER_COLOR)
    val DISABLED_BUTTON_COLORS: ButtonStateColors get() = sameColorButtonState(DISABLED_TAG_BACKGROUND_COLOR)
    val PRESET_TOGGLE_INACTIVE_COLORS: ButtonStateColors get() = UNCOLOURED_BUTTON_COLORS

    // Shared layout constants
    const val MAIN_PADDING = 0f
    const val PANEL_PADDING = 4f
    const val TAG_ITEM_HEIGHT = 20f
    const val TAG_ITEM_HGAP = 0f
    const val TAG_ITEM_VGAP = 2f // Dense weapon-tag grids use tighter spacing to keep seven columns readable.
    const val TAG_ITEM_MIN_WIDTH = 96f
    const val SHIP_MODE_ITEM_HEIGHT = 20f
    const val SHIP_MODE_ITEM_HGAP = 0f
    const val SHIP_MODE_ITEM_VGAP = 4f // Single-column ship-mode rows get modal-style breathing room.
    const val SHIP_MODE_ITEM_MIN_WIDTH = 78f
    const val SHIP_MODE_COLUMN_COUNT = 1
    const val ITEM_TEXT_HORIZONTAL_PADDING = 4f
    const val ITEM_TEXT_TOP_PADDING = 2f
    const val ITEM_HIGHLIGHT_X_OFFSET = -3f
    const val CENTERED_LABEL_CHAR_WIDTH_ESTIMATE = 6.8f
    const val SHIP_MODE_LABEL_CHAR_WIDTH_ESTIMATE = 5.8f
    const val HEADING_CHAR_WIDTH_ESTIMATE = 7.2f
    const val WEAPON_ENTRY_TOOLTIP_WIDTH = 390f
    const val STANDARD_TOOLTIP_WIDTH = WEAPON_ENTRY_TOOLTIP_WIDTH
    const val SUGGESTED_FILTER_INFO_HEIGHT = 42f
    const val WEAPON_TAG_SCROLL_STEP = 2
    const val SUGGESTED_FILTER_SCROLL_STEP = 3
    const val SCROLL_INDICATOR_ABOVE = "^     ^     ^     ^     ^"
    const val SCROLL_INDICATOR_BELOW = "v     v     v     v     v"
    const val GUI_PERF_LOG_THRESHOLD_MS = 25L
    const val ACTION_ROW_GAP = 2f // Dense option/action panels match tag-list tightness.
    const val ACTION_ROW_PADDING = 4f
    const val ACTION_LABEL_APPROX_CHAR_WIDTH = 6.4f
    const val ACTION_LABEL_LINE_HEIGHT = 15f
    const val MODAL_BODY_LINE_HEIGHT = 18f
    const val CONTAINER_HEADING_HEIGHT = 20f
    const val ACTION_LABEL_MAX_LINES = 2
    const val SUGGESTED_ACTION_LABEL_MAX_LINES = 2
    const val MODAL_PADDING = 14f
    const val MODAL_ROW_HEIGHT = 22f
    const val MODAL_ROW_GAP = 4f
    const val MODAL_SECTION_GAP = MODAL_ROW_HEIGHT / 2f
    const val MODAL_TIGHT_GAP = MODAL_ROW_GAP
    const val MODAL_RELATED_CONTROL_GAP = MODAL_ROW_GAP
    const val MODAL_TEXT_COMPONENT_GAP = 2f * MODAL_ROW_GAP
    const val MODAL_BODY_CONTROL_GAP = MODAL_TEXT_COMPONENT_GAP
    const val MODAL_TITLE_BODY_GAP = MODAL_TEXT_COMPONENT_GAP
    const val MODAL_SECTION_BREAK_GAP = MODAL_TITLE_BODY_GAP
    const val MODAL_BODY_ACTION_GAP = MODAL_BODY_CONTROL_GAP
    const val MODAL_COMPONENT_HORIZONTAL_INSET = ITEM_TEXT_HORIZONTAL_PADDING
    const val MODAL_FOOTER_HORIZONTAL_INSET = MODAL_COMPONENT_HORIZONTAL_INSET
    const val MODAL_MAX_HEIGHT_FRACTION = 0.8f
    const val MODAL_BUTTON_WIDTH = 120f
    const val MODAL_HEADING_HEIGHT = 30f
    // Modal dimensions
    // Keep edit-tag style labelled components close to a 50/50 label/value split.
    const val EDIT_TAG_LABEL_WIDTH = 248f
    const val EDIT_TAG_VALUE_GAP = 8f
    const val EDIT_TAG_VALUE_WIDTH = 248f
    const val EDIT_TAG_MODAL_WIDTH = 2f * MODAL_PADDING + EDIT_TAG_LABEL_WIDTH + EDIT_TAG_VALUE_GAP + EDIT_TAG_VALUE_WIDTH
    const val ROOT_CONTENT_MIN_WIDTH = 300f
    const val ROOT_CONTENT_MIN_HEIGHT = 300f
    const val ROOT_CONTENT_MIN_HEIGHT_FRACTION = 0.8f
    fun maxModalHeight(screenHeight: Float, minimum: Float = MODAL_ROW_HEIGHT): Float {
        return min(
            screenHeight * MODAL_MAX_HEIGHT_FRACTION,
            screenHeight - 2f * PANEL_PADDING
        ).coerceAtLeast(minimum)
    }
    fun modalHeightForBody(
        bodyHeight: Float,
        headingHeight: Float = MODAL_HEADING_HEIGHT,
        footerHeight: Float = MODAL_ROW_HEIGHT,
        titleBodyGap: Float = MODAL_TITLE_BODY_GAP,
        bodyActionGap: Float = MODAL_BODY_ACTION_GAP,
    ): Float {
        return MODAL_PADDING +
            headingHeight +
            titleBodyGap +
            bodyHeight +
            bodyActionGap +
            footerHeight +
            MODAL_PADDING
    }
    fun modalFixedHeightExcludingBody(
        headingHeight: Float = MODAL_HEADING_HEIGHT,
        footerHeight: Float = MODAL_ROW_HEIGHT,
        titleBodyGap: Float = MODAL_TITLE_BODY_GAP,
        bodyActionGap: Float = MODAL_BODY_ACTION_GAP,
    ): Float {
        return modalHeightForBody(
            bodyHeight = 0f,
            headingHeight = headingHeight,
            footerHeight = footerHeight,
            titleBodyGap = titleBodyGap,
            bodyActionGap = bodyActionGap,
        )
    }
    fun modalBodyHeightForDialog(
        dialogHeight: Float,
        headingHeight: Float = MODAL_HEADING_HEIGHT,
        footerHeight: Float = MODAL_ROW_HEIGHT,
        minimumBodyHeight: Float = MODAL_ROW_HEIGHT,
        titleBodyGap: Float = MODAL_TITLE_BODY_GAP,
        bodyActionGap: Float = MODAL_BODY_ACTION_GAP,
    ): Float {
        return max(
            minimumBodyHeight,
            dialogHeight - modalFixedHeightExcludingBody(
                headingHeight = headingHeight,
                footerHeight = footerHeight,
                titleBodyGap = titleBodyGap,
                bodyActionGap = bodyActionGap,
            )
        )
    }
    const val ERROR_FALLBACK_MIN_WIDTH = 500f
    const val ERROR_FALLBACK_HEIGHT = 220f
    const val ERROR_FALLBACK_PADDING = 8f
    private const val TAG_MODE_BORDER_THICKNESS = -1f

    // Text highlighting
    val ACTION_SHORTCUT_HIGHLIGHTS = listOf(
        "[TAB]",
        "[DELETE]",
        "[DEL]",
        "[ESCAPE]",
        "[LEFT]",
        "[LEFT ARROW]",
        "[D]",
        "[A]",
        "[RIGHT]",
        "[RIGHT ARROW]",
        "RIGHT",
        "LEFT",
        "[F]",
    )

    private const val ALERT_RED_COLOR_KEY = "ALERT_RED_COLOR"
    private const val DISABLED_TAG_BACKGROUND_COLOR_KEY = "DISABLED_TAG_BACKGROUND_COLOR"
    private const val DISABLED_TAG_DARK_COLOR_KEY = "DISABLED_TAG_DARK_COLOR"
    private const val SAVE_BUTTON_IDLE_COLOR_KEY = "SAVE_BUTTON_IDLE_COLOR"
    private const val LOAD_BUTTON_IDLE_COLOR_KEY = "LOAD_BUTTON_IDLE_COLOR"
    private const val CONFIRM_BUTTON_IDLE_COLOR_KEY = "CONFIRM_BUTTON_IDLE_COLOR"
    private const val CANCEL_BUTTON_IDLE_COLOR_KEY = "CANCEL_BUTTON_IDLE_COLOR"
    private const val UNCOLOURED_BUTTON_IDLE_COLOR_KEY = "UNCOLOURED_BUTTON_IDLE_COLOR"
    private const val PRESET_SCOPE_BUTTON_IDLE_COLOR_KEY = "PRESET_SCOPE_BUTTON_IDLE_COLOR"
    private const val ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR_KEY = "ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR"
    private const val PANEL_HEADING_COLOUR_KEY = "PANEL_HEADING_COLOUR"
    private const val STALE_WEAPON_GROUP_PANEL_HEADING_COLOUR_KEY = "STALE_WEAPON_GROUP_PANEL_HEADING_COLOUR"
    private const val COLLAPSIBLE_HEADING_COLOUR_KEY = "COLLAPSIBLE_HEADING_COLOUR"
    private const val UNSAVED_COLLAPSIBLE_HEADING_COLOUR_KEY = "UNSAVED_COLLAPSIBLE_HEADING_COLOUR"
    private const val TOP_LEVEL_TAG_SHIP_MODE_HEADING_COLOR_KEY = "SELECTED_WEAPON_SHIP_IDLE_COLOR"
    private const val SPECIFIC_TAG_SHIP_MODE_HEADING_COLOR_KEY = "TAG_COLLAPSIBLE_HEADING_COLOUR"
    private const val SUGGESTED_FILTER_HEADING_COLOR_KEY = "SUGGESTED_FILTER_HEADING_COLOR"
    private const val SUGGESTED_FILTER_ACTIVE_COLOR_KEY = "SUGGESTED_FILTER_ACTIVE_COLOR"

    // Debug color persistence
    private val runtimeColorOverrides = mutableMapOf<String, Color>()
    private var persistentDebugColorsLoaded = false
    private val debugColorTargetList = listOf(
        DebugColorTarget(SAVE_BUTTON_IDLE_COLOR_KEY, "Save / Apply buttons", Color(200, 170, 225, 225)),
        DebugColorTarget(LOAD_BUTTON_IDLE_COLOR_KEY, "Load / Delete buttons", Color(205, 190, 105, 225)),
        DebugColorTarget(CONFIRM_BUTTON_IDLE_COLOR_KEY, "Confirm buttons", Color(160, 225, 135, 225)),
        DebugColorTarget(CANCEL_BUTTON_IDLE_COLOR_KEY, "Cancel buttons", Color(225, 120, 120, 225)),
        DebugColorTarget(UNCOLOURED_BUTTON_IDLE_COLOR_KEY, "Neutral buttons", Color(120, 120, 120, 225)),
        DebugColorTarget(PRESET_SCOPE_BUTTON_IDLE_COLOR_KEY, "Preset scope buttons", Color(225, 155, 80, 225)),
        DebugColorTarget(ACTIVE_WEAPON_TAG_SHIP_MODE_COLOR_KEY, "Active weapon tags / ship modes", Color(160, 225, 135, 225)),
        DebugColorTarget(PANEL_HEADING_COLOUR_KEY, "Panel headings", Color(40, 40, 40, 225)),
        DebugColorTarget(STALE_WEAPON_GROUP_PANEL_HEADING_COLOUR_KEY, "Stale group headings", Color(130, 130, 130, 225)),
        DebugColorTarget(COLLAPSIBLE_HEADING_COLOUR_KEY, "Collapsible headings", Color(200, 200, 200, 225)),
        DebugColorTarget(UNSAVED_COLLAPSIBLE_HEADING_COLOUR_KEY, "Unsaved / active headings", Color(200, 200, 200, 225)),
        DebugColorTarget(TOP_LEVEL_TAG_SHIP_MODE_HEADING_COLOR_KEY, "Top-level tag / ship mode headings", Color(200, 200, 200, 225)),
        DebugColorTarget(SPECIFIC_TAG_SHIP_MODE_HEADING_COLOR_KEY, "Specific tag / ship mode headings", Color(80, 150, 220, 225)),
        DebugColorTarget(SUGGESTED_FILTER_HEADING_COLOR_KEY, "Suggested filter headings", Color(225, 155, 80, 225)),
        DebugColorTarget(SUGGESTED_FILTER_ACTIVE_COLOR_KEY, "Active suggested filters", Color(160, 225, 135, 225)),
        DebugColorTarget(ALERT_RED_COLOR_KEY, "Alert red", Color(245, 95, 85, 240)),
        DebugColorTarget(DISABLED_TAG_BACKGROUND_COLOR_KEY, "Disabled tag background", Color(28, 28, 28, 235)),
        DebugColorTarget(DISABLED_TAG_DARK_COLOR_KEY, "Disabled tag checked fill", Color(18, 18, 18, 235)),
    )

    private fun debugColor(key: String, defaultColor: Color): Color {
        ensurePersistentDebugColorsLoaded()
        return runtimeColorOverrides[key] ?: defaultColor
    }

    fun debugColorTargets(): List<DebugColorTarget> = debugColorTargetList

    fun currentDebugColor(target: DebugColorTarget): Color {
        ensurePersistentDebugColorsLoaded()
        return runtimeColorOverrides[target.key] ?: target.defaultColor
    }

    fun setDebugColorOverride(target: DebugColorTarget, color: Color, persistent: Boolean = false) {
        val normalized = Color(
            color.red.coerceIn(0, 255),
            color.green.coerceIn(0, 255),
            color.blue.coerceIn(0, 255),
            target.defaultColor.alpha
        )
        runtimeColorOverrides[target.key] = normalized
        if (persistent) {
            savePersistentDebugColor(target, normalized)
        }
    }

    private fun ensurePersistentDebugColorsLoaded() {
        if (persistentDebugColorsLoaded) return
        persistentDebugColorsLoaded = true
        try {
            val root = JSONUtils.loadCommonJSON(Values.DEBUG_GUI_COLORS_JSON_FILE_NAME)
            debugColorTargetList.forEach { target ->
                val entry = root.optJSONObject(target.key) ?: return@forEach
                runtimeColorOverrides[target.key] = Color(
                    entry.optInt("r", target.defaultColor.red).coerceIn(0, 255),
                    entry.optInt("g", target.defaultColor.green).coerceIn(0, 255),
                    entry.optInt("b", target.defaultColor.blue).coerceIn(0, 255),
                    target.defaultColor.alpha
                )
            }
        } catch (ex: Throwable) {
            log.warn("Unable to load ${Values.DEBUG_GUI_COLORS_JSON_FILE_NAME}", ex)
        }
    }

    private fun savePersistentDebugColor(target: DebugColorTarget, color: Color) {
        try {
            val root = JSONUtils.loadCommonJSON(Values.DEBUG_GUI_COLORS_JSON_FILE_NAME)
            root.put(
                target.key,
                JSONObject()
                    .put("r", color.red)
                    .put("g", color.green)
                    .put("b", color.blue)
            )
            root.save()
        } catch (ex: Throwable) {
            log.warn("Unable to save ${Values.DEBUG_GUI_COLORS_JSON_FILE_NAME}", ex)
        }
    }

    fun checkboxColorsForButton(colors: ButtonStateColors): CheckboxColors {
        return CheckboxColors(
            base = colors.hover,
            bg = colors.idle,
            bright = colors.hover
        )
    }

    fun colorsForActionButton(kind: CampaignActionButtonKind): ButtonStateColors {
        return when (kind) {
            CampaignActionButtonKind.SAVE -> SAVE_BUTTON_COLORS
            CampaignActionButtonKind.LOAD -> LOAD_BUTTON_COLORS
            CampaignActionButtonKind.CONFIRM -> CONFIRM_BUTTON_COLORS
            CampaignActionButtonKind.CANCEL -> CANCEL_BUTTON_COLORS
            CampaignActionButtonKind.ACTIVE -> ACTIVE_FILTER_BUTTON_COLORS
            CampaignActionButtonKind.FILTER_CATEGORY -> FILTER_CATEGORY_BUTTON_COLORS
            CampaignActionButtonKind.FILTER_CATEGORY_ACTIVE -> FILTER_CATEGORY_ACTIVE_BUTTON_COLORS
            CampaignActionButtonKind.SUGGESTED_FILTER_ACTIVE -> SUGGESTED_FILTER_ACTIVE_BUTTON_COLORS
            CampaignActionButtonKind.TAG_TOP_LEVEL_SECTION -> TAG_TOP_LEVEL_SECTION_BUTTON_COLORS
            CampaignActionButtonKind.TAG_CATEGORY -> TAG_CATEGORY_BUTTON_COLORS
            CampaignActionButtonKind.TAG_CATEGORY_ACTIVE -> TAG_CATEGORY_ACTIVE_BUTTON_COLORS
            CampaignActionButtonKind.ADD_TAG -> ADD_TAG_BUTTON_COLORS
            CampaignActionButtonKind.DISABLED -> DISABLED_BUTTON_COLORS
            CampaignActionButtonKind.UNCOLOURED -> UNCOLOURED_BUTTON_COLORS
        }
    }

    fun shouldFillActionButtonIdle(kind: CampaignActionButtonKind): Boolean {
        return true
    }

    fun actionButtonTemplate(
        kind: CampaignActionButtonKind,
        enabled: Boolean = true,
        disabledKind: CampaignActionButtonKind = CampaignActionButtonKind.DISABLED,
    ): ActionButtonTemplate {
        return ActionButtonTemplate(
            kind = if (enabled) kind else disabledKind,
            textColor = if (enabled) DEFAULT_TEXT_COLOUR else DISABLED_TAG_TEXT_COLOR,
            enabled = enabled
        )
    }

    fun renderedIdleFillColorForActionButton(kind: CampaignActionButtonKind): Color {
        return artificiallyDarkenedIdleColor(colorsForActionButton(kind).idle)
    }

    fun colorsForModalComponentButton(kind: CampaignActionButtonKind): ButtonStateColors {
        return if (kind == CampaignActionButtonKind.UNCOLOURED) {
            MODAL_COMPONENT_BUTTON_COLORS
        } else {
            colorsForActionButton(kind)
        }
    }

    fun renderedIdleFillColorForModalComponentButton(kind: CampaignActionButtonKind): Color {
        return artificiallyDarkenedIdleColor(colorsForModalComponentButton(kind).idle)
    }

    fun formatActionShortcutName(keyCode: Int): String {
        val rawName = Keyboard.getKeyName(keyCode)
        val uppercase = rawName.uppercase()
        return when {
            uppercase == "DELETE" -> "DEL"
            uppercase.contains("RIGHT") -> "RIGHT"
            uppercase.contains("LEFT") -> "LEFT"
            else -> uppercase
        }
    }

    fun actionLabelText(label: String, shortcuts: List<Int>): String {
        val shortcutText = shortcuts.joinToString(" ") { "[${formatActionShortcutName(it)}]" }
        return if (shortcutText.isBlank()) label else "$label $shortcutText"
    }

    fun actionLabelLayout(labelText: String, width: Float, maxLines: Int): WrappedLabelLayout {
        return computeWrappedLabelLayout(
            text = labelText,
            rowWidth = width - 2f * ACTION_ROW_PADDING,
            minButtonHeight = 18f,
            horizontalPadding = 2f * ACTION_ROW_PADDING,
            verticalPadding = 2f * ACTION_ROW_PADDING,
            approxCharWidthPx = ACTION_LABEL_APPROX_CHAR_WIDTH,
            lineHeightPx = ACTION_LABEL_LINE_HEIGHT,
            maxLines = maxLines
        )
    }

    fun campaignTagGridMetrics(tags: List<String>, panelWidth: Float, panelHeight: Float): WrapGridMetrics {
        val itemHeight = tags.map { tag ->
            computeWrappedLabelLayout(
                text = tag,
                rowWidth = panelWidth - 2f * ITEM_TEXT_HORIZONTAL_PADDING,
                minButtonHeight = TAG_ITEM_HEIGHT,
                horizontalPadding = 2f * ITEM_TEXT_HORIZONTAL_PADDING,
                verticalPadding = 2f * ITEM_TEXT_TOP_PADDING,
                maxLines = 1
            ).rowHeight
        }.maxOrNull() ?: TAG_ITEM_HEIGHT
        return computeWrapGridMetrics(
            itemCount = max(tags.size, 1),
            availableWidth = panelWidth,
            availableHeight = panelHeight,
            minItemWidth = TAG_ITEM_MIN_WIDTH,
            itemHeight = itemHeight,
            horizontalGap = TAG_ITEM_HGAP,
            verticalGap = TAG_ITEM_VGAP,
            maxColumns = 1
        )
    }

    /**
     * Decompiled addAreaCheckbox(base, bg, bright) behavior:
     * - base controls the hover/glow color.
     * - bg controls checked fill and border.
     * - bright controls the built-in label text color, which AGC leaves blank here.
     *
     * The Starsector area-checkbox renderer draws a black base, checked fill, border,
     * and then an inset glow while hovered. Negative border thickness is intentional
     * here: it expands the black/glow rectangles to reduce the bright-ring/black-center
     * appearance that otherwise shows on blank-label tag and ship-mode buttons.
     *
     * Weapon tags and ship modes are the same area-checkbox widget class in checked
     * and unchecked states. Starsector does not darken checked controls the same
     * way it darkens normal button idle interiors, so checked state uses AGC's
     * derived rendered color for the backing panel, checkbox fill, border, and
     * glow override.
     */
    fun toggleableCheckboxColors(): CheckboxColors {
        return CheckboxColors(
            base = INACTIVE_WEAPON_TAG_SHIP_MODE_HOVER_COLOR,
            bg = ACTIVE_WEAPON_TAG_SHIP_MODE_RENDERED_COLOR,
            bright = ACTIVE_WEAPON_TAG_SHIP_MODE_HOVER_COLOR
        )
    }

    private fun artificiallyDarkenedIdleColor(color: Color): Color {
        return Color(
            (color.red * CHECKED_IDLE_DIM_MULT).toInt().coerceIn(0, 255),
            (color.green * CHECKED_IDLE_DIM_MULT).toInt().coerceIn(0, 255),
            (color.blue * CHECKED_IDLE_DIM_MULT).toInt().coerceIn(0, 255),
            color.alpha
        )
    }

    private data class ButtonOverrideMethods(
        val setGlowOverride: Method,
        val setBorderOverride: Method,
        val setBorderThickness: Method,
    )

    private val buttonOverrideMethodsByClass = mutableMapOf<Class<*>, ButtonOverrideMethods?>()

    private fun buttonOverrideMethods(button: ButtonAPI): ButtonOverrideMethods? {
        val buttonClass = button.javaClass
        if (buttonOverrideMethodsByClass.containsKey(buttonClass)) {
            return buttonOverrideMethodsByClass[buttonClass]
        }
        val methods = try {
            ButtonOverrideMethods(
                setGlowOverride = buttonClass.getMethod("setGlowOverride", Color::class.java),
                setBorderOverride = buttonClass.getMethod("setBorderOverride", Color::class.java),
                setBorderThickness = buttonClass.getMethod("setBorderThickness", java.lang.Float.TYPE),
            )
        } catch (_: Throwable) {
            log.info("AGC area-checkbox color override methods are unavailable for ${buttonClass.name}; using stock checkbox colors for this UI class.")
            null
        }
        buttonOverrideMethodsByClass[buttonClass] = methods
        return methods
    }

    private fun invokeColorOverride(button: ButtonAPI, method: Method, color: Color) {
        try {
            method.invoke(button, color)
        } catch (ex: Throwable) {
            log.warn("AGC failed to apply area-checkbox color override ${method.name}", ex)
        }
    }

    private fun invokeFloatOverride(button: ButtonAPI, method: Method, value: Float) {
        try {
            method.invoke(button, value)
        } catch (ex: Throwable) {
            log.warn("AGC failed to apply area-checkbox float override ${method.name}", ex)
        }
    }

    fun applyToggleableCheckboxVisualState(button: ButtonAPI) {
        val glowColor = if (button.isChecked) {
            ACTIVE_WEAPON_TAG_SHIP_MODE_RENDERED_COLOR
        } else {
            INACTIVE_WEAPON_TAG_SHIP_MODE_HOVER_COLOR
        }
        val borderColor = if (button.isChecked) {
            ACTIVE_WEAPON_TAG_SHIP_MODE_RENDERED_COLOR
        } else {
            INACTIVE_WEAPON_TAG_SHIP_MODE_COLOR
        }
        applyCheckboxVisualOverrides(button, glowColor, borderColor, TAG_MODE_BORDER_THICKNESS)
    }

    fun applyCheckboxVisualOverrides(
        button: ButtonAPI,
        glowColor: Color,
        borderColor: Color,
        borderThickness: Float,
    ) {
        val methods = buttonOverrideMethods(button) ?: return
        invokeColorOverride(button, methods.setGlowOverride, glowColor)
        invokeColorOverride(button, methods.setBorderOverride, borderColor)
        invokeFloatOverride(button, methods.setBorderThickness, borderThickness)
    }

    fun applyUnavailableCheckboxVisualState(button: ButtonAPI) {
        val methods = buttonOverrideMethods(button) ?: return
        invokeColorOverride(button, methods.setGlowOverride, DISABLED_TAG_BRIGHT_COLOR)
        invokeColorOverride(button, methods.setBorderOverride, DISABLED_TAG_BORDER_COLOR)
        invokeFloatOverride(button, methods.setBorderThickness, 1f)
    }
}
