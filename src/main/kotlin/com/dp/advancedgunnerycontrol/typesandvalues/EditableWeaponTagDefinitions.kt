package com.dp.advancedgunnerycontrol.typesandvalues

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.weaponais.tags.DamageTypeExclusions
import java.util.Locale
import kotlin.math.roundToInt

enum class EditableTagParameterKind {
    TEXT,
    TOGGLE,
    CHOICE,
    NUMBER,
    DECIMAL
}

data class TagParameterOption(
    val id: String,
    val label: String
)

sealed class EditableTagParameterDefinition(
    open val id: String,
    open val label: String,
    val kind: EditableTagParameterKind,
    val useHiddenChangeMarker: Boolean = false,
)

data class TextParameter(
    override val id: String,
    override val label: String,
    val defaultValue: String = "",
    val maxLength: Int? = null,
    val markHiddenChange: Boolean = false,
) : EditableTagParameterDefinition(id, label, EditableTagParameterKind.TEXT, markHiddenChange)

data class ToggleParameter(
    override val id: String,
    override val label: String,
    val defaultValue: Boolean = false,
    val markHiddenChange: Boolean = false,
) : EditableTagParameterDefinition(id, label, EditableTagParameterKind.TOGGLE, markHiddenChange)

data class ChoiceParameter(
    override val id: String,
    override val label: String,
    val options: List<TagParameterOption>,
    val defaultOptionId: String,
    val markHiddenChange: Boolean = false,
) : EditableTagParameterDefinition(id, label, EditableTagParameterKind.CHOICE, markHiddenChange)

data class NumberParameter(
    override val id: String,
    override val label: String,
    val minValue: Int,
    val maxValue: Int,
    val defaultValue: Int,
    val suffix: String = "",
    val markHiddenChange: Boolean = false,
) : EditableTagParameterDefinition(id, label, EditableTagParameterKind.NUMBER, markHiddenChange)

data class DecimalParameter(
    override val id: String,
    override val label: String,
    val minValue: Float,
    val maxValue: Float,
    val defaultValue: Float,
    val minorStep: Float = 0.1f,
    val majorStep: Float = 1f,
    val decimalPlaces: Int = 2,
    val suffix: String = "",
    val markHiddenChange: Boolean = false,
) : EditableTagParameterDefinition(id, label, EditableTagParameterKind.DECIMAL, markHiddenChange)

data class EditableTagValidationError(
    val parameterId: String,
    val message: String
)

data class EditableTagBuildResult(
    val canonicalTag: String?,
    val errors: List<EditableTagValidationError>
) {
    val isValid: Boolean
        get() = canonicalTag != null && errors.isEmpty()
}

data class ParsedEditableWeaponTag(
    val canonicalTag: String,
    val definitionId: String,
    val family: String,
    val templateTag: String,
    val parameterValues: Map<String, String>,
    val exclusivityKeys: Set<String>
)

data class EditableWeaponTagDefinition(
    val id: String,
    val family: String,
    val templateTag: String,
    val acceptedTemplateTags: Set<String> = setOf(templateTag),
    val parameters: List<EditableTagParameterDefinition>,
    val defaultValues: Map<String, String>,
    private val parser: (String) -> ParsedEditableWeaponTag?,
    private val builder: (Map<String, String>) -> EditableTagBuildResult
) {
    fun parse(canonicalTag: String): ParsedEditableWeaponTag? = parser(canonicalTag)

    fun buildCanonicalTag(parameterValues: Map<String, String>): EditableTagBuildResult = builder(parameterValues)
}

object EditableWeaponTagDefinitions {
    const val PARAM_FLUX_METRIC = "fluxMetric"
    const val PARAM_THRESHOLD = "threshold"
    const val PARAM_TARGET_SHIELD_THRESHOLD = "targetShieldThreshold"
    const val PARAM_TOTAL_FLUX_CAP = "totalFluxCap"
    const val PARAM_PRIORITY_MULTIPLIER = "priorityMultiplier"
    const val PARAM_KINETIC_THRESHOLD = "kineticThreshold"
    const val PARAM_HIGH_EXPLOSIVE_THRESHOLD = "highExplosiveThreshold"
    const val PARAM_TRIGGER_HAPPINESS = "triggerHappiness"
    const val PARAM_CLEANUP_DAMAGE_CAP = "cleanupDamageCap"
    const val PARAM_REQUIRE_SHIP_TARGET = "requireShipTarget"
    const val PARAM_TRIGGER_AMMO_FEEDER = "triggerAmmoFeeder"
    const val PARAM_TRIGGER_HIGH_ENERGY_FOCUS = "triggerHighEnergyFocus"
    const val PARAM_TRIGGER_LIDAR_ARRAY = "triggerLidarArray"
    const val PARAM_TRIGGER_TEMPORAL_SHELL = "triggerTemporalShell"
    const val PARAM_TRIGGER_ENTROPY_AMPLIFIER = "triggerEntropyAmplifier"
    const val PARAM_IGNORE_IF_BEAMED = "ignoreIfBeamed"
    const val PARAM_BEAM_WINDOW = "beamWindow"
    const val PARAM_IGNORE_KINETIC_WEAPONS = "ignoreKineticWeapons"
    const val PARAM_IGNORE_HIGH_EXPLOSIVE_WEAPONS = "ignoreHighExplosiveWeapons"
    const val PARAM_IGNORE_FRAGMENTATION_WEAPONS = "ignoreFragmentationWeapons"
    const val PARAM_IGNORE_ENERGY_WEAPONS = "ignoreEnergyWeapons"
    const val PARAM_IGNORE_BEAM_WEAPONS = "ignoreBeamWeapons"
    const val PARAM_IGNORE_MISSILE_WEAPONS = "ignoreMissileWeapons"
    const val PARAM_IGNORE_PROJECTILE_WEAPONS = "ignoreProjectileWeapons"

    private const val HOLD_FIRE_BEAM_WINDOW_DEFAULT_SECONDS = 2f
    private const val DAMAGE_TYPE_EXCLUSION_LIST_PATTERN = "(?:K|HE|F|E|B|M|P)(?:,(?:K|HE|F|E|B|M|P))*"
    private const val OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN = "(?:,Ignore<($DAMAGE_TYPE_EXCLUSION_LIST_PATTERN)>)?"
    private const val SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN = "\\(Ignore<($DAMAGE_TYPE_EXCLUSION_LIST_PATTERN)>\\)"

    private val definitionByTemplateCache = mutableMapOf<String, EditableWeaponTagDefinition?>()
    private val parsedByRawTagCache = mutableMapOf<String, ParsedEditableWeaponTag?>()

    private val fluxMetricOptions = listOf(
        TagParameterOption("TF", "Total flux"),
        TagParameterOption("SF", "Soft flux"),
        TagParameterOption("HF", "Hard flux")
    )

    private val damageTypeExclusionParameters = listOf(
        ToggleParameter(
            id = PARAM_IGNORE_KINETIC_WEAPONS,
            label = "Ignore kinetic weapons",
            defaultValue = false,
            markHiddenChange = true,
        ),
        ToggleParameter(
            id = PARAM_IGNORE_HIGH_EXPLOSIVE_WEAPONS,
            label = "Ignore HE weapons",
            defaultValue = false,
            markHiddenChange = true,
        ),
        ToggleParameter(
            id = PARAM_IGNORE_FRAGMENTATION_WEAPONS,
            label = "Ignore frag weapons",
            defaultValue = false,
            markHiddenChange = true,
        ),
        ToggleParameter(
            id = PARAM_IGNORE_ENERGY_WEAPONS,
            label = "Ignore energy weapons",
            defaultValue = false,
            markHiddenChange = true,
        ),
        ToggleParameter(
            id = PARAM_IGNORE_BEAM_WEAPONS,
            label = "Ignore beam weapons",
            defaultValue = false,
            markHiddenChange = true,
        ),
        ToggleParameter(
            id = PARAM_IGNORE_MISSILE_WEAPONS,
            label = "Ignore missile weapons",
            defaultValue = false,
            markHiddenChange = true,
        ),
        ToggleParameter(
            id = PARAM_IGNORE_PROJECTILE_WEAPONS,
            label = "Ignore projectile weapons",
            defaultValue = false,
            markHiddenChange = true,
        ),
    )

    val definitions: List<EditableWeaponTagDefinition> =
        fluxConditionDefinitions() +
            ammoAndTargetingThresholdDefinitions() +
            simpleDamageExclusionDefinitions() +
            priorityMultiplierDefinitions() +
            synchronizedAttackDefinitions()

    fun allTemplateTags(): List<String> = definitions.map { it.templateTag }

    fun clearCaches() {
        definitionByTemplateCache.clear()
        parsedByRawTagCache.clear()
    }

    fun definitionForTemplate(templateTag: String): EditableWeaponTagDefinition? {
        if (definitionByTemplateCache.containsKey(templateTag)) {
            return definitionByTemplateCache[templateTag]
        }
        val canonicalTemplate = tagNameToRegexName(templateTag)
        val definition = definitions.firstOrNull {
            canonicalTemplate in it.acceptedTemplateTags || templateTag in it.acceptedTemplateTags
        }
        definitionByTemplateCache[templateTag] = definition
        return definition
    }

