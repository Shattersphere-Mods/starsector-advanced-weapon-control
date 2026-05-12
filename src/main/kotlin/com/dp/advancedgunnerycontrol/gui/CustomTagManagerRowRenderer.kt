package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinition
import com.fs.starfarer.api.ui.CustomPanelAPI

/**
 * Manage Tags and Ship Modes list-row renderer.
 * Draws top-level Tags/Ship Modes sections, category/archetype headings,
 * exact tag/mode rows, Add rows, and Custom List Changes review rows.
 */
internal object CustomTagManagerRowRenderer {
    fun renderManagerRow(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        editSourceForTag: (String) -> String?,
        onToggleCategory: (String) -> Unit,
        onToggleListSection: (String) -> Unit,
        onToggleArchetype: (String) -> Unit,
        onToggleTag: (CustomTagManagerRow.TagEntry, String?) -> Unit,
        onToggleShipMode: (CustomTagManagerRow.ShipModeEntry) -> Unit,
        onEditShipMode: (mode: String, pendingAddition: Boolean, sourceMode: String) -> Unit,
        onEditTag: (tag: String, pendingAddition: Boolean, sourceTag: String) -> Unit,
        onAddDefinition: (EditableWeaponTagDefinition) -> Unit,
        onAddDirectTag: (String) -> Unit,
        onAddShipModeDefinition: (EditableWeaponTagDefinition) -> Unit,
        onAddShipMode: (String) -> Unit,
    ) {
        when (row) {
            is CustomTagManagerRow.ListSectionHeading -> renderListSectionHeading(
                dialog = dialog,
                row = row,
                y = y,
                width = width,
                buttons = buttons,
                onToggleListSection = onToggleListSection,
            )
            is CustomTagManagerRow.CategoryHeading -> renderCategoryHeading(
                dialog = dialog,
                row = row,
                y = y,
                width = width,
                buttons = buttons,
                onToggleCategory = onToggleCategory,
            )
            is CustomTagManagerRow.ArchetypeHeading -> renderArchetypeHeading(
                dialog = dialog,
                row = row,
                y = y,
                width = width,
                buttons = buttons,
                onToggleArchetype = onToggleArchetype,
            )
            is CustomTagManagerRow.TagEntry -> renderTagEntry(
                dialog = dialog,
                row = row,
                y = y,
                width = width,
                buttons = buttons,
                editSourceForTag = editSourceForTag,
                onToggleTag = onToggleTag,
                onEditTag = onEditTag,
            )
            is CustomTagManagerRow.AddTagEntry -> renderAddTagEntry(
                dialog = dialog,
                row = row,
                y = y,
                width = width,
                buttons = buttons,
                onAddDefinition = onAddDefinition,
                onAddDirectTag = onAddDirectTag,
            )
            is CustomTagManagerRow.ShipModeEntry -> renderShipModeEntry(
                dialog = dialog,
                row = row,
                y = y,
                width = width,
                buttons = buttons,
                onToggleShipMode = onToggleShipMode,
                onEditShipMode = onEditShipMode,
            )
            is CustomTagManagerRow.AddShipModeEntry -> renderAddShipModeEntry(
                dialog = dialog,
                row = row,
                y = y,
                width = width,
                buttons = buttons,
                onAddShipModeDefinition = onAddShipModeDefinition,
                onAddShipMode = onAddShipMode,
            )
        }
    }

    fun renderChangeReviewRow(
        dialog: CustomPanelAPI,
        row: CustomTagChangeReviewRow,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        onToggleSection: (String) -> Unit,
    ) {
        when (row) {
            is CustomTagChangeReviewRow.ListHeading -> {
                val hasContents = row.count > 0
                val label = if (hasContents) {
                    "${row.label} (${row.count}) (${if (row.expanded) "-" else "+"})"
                } else {
                    "${row.label} (${row.count})"
                }
                val template = CampaignGuiStyle.actionButtonTemplate(
                    CampaignActionButtonKind.TAG_TOP_LEVEL_SECTION,
                    enabled = hasContents
                )
                buttons.add(addManagerRowButton(
                    parent = dialog,
                    data = "custom_list_change_review_heading:${row.id}",
                    y = y,
                    rowWidth = width,
                    template = template,
                    labelText = label,
                    tooltip = if (row.expanded) {
                        "Collapse ${row.label} changes."
                    } else {
                        "Expand ${row.label} changes."
                    },
                ) {
                    if (hasContents) onToggleSection(row.id)
                })
            }
            is CustomTagChangeReviewRow.Heading -> {
                val hasContents = row.count > 0
                val label = if (hasContents) {
                    "${row.section.label} (${row.count}) (${if (row.expanded) "-" else "+"})"
                } else {
                    "${row.section.label} (${row.count})"
                }
                val template = CampaignGuiStyle.actionButtonTemplate(
                    CampaignActionButtonKind.FILTER_CATEGORY,
                    enabled = hasContents
                )
                buttons.add(addManagerRowButton(
                    parent = dialog,
                    data = "custom_tag_change_review_heading:${row.section.id}",
                    y = y,
                    rowWidth = width,
                    indentLevel = 1,
                    template = template,
                    labelText = label,
                    tooltip = if (row.expanded) {
                        "Collapse ${row.section.label.lowercase()} changes."
                    } else {
                        "Expand ${row.section.label.lowercase()} changes."
                    },
                ) {
                    if (hasContents) onToggleSection(row.section.id)
                })
            }
            is CustomTagChangeReviewRow.Entry -> {
                val template = CampaignGuiStyle.actionButtonTemplate(row.kind)
                buttons.add(addManagerRowButton(
                    parent = dialog,
                    data = "custom_tag_change_review_entry:${row.kind}:${row.label}",
                    y = y,
                    rowWidth = width,
                    indentLevel = 2,
                    template = template,
                    labelText = row.label,
                    centerConfirmCancelText = false,
                ) {})
            }
        }
    }

