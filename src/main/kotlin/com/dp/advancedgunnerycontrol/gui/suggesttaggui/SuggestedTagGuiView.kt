package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.gui.ButtonBase
import com.dp.advancedgunnerycontrol.gui.CampaignButtonCallbackPoller
import com.dp.advancedgunnerycontrol.gui.CampaignActionButtonKind
import com.dp.advancedgunnerycontrol.gui.CampaignPanelType
import com.dp.advancedgunnerycontrol.gui.CampaignGuiStyle
import com.dp.advancedgunnerycontrol.gui.CampaignToggleHeading
import com.dp.advancedgunnerycontrol.gui.CustomView
import com.dp.advancedgunnerycontrol.gui.CampaignPanelPlugin
import com.dp.advancedgunnerycontrol.gui.ShipEditorLayoutCalculator
import com.dp.advancedgunnerycontrol.gui.CampaignTooltipCopy
import com.dp.advancedgunnerycontrol.gui.addCampaignPanelHeading
import com.dp.advancedgunnerycontrol.gui.addAgcText
import com.dp.advancedgunnerycontrol.gui.addTemplatedCampaignMomentaryActionButton
import com.dp.advancedgunnerycontrol.gui.clearRegisteredCampaignButtons
import com.dp.advancedgunnerycontrol.settings.Settings
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.MissileSpecAPI
import com.fs.starfarer.api.loading.ProjectileSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Main Customize Suggested Tags view component.
 * Renders the left Options/Filter column and the paged weapon panels that each
 * contain a suggested-tag list.
 */
