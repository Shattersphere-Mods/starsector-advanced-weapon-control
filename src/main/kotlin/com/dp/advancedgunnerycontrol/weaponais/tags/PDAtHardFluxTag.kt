package com.dp.advancedgunnerycontrol.weaponais.tags

import com.dp.advancedgunnerycontrol.utils.FluxComparator
import com.dp.advancedgunnerycontrol.utils.FluxCondition
import com.dp.advancedgunnerycontrol.utils.FluxMetric
import com.fs.starfarer.api.combat.WeaponAPI

class PDAtHardFluxTag(weapon: WeaponAPI, threshold: Float) : PDTag(
    weapon,
    FluxCondition(FluxMetric.HARD, FluxComparator.GREATER_THAN, threshold)
)