    fun definitionById(id: String): EditableWeaponTagDefinition? =
        definitions.firstOrNull { it.id == id }

    fun parse(tag: String): ParsedEditableWeaponTag? {
        if (parsedByRawTagCache.containsKey(tag)) {
            return parsedByRawTagCache[tag]
        }
        return parseUncached(tag).also { parsedByRawTagCache[tag] = it }
    }

    private fun parseUncached(tag: String): ParsedEditableWeaponTag? {
        val canonicalTag = canonicalizeWeaponTagName(tag)
        return definitions.firstNotNullOfOrNull { it.parse(canonicalTag) }
    }

    fun isEditable(tag: String): Boolean = parse(tag) != null || definitionForTemplate(tag) != null

    fun visibleParameters(definition: EditableWeaponTagDefinition): List<EditableTagParameterDefinition> =
        definition.parameters.filter(::isParameterVisible)

    fun visibleParameters(
        definition: EditableWeaponTagDefinition,
        parameterValues: Map<String, String>,
    ): List<EditableTagParameterDefinition> =
        visibleParameters(definition).filter { parameter ->
            if (parameter.id == PARAM_TOTAL_FLUX_CAP) {
                val metric = parameterValues[PARAM_FLUX_METRIC]
                    ?: defaultValuesFor(definition)[PARAM_FLUX_METRIC]
                    ?: return@filter true
                return@filter totalFluxCapAppliesToMetric(definition, metric)
            }
            true
        }

    fun displayName(tag: String): String {
        val canonicalTag = canonicalizeWeaponTagName(tag)
        val parsed = parse(canonicalTag) ?: return canonicalTag
        val definition = definitionById(parsed.definitionId) ?: return canonicalTag
        val hiddenChanged = hasHiddenChangedParameters(parsed, definition)
        fluxConditionDisplayName(parsed, definition, hiddenChanged)?.let { return it }
        if (!hiddenChanged) {
            return definition.buildCanonicalTag(parsed.parameterValues).canonicalTag
                ?.takeIf { canonicalTag.contains("Ignore<") }
                ?: canonicalTag
        }
        parseSyncTagOptions(parsed.canonicalTag)?.displayName()?.let { return it }
        return "*${parsed.family}"
    }

    private fun fluxConditionDisplayName(
        parsed: ParsedEditableWeaponTag,
        definition: EditableWeaponTagDefinition,
        hiddenChanged: Boolean,
    ): String? {
        if (
            parsed.definitionId !in setOf(
                "hold_fire_flux_threshold",
                "force_fire_flux_threshold",
                "avoid_shield_flux_threshold",
                "target_shield_flux_threshold",
            )
        ) return null
        val metric = parsed.parameterValues[PARAM_FLUX_METRIC] ?: return null
        val threshold = parsed.parameterValues[PARAM_THRESHOLD] ?: return null
        val comparator = if (parsed.definitionId == "force_fire_flux_threshold") "<" else ">"
        val defaultCap = defaultValuesFor(definition)[PARAM_TOTAL_FLUX_CAP]
        val cap = parsed.parameterValues[PARAM_TOTAL_FLUX_CAP]
            ?.takeIf { totalFluxCapAppliesToMetric(definition, metric) }
            ?.takeIf { defaultCap == null || it != defaultCap }
            ?.let { ",TF<$it%" }
            .orEmpty()
        val marker = if (hiddenChanged) "*" else ""
        return "$marker${parsed.family}($metric$comparator$threshold%$cap)"
    }

    fun areMutuallyExclusive(tag: String, existingTag: String): Boolean {
        val parsedTag = parse(tag) ?: return false
        val parsedExistingTag = parse(existingTag) ?: return false
        if (parsedTag.definitionId != parsedExistingTag.definitionId) return false
        return parsedTag.exclusivityKeys.any { it in parsedExistingTag.exclusivityKeys }
    }

    fun sharesEditableDefinition(tag: String, existingTag: String): Boolean {
        val parsedTag = parse(tag) ?: return false
        val parsedExistingTag = parse(existingTag) ?: return false
        return parsedTag.definitionId == parsedExistingTag.definitionId
    }

    fun buildCanonicalTag(templateTag: String, parameterValues: Map<String, String>): EditableTagBuildResult {
        val definition = definitionForTemplate(templateTag)
            ?: return EditableTagBuildResult(
                canonicalTag = null,
                errors = listOf(EditableTagValidationError("", "No editable tag definition exists for $templateTag."))
            )
        return definition.buildCanonicalTag(parameterValues)
    }

    fun defaultValuesFor(definition: EditableWeaponTagDefinition): Map<String, String> {
        val defaults = definition.defaultValues.toMutableMap()
        when (definition.id) {
            "avoid_shield_target_threshold" ->
                defaults[PARAM_TARGET_SHIELD_THRESHOLD] = avoidShieldDefaultPercent().toString()
            "target_shield_target_threshold" ->
                defaults[PARAM_TARGET_SHIELD_THRESHOLD] = targetShieldDefaultPercent().toString()
            "avoid_shield_flux_threshold" ->
                defaults[PARAM_TARGET_SHIELD_THRESHOLD] = avoidShieldDefaultPercent().toString()
            "target_shield_flux_threshold" ->
                defaults[PARAM_TARGET_SHIELD_THRESHOLD] = targetShieldDefaultPercent().toString()
            "ship_low_shield_flux_threshold" ->
                defaults[PARAM_THRESHOLD] = settingsPercent(Settings.shieldOffThreshold()).toString()
            "ship_vent" -> {
                defaults[PARAM_THRESHOLD] = settingsPercent(Settings.ventFluxThreshold()).toString()
                defaults[EditableShipModeDefinitions.PARAM_VENT_SAFETY_FACTOR] =
                    formatDecimalTagNumber(Settings.ventSafetyFactor().coerceIn(0.1f, 10f))
            }
            "opportunist_tuning" -> {
                defaults[PARAM_KINETIC_THRESHOLD] = opportunistKineticDefaultPercent().toString()
                defaults[PARAM_HIGH_EXPLOSIVE_THRESHOLD] = opportunistHighExplosiveDefaultPercent().toString()
                defaults[PARAM_TRIGGER_HAPPINESS] = opportunistTriggerDefaultPercent().toString()
            }
        }
        if (definition.parameters.any { it.id == PARAM_PRIORITY_MULTIPLIER }) {
            defaults[PARAM_PRIORITY_MULTIPLIER] = priorityMultiplierDefault().toString()
        }
        return defaults
    }

    private fun damageTypeExclusionDefaults(): Map<String, String> = mapOf(
        PARAM_IGNORE_KINETIC_WEAPONS to false.toString(),
        PARAM_IGNORE_HIGH_EXPLOSIVE_WEAPONS to false.toString(),
        PARAM_IGNORE_FRAGMENTATION_WEAPONS to false.toString(),
        PARAM_IGNORE_ENERGY_WEAPONS to false.toString(),
        PARAM_IGNORE_BEAM_WEAPONS to false.toString(),
        PARAM_IGNORE_MISSILE_WEAPONS to false.toString(),
        PARAM_IGNORE_PROJECTILE_WEAPONS to false.toString(),
    )

    private fun damageTypeExclusionValues(exclusions: DamageTypeExclusions): Map<String, String> {
        val active = exclusions.activeForCurrentSettings()
        return mapOf(
            PARAM_IGNORE_KINETIC_WEAPONS to active.kinetic.toString(),
            PARAM_IGNORE_HIGH_EXPLOSIVE_WEAPONS to active.highExplosive.toString(),
            PARAM_IGNORE_FRAGMENTATION_WEAPONS to active.fragmentation.toString(),
            PARAM_IGNORE_ENERGY_WEAPONS to active.energy.toString(),
            PARAM_IGNORE_BEAM_WEAPONS to active.beam.toString(),
            PARAM_IGNORE_MISSILE_WEAPONS to active.missile.toString(),
            PARAM_IGNORE_PROJECTILE_WEAPONS to active.projectile.toString(),
        )
    }

    private fun damageTypeExclusionsFromValues(values: Map<String, String>): DamageTypeExclusions =
        DamageTypeExclusions(
            kinetic = values[PARAM_IGNORE_KINETIC_WEAPONS]?.toBooleanStrictOrNull() ?: false,
            highExplosive = values[PARAM_IGNORE_HIGH_EXPLOSIVE_WEAPONS]?.toBooleanStrictOrNull() ?: false,
            fragmentation = values[PARAM_IGNORE_FRAGMENTATION_WEAPONS]?.toBooleanStrictOrNull() ?: false,
            energy = values[PARAM_IGNORE_ENERGY_WEAPONS]?.toBooleanStrictOrNull() ?: false,
            beam = values[PARAM_IGNORE_BEAM_WEAPONS]?.toBooleanStrictOrNull() ?: false,
            missile = values[PARAM_IGNORE_MISSILE_WEAPONS]?.toBooleanStrictOrNull() ?: false,
            projectile = values[PARAM_IGNORE_PROJECTILE_WEAPONS]?.toBooleanStrictOrNull() ?: false,
        ).activeForCurrentSettings()

    private fun damageTypeExclusionsFromMatch(rawTokens: String?): DamageTypeExclusions? =
        DamageTypeExclusions.fromTokens(rawTokens?.takeIf { it.isNotBlank() })

