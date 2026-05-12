package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI

abstract class WeaponFilter {
    abstract fun matches(weaponSpec: WeaponSpecAPI): Boolean
    abstract fun type(): FilterType
    abstract fun name(): String

    enum class FilterType{SIZE, MOUNT_TYPE, FIRE_FORM, RANGE, FLUX_DAMAGE, AMMO, EMP, POINT_DEFENSE, DAMAGE_TYPE}
    enum class FilterCategory(val title: String) {
        MOUNT("Mount"),
        SIZE("Size"),
        FIRE_FORM("Form"),
        DAMAGE_TYPE("Damage"),
        SPECIAL("Special"),
        FLUX_DAMAGE("Flux/Damage"),
        RANGE("Range"),
    }

    fun category(): FilterCategory {
        return when (type()) {
            FilterType.MOUNT_TYPE -> FilterCategory.MOUNT
            FilterType.SIZE -> FilterCategory.SIZE
            FilterType.FIRE_FORM -> FilterCategory.FIRE_FORM
            FilterType.DAMAGE_TYPE -> FilterCategory.DAMAGE_TYPE
            FilterType.AMMO, FilterType.EMP, FilterType.POINT_DEFENSE -> FilterCategory.SPECIAL
            FilterType.FLUX_DAMAGE -> FilterCategory.FLUX_DAMAGE
            FilterType.RANGE -> FilterCategory.RANGE
        }
    }

    fun matches(weapon: String): Boolean{
        return specForOrNull(weapon)?.let(::matches) == true
    }