class SuggestedTagGuiView(
    private val weaponListView: WeaponListView,
    private val initialTagScrollOffsets: Map<String, Int> = emptyMap(),
    private val initialTagExpandedCategoryTitles: Map<String, Set<String>> = emptyMap(),
    initialCollapsedWeaponInfoSections: Set<String> = emptySet(),
    initialExpandedAdvancedWeaponInfoSections: Set<String> = emptySet(),
    private var visibleTagList: List<String>,
) : CustomView() {
    companion object {
        private const val TOTAL_COLUMNS = 5
        private const val SUGGESTED_TAG_LIST_MIN_HEIGHT = 120f
        private const val MIN_RENDERABLE_TAG_LIST_HEIGHT = 48f
        private const val WEAPON_INFO_MIN_HEIGHT = 90f
        private const val WEAPON_INFO_TEXT_COMPONENT_GAP = CampaignGuiStyle.MODAL_TEXT_COMPONENT_GAP
        private const val WEAPON_INFO_TO_TAG_GAP = 0f
        private const val WEAPON_IMAGE_TOP_GAP = 5f
        private const val WEAPON_IMAGE_BOTTOM_GAP = 6f
        private const val WEAPON_IMAGE_MAX = 52f
        private const val WEAPON_INFO_ROW_HEIGHT = 15f
        private const val WEAPON_INFO_APPROX_CHAR_WIDTH = 6.2f
        private const val BASIC_INFO_MAX_VISIBLE_ROWS = 16
        private const val BASIC_INFO_SECTION_HEIGHT =
            CampaignGuiStyle.TAG_ITEM_HEIGHT +
                2f * WEAPON_INFO_TEXT_COMPONENT_GAP +
                BASIC_INFO_MAX_VISIBLE_ROWS * WEAPON_INFO_ROW_HEIGHT
    }

    private val buttons: MutableList<ButtonBase<*>> = mutableListOf()
    private val tagListRenderer = SuggestedTagListRenderer(
        initialTagScrollOffsets,
        initialTagExpandedCategoryTitles,
        buttons,
        onMissingRefreshContext = { scrollDirty = true }
    )
    private var scrollDirty = false
    private var observedTagSelectionVersion = SuggestedTagButton.suggestedTagSelectionVersion
    private val buttonCallbackPoller = CampaignButtonCallbackPoller(buttons)
    private var contentPanel: CustomPanelAPI? = null
    private var leftColumnPanel: CustomPanelAPI? = null
    private var weaponPanelsPanel: CustomPanelAPI? = null
    private val collapsedWeaponInfoSections = initialCollapsedWeaponInfoSections.toMutableSet()
    private val expandedAdvancedWeaponInfoSections = initialExpandedAdvancedWeaponInfoSections.toMutableSet()

    override fun advance(amount: Float) {
        buttonCallbackPoller.processIfRequested()
    }

    override fun processInput(events: MutableList<InputEventAPI>?) {
        buttonCallbackPoller.requestPollFromInput(events)
        tagListRenderer.processInput(events, pos)
    }

    override fun buttonPressed(buttonId: Any?) {}

    fun captureTagScrollOffsets(): Map<String, Int> = tagListRenderer.captureScrollOffsets()
    fun captureTagExpandedCategoryTitles(): Map<String, Set<String>> =
        tagListRenderer.captureExpandedCategoryTitles()
    fun captureCollapsedWeaponInfoSections(): Set<String> = collapsedWeaponInfoSections.toSet()
    fun captureExpandedAdvancedWeaponInfoSections(): Set<String> = expandedAdvancedWeaponInfoSections.toSet()

    fun shouldRegenerate(): Boolean =
        scrollDirty || observedTagSelectionVersion != SuggestedTagButton.suggestedTagSelectionVersion

    private fun fitSprite(spriteName: String?, maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
        if (spriteName.isNullOrBlank()) return 0f to 0f
        return try {
            val sprite = Global.getSettings().getSprite(spriteName)
            if (sprite.width <= 0f || sprite.height <= 0f) {
                min(maxWidth, maxHeight) to min(maxWidth, maxHeight)
            } else {
                val scale = min(maxWidth / sprite.width, maxHeight / sprite.height)
                sprite.width * scale to sprite.height * scale
            }
        } catch (_: Throwable) {
            min(maxWidth, maxHeight) to min(maxWidth, maxHeight)
        }
    }

    private fun formatStat(value: Float, allowDecimals: Boolean = false): String {
        if (!value.isFinite() || value < 0f) return "0"
        return if (!allowDecimals || abs(value - value.roundToInt()) < 0.005f) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun buildWeaponInfo(panel: CustomPanelAPI, weaponId: String?) {
        addCampaignPanelHeading(panel, "Weapon", headingHeight = CampaignGuiStyle.CONTAINER_HEADING_HEIGHT)
        if (weaponId == null) return

        val showAdvancedInfo = Settings.showSuggestedTagAdvancedInfo()
        val spec = Global.getSettings().getWeaponSpec(weaponId)
        val innerWidth = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
        val imageMax = min(WEAPON_IMAGE_MAX, innerWidth - 8f)
        val bodyTop = CampaignGuiStyle.PANEL_PADDING + CampaignGuiStyle.CONTAINER_HEADING_HEIGHT + WEAPON_IMAGE_TOP_GAP
        val spriteName = spec.turretSpriteName
        val (imageWidth, imageHeight) = fitSprite(spriteName, imageMax, imageMax)
        if (imageWidth > 0f && imageHeight > 0f) {
            val imagePanel = panel.createUIElement(imageWidth, imageHeight, false)
            imagePanel.addImage(spriteName, imageWidth, imageHeight, 0f)
            panel.addUIElement(imagePanel).inTL((panel.position.width - imageWidth) / 2f, bodyTop)
        }

        val statsTop = bodyTop + imageMax + WEAPON_IMAGE_BOTTOM_GAP
        val statsHeight = (panel.position.height - statsTop).coerceAtLeast(0f)
        if (statsHeight <= 0f) return
        val statsPanel = panel.createCustomPanel(
            panel.position.width,
            statsHeight,
            CampaignPanelPlugin(CampaignPanelType.SUGGESTED_WEAPON_PANEL)
        )
        panel.addComponent(statsPanel)
        statsPanel.position.inTL(0f, statsTop)
        var y = 0f
        y = renderWeaponInfoSection(
            panel = statsPanel,
            weaponId = weaponId,
            sectionId = "basic",
            title = "Basic Info",
            rows = basicInfoRows(spec),
            y = y,
            reserveBottomHeight = if (showAdvancedInfo) CampaignGuiStyle.TAG_ITEM_HEIGHT else 0f,
        )
        if (showAdvancedInfo) {
            renderWeaponInfoSection(
                panel = statsPanel,
                weaponId = weaponId,
                sectionId = "advanced",
                title = "Advanced Info",
                rows = advancedInfoRows(spec),
                y = y,
            )
        }
    }

    private fun suggestedWeaponInfoHeight(columnWidth: Float, columnHeight: Float, weaponId: String?): Float {
        val baseHeight = CampaignGuiStyle.PANEL_PADDING +
            CampaignGuiStyle.CONTAINER_HEADING_HEIGHT +
            WEAPON_IMAGE_TOP_GAP
        if (weaponId == null) return baseHeight

        val spec = Global.getSettings().getWeaponSpec(weaponId)
        val innerWidth = columnWidth - 2f * CampaignGuiStyle.PANEL_PADDING
        val imageMax = min(WEAPON_IMAGE_MAX, innerWidth - 8f)
        val statsTop = baseHeight + imageMax + WEAPON_IMAGE_BOTTOM_GAP
        val rowWidth = columnWidth - 2f * CampaignGuiStyle.PANEL_PADDING
        val desiredHeight = statsTop +
            estimatedWeaponInfoSectionHeight(weaponId, "basic", basicInfoRows(spec), rowWidth) +
            (if (Settings.showSuggestedTagAdvancedInfo()) {
                estimatedWeaponInfoSectionHeight(weaponId, "advanced", advancedInfoRows(spec), rowWidth)
            } else {
                0f
            })
        val minInfoHeight = min(
            columnHeight.coerceAtLeast(0f),
            max(WEAPON_INFO_MIN_HEIGHT, statsTop + CampaignGuiStyle.SHIP_MODE_ITEM_HEIGHT)
        )
        val maxInfoHeight = (columnHeight - SUGGESTED_TAG_LIST_MIN_HEIGHT - WEAPON_INFO_TO_TAG_GAP)
            .coerceAtLeast(minInfoHeight)
            .coerceAtMost(columnHeight.coerceAtLeast(0f))
        return desiredHeight
            .coerceAtLeast(minInfoHeight)
            .coerceAtMost(maxInfoHeight)
    }

    private fun estimatedWeaponInfoSectionHeight(
        weaponId: String,
        sectionId: String,
        rows: List<String>,
        rowWidth: Float,
    ): Float {
        val key = "$weaponId:$sectionId"
        val collapsed = weaponInfoSectionCollapsed(key, sectionId)
        if (sectionId == "basic" && !collapsed) return BASIC_INFO_SECTION_HEIGHT
        return CampaignGuiStyle.TAG_ITEM_HEIGHT +
            if (collapsed) {
                0f
            } else {
                2f * WEAPON_INFO_TEXT_COMPONENT_GAP +
                    rows.sumOf { wrappedInfoLines(it, rowWidth).size } * WEAPON_INFO_ROW_HEIGHT
            }
    }

    private fun weaponInfoSectionCollapsed(key: String, sectionId: String): Boolean {
        return when (sectionId) {
            "advanced" -> key !in expandedAdvancedWeaponInfoSections
            else -> key in collapsedWeaponInfoSections
        }
    }

    private fun renderWeaponInfoSection(
        panel: CustomPanelAPI,
        weaponId: String,
        sectionId: String,
        title: String,
        rows: List<String>,
        y: Float,
        reserveBottomHeight: Float = 0f,
    ): Float {
        val key = "$weaponId:$sectionId"
        val collapsed = weaponInfoSectionCollapsed(key, sectionId)
        if (y + CampaignGuiStyle.TAG_ITEM_HEIGHT > panel.position.height) return panel.position.height
        val heading = CampaignToggleHeading(
            title = title,
            expanded = !collapsed,
            active = false,
            subject = "weapon info",
            inactiveKind = CampaignActionButtonKind.TAG_TOP_LEVEL_SECTION,
            activeKind = CampaignActionButtonKind.TAG_TOP_LEVEL_SECTION,
        )
        val button = addTemplatedCampaignMomentaryActionButton(
            parent = panel,
            data = "suggested_weapon_info:$key",
            x = 0f,
            y = y,
            width = panel.position.width,
            height = CampaignGuiStyle.TAG_ITEM_HEIGHT,
            template = CampaignGuiStyle.actionButtonTemplate(heading.kind),
            labelText = heading.label,
            tooltip = CampaignTooltipCopy.weaponInfoSection(title, !collapsed),
            centerText = true,
        ) {
            toggleWeaponInfoSection(sectionId, key)
        }
        buttons.add(button)
        var nextY = y + CampaignGuiStyle.TAG_ITEM_HEIGHT
        if (!collapsed) {
            nextY += WEAPON_INFO_TEXT_COMPONENT_GAP
            val rowWidth = panel.position.width - 2f * CampaignGuiStyle.PANEL_PADDING
            rows.forEachIndexed { rowIndex, line ->
                val wrappedLines = wrappedInfoLines(line, rowWidth)
                wrappedLines.forEachIndexed { index, wrappedLine ->
                    val rowBottomLimit = panel.position.height - reserveBottomHeight
                    if (nextY + WEAPON_INFO_ROW_HEIGHT > rowBottomLimit) return nextY
                    val hasMoreRows = index < wrappedLines.lastIndex || rowIndex < rows.lastIndex
                    val isLastAvailableRow = hasMoreRows &&
                        nextY + 2f * WEAPON_INFO_ROW_HEIGHT > rowBottomLimit
                    val renderedLine = if (isLastAvailableRow) "..." else wrappedLine
                    val row = panel.createUIElement(
                        rowWidth,
                        WEAPON_INFO_ROW_HEIGHT,
                        false
                    )
                    row.addAgcText(renderedLine, 0f)
                    panel.addUIElement(row).inTL(CampaignGuiStyle.PANEL_PADDING, nextY)
                    nextY += WEAPON_INFO_ROW_HEIGHT
                    if (isLastAvailableRow) return nextY
                }
            }
            nextY += WEAPON_INFO_TEXT_COMPONENT_GAP
        }
        return nextY
    }

    private fun toggleWeaponInfoSection(sectionId: String, key: String) {
        if (sectionId == "advanced") {
            if (!expandedAdvancedWeaponInfoSections.add(key)) {
                expandedAdvancedWeaponInfoSections.remove(key)
            }
        } else if (!collapsedWeaponInfoSections.add(key)) {
            collapsedWeaponInfoSections.remove(key)
        }
        scrollDirty = true
    }

    private fun wrappedInfoLines(line: String, width: Float): List<String> {
        val maxChars = maxInfoChars(width)
        if (line.length <= maxChars) return listOf(line)
        val firstBreak = bestWrapIndex(line, maxChars)
        val firstLine = line.take(firstBreak).trimEnd()
        val remainder = line.drop(firstBreak).trimStart()
        if (remainder.isBlank()) return listOf(firstLine)
        return listOf(firstLine, fitInfoLine(remainder, width))
    }

    private fun bestWrapIndex(line: String, maxChars: Int): Int {
        val lastSpace = line.take(maxChars + 1).lastIndexOf(' ')
        return if (lastSpace >= maxChars / 2) lastSpace else maxChars
    }

    private fun maxInfoChars(width: Float): Int {
        return max(8, (width / WEAPON_INFO_APPROX_CHAR_WIDTH).toInt())
    }

    private fun fitInfoLine(line: String, width: Float): String {
        val maxChars = maxInfoChars(width)
        if (line.length <= maxChars) return line
        return line.take(max(1, maxChars - 3)).trimEnd() + "..."
    }

    private fun basicInfoRows(spec: WeaponSpecAPI): List<String> {
        val derived = spec.derivedStats
        val damage = max(derived.burstDamage, derived.damagePerShot)
        val rows = mutableListOf<String>()
        rows += "Name: ${spec.weaponName?.takeIf { it.isNotBlank() } ?: spec.weaponId}"
        addTextRow(rows, "Role", spec.primaryRoleStr)
        rows += "Size: ${spec.size}"
        rows += "Type: ${spec.type}"
        addNumberRow(rows, "OP", runCatching { spec.getOrdnancePointCost(null) }.getOrDefault(0f))
        addNumberRow(rows, "Range", spec.maxRange)
        (spec as? ProjectileWeaponSpecAPI)?.let { addNumberRow(rows, "Refire", it.refireDelay, "s", allowDecimals = true) }
        addNumberRow(rows, "Damage", damage)
        addNumberRow(rows, "Sust DPS", derived.sustainedDps, "(${shortDamageType(spec.damageType?.toString())})")
        addNumberRow(rows, "Sust Flux", derived.sustainedFluxPerSecond)
        addNumberRow(rows, "Flux/Dmg", derived.fluxPerDam, allowDecimals = true)
        addNumberRow(rows, "EMP", max(derived.empPerSecond, derived.empPerShot))
        addAmmoOrChargeRows(rows, spec)
        addTextRow(rows, "Acc", spec.accuracyStr?.takeIf { it.isNotBlank() } ?: accuracyFallback(spec))
        return rows
    }

    private fun advancedInfoRows(spec: WeaponSpecAPI): List<String> {
        val derived = spec.derivedStats
        val rows = mutableListOf<String>()
        addNumberRow(rows, "EMP/s", derived.empPerSecond)
        if (derived.empPerSecond > 0f || derived.empPerShot > 0f) {
            addNumberRow(rows, "Flux/EMP", derived.fluxPerSecond / max(derived.empPerSecond, derived.empPerShot), allowDecimals = true)
        }
        (spec as? BeamWeaponSpecAPI)?.let { beam ->
            addNumberRow(rows, "Beam DPS", beam.damagePerSecond)
            addNumberRow(rows, "Beam Spd", beam.beamSpeed)
            addNumberRow(rows, "Charge Up", beam.chargeupTime, allowDecimals = true)
            addNumberRow(rows, "Charge Down", beam.chargedownTime, allowDecimals = true)
        }
        (spec as? ProjectileWeaponSpecAPI)?.let { projectile ->
            addNumberRow(rows, "Burst", projectile.burstDelay, allowDecimals = true)
        }
        addNumberRow(rows, "Turn/s", spec.turnRate, "deg")
        addNumberRow(rows, "Min Spr", spec.minSpread, allowDecimals = true)
        addNumberRow(rows, "Max Spr", spec.maxSpread, allowDecimals = true)
        addNumberRow(rows, "Spr/shot", spec.spreadBuildup, allowDecimals = true)
        addNumberRow(rows, "Spr decay", spec.spreadDecayRate, allowDecimals = true)
        (spec.projectileSpec as? ProjectileSpecAPI)?.let { projectile ->
            addNumberRow(rows, "Proj Spd", projectile.getMoveSpeed(null, null))
            addNumberRow(rows, "Proj HP", projectile.maxHealth)
        }
        (spec.projectileSpec as? MissileSpecAPI)?.let { missile ->
            addNumberRow(rows, "Launch", missile.launchSpeed)
            addNumberRow(rows, "Flight", missile.maxFlightTime, "s", allowDecimals = true)
            rows += "Guided: ${missile.behaviorSpec != null}"
        }
        return rows
    }

    private fun shortDamageType(value: String?): String {
        return when (value) {
            "HIGH_EXPLOSIVE" -> "HE"
            "FRAGMENTATION" -> "Frag"
            null -> ""
            else -> value
        }
    }

    private fun addAmmoOrChargeRows(rows: MutableList<String>, spec: WeaponSpecAPI) {
        if (!spec.usesAmmo() || spec.maxAmmo <= 0) return
        val usesRegeneratingCharges = spec.ammoPerSecond > 0f
        if (usesRegeneratingCharges) {
            rows += "Max Charges: ${spec.maxAmmo}"
            addNumberRow(rows, "Sec / Recharge", 1f / spec.ammoPerSecond, allowDecimals = true)
            addNumberRow(rows, "Charge Gain", spec.reloadSize)
        } else {
            rows += "Max Ammo: ${spec.maxAmmo}"
            if (spec.ammoPerSecond > 0f) addNumberRow(rows, "Sec / Reload", 1f / spec.ammoPerSecond, allowDecimals = true)
            addNumberRow(rows, "Ammo Gain", spec.reloadSize)
        }
    }

    private fun addTextRow(rows: MutableList<String>, label: String, value: String?) {
        val clean = value?.trim().orEmpty()
        if (clean.isNotBlank()) rows += "$label: $clean"
    }

    private fun addNumberRow(
        rows: MutableList<String>,
        label: String,
        value: Float,
        suffix: String = "",
        allowDecimals: Boolean = false,
    ) {
        if (!value.isFinite() || value <= 0f) return
        val suffixText = if (suffix.isBlank()) "" else " $suffix"
        rows += "$label: ${formatStat(value, allowDecimals)}$suffixText"
    }

    private fun accuracyFallback(spec: WeaponSpecAPI): String {
        return if (spec.maxSpread <= 0f && spec.spreadBuildup <= 0f) "Perfect" else formatStat(spec.maxSpread, allowDecimals = true)
    }

    private fun buildSuggestedWeaponPanel(
        panel: CustomPanelAPI,
        weaponId: String?,
        relativeLeft: Float,
        contentHeight: Float,
    ) {
        val infoPanel = panel.createCustomPanel(
            panel.position.width,
            suggestedWeaponInfoHeight(panel.position.width, panel.position.height, weaponId),
            CampaignPanelPlugin(CampaignPanelType.SUGGESTED_WEAPON_PANEL)
        )
        panel.addComponent(infoPanel)
        infoPanel.position.inTL(0f, 0f)
        buildWeaponInfo(infoPanel, weaponId)

        val tagTop = infoPanel.position.height + WEAPON_INFO_TO_TAG_GAP
        val tagHeight = (panel.position.height - tagTop).coerceAtLeast(0f)
        if (tagHeight < MIN_RENDERABLE_TAG_LIST_HEIGHT) return
        val tagPanel = panel.createCustomPanel(
            panel.position.width,
            tagHeight,
            CampaignPanelPlugin(CampaignPanelType.SUGGESTED_TAG_LIST_PANEL)
        )
        panel.addComponent(tagPanel)
        tagPanel.position.inTL(0f, tagTop)
        tagListRenderer.build(
            panel = tagPanel,
            weaponId = weaponId,
            relativeLeft = relativeLeft,
            relativeBottom = contentHeight - tagTop - tagHeight,
            visibleTags = visibleTagList,
        )
    }

    private fun buildSuggestedWeaponPanels(panel: CustomPanelAPI, contentHeight: Float, leftColumnWidth: Float) {
        val ids = weaponListView.currentIds()
        val cardWidth = panel.position.width / TOTAL_COLUMNS
        repeat(TOTAL_COLUMNS) { index ->
            val effectiveWidth = if (index == TOTAL_COLUMNS - 1) {
                panel.position.width - cardWidth * (TOTAL_COLUMNS - 1)
            } else {
                cardWidth
            }
            val column = panel.createCustomPanel(
                effectiveWidth,
                panel.position.height,
                CampaignPanelPlugin(CampaignPanelType.SUGGESTED_WEAPON_PANEL)
            )
            panel.addComponent(column)
            column.position.inTL(index * cardWidth, 0f)
            buildSuggestedWeaponPanel(
                column,
                ids.getOrNull(index),
                relativeLeft = leftColumnWidth + index * cardWidth,
                contentHeight = contentHeight
            )
        }
    }

    fun buildIn(
        panel: CustomPanelAPI,
        buildOptionsPanel: (CustomPanelAPI) -> Unit,
    ) {
        contentPanel = panel
        refreshInPlace(buildOptionsPanel)
    }

    fun refreshInPlace(
        buildOptionsPanel: (CustomPanelAPI) -> Unit,
        visibleTagList: List<String> = this.visibleTagList,
    ): Boolean {
        this.visibleTagList = visibleTagList
        val panel = contentPanel ?: return false
        leftColumnPanel?.let(panel::removeComponent)
        weaponPanelsPanel?.let(panel::removeComponent)
        clearRegisteredCampaignButtons()
        buttons.clear()
        tagListRenderer.clear()
        scrollDirty = false
        observedTagSelectionVersion = SuggestedTagButton.suggestedTagSelectionVersion

        val leftColumnWidth = ShipEditorLayoutCalculator.sharedLeftColumnWidth(panel.position.width)
        val weaponPanelsWidth = panel.position.width - leftColumnWidth

        val leftColumnPanel = panel.createCustomPanel(
            leftColumnWidth,
            panel.position.height,
            CampaignPanelPlugin(CampaignPanelType.LEFT_COLUMN_PANEL)
        )
        this.leftColumnPanel = leftColumnPanel
        panel.addComponent(leftColumnPanel)
        leftColumnPanel.position.inTL(0f, 0f)
        buildOptionsPanel(leftColumnPanel)

        val weaponPanelsPanel = panel.createCustomPanel(
            weaponPanelsWidth,
            panel.position.height,
            CampaignPanelPlugin(CampaignPanelType.SUGGESTED_WEAPON_PANELS_PANEL)
        )
        this.weaponPanelsPanel = weaponPanelsPanel
        panel.addComponent(weaponPanelsPanel)
        weaponPanelsPanel.position.rightOfTop(leftColumnPanel, 0f)
        buildSuggestedWeaponPanels(weaponPanelsPanel, panel.position.height, leftColumnWidth)
        return true
    }

    override fun render(alpha: Float) {}
}