    private fun simpleDamageTypeExclusionTagSuffix(exclusions: DamageTypeExclusions): String =
        exclusions.suffix().removePrefix(",").takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()

    private fun hasHiddenChangedParameters(
        parsed: ParsedEditableWeaponTag,
        definition: EditableWeaponTagDefinition,
    ): Boolean {
        for (parameter in visibleParameters(definition)) {
            if (!parameter.useHiddenChangeMarker) continue
            val currentValue = parsed.parameterValues[parameter.id] ?: continue
            val defaultValue = defaultValuesFor(definition)[parameter.id] ?: continue
            if (currentValue != defaultValue) return true
        }
        return false
    }

    private fun settingsPercent(value: Float, min: Int = 0, max: Int = 100): Int =
        (value * 100f).roundToInt().coerceIn(min, max)

    private fun isParameterVisible(parameter: EditableTagParameterDefinition): Boolean =
        when (parameter.id) {
            PARAM_IGNORE_ENERGY_WEAPONS -> Settings.showEnergyDamageTypeExclusionOption()
            PARAM_IGNORE_MISSILE_WEAPONS -> Settings.showMissileDamageTypeExclusionOption()
            PARAM_IGNORE_PROJECTILE_WEAPONS -> Settings.showProjectileDamageTypeExclusionOption()
            else -> true
        }

    private fun formatDecimalTagNumber(value: Float): String {
        var rendered = String.format(Locale.US, "%.2f", value)
        if (rendered.contains('.')) {
            while (rendered.endsWith("0")) {
                rendered = rendered.dropLast(1)
            }
            if (rendered.endsWith(".")) {
                rendered = rendered.dropLast(1)
            }
        }
        return rendered
    }

    private fun avoidShieldDefaultPercent(): Int = settingsPercent(Settings.avoidShieldThreshold())

    private fun targetShieldDefaultPercent(): Int = settingsPercent(Settings.targetShieldThreshold())

    private fun opportunistKineticDefaultPercent(): Int =
        settingsPercent(Settings.opportunistKineticThreshold())

    private fun opportunistHighExplosiveDefaultPercent(): Int =
        settingsPercent(Settings.opportunistHEThreshold())

    private fun opportunistTriggerDefaultPercent(): Int =
        settingsPercent(Settings.opportunistModifier(), min = 10, max = 500)

    private fun noPdWasteCleanupDamageCapDefault(): Int =
        Settings.noPDWasteCleanupDamageCap().roundToInt().coerceIn(0, 10000)

    private fun priorityMultiplierDefault(): Int =
        Settings.prioXModifier().roundToInt().coerceIn(1, 10000)

    private fun totalFluxCapDefaultPercent(): Int =
        settingsPercent(Settings.softFluxTotalFluxCap(), min = 1, max = 100)

    private fun totalFluxCapAppliesToMetric(
        definition: EditableWeaponTagDefinition,
        metric: String,
    ): Boolean {
        return definition.id != "hold_fire_flux_threshold" || metric == "SF"
    }

    private fun fluxConditionDefinitions(): List<EditableWeaponTagDefinition> = listOf(
        fluxThresholdDefinition(
            id = "hold_fire_flux_threshold",
            family = "HoldFire",
            representativeTemplateTag = "HoldFire(TF>N%)",
            acceptedTemplateTags = setOf("HoldFire(TF>N%)", "HoldFire(SF>N%)", "HoldFire(HF>N%)"),
            comparator = ">",
            thresholdMinimum = 0,
            thresholdMaximum = 99,
            defaultMetric = "TF",
            defaultThreshold = 25,
            exclusivityPrefix = "HoldFire",
            includeTotalFluxCap = true,
            totalFluxCapAllowedMetricIds = setOf("SF"),
            includeRecentBeamException = true,
        ),
        fluxThresholdDefinition(
            id = "force_fire_flux_threshold",
            family = "Force",
            representativeTemplateTag = "Force(TF<N%)",
            acceptedTemplateTags = setOf("Force(TF<N%)", "Force(SF<N%)", "Force(HF<N%)"),
            comparator = "<",
            thresholdMinimum = 0,
            thresholdMaximum = 99,
            defaultMetric = "TF",
            defaultThreshold = 25,
            exclusivityPrefix = "Force",
            includeTotalFluxCap = true,
        ),
        shieldThresholdDefinition(
            id = "avoid_shield_target_threshold",
            family = "AvoidShield",
            representativeTemplateTag = "AvoidShield(S<N%)",
            acceptedTemplateTags = setOf("AvoidShield", "AvoidShield+", "AvoidShield(S<N%)"),
            comparator = "<",
            defaultThresholdProvider = ::avoidShieldDefaultPercent,
            aliasThresholds = mapOf("AvoidShield+" to 2),
            exclusivityPrefix = "AvoidShield:S",
        ),
        shieldThresholdDefinition(
            id = "target_shield_target_threshold",
            family = "TargetShield",
            representativeTemplateTag = "TargetShield(S>N%)",
            acceptedTemplateTags = setOf("TargetShield", "TargetShield+", "TargetShield(S>N%)"),
            comparator = ">",
            defaultThresholdProvider = ::targetShieldDefaultPercent,
            aliasThresholds = mapOf("TargetShield+" to 1),
            exclusivityPrefix = "TargetShield:S",
        ),
        fluxThresholdDefinition(
            id = "avoid_shield_flux_threshold",
            family = "AvoidShield",
            representativeTemplateTag = "AvoidShield(TF>N%)",
            acceptedTemplateTags = setOf("AvoidShield(TF>N%)", "AvoidShield(SF>N%)", "AvoidShield(HF>N%)"),
            comparator = ">",
            thresholdMinimum = 0,
            thresholdMaximum = 99,
            defaultMetric = "TF",
            defaultThreshold = 10,
            exclusivityPrefix = "AvoidShield",
            includeTotalFluxCap = true,
            targetShieldThresholdComparator = "<",
            defaultTargetShieldThresholdProvider = ::avoidShieldDefaultPercent,
            includeDamageTypeExclusions = true,
        ),
        fluxThresholdDefinition(
            id = "target_shield_flux_threshold",
            family = "TargetShield",
            representativeTemplateTag = "TargetShield(TF>N%)",
            acceptedTemplateTags = setOf("TargetShield(TF>N%)", "TargetShield(SF>N%)", "TargetShield(HF>N%)"),
            comparator = ">",
            thresholdMinimum = 0,
            thresholdMaximum = 99,
            defaultMetric = "TF",
            defaultThreshold = 10,
            exclusivityPrefix = "TargetShield",
            includeTotalFluxCap = true,
            targetShieldThresholdComparator = ">",
            defaultTargetShieldThresholdProvider = ::targetShieldDefaultPercent,
            includeDamageTypeExclusions = true,
        ),
        fluxThresholdDefinition(
            id = "pd_flux_threshold",
            family = "PD",
            representativeTemplateTag = "PD(TF>N%)",
            acceptedTemplateTags = setOf("PD(TF>N%)", "PD(SF>N%)", "PD(HF>N%)"),
            comparator = ">",
            thresholdMinimum = 0,
            thresholdMaximum = 99,
            defaultMetric = "TF",
            defaultThreshold = 50,
            exclusivityPrefix = "PD"
        )
    )

    private fun ammoAndTargetingThresholdDefinitions(): List<EditableWeaponTagDefinition> = listOf(
        opportunistAmmoDefinition(),
        opportunistTuningDefinition(),
        integerThresholdDefinition(
            id = "pd_ammo_threshold",
            family = "PD",
            representativeTemplateTag = "PD(A<N%)",
            acceptedTemplateTags = setOf("PD(A<N%)", "ConservePDAmmo", "CnsrvPDAmmo"),
            label = "Ammo below",
            prefix = "PD(A<",
            suffix = "%)",
            thresholdMinimum = 0,
            thresholdMaximum = 99,
            defaultThreshold = 80,
            exclusivityPrefix = "PD:A"
        ),
        noPdWasteDefinition(),
        integerThresholdDefinition(
            id = "no_pd_health_threshold",
            family = "AvoidPD",
            representativeTemplateTag = "AvoidPD(H<N)",
            acceptedTemplateTags = setOf("AvoidPD(H<N>)", "AvoidPD(H<N)", "NoPD(H<N>)", "NoPD(H<N)", "IgnoreMinorPD"),
            label = "Health below",
            prefix = "AvoidPD(H<",
            suffix = ")",
            thresholdMinimum = 1,
            thresholdMaximum = 9999,
            defaultThreshold = 145,
            exclusivityPrefix = "AvoidPD:H"
        ),
        integerThresholdDefinition(
            id = "avoid_armor_threshold",
            family = "AvoidArmor",
            representativeTemplateTag = "AvoidArmor(N%)",
            acceptedTemplateTags = setOf("AvoidArmor", "AvoidArmor(N%)"),
            label = "Armor effectiveness",
            prefix = "AvoidArmor(",
            suffix = "%)",
            thresholdMinimum = 0,
            thresholdMaximum = 100,
            defaultThreshold = 33,
            exclusivityPrefix = "AvoidArmor",
            includeDamageTypeExclusions = true,
        ),
        integerThresholdDefinition(
            id = "panic_hull_threshold",
            family = "Panic",
            representativeTemplateTag = "Panic(H<N%)",
            acceptedTemplateTags = setOf("Panic", "Panic(H<N%)"),
            label = "Hull below",
            prefix = "Panic(H<",
            suffix = "%)",
            thresholdMinimum = 0,
            thresholdMaximum = 99,
            defaultThreshold = 25,
            exclusivityPrefix = "Panic:H"
        ),
        integerThresholdDefinition(
            id = "range_threshold",
            family = "Range",
            representativeTemplateTag = "Range<N%",
            acceptedTemplateTags = setOf("Range", "Range<N%"),
            label = "Range limit",
            prefix = "Range<",
            suffix = "%",
            thresholdMinimum = 1,
            thresholdMaximum = 99,
            defaultThreshold = 60,
            exclusivityPrefix = "Range"
        ),
        integerThresholdDefinition(
            id = "low_rof_threshold",
            family = "LowRoF",
            representativeTemplateTag = "LowRoF(N%)",
            acceptedTemplateTags = setOf("LowRoF(N%)", "LowRoF(200%)"),
            label = "Rate-of-fire factor",
            prefix = "LowRoF(",
            suffix = "%)",
            thresholdMinimum = 1,
            thresholdMaximum = 999,
            defaultThreshold = 200,
            exclusivityPrefix = "LowRoF"
        )
    )