    companion object{
        fun specFor(weapon: String): WeaponSpecAPI {
            return Global.getSettings().getWeaponSpec(weapon)
        }

        fun specForOrNull(weapon: String): WeaponSpecAPI? {
            return runCatching { specFor(weapon) }.getOrNull()
        }

        object ballisticsFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.mountType == WeaponAPI.WeaponType.BALLISTIC
            override fun type(): FilterType = FilterType.MOUNT_TYPE
            override fun name(): String = "Ballistic Mount"

        }
        object energyFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.mountType == WeaponAPI.WeaponType.ENERGY
            override fun type(): FilterType = FilterType.MOUNT_TYPE
            override fun name(): String = "Energy Mount"

        }
        object missileFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.mountType == WeaponAPI.WeaponType.MISSILE
            override fun type(): FilterType = FilterType.MOUNT_TYPE
            override fun name(): String = "Missile Mount"

        }
        object hybridMountFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.mountType == WeaponAPI.WeaponType.HYBRID
            override fun type(): FilterType = FilterType.MOUNT_TYPE
            override fun name(): String = "Hybrid Mount"
        }
        object synergyMountFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.mountType == WeaponAPI.WeaponType.SYNERGY
            override fun type(): FilterType = FilterType.MOUNT_TYPE
            override fun name(): String = "Synergy Mount"
        }
        object compositeMountFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.mountType == WeaponAPI.WeaponType.COMPOSITE
            override fun type(): FilterType = FilterType.MOUNT_TYPE
            override fun name(): String = "Composite Mount"
        }
        object smallFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.size == WeaponAPI.WeaponSize.SMALL
            override fun type(): FilterType = FilterType.SIZE
            override fun name(): String = "Small"
        }
        object mediumFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.size == WeaponAPI.WeaponSize.MEDIUM
            override fun type(): FilterType = FilterType.SIZE
            override fun name(): String = "Medium"
        }
        object largeFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.size == WeaponAPI.WeaponSize.LARGE
            override fun type(): FilterType = FilterType.SIZE
            override fun name(): String = "Large"
        }
        object beamFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.isBeam
            override fun type(): FilterType = FilterType.FIRE_FORM
            override fun name(): String = "Beam"
        }
        object projectileFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = !weaponSpec.isBeam
            override fun type(): FilterType = FilterType.FIRE_FORM
            override fun name(): String = "Projectile"
        }
        object range0To500Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = rangeIn(weaponSpec, 0f, 500f)
            override fun type(): FilterType = FilterType.RANGE
            override fun name(): String = "Range [0, 500]"
        }
        object range500To1000Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = rangeIn(weaponSpec, 500f, 1000f)
            override fun type(): FilterType = FilterType.RANGE
            override fun name(): String = "Range [500, 1000]"
        }
        object range1000To1500Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = rangeIn(weaponSpec, 1000f, 1500f)
            override fun type(): FilterType = FilterType.RANGE
            override fun name(): String = "Range [1000, 1500]"
        }
        object range1500To2000Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = rangeIn(weaponSpec, 1500f, 2000f)
            override fun type(): FilterType = FilterType.RANGE
            override fun name(): String = "Range [1500, 2000]"
        }
        object range2000To2500Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = rangeIn(weaponSpec, 2000f, 2500f)
            override fun type(): FilterType = FilterType.RANGE
            override fun name(): String = "Range [2000, 2500]"
        }
        object range2500PlusFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.maxRange >= 2500f
            override fun type(): FilterType = FilterType.RANGE
            override fun name(): String = "Range [2500+]"
        }
        object fluxDamage0To05Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = fluxPerDamageIn(weaponSpec, 0f, 0.5f)
            override fun type(): FilterType = FilterType.FLUX_DAMAGE
            override fun name(): String = "Flux/Damage [0.0, 0.5]"
        }
        object fluxDamage05To075Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = fluxPerDamageIn(weaponSpec, 0.5f, 0.75f)
            override fun type(): FilterType = FilterType.FLUX_DAMAGE
            override fun name(): String = "Flux/Damage [0.5, 0.75]"
        }
        object fluxDamage075To10Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = fluxPerDamageIn(weaponSpec, 0.75f, 1.0f)
            override fun type(): FilterType = FilterType.FLUX_DAMAGE
            override fun name(): String = "Flux/Damage [0.75, 1.0]"
        }
        object fluxDamage10To125Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = fluxPerDamageIn(weaponSpec, 1.0f, 1.25f)
            override fun type(): FilterType = FilterType.FLUX_DAMAGE
            override fun name(): String = "Flux/Damage [1.0, 1.25]"
        }
        object fluxDamage125To15Filter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = fluxPerDamageIn(weaponSpec, 1.25f, 1.5f)
            override fun type(): FilterType = FilterType.FLUX_DAMAGE
            override fun name(): String = "Flux/Damage [1.25, 1.5]"
        }
        object fluxDamage15PlusFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = fluxPerDamageAtLeast(weaponSpec, 1.5f)
            override fun type(): FilterType = FilterType.FLUX_DAMAGE
            override fun name(): String = "Flux/Damage [1.5+]"
        }
        object ammoOrChargesFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = hasAmmoOrCharges(weaponSpec)
            override fun type(): FilterType = FilterType.AMMO
            override fun name(): String = "Ammo/Charges"
        }
        object noAmmoOrChargesFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = !hasAmmoOrCharges(weaponSpec)
            override fun type(): FilterType = FilterType.AMMO
            override fun name(): String = "No Ammo/Charges"
        }
        object empFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = hasEmp(weaponSpec)
            override fun type(): FilterType = FilterType.EMP
            override fun name(): String = "EMP"
        }
        object noEmpFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = !hasEmp(weaponSpec)
            override fun type(): FilterType = FilterType.EMP
            override fun name(): String = "No EMP"
        }
        object pdFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = isPointDefense(weaponSpec)
            override fun type(): FilterType = FilterType.POINT_DEFENSE
            override fun name(): String = "PD"
        }
        object noPdFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = !isPointDefense(weaponSpec)
            override fun type(): FilterType = FilterType.POINT_DEFENSE
            override fun name(): String = "No PD"
        }
        object explosiveDamageFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.damageType == DamageType.HIGH_EXPLOSIVE
            override fun type(): FilterType = FilterType.DAMAGE_TYPE
            override fun name(): String = "Explosive"
        }
        object kineticDamageFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.damageType == DamageType.KINETIC
            override fun type(): FilterType = FilterType.DAMAGE_TYPE
            override fun name(): String = "Kinetic"
        }
        object fragmentationDamageFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.damageType == DamageType.FRAGMENTATION
            override fun type(): FilterType = FilterType.DAMAGE_TYPE
            override fun name(): String = "Fragmentation"
        }
        object energyDamageFilter : WeaponFilter() {
            override fun matches(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.damageType == DamageType.ENERGY
            override fun type(): FilterType = FilterType.DAMAGE_TYPE
            override fun name(): String = "Energy Damage"
        }

        private fun rangeIn(weaponSpec: WeaponSpecAPI, minInclusive: Float, maxExclusive: Float): Boolean {
            val value = weaponSpec.maxRange
            return value.isFinite() && value >= minInclusive && value < maxExclusive
        }

        private fun fluxPerDamageIn(weaponSpec: WeaponSpecAPI, minInclusive: Float, maxExclusive: Float): Boolean {
            val value = weaponSpec.derivedStats.fluxPerDam
            return value.isFinite() && value >= minInclusive && value < maxExclusive
        }

        private fun fluxPerDamageAtLeast(weaponSpec: WeaponSpecAPI, minInclusive: Float): Boolean {
            val value = weaponSpec.derivedStats.fluxPerDam
            return value.isFinite() && value >= minInclusive
        }

        private fun hasAmmoOrCharges(weaponSpec: WeaponSpecAPI): Boolean = weaponSpec.usesAmmo()

        private fun hasEmp(weaponSpec: WeaponSpecAPI): Boolean =
            weaponSpec.derivedStats.empPerShot > 0f || weaponSpec.derivedStats.empPerSecond > 0f

        private fun isPointDefense(weaponSpec: WeaponSpecAPI): Boolean =
            weaponSpec.aiHints.any { it == WeaponAPI.AIHints.PD || it == WeaponAPI.AIHints.PD_ONLY || it == WeaponAPI.AIHints.PD_ALSO }

        public val allFilters = listOf(
            ballisticsFilter,
            energyFilter,
            missileFilter,
            hybridMountFilter,
            synergyMountFilter,
            compositeMountFilter,
            smallFilter,
            mediumFilter,
            largeFilter,
            beamFilter,
            projectileFilter,
            explosiveDamageFilter,
            kineticDamageFilter,
            fragmentationDamageFilter,
            energyDamageFilter,
            ammoOrChargesFilter,
            noAmmoOrChargesFilter,
            empFilter,
            noEmpFilter,
            pdFilter,
            noPdFilter,
            fluxDamage0To05Filter,
            fluxDamage05To075Filter,
            fluxDamage075To10Filter,
            fluxDamage10To125Filter,
            fluxDamage125To15Filter,
            fluxDamage15PlusFilter,
            range0To500Filter,
            range500To1000Filter,
            range1000To1500Filter,
            range1500To2000Filter,
            range2000To2500Filter,
            range2500PlusFilter
        )
        val filterCategories = listOf(
            FilterCategory.MOUNT,
            FilterCategory.SIZE,
            FilterCategory.FIRE_FORM,
            FilterCategory.DAMAGE_TYPE,
            FilterCategory.SPECIAL,
            FilterCategory.FLUX_DAMAGE,
            FilterCategory.RANGE,
        )

        private val filtersByCategory: Map<FilterCategory, List<WeaponFilter>> = buildFiltersByCategory()

        fun filtersIn(category: FilterCategory): List<WeaponFilter> {
            return filtersByCategory[category].orEmpty()
        }

        private fun buildFiltersByCategory(): Map<FilterCategory, List<WeaponFilter>> {
            val grouped = linkedMapOf<FilterCategory, MutableList<WeaponFilter>>()
            allFilters.forEach { filter ->
                val category = filter.category()
                var categoryFilters = grouped[category]
                if (categoryFilters == null) {
                    categoryFilters = mutableListOf()
                    grouped[category] = categoryFilters
                }
                categoryFilters.add(filter)
            }
            return grouped
        }

        fun categoryLabel(category: FilterCategory, expanded: Boolean): String {
            return "${category.title} (${if (expanded) "-" else "+"})"
        }
    }
}
