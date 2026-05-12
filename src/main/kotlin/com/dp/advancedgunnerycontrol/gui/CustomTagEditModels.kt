package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.typesandvalues.EditableWeaponTagDefinition

internal data class CustomTagEditAvailability(
    val enabled: Boolean,
    val tooltip: String,
)

internal data class CustomTagEditRenderState(
    val definition: EditableWeaponTagDefinition?,
    val fixedTagEdit: Boolean,
    val draftValues: Map<String, String>,
    val preview: String,
    val canonicalTag: String?,
    val isValid: Boolean,
    val editSource: Pair<Int, String>?,
    val isManagerEdit: Boolean,
    val managerEditSource: String?,
    val managerEditSourceIsPendingAddition: Boolean,
    val editSourceTag: String?,
    val existingTags: Set<String>,
    val confirmAvailability: CustomTagEditAvailability,
    val copyAvailability: CustomTagEditAvailability,
    val deleteAvailability: CustomTagEditAvailability,
)

internal data class CustomTagEditSourceState(
    val canonicalTag: String?,
    val inCustomList: Boolean,
    val activeOnShip: Boolean,
)

internal enum class CustomTagEditAction {
    CONFIRM,
    COPY,
    DELETE,
}

internal data class ModalFooterButtonSpec(
    val data: Any,
    val kind: CampaignActionButtonKind,
    val enabled: Boolean = true,
    val labelText: String,
    val tooltip: String? = null,
    val showTooltipWhileInactive: Boolean = false,
    val onClick: () -> Unit,
)