    private fun simpleDamageExclusionDefinitions(): List<EditableWeaponTagDefinition> = listOf(
        simpleDamageExclusionDefinition(
            id = "target_phase_damage_type_exclusions",
            family = "TargetPhase",
        ),
        simpleDamageExclusionDefinition(
            id = "avoid_phased_damage_type_exclusions",
            family = "AvoidPhased",
        ),
        simpleDamageExclusionDefinition(
            id = "prio_wounded_damage_type_exclusions",
            family = "PrioWounded",
        ),
    )

    private fun priorityMultiplierDefinitions(): List<EditableWeaponTagDefinition> = listOf(
        priorityMultiplierDefinition(
            id = "prio_small_multiplier",
            family = "PrioSmall",
            representativeTemplateTag = "PrioSmall(N)",
            acceptedTemplateTags = setOf("PrioSmall", "PrioSmall(N)", "PrioPD", "PrioritizePD", "PrioritisePD"),
        ),
        priorityMultiplierDefinition(
            id = "prio_fighter_multiplier",
            family = "PrioFighter",
            representativeTemplateTag = "PrioFighter(N)",
            acceptedTemplateTags = setOf("PrioFighter", "PrioFighter(N)"),
        ),
        priorityMultiplierDefinition(
            id = "prio_missile_multiplier",
            family = "PrioMissile",
            representativeTemplateTag = "PrioMissile(N)",
            acceptedTemplateTags = setOf("PrioMissile", "PrioMissile(N)"),
        ),
        priorityMultiplierDefinition(
            id = "prio_ship_multiplier",
            family = "PrioShip",
            representativeTemplateTag = "PrioShip(N)",
            acceptedTemplateTags = setOf("PrioShip", "PrioShips", "PrioShip(N)"),
        ),
        priorityMultiplierDefinition(
            id = "prio_focused_multiplier",
            family = "PrioFocused",
            representativeTemplateTag = "PrioFocused(N)",
            acceptedTemplateTags = setOf("PrioFocused", "PrioFocused(N)"),
        ),
        priorityMultiplierDefinition(
            id = "prio_shields_multiplier",
            family = "PrioShields",
            representativeTemplateTag = "PrioShields(N)",
            acceptedTemplateTags = setOf("PrioShields", "PrioShields(N)"),
            includeDamageTypeExclusions = true,
        ),
        priorityMultiplierDefinition(
            id = "prio_hull_multiplier",
            family = "PrioHull",
            representativeTemplateTag = "PrioHull(N)",
            acceptedTemplateTags = setOf("PrioHull", "PrioHull(N)"),
            includeDamageTypeExclusions = true,
        ),
        priorityMultiplierDefinition(
            id = "prio_close_multiplier",
            family = "PrioClose",
            representativeTemplateTag = "PrioClose(N)",
            acceptedTemplateTags = setOf("PrioClose", "PrioClose(N)"),
        ),
        priorityMultiplierDefinition(
            id = "prio_far_multiplier",
            family = "PrioFar",
            representativeTemplateTag = "PrioFar(N)",
            acceptedTemplateTags = setOf("PrioFar", "PrioFar(N)"),
        )
    )

    private fun synchronizedAttackDefinitions(): List<EditableWeaponTagDefinition> = listOf(
        syncTargetGateDefinition(
            id = "sync_window_ship_target",
            family = "SyncWindow",
        ),
        syncTargetGateDefinition(
            id = "sync_volley_ship_target",
            family = "SyncVolley",
        ),
        syncTargetGateDefinition(
            id = "ambush_ship_target",
            family = "Ambush",
        )
    )