    private fun renderListSectionHeading(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow.ListSectionHeading,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        onToggleListSection: (String) -> Unit,
    ) {
        val label = "${row.label} (${if (row.expanded) "-" else "+"})"
        val template = CampaignGuiStyle.actionButtonTemplate(CampaignActionButtonKind.TAG_TOP_LEVEL_SECTION)
        buttons.add(addManagerRowButton(
            parent = dialog,
            data = "custom_list_manager_section:${row.id}",
            y = y,
            rowWidth = width,
            template = template,
            labelText = label,
            tooltip = if (row.expanded) {
                "Collapse ${row.label}."
            } else {
                "Expand ${row.label}."
            },
        ) {
            onToggleListSection(row.id)
        })
    }

    private fun renderCategoryHeading(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow.CategoryHeading,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        onToggleCategory: (String) -> Unit,
    ) {
        val heading = CampaignToggleHeading(
            title = row.category.title,
            expanded = row.expanded,
            active = row.active,
            subject = "custom tag archetypes",
            inactiveKind = CampaignActionButtonKind.TAG_CATEGORY,
            activeKind = CampaignActionButtonKind.TAG_CATEGORY_ACTIVE,
        )
        val template = CampaignGuiStyle.actionButtonTemplate(heading.kind)
        buttons.add(addManagerRowButton(
            parent = dialog,
            data = "custom_tag_manager_category:${row.category.title}",
            y = y,
            rowWidth = width,
            template = template,
            labelText = heading.label,
            tooltip = heading.tooltip,
        ) {
            onToggleCategory(row.category.title)
        })
    }

    private fun renderArchetypeHeading(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow.ArchetypeHeading,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        onToggleArchetype: (String) -> Unit,
    ) {
        val expandedLabel = "${row.archetype.label} (${if (row.expanded) "-" else "+"})"
        val template = CampaignGuiStyle.actionButtonTemplate(
            if (row.active) CampaignActionButtonKind.TAG_CATEGORY_ACTIVE else CampaignActionButtonKind.TAG_CATEGORY
        )
        buttons.add(addManagerRowButton(
            parent = dialog,
            data = "custom_tag_manager_archetype:${row.archetype.id}",
            y = y,
            rowWidth = width,
            indentLevel = 1,
            template = template,
            labelText = expandedLabel,
            tooltip = if (row.expanded) {
                "Collapse ${row.archetype.label} custom tag variants."
            } else {
                "Expand ${row.archetype.label} custom tag variants."
            },
        ) {
            onToggleArchetype(row.archetype.id)
        })
    }

    private fun renderTagEntry(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow.TagEntry,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        editSourceForTag: (String) -> String?,
        onToggleTag: (CustomTagManagerRow.TagEntry, String?) -> Unit,
        onEditTag: (tag: String, pendingAddition: Boolean, sourceTag: String) -> Unit,
    ) {
        val status = CustomTagManagerRows.status(row)
        val label = CustomTagManagerRows.tagLabel(row.tag, status)
        val editSource = editSourceForTag(row.tag)
        val template = CampaignGuiStyle.actionButtonTemplate(status.kind)
        val managerButton = addManagerRowButton(
            parent = dialog,
            data = "custom_tag_manager_tag:${row.tag}",
            y = y,
            rowWidth = width,
            indentLevel = 2,
            template = template,
            labelText = label,
            highlightTokens = CustomTagManagerRows.highlightTokens(label, status),
            tooltip = CustomTagManagerRows.tagTooltip(row, status, editSource),
            centerConfirmCancelText = false,
        ) {
            onToggleTag(row, editSource)
        }
        if (!row.markedForRemoval) {
            managerButton.onRightClick {
                onEditTag(
                    row.tag,
                    row.pendingAddition,
                    if (row.pendingEdit) editSource ?: row.tag else row.tag,
                )
            }
        }
        buttons.add(managerButton)
    }

