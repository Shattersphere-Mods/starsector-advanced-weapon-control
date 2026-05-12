package com.dp.advancedgunnerycontrol.weaponais.tags

import com.dp.advancedgunnerycontrol.utils.FluxComparator
import com.dp.advancedgunnerycontrol.utils.FluxCondition
import com.dp.advancedgunnerycontrol.utils.FluxMetric
import com.fs.starfarer.api.combat.WeaponAPI

class PDAtTotalFluxTag(weapon: WeaponAPI, threshold: Float) : PDTag(
    weapon,
    FluxCondition(FluxMetric.TOTAL, FluxComparator.GREATER_THAN, threshold)
)