    private fun fluxThresholdDefinition(
        id: String,
        family: String,
        representativeTemplateTag: String,
        acceptedTemplateTags: Set<String>,
        comparator: String,
        thresholdMinimum: Int,
        thresholdMaximum: Int,
        defaultMetric: String,
        defaultThreshold: Int,
        exclusivityPrefix: String,
        includeTotalFluxCap: Boolean = false,
        totalFluxCapAllowedMetricIds: Set<String> = setOf("TF", "SF", "HF"),
        includeRecentBeamException: Boolean = false,
        targetShieldThresholdComparator: String? = null,
        defaultTargetShieldThresholdProvider: (() -> Int)? = null,
        includeDamageTypeExclusions: Boolean = false,
    ): EditableWeaponTagDefinition {
        val defaultTargetShieldThreshold = defaultTargetShieldThresholdProvider?.invoke()
        val totalFluxCapPattern = if (includeTotalFluxCap) "(?:,TF<(\\d+)%)?" else ""
        val beamWindowPattern = if (includeRecentBeamException) "(?:,Beam<(\\d+(?:\\.\\d+)?)s)?" else ""
        val damageTypeExclusionPattern = if (includeDamageTypeExclusions) OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN else ""
        val shieldThresholdPattern = targetShieldThresholdComparator
            ?.let { "(?:,S${Regex.escape(it)}(\\d+)%)?" }
            ?: ""
        val regex = Regex("${Regex.escape(family)}\\((TF|SF|HF)${Regex.escape(comparator)}(\\d+)%$shieldThresholdPattern$totalFluxCapPattern$beamWindowPattern$damageTypeExclusionPattern\\)")
        val parameters = mutableListOf<EditableTagParameterDefinition>(
            ChoiceParameter(
                id = PARAM_FLUX_METRIC,
                label = "Flux type",
                options = fluxMetricOptions,
                defaultOptionId = defaultMetric
            ),
            NumberParameter(
                id = PARAM_THRESHOLD,
                label = "Threshold",
                minValue = thresholdMinimum,
                maxValue = thresholdMaximum,
                defaultValue = defaultThreshold,
                suffix = "%"
            )
        )
        if (targetShieldThresholdComparator != null && defaultTargetShieldThreshold != null) {
            parameters += NumberParameter(
                id = PARAM_TARGET_SHIELD_THRESHOLD,
                label = "Target shield",
                minValue = 0,
                maxValue = 100,
                defaultValue = defaultTargetShieldThreshold,
                suffix = "%",
                markHiddenChange = true,
            )
        }
        if (includeTotalFluxCap) {
            parameters += NumberParameter(
                id = PARAM_TOTAL_FLUX_CAP,
                label = "Max TF",
                minValue = 1,
                maxValue = 100,
                defaultValue = totalFluxCapDefaultPercent(),
                suffix = "%"
            )
        }
        if (includeRecentBeamException) {
            parameters += ToggleParameter(
                id = PARAM_IGNORE_IF_BEAMED,
                label = "Ignore if beamed",
                defaultValue = false,
                markHiddenChange = true,
            )
            parameters += DecimalParameter(
                id = PARAM_BEAM_WINDOW,
                label = "Beam window",
                minValue = 0.1f,
                maxValue = 10f,
                defaultValue = HOLD_FIRE_BEAM_WINDOW_DEFAULT_SECONDS,
                minorStep = 0.1f,
                majorStep = 1f,
                decimalPlaces = 2,
                suffix = "s",
                markHiddenChange = true,
            )
        }
        if (includeDamageTypeExclusions) {
            parameters += damageTypeExclusionParameters
        }
        val defaults = mutableMapOf(
            PARAM_FLUX_METRIC to defaultMetric,
            PARAM_THRESHOLD to defaultThreshold.toString()
        )
        if (includeTotalFluxCap) {
            defaults[PARAM_TOTAL_FLUX_CAP] = totalFluxCapDefaultPercent().toString()
        }
        if (includeRecentBeamException) {
            defaults[PARAM_IGNORE_IF_BEAMED] = false.toString()
            defaults[PARAM_BEAM_WINDOW] = formatDecimalTagNumber(HOLD_FIRE_BEAM_WINDOW_DEFAULT_SECONDS)
        }
        if (targetShieldThresholdComparator != null && defaultTargetShieldThreshold != null) {
            defaults[PARAM_TARGET_SHIELD_THRESHOLD] = defaultTargetShieldThreshold.toString()
        }
        if (includeDamageTypeExclusions) {
            defaults += damageTypeExclusionDefaults()
        }

        return EditableWeaponTagDefinition(
            id = id,
            family = family,
            templateTag = representativeTemplateTag,
            acceptedTemplateTags = acceptedTemplateTags,
            parameters = parameters,
            defaultValues = defaults,
            parser = parser@{ canonicalTag ->
                val match = regex.matchEntire(canonicalTag) ?: return@parser null
                val metric = match.groupValues[1]
                val threshold = match.groupValues[2]
                var nextGroupIndex = 3
                val targetShieldThreshold = if (targetShieldThresholdComparator != null) {
                    match.groupValues.getOrNull(nextGroupIndex++)
                        ?.takeIf { it.isNotBlank() }
                        ?: defaultTargetShieldThresholdProvider?.invoke()?.toString()
                } else {
                    null
                }
                val totalFluxCap = if (includeTotalFluxCap) {
                    val parsedCap = match.groupValues.getOrNull(nextGroupIndex++)
                        ?.takeIf { it.isNotBlank() }
                    if (metric in totalFluxCapAllowedMetricIds) {
                        parsedCap ?: totalFluxCapDefaultPercent().toString()
                    } else {
                        null
                    }
                } else {
                    null
                }
                val beamWindow = if (includeRecentBeamException) {
                    match.groupValues.getOrNull(nextGroupIndex++)
                        ?.takeIf { it.isNotBlank() }
                        ?.toFloatOrNull()
                } else {
                    null
                }
                val damageTypeExclusions = if (includeDamageTypeExclusions) {
                    damageTypeExclusionsFromMatch(match.groupValues.getOrNull(nextGroupIndex))
                        ?: return@parser null
                } else {
                    DamageTypeExclusions.NONE
                }
                if (beamWindow != null && metric != "SF") return@parser null
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = id,
                    family = family,
                    templateTag = tagNameToRegexName(canonicalTag),
                    parameterValues = buildMap {
                        put(PARAM_FLUX_METRIC, metric)
                        put(PARAM_THRESHOLD, threshold)
                        if (targetShieldThreshold != null) put(PARAM_TARGET_SHIELD_THRESHOLD, targetShieldThreshold)
                        if (totalFluxCap != null) put(PARAM_TOTAL_FLUX_CAP, totalFluxCap)
                        if (includeRecentBeamException) {
                            put(PARAM_IGNORE_IF_BEAMED, (beamWindow != null).toString())
                            put(
                                PARAM_BEAM_WINDOW,
                                formatDecimalTagNumber(beamWindow ?: HOLD_FIRE_BEAM_WINDOW_DEFAULT_SECONDS)
                            )
                        }
                        if (includeDamageTypeExclusions) {
                            putAll(damageTypeExclusionValues(damageTypeExclusions))
                        }
                    },
                    // Family alone is too broad: TF and SF variants can be compatible if the tag declares distinct slots.
                    exclusivityKeys = setOf("$exclusivityPrefix:$metric")
                )
            },
            builder = { values ->
                val metric = values[PARAM_FLUX_METRIC] ?: defaultMetric
                val thresholdText = values[PARAM_THRESHOLD] ?: defaultThreshold.toString()
                val currentDefaultTargetShieldThreshold = defaultTargetShieldThresholdProvider?.invoke()
                val currentDefaultTotalFluxCap = totalFluxCapDefaultPercent()
                val targetShieldThresholdText =
                    values[PARAM_TARGET_SHIELD_THRESHOLD] ?: currentDefaultTargetShieldThreshold?.toString()
                val totalFluxCapText = values[PARAM_TOTAL_FLUX_CAP] ?: currentDefaultTotalFluxCap.toString()
                val ignoreIfBeamed = values[PARAM_IGNORE_IF_BEAMED]?.toBooleanStrictOrNull() ?: false
                val beamWindowText = values[PARAM_BEAM_WINDOW] ?: formatDecimalTagNumber(HOLD_FIRE_BEAM_WINDOW_DEFAULT_SECONDS)
                val damageTypeExclusions = damageTypeExclusionsFromValues(values)
                val errors = mutableListOf<EditableTagValidationError>()
                val totalFluxCapApplies = includeTotalFluxCap && metric in totalFluxCapAllowedMetricIds

                if (metric !in fluxMetricOptions.map { it.id }) {
                    errors += EditableTagValidationError(PARAM_FLUX_METRIC, "Choose one of TF, SF, or HF.")
                }

                val threshold = thresholdText.toIntOrNull()
                if (threshold == null || threshold !in thresholdMinimum..thresholdMaximum) {
                    errors += EditableTagValidationError(
                        PARAM_THRESHOLD,
                        "Enter a whole number from $thresholdMinimum to $thresholdMaximum."
                    )
                }
                val totalFluxCap = totalFluxCapText.toIntOrNull()
                if (totalFluxCapApplies && (totalFluxCap == null || totalFluxCap !in 1..100)) {
                    errors += EditableTagValidationError(
                        PARAM_TOTAL_FLUX_CAP,
                        "Enter a whole number from 1 to 100."
                    )
                }
                val targetShieldThreshold = targetShieldThresholdText?.toIntOrNull()
                if (
                    targetShieldThresholdComparator != null &&
                    (targetShieldThreshold == null || targetShieldThreshold !in 0..100)
                ) {
                    errors += EditableTagValidationError(
                        PARAM_TARGET_SHIELD_THRESHOLD,
                        "Enter a whole number from 0 to 100."
                    )
                }
                val beamWindow = beamWindowText.toFloatOrNull()
                if (
                    includeRecentBeamException &&
                    metric == "SF" &&
                    ignoreIfBeamed &&
                    (beamWindow == null || beamWindow !in 0.1f..10f)
                ) {
                    errors += EditableTagValidationError(
                        PARAM_BEAM_WINDOW,
                        "Enter seconds from 0.1 to 10."
                    )
                }

                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else {
                    val shieldSuffix = if (
                        targetShieldThresholdComparator != null &&
                        targetShieldThreshold != null &&
                        targetShieldThreshold != currentDefaultTargetShieldThreshold
                    ) {
                        ",S$targetShieldThresholdComparator$targetShieldThreshold%"
                    } else {
                        ""
                    }
                    val capSuffix = if (
                        totalFluxCapApplies &&
                        totalFluxCap != null &&
                        totalFluxCap != currentDefaultTotalFluxCap
                    ) {
                        ",TF<$totalFluxCap%"
                    } else {
                        ""
                    }
                    val beamSuffix = if (
                        includeRecentBeamException &&
                        metric == "SF" &&
                        ignoreIfBeamed &&
                        beamWindow != null
                    ) {
                        ",Beam<${formatDecimalTagNumber(beamWindow)}s"
                    } else {
                        ""
                    }
                    val damageTypeSuffix = if (includeDamageTypeExclusions) damageTypeExclusions.suffix() else ""
                    EditableTagBuildResult(
                        canonicalTag = "$family($metric$comparator$threshold%$shieldSuffix$capSuffix$beamSuffix$damageTypeSuffix)",
                        errors = emptyList()
                    )
                }
            }
        )
    }

    private fun integerThresholdDefinition(
        id: String,
        family: String,
        representativeTemplateTag: String,
        acceptedTemplateTags: Set<String>,
        label: String,
        prefix: String,
        suffix: String,
        thresholdMinimum: Int,
        thresholdMaximum: Int,
        defaultThreshold: Int,
        exclusivityPrefix: String,
        includeDamageTypeExclusions: Boolean = false,
    ): EditableWeaponTagDefinition {
        val regex = if (includeDamageTypeExclusions && suffix.endsWith(")")) {
            val suffixBeforeClose = suffix.dropLast(1)
            Regex("${Regex.escape(prefix)}(\\d+)${Regex.escape(suffixBeforeClose)}$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
        } else {
            Regex("${Regex.escape(prefix)}(\\d+)${Regex.escape(suffix)}")
        }
        val parameters = mutableListOf<EditableTagParameterDefinition>(
            NumberParameter(
                id = PARAM_THRESHOLD,
                label = label,
                minValue = thresholdMinimum,
                maxValue = thresholdMaximum,
                defaultValue = defaultThreshold,
                suffix = if (suffix.contains("%")) "%" else ""
            )
        )
        if (includeDamageTypeExclusions) {
            parameters += damageTypeExclusionParameters
        }
        val defaults = mutableMapOf(PARAM_THRESHOLD to defaultThreshold.toString())
        if (includeDamageTypeExclusions) {
            defaults += damageTypeExclusionDefaults()
        }

        return EditableWeaponTagDefinition(
            id = id,
            family = family,
            templateTag = representativeTemplateTag,
            acceptedTemplateTags = acceptedTemplateTags,
            parameters = parameters,
            defaultValues = defaults,
            parser = parser@{ canonicalTag ->
                val match = regex.matchEntire(canonicalTag) ?: return@parser null
                val threshold = match.groupValues[1]
                val damageTypeExclusions = if (includeDamageTypeExclusions) {
                    damageTypeExclusionsFromMatch(match.groupValues.getOrNull(2))
                        ?: return@parser null
                } else {
                    DamageTypeExclusions.NONE
                }
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = id,
                    family = family,
                    templateTag = tagNameToRegexName(canonicalTag),
                    parameterValues = buildMap {
                        put(PARAM_THRESHOLD, threshold)
                        if (includeDamageTypeExclusions) {
                            putAll(damageTypeExclusionValues(damageTypeExclusions))
                        }
                    },
                    exclusivityKeys = setOf(exclusivityPrefix)
                )
            },
            builder = { values ->
                val thresholdText = values[PARAM_THRESHOLD] ?: defaultThreshold.toString()
                val threshold = thresholdText.toIntOrNull()
                val damageTypeExclusions = damageTypeExclusionsFromValues(values)
                val errors = mutableListOf<EditableTagValidationError>()
                if (threshold == null || threshold !in thresholdMinimum..thresholdMaximum) {
                    errors += EditableTagValidationError(
                        PARAM_THRESHOLD,
                        "Enter a whole number from $thresholdMinimum to $thresholdMaximum."
                    )
                }

                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else {
                    val builtSuffix = if (includeDamageTypeExclusions && suffix.endsWith(")")) {
                        "${suffix.dropLast(1)}${damageTypeExclusions.suffix()})"
                    } else {
                        suffix
                    }
                    EditableTagBuildResult(canonicalTag = "$prefix$threshold$builtSuffix", errors = emptyList())
                }
            }
        )
    }

    private fun opportunistAmmoDefinition(): EditableWeaponTagDefinition {
        val regex = Regex("Opportunist\\(A<(\\d+)%(?:,K<(\\d+)%,HE<(\\d+)%,TH<(\\d+)%)?\\)")
        fun currentDefaults(): Map<String, String> = mapOf(
            PARAM_THRESHOLD to Settings.conserveAmmo().let(::settingsPercent).coerceIn(0, 99).toString(),
            PARAM_KINETIC_THRESHOLD to opportunistKineticDefaultPercent().toString(),
            PARAM_HIGH_EXPLOSIVE_THRESHOLD to opportunistHighExplosiveDefaultPercent().toString(),
            PARAM_TRIGGER_HAPPINESS to opportunistTriggerDefaultPercent().toString(),
        )
        val parameters = listOf(
            NumberParameter(
                id = PARAM_THRESHOLD,
                label = "Ammo below",
                minValue = 0,
                maxValue = 99,
                defaultValue = currentDefaults().getValue(PARAM_THRESHOLD).toInt(),
                suffix = "%",
            ),
            NumberParameter(
                id = PARAM_KINETIC_THRESHOLD,
                label = "Kinetic",
                minValue = 0,
                maxValue = 100,
                defaultValue = opportunistKineticDefaultPercent(),
                suffix = "%",
                markHiddenChange = true,
            ),
            NumberParameter(
                id = PARAM_HIGH_EXPLOSIVE_THRESHOLD,
                label = "HE/Frag",
                minValue = 0,
                maxValue = 100,
                defaultValue = opportunistHighExplosiveDefaultPercent(),
                suffix = "%",
                markHiddenChange = true,
            ),
            NumberParameter(
                id = PARAM_TRIGGER_HAPPINESS,
                label = "Trigger",
                minValue = 10,
                maxValue = 500,
                defaultValue = opportunistTriggerDefaultPercent(),
                suffix = "%",
                markHiddenChange = true,
            )
        )
        return EditableWeaponTagDefinition(
            id = "opportunist_ammo_threshold",
            family = "Opportunist",
            templateTag = "Opportunist(A<N%)",
            acceptedTemplateTags = setOf("Opportunist(A<N%)", "ConserveAmmo"),
            parameters = parameters,
            defaultValues = currentDefaults(),
            parser = parser@{ canonicalTag ->
                val values = when {
                    canonicalTag == "ConserveAmmo" -> currentDefaults()
                    regex.matches(canonicalTag) -> {
                        val match = regex.matchEntire(canonicalTag) ?: return@parser null
                        val defaults = currentDefaults()
                        mapOf(
                            PARAM_THRESHOLD to match.groupValues[1],
                            PARAM_KINETIC_THRESHOLD to (
                                match.groupValues.getOrNull(2).takeUnless { it.isNullOrBlank() }
                                    ?: defaults.getValue(PARAM_KINETIC_THRESHOLD)
                                ),
                            PARAM_HIGH_EXPLOSIVE_THRESHOLD to (
                                match.groupValues.getOrNull(3).takeUnless { it.isNullOrBlank() }
                                    ?: defaults.getValue(PARAM_HIGH_EXPLOSIVE_THRESHOLD)
                                ),
                            PARAM_TRIGGER_HAPPINESS to (
                                match.groupValues.getOrNull(4).takeUnless { it.isNullOrBlank() }
                                    ?: defaults.getValue(PARAM_TRIGGER_HAPPINESS)
                                ),
                        )
                    }
                    else -> return@parser null
                }
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = "opportunist_ammo_threshold",
                    family = "Opportunist",
                    templateTag = "Opportunist(A<N%)",
                    parameterValues = values,
                    exclusivityKeys = setOf("Opportunist:A")
                )
            },
            builder = { values ->
                val dynamicDefaults = currentDefaults()
                val ammoText = values[PARAM_THRESHOLD] ?: dynamicDefaults.getValue(PARAM_THRESHOLD)
                val kineticText = values[PARAM_KINETIC_THRESHOLD] ?: dynamicDefaults.getValue(PARAM_KINETIC_THRESHOLD)
                val heText = values[PARAM_HIGH_EXPLOSIVE_THRESHOLD] ?: dynamicDefaults.getValue(PARAM_HIGH_EXPLOSIVE_THRESHOLD)
                val triggerText = values[PARAM_TRIGGER_HAPPINESS] ?: dynamicDefaults.getValue(PARAM_TRIGGER_HAPPINESS)
                val ammo = ammoText.toIntOrNull()
                val kinetic = kineticText.toIntOrNull()
                val he = heText.toIntOrNull()
                val trigger = triggerText.toIntOrNull()
                val errors = mutableListOf<EditableTagValidationError>()
                if (ammo == null || ammo !in 0..99) {
                    errors += EditableTagValidationError(PARAM_THRESHOLD, "Enter a whole number from 0 to 99.")
                }
                if (kinetic == null || kinetic !in 0..100) {
                    errors += EditableTagValidationError(PARAM_KINETIC_THRESHOLD, "Enter a whole number from 0 to 100.")
                }
                if (he == null || he !in 0..100) {
                    errors += EditableTagValidationError(PARAM_HIGH_EXPLOSIVE_THRESHOLD, "Enter a whole number from 0 to 100.")
                }
                if (trigger == null || trigger !in 10..500) {
                    errors += EditableTagValidationError(PARAM_TRIGGER_HAPPINESS, "Enter a whole number from 10 to 500.")
                }
                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else {
                    val tuningSuffix = if (
                        kineticText == dynamicDefaults.getValue(PARAM_KINETIC_THRESHOLD) &&
                        heText == dynamicDefaults.getValue(PARAM_HIGH_EXPLOSIVE_THRESHOLD) &&
                        triggerText == dynamicDefaults.getValue(PARAM_TRIGGER_HAPPINESS)
                    ) {
                        ""
                    } else {
                        ",K<$kinetic%,HE<$he%,TH<$trigger%"
                    }
                    EditableTagBuildResult(
                        canonicalTag = "Opportunist(A<$ammo%$tuningSuffix)",
                        errors = emptyList()
                    )
                }
            }
        )
    }

    private fun noPdWasteDefinition(): EditableWeaponTagDefinition {
        val regex = Regex("AvoidPD\\(Waste>(\\d+)%(?:,Cap<(\\d+))?\\)")
        fun currentDefaults(): Map<String, String> = mapOf(
            PARAM_THRESHOLD to "40",
            PARAM_CLEANUP_DAMAGE_CAP to noPdWasteCleanupDamageCapDefault().toString(),
        )
        val parameters = listOf(
            NumberParameter(
                id = PARAM_THRESHOLD,
                label = "Waste above",
                minValue = 0,
                maxValue = 100,
                defaultValue = 40,
                suffix = "%",
            ),
            NumberParameter(
                id = PARAM_CLEANUP_DAMAGE_CAP,
                label = "Cleanup cap",
                minValue = 0,
                maxValue = 10000,
                defaultValue = noPdWasteCleanupDamageCapDefault(),
                markHiddenChange = true,
            )
        )
        return EditableWeaponTagDefinition(
            id = "no_pd_waste_threshold",
            family = "AvoidPD",
            templateTag = "AvoidPD(Waste>N%)",
            acceptedTemplateTags = setOf("AvoidPD(Waste>N%)", "NoPD(Waste>N%)"),
            parameters = parameters,
            defaultValues = currentDefaults(),
            parser = parser@{ canonicalTag ->
                val values = when {
                    regex.matches(canonicalTag) -> {
                        val match = regex.matchEntire(canonicalTag) ?: return@parser null
                        val defaults = currentDefaults()
                        mapOf(
                            PARAM_THRESHOLD to match.groupValues[1],
                            PARAM_CLEANUP_DAMAGE_CAP to (match.groupValues.getOrNull(2).takeUnless { it.isNullOrBlank() }
                                ?: defaults.getValue(PARAM_CLEANUP_DAMAGE_CAP)),
                        )
                    }
                    else -> return@parser null
                }
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = "no_pd_waste_threshold",
                    family = "AvoidPD",
                    templateTag = "AvoidPD(Waste>N%)",
                    parameterValues = values,
                    exclusivityKeys = setOf("AvoidPD:Waste")
                )
            },
            builder = { values ->
                val dynamicDefaults = currentDefaults()
                val wasteText = values[PARAM_THRESHOLD] ?: dynamicDefaults.getValue(PARAM_THRESHOLD)
                val cleanupCapText = values[PARAM_CLEANUP_DAMAGE_CAP] ?: dynamicDefaults.getValue(PARAM_CLEANUP_DAMAGE_CAP)
                val waste = wasteText.toIntOrNull()
                val cleanupCap = cleanupCapText.toIntOrNull()
                val errors = mutableListOf<EditableTagValidationError>()
                if (waste == null || waste !in 0..100) {
                    errors += EditableTagValidationError(PARAM_THRESHOLD, "Enter a whole number from 0 to 100.")
                }
                if (cleanupCap == null || cleanupCap !in 0..10000) {
                    errors += EditableTagValidationError(PARAM_CLEANUP_DAMAGE_CAP, "Enter a whole number from 0 to 10000.")
                }
                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else {
                    val capSuffix = if (cleanupCapText == dynamicDefaults.getValue(PARAM_CLEANUP_DAMAGE_CAP)) {
                        ""
                    } else {
                        ",Cap<$cleanupCap"
                    }
                    EditableTagBuildResult(
                        canonicalTag = "AvoidPD(Waste>$waste%$capSuffix)",
                        errors = emptyList()
                    )
                }
            }
        )
    }

    private fun shieldThresholdDefinition(
        id: String,
        family: String,
        representativeTemplateTag: String,
        acceptedTemplateTags: Set<String>,
        comparator: String,
        defaultThresholdProvider: () -> Int,
        aliasThresholds: Map<String, Int> = emptyMap(),
        exclusivityPrefix: String,
    ): EditableWeaponTagDefinition {
        val defaultThreshold = defaultThresholdProvider()
        val regex = Regex("${Regex.escape(family)}\\(S${Regex.escape(comparator)}(\\d+)%$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
        val simpleExclusionRegex = Regex("${Regex.escape(family)}$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
        val parameter = NumberParameter(
            id = PARAM_TARGET_SHIELD_THRESHOLD,
            label = "Target shield",
            minValue = 0,
            maxValue = 100,
            defaultValue = defaultThreshold,
            suffix = "%",
        )
        val defaults = mutableMapOf(PARAM_TARGET_SHIELD_THRESHOLD to defaultThreshold.toString())
        defaults += damageTypeExclusionDefaults()
        return EditableWeaponTagDefinition(
            id = id,
            family = family,
            templateTag = representativeTemplateTag,
            acceptedTemplateTags = acceptedTemplateTags,
            parameters = listOf(parameter) + damageTypeExclusionParameters,
            defaultValues = defaults,
            parser = parser@{ canonicalTag ->
                val damageTypeExclusions = when {
                    simpleExclusionRegex.matches(canonicalTag) -> {
                        val match = simpleExclusionRegex.matchEntire(canonicalTag) ?: return@parser null
                        damageTypeExclusionsFromMatch(match.groupValues.getOrNull(1))
                            ?: return@parser null
                    }
                    regex.matches(canonicalTag) -> {
                        val match = regex.matchEntire(canonicalTag) ?: return@parser null
                        damageTypeExclusionsFromMatch(match.groupValues.getOrNull(2))
                            ?: return@parser null
                    }
                    else -> DamageTypeExclusions.NONE
                }
                val threshold = when {
                    canonicalTag == family -> defaultThresholdProvider().toString()
                    canonicalTag in aliasThresholds -> aliasThresholds[canonicalTag]?.toString()
                    simpleExclusionRegex.matches(canonicalTag) -> defaultThresholdProvider().toString()
                    regex.matches(canonicalTag) -> regex.matchEntire(canonicalTag)?.groupValues?.get(1)
                    else -> null
                } ?: return@parser null
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = id,
                    family = family,
                    templateTag = representativeTemplateTag,
                    parameterValues = buildMap {
                        put(PARAM_TARGET_SHIELD_THRESHOLD, threshold)
                        putAll(damageTypeExclusionValues(damageTypeExclusions))
                    },
                    exclusivityKeys = setOf(exclusivityPrefix)
                )
            },
            builder = { values ->
                val currentDefaultThreshold = defaultThresholdProvider()
                val thresholdText = values[PARAM_TARGET_SHIELD_THRESHOLD] ?: currentDefaultThreshold.toString()
                val threshold = thresholdText.toIntOrNull()
                val damageTypeExclusions = damageTypeExclusionsFromValues(values)
                val errors = mutableListOf<EditableTagValidationError>()
                if (threshold == null || threshold !in parameter.minValue..parameter.maxValue) {
                    errors += EditableTagValidationError(
                        PARAM_TARGET_SHIELD_THRESHOLD,
                        "Enter a whole number from ${parameter.minValue} to ${parameter.maxValue}."
                    )
                }
                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else if (threshold == currentDefaultThreshold) {
                    EditableTagBuildResult(
                        canonicalTag = "$family${simpleDamageTypeExclusionTagSuffix(damageTypeExclusions)}",
                        errors = emptyList()
                    )
                } else {
                    EditableTagBuildResult(
                        canonicalTag = "$family(S$comparator$threshold%${damageTypeExclusions.suffix()})",
                        errors = emptyList()
                    )
                }
            }
        )
    }

    private fun simpleDamageExclusionDefinition(
        id: String,
        family: String,
    ): EditableWeaponTagDefinition {
        val regex = Regex("${Regex.escape(family)}$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
        val defaults = damageTypeExclusionDefaults()
        return EditableWeaponTagDefinition(
            id = id,
            family = family,
            templateTag = family,
            acceptedTemplateTags = setOf(family),
            parameters = damageTypeExclusionParameters,
            defaultValues = defaults,
            parser = parser@{ canonicalTag ->
                val exclusions = when {
                    canonicalTag == family -> DamageTypeExclusions.NONE
                    regex.matches(canonicalTag) -> {
                        val match = regex.matchEntire(canonicalTag) ?: return@parser null
                        damageTypeExclusionsFromMatch(match.groupValues.getOrNull(1))
                            ?: return@parser null
                    }
                    else -> return@parser null
                }
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = id,
                    family = family,
                    templateTag = family,
                    parameterValues = damageTypeExclusionValues(exclusions),
                    exclusivityKeys = setOf(family)
                )
            },
            builder = { values ->
                val exclusions = damageTypeExclusionsFromValues(values)
                EditableTagBuildResult(
                    canonicalTag = "$family${simpleDamageTypeExclusionTagSuffix(exclusions)}",
                    errors = emptyList()
                )
            }
        )
    }

    private fun priorityMultiplierDefinition(
        id: String,
        family: String,
        representativeTemplateTag: String,
        acceptedTemplateTags: Set<String>,
        includeDamageTypeExclusions: Boolean = false,
    ): EditableWeaponTagDefinition {
        val defaultMultiplier = priorityMultiplierDefault()
        val regex = Regex("${Regex.escape(family)}\\((\\d+)${
            if (includeDamageTypeExclusions) OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN else ""
        }\\)")
        val simpleExclusionRegex = Regex("${Regex.escape(family)}$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
        val parameters = mutableListOf<EditableTagParameterDefinition>(
            NumberParameter(
                id = PARAM_PRIORITY_MULTIPLIER,
                label = "Priority multiplier",
                minValue = 1,
                maxValue = 10000,
                defaultValue = defaultMultiplier,
            )
        )
        if (includeDamageTypeExclusions) {
            parameters += damageTypeExclusionParameters
        }
        val defaults = mutableMapOf(PARAM_PRIORITY_MULTIPLIER to defaultMultiplier.toString())
        if (includeDamageTypeExclusions) {
            defaults += damageTypeExclusionDefaults()
        }

        return EditableWeaponTagDefinition(
            id = id,
            family = family,
            templateTag = representativeTemplateTag,
            acceptedTemplateTags = acceptedTemplateTags,
            parameters = parameters,
            defaultValues = defaults,
            parser = parser@{ canonicalTag ->
                val exclusions = if (includeDamageTypeExclusions && simpleExclusionRegex.matches(canonicalTag)) {
                    val match = simpleExclusionRegex.matchEntire(canonicalTag) ?: return@parser null
                    damageTypeExclusionsFromMatch(match.groupValues.getOrNull(1))
                        ?: return@parser null
                } else {
                    DamageTypeExclusions.NONE
                }
                val multiplier = when {
                    canonicalTag == family -> priorityMultiplierDefault().toString()
                    includeDamageTypeExclusions && simpleExclusionRegex.matches(canonicalTag) ->
                        priorityMultiplierDefault().toString()
                    regex.matches(canonicalTag) -> regex.matchEntire(canonicalTag)?.groupValues?.get(1)
                    else -> null
                } ?: return@parser null
                val parsedExclusions = if (regex.matches(canonicalTag)) {
                    val match = regex.matchEntire(canonicalTag) ?: return@parser null
                    damageTypeExclusionsFromMatch(match.groupValues.getOrNull(2))
                        ?: return@parser null
                } else {
                    exclusions
                }
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = id,
                    family = family,
                    templateTag = representativeTemplateTag,
                    parameterValues = buildMap {
                        put(PARAM_PRIORITY_MULTIPLIER, multiplier)
                        if (includeDamageTypeExclusions) {
                            putAll(damageTypeExclusionValues(parsedExclusions))
                        }
                    },
                    exclusivityKeys = setOf(family)
                )
            },
            builder = { values ->
                val multiplierText = values[PARAM_PRIORITY_MULTIPLIER] ?: priorityMultiplierDefault().toString()
                val multiplier = multiplierText.toIntOrNull()
                val damageTypeExclusions = damageTypeExclusionsFromValues(values)
                val errors = mutableListOf<EditableTagValidationError>()
                if (multiplier == null || multiplier !in 1..10000) {
                    errors += EditableTagValidationError(
                        PARAM_PRIORITY_MULTIPLIER,
                        "Enter a whole number from 1 to 10000."
                    )
                }

                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else if (includeDamageTypeExclusions && multiplier == priorityMultiplierDefault()) {
                    EditableTagBuildResult(
                        canonicalTag = "$family${simpleDamageTypeExclusionTagSuffix(damageTypeExclusions)}",
                        errors = emptyList()
                    )
                } else {
                    EditableTagBuildResult(
                        canonicalTag = "$family($multiplier${if (includeDamageTypeExclusions) damageTypeExclusions.suffix() else ""})",
                        errors = emptyList()
                    )
                }
            }
        )
    }

    private fun opportunistTuningDefinition(): EditableWeaponTagDefinition {
        val regex = Regex("Opportunist\\(K<(\\d+)%,HE<(\\d+)%,TH<(\\d+)%\\)")
        fun currentDefaults(): Map<String, String> = mapOf(
            PARAM_KINETIC_THRESHOLD to opportunistKineticDefaultPercent().toString(),
            PARAM_HIGH_EXPLOSIVE_THRESHOLD to opportunistHighExplosiveDefaultPercent().toString(),
            PARAM_TRIGGER_HAPPINESS to opportunistTriggerDefaultPercent().toString(),
        )
        val parameters = listOf(
            NumberParameter(
                id = PARAM_KINETIC_THRESHOLD,
                label = "Kinetic",
                minValue = 0,
                maxValue = 100,
                defaultValue = opportunistKineticDefaultPercent(),
                suffix = "%",
                markHiddenChange = true
            ),
            NumberParameter(
                id = PARAM_HIGH_EXPLOSIVE_THRESHOLD,
                label = "HE/Frag",
                minValue = 0,
                maxValue = 100,
                defaultValue = opportunistHighExplosiveDefaultPercent(),
                suffix = "%",
                markHiddenChange = true
            ),
            NumberParameter(
                id = PARAM_TRIGGER_HAPPINESS,
                label = "Trigger",
                minValue = 10,
                maxValue = 500,
                defaultValue = opportunistTriggerDefaultPercent(),
                suffix = "%",
                markHiddenChange = true
            )
        )
        val defaults = currentDefaults()

        return EditableWeaponTagDefinition(
            id = "opportunist_tuning",
            family = "Opportunist",
            templateTag = "Opportunist(K<N%,HE<N%,TH<N%)",
            acceptedTemplateTags = setOf("Opportunist", "Opportunist(K<N%,HE<N%,TH<N%)"),
            parameters = parameters,
            defaultValues = defaults,
            parser = parser@{ canonicalTag ->
                val values = when {
                    canonicalTag == "Opportunist" -> currentDefaults()
                    regex.matches(canonicalTag) -> {
                        val match = regex.matchEntire(canonicalTag) ?: return@parser null
                        mapOf(
                            PARAM_KINETIC_THRESHOLD to match.groupValues[1],
                            PARAM_HIGH_EXPLOSIVE_THRESHOLD to match.groupValues[2],
                            PARAM_TRIGGER_HAPPINESS to match.groupValues[3],
                        )
                    }
                    else -> return@parser null
                }
                ParsedEditableWeaponTag(
                    canonicalTag = canonicalTag,
                    definitionId = "opportunist_tuning",
                    family = "Opportunist",
                    templateTag = "Opportunist",
                    parameterValues = values,
                    exclusivityKeys = setOf("Opportunist")
                )
            },
            builder = { values ->
                val dynamicDefaults = currentDefaults()
                val kineticText = values[PARAM_KINETIC_THRESHOLD] ?: dynamicDefaults.getValue(PARAM_KINETIC_THRESHOLD)
                val heText = values[PARAM_HIGH_EXPLOSIVE_THRESHOLD] ?: dynamicDefaults.getValue(PARAM_HIGH_EXPLOSIVE_THRESHOLD)
                val triggerText = values[PARAM_TRIGGER_HAPPINESS] ?: dynamicDefaults.getValue(PARAM_TRIGGER_HAPPINESS)
                val kinetic = kineticText.toIntOrNull()
                val he = heText.toIntOrNull()
                val trigger = triggerText.toIntOrNull()
                val errors = mutableListOf<EditableTagValidationError>()
                if (kinetic == null || kinetic !in 0..100) {
                    errors += EditableTagValidationError(PARAM_KINETIC_THRESHOLD, "Enter a whole number from 0 to 100.")
                }
                if (he == null || he !in 0..100) {
                    errors += EditableTagValidationError(PARAM_HIGH_EXPLOSIVE_THRESHOLD, "Enter a whole number from 0 to 100.")
                }
                if (trigger == null || trigger !in 10..500) {
                    errors += EditableTagValidationError(PARAM_TRIGGER_HAPPINESS, "Enter a whole number from 10 to 500.")
                }

                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else {
                    val canonicalTag = if (
                        kineticText == dynamicDefaults.getValue(PARAM_KINETIC_THRESHOLD) &&
                        heText == dynamicDefaults.getValue(PARAM_HIGH_EXPLOSIVE_THRESHOLD) &&
                        triggerText == dynamicDefaults.getValue(PARAM_TRIGGER_HAPPINESS)
                    ) {
                        "Opportunist"
                    } else {
                        "Opportunist(K<$kinetic%,HE<$he%,TH<$trigger%)"
                    }
                    EditableTagBuildResult(canonicalTag = canonicalTag, errors = emptyList())
                }
            }
        )
    }

    private fun syncTargetGateDefinition(
        id: String,
        family: String,
    ): EditableWeaponTagDefinition {
        val triggerParameters = listOf(
            SyncSystemTriggerOption.ACCELERATED_AMMO_FEEDER to PARAM_TRIGGER_AMMO_FEEDER,
            SyncSystemTriggerOption.HIGH_ENERGY_FOCUS to PARAM_TRIGGER_HIGH_ENERGY_FOCUS,
            SyncSystemTriggerOption.LIDAR_ARRAY to PARAM_TRIGGER_LIDAR_ARRAY,
            SyncSystemTriggerOption.TEMPORAL_SHELL to PARAM_TRIGGER_TEMPORAL_SHELL,
            SyncSystemTriggerOption.ENTROPY_AMPLIFIER to PARAM_TRIGGER_ENTROPY_AMPLIFIER,
        )
        val parameters = listOf(
            ToggleParameter(
                id = PARAM_REQUIRE_SHIP_TARGET,
                label = "Require Active Ship Target",
                defaultValue = false
            )
        ) + triggerParameters.map { (trigger, parameterId) ->
            ToggleParameter(
                id = parameterId,
                label = "Trigger ${trigger.label}",
                defaultValue = false,
                markHiddenChange = true
            )
        }
        val defaults = buildMap {
            put(PARAM_REQUIRE_SHIP_TARGET, "false")
            triggerParameters.forEach { (_, parameterId) -> put(parameterId, "false") }
        }

        return EditableWeaponTagDefinition(
            id = id,
            family = family,
            templateTag = family,
            acceptedTemplateTags = setOf(family, "$family(X)"),
            parameters = parameters,
            defaultValues = defaults,
            parser = parser@{ canonicalTag ->
                val options = parseSyncTagOptions(canonicalTag)
                    ?.takeIf { it.family == family }
                    ?: return@parser null
                ParsedEditableWeaponTag(
                    canonicalTag = options.canonicalTag(),
                    definitionId = id,
                    family = family,
                    templateTag = family,
                    parameterValues = buildMap {
                        put(PARAM_REQUIRE_SHIP_TARGET, options.requireShipTarget.toString())
                        triggerParameters.forEach { (trigger, parameterId) ->
                            put(parameterId, (trigger in options.systemTriggers).toString())
                        }
                    },
                    exclusivityKeys = setOf(family)
                )
            },
            builder = { values ->
                val requireShipTarget = values[PARAM_REQUIRE_SHIP_TARGET]?.toBooleanStrictOrNull() ?: false
                val triggers = triggerParameters
                    .filter { (_, parameterId) -> values[parameterId]?.toBooleanStrictOrNull() ?: false }
                    .map { (trigger, _) -> trigger }
                    .toSet()
                EditableTagBuildResult(
                    canonicalTag = SyncTagOptions(family, requireShipTarget, triggers).canonicalTag(),
                    errors = emptyList()
                )
            }
        )
    }
}

fun clearEditableWeaponTagDefinitionCaches() {
    EditableWeaponTagDefinitions.clearCaches()
}