    private fun renderAddTagEntry(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow.AddTagEntry,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        onAddDefinition: (EditableWeaponTagDefinition) -> Unit,
        onAddDirectTag: (String) -> Unit,
    ) {
        val archetype = row.archetype
        val template = CampaignGuiStyle.actionButtonTemplate(CampaignActionButtonKind.ADD_TAG)
        buttons.add(addManagerRowButton(
            parent = dialog,
            data = "custom_tag_manager_add:${archetype.id}",
            y = y,
            rowWidth = width,
            indentLevel = 2,
            template = template,
            labelText = "Add Tag",
            tooltip = "Add a ${archetype.label} custom tag.",
        ) {
            when {
                archetype.definition != null -> onAddDefinition(archetype.definition)
                archetype.directTag != null -> onAddDirectTag(archetype.directTag)
            }
        })
    }

    private fun renderShipModeEntry(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow.ShipModeEntry,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        onToggleShipMode: (CustomTagManagerRow.ShipModeEntry) -> Unit,
        onEditShipMode: (mode: String, pendingAddition: Boolean, sourceMode: String) -> Unit,
    ) {
        val status = CustomTagManagerRows.shipModeStatus(row)
        val label = CustomTagManagerRows.shipModeLabel(row.mode, status)
        val template = CampaignGuiStyle.actionButtonTemplate(status.kind)
        val managerButton = addManagerRowButton(
            parent = dialog,
            data = "custom_ship_mode_manager_mode:${row.mode}",
            y = y,
            rowWidth = width,
            indentLevel = 1,
            template = template,
            labelText = label,
            highlightTokens = CustomTagManagerRows.highlightTokens(label, status),
            tooltip = CustomTagManagerRows.shipModeTooltip(row, status),
            centerConfirmCancelText = false,
        ) {
            onToggleShipMode(row)
        }
        if (!row.markedForRemoval) {
            managerButton.onRightClick {
                onEditShipMode(row.mode, row.pendingAddition, row.mode)
            }
        }
        buttons.add(managerButton)
    }

    private fun renderAddShipModeEntry(
        dialog: CustomPanelAPI,
        row: CustomTagManagerRow.AddShipModeEntry,
        y: Float,
        width: Float,
        buttons: MutableList<ButtonBase<*>>,
        onAddShipModeDefinition: (EditableWeaponTagDefinition) -> Unit,
        onAddShipMode: (String) -> Unit,
    ) {
        val template = CampaignGuiStyle.actionButtonTemplate(CampaignActionButtonKind.ADD_TAG)
        val label = CustomTagManagerRows.shipModeLabel(row.mode, CustomTagManagerTagStatus.NORMAL)
        buttons.add(addManagerRowButton(
            parent = dialog,
            data = "custom_ship_mode_manager_add:${row.definition?.id ?: row.mode}",
            y = y,
            rowWidth = width,
            indentLevel = 1,
            template = template,
            labelText = "Add $label",
            tooltip = "Add $label to this Custom ship-mode list.",
        ) {
            row.definition?.let(onAddShipModeDefinition) ?: onAddShipMode(row.mode)
        })
    }

    private fun addManagerRowButton(
        parent: CustomPanelAPI,
        data: String,
        y: Float,
        rowWidth: Float,
        template: CampaignGuiStyle.ActionButtonTemplate,
        labelText: String,
        indentLevel: Int = 0,
        tooltip: String? = null,
        highlightTokens: List<String> = emptyList(),
        centerConfirmCancelText: Boolean = true,
        onClick: () -> Unit,
    ): CampaignMomentaryButton {
        val indent = indentLevel * CampaignGuiStyle.CHILD_ROW_INDENT
        return addTemplatedCampaignMomentaryActionButton(
            parent = parent,
            data = data,
            x = CustomListModalListRenderer.LIST_INSET + indent,
            y = y,
            width = rowWidth - CustomListModalListRenderer.LIST_INSET - indent,
            height = CampaignGuiStyle.MODAL_ROW_HEIGHT,
            template = template,
            labelText = labelText,
            highlightTokens = highlightTokens,
            tooltip = tooltip,
            centerConfirmCancelText = centerConfirmCancelText,
            callback = onClick,
        )
    }
}
