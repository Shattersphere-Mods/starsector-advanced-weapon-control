package com.dp.advancedgunnerycontrol.weaponais.tags

import com.dp.advancedgunnerycontrol.utils.FluxComparator
import com.dp.advancedgunnerycontrol.utils.FluxCondition
import com.dp.advancedgunnerycontrol.utils.FluxMetric
import com.fs.starfarer.api.combat.WeaponAPI

class BurstPDSoftFluxTag(weapon: WeaponAPI, freeFireSoftFluxThreshold: Float) : PDTag(
    weapon,
    FluxCondition(
        metric = FluxMetric.SOFT,
        comparator = FluxComparator.GREATER_THAN,
        threshold = freeFireSoftFluxThreshold,
        requireTotalFluxBelowSoftFluxCap = true
    )
)
