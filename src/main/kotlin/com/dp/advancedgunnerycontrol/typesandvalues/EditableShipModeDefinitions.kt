package com.dp.advancedgunnerycontrol.typesandvalues

import com.dp.advancedgunnerycontrol.settings.Settings
import java.util.Locale
import kotlin.math.roundToInt

object EditableShipModeDefinitions {
    const val PARAM_THRESHOLD = "threshold"
    private const val PARAM_PERSONALITY = "personality"
    private const val PARAM_DIRECT_RETREAT = "directRetreat"
    const val PARAM_VENT_SAFETY_FACTOR = "safetyFactor"
    private const val PARAM_VENT_AGGRESSIVE = "aggressive"
    private const val PARAM_MULTI_ENABLED_PREFIX = "enabledThreshold"
    private const val PARAM_MULTI_THRESHOLD_PREFIX = "threshold"
    private val multiThresholdRetreatDefaults = listOf(75, 50, 25)

    private fun settingsPercent(value: Float): Int = (value * 100f).roundToInt().coerceIn(0, 100)

    val definitions: List<EditableWeaponTagDefinition> = listOf(
        personalityOverrideDefinition(),
        percentThresholdDefinition(
            id = "ship_low_shield_flux_threshold",
            representativeTemplate = "LowShield(TF>N%)",
            label = "Flux above",
            mode = ShipModes.SHIELDS_OFF,
            prefix = "LowShield(TF>",
            suffix = "%)",
            defaultThresholdProvider = { Settings.shieldOffThreshold().let(::settingsPercent) },
        ),
        ventDefinition(),
        multiThresholdRetreatDefinition(
            id = "ship_cr_retreat_thresholds",
            representativeTemplate = "RunCR(CR<A/B/C%)",
            labelPrefix = "CR",
            mode = ShipModes.CR_RETREAT,
            canonicalPrefix = "RunCR(CR<",
        ),
        multiThresholdRetreatDefinition(
            id = "ship_hull_retreat_thresholds",
            representativeTemplate = "RunHP(HP<A/B/C%)",
            labelPrefix = "Hull",
            mode = ShipModes.HULL_RETREAT,
            canonicalPrefix = "RunHP(HP<",
        ),
        percentThresholdDefinition(
            id = "ship_shield_up_flux_threshold",
            representativeTemplate = "ShieldUp(TF<N%)",
            label = "Flux below",
            mode = ShipModes.SHIELDS_UP,
            prefix = "ShieldUp(TF<",
            suffix = "%)",
            defaultThresholdProvider = { 90 },
        ),
        percentThresholdDefinition(
            id = "ship_shield_up_plus_flux_threshold",
            representativeTemplate = "ShieldUp+(TF<N%)",
            label = "Flux below",
            mode = ShipModes.SHIELDS_UP_PLUS,
            prefix = "ShieldUp+(TF<",
            suffix = "%)",
            defaultThresholdProvider = { 100 },
        ),
    )

    fun definitionById(id: String): EditableWeaponTagDefinition? =
        definitions.firstOrNull { it.id == id }

    fun definitionForTemplate(template: String): EditableWeaponTagDefinition? =
        definitions.firstOrNull { it.templateTag == template }

    fun definitionForMode(modeName: String): EditableWeaponTagDefinition? {
        val parsed = parseShipMode(modeName) ?: return null
        return definitions.firstOrNull { definition ->
            parseShipMode(definition.buildCanonicalTag(definition.defaultValues).canonicalTag ?: return@firstOrNull false)
                ?.mode == parsed.mode
        }
    }

    fun parse(modeName: String): ParsedEditableWeaponTag? {
        val parsed = parseShipMode(modeName) ?: return null
        val definition = definitionForMode(parsed.canonicalName) ?: return null
        if (parsed.mode == ShipModes.PERSONALITY_OVERRIDE) {
            return parsePersonalityMode(parsed, definition)
        }
        if (parsed.mode == ShipModes.VENT) {
            return parseVentMode(parsed, definition)
        }
        val threshold = when (parsed.mode) {
            ShipModes.RETREAT -> parsed.hullThreshold
            ShipModes.CR_RETREAT -> null
            ShipModes.HULL_RETREAT -> null
            else -> parsed.fluxThreshold
        }
        if (parsed.mode == ShipModes.CR_RETREAT || parsed.mode == ShipModes.HULL_RETREAT) {
            return parseMultiThresholdRetreatMode(parsed, definition)
        }
        val thresholdValue = threshold ?: return null
        return ParsedEditableWeaponTag(
            canonicalTag = parsed.canonicalName,
            definitionId = definition.id,
            family = definition.family,
            templateTag = definition.templateTag,
            parameterValues = mapOf(PARAM_THRESHOLD to (thresholdValue * 100f).toInt().toString()),
            exclusivityKeys = setOf("ShipMode:${parsed.mode.name}"),
        )
    }

    private fun parsePersonalityMode(
        parsed: ParsedShipMode,
        definition: EditableWeaponTagDefinition,
    ): ParsedEditableWeaponTag {
        val personality = parsed.personality ?: "Steady"
        return ParsedEditableWeaponTag(
            canonicalTag = parsed.canonicalName,
            definitionId = definition.id,
            family = definition.family,
            templateTag = definition.templateTag,
            parameterValues = mapOf(PARAM_PERSONALITY to personality.lowercase()),
            exclusivityKeys = setOf("ShipMode:${parsed.mode.name}"),
        )
    }

    private fun parseVentMode(
        parsed: ParsedShipMode,
        definition: EditableWeaponTagDefinition,
    ): ParsedEditableWeaponTag {
        return ParsedEditableWeaponTag(
            canonicalTag = parsed.canonicalName,
            definitionId = definition.id,
            family = definition.family,
            templateTag = definition.templateTag,
            parameterValues = mapOf(
                PARAM_THRESHOLD to ((parsed.fluxThreshold ?: Settings.ventFluxThreshold()) * 100f).toInt().toString(),
                PARAM_VENT_SAFETY_FACTOR to formatVentParameter(parsed.ventSafetyFactor ?: Settings.ventSafetyFactor()),
                PARAM_VENT_AGGRESSIVE to parsed.aggressiveVent.toString(),
            ),
            exclusivityKeys = setOf("ShipMode:${parsed.mode.name}"),
        )
    }

    private fun parseMultiThresholdRetreatMode(
        parsed: ParsedShipMode,
        definition: EditableWeaponTagDefinition,
    ): ParsedEditableWeaponTag {
        val slots = when (parsed.mode) {
            ShipModes.CR_RETREAT -> parsed.crThresholds
            ShipModes.HULL_RETREAT -> parsed.hullThresholds
            else -> emptyList()
        }
        fun enabledValue(index: Int): String = (slots.getOrNull(index) != null).toString()
        fun thresholdValue(index: Int): String =
            (slots.getOrNull(index)?.let { (it * 100f).toInt() } ?: multiThresholdRetreatDefaults[index]).toString()
        return ParsedEditableWeaponTag(
            canonicalTag = parsed.canonicalName,
            definitionId = definition.id,
            family = definition.family,
            templateTag = definition.templateTag,
            parameterValues = multiThresholdRetreatDefaults.indices.flatMap { index ->
                listOf(
                    enabledParameterId(index) to enabledValue(index),
                    thresholdParameterId(index) to thresholdValue(index),
                )
            }.toMap() + mapOf(PARAM_DIRECT_RETREAT to (parsed.directRetreat == true).toString()),
            exclusivityKeys = setOf("ShipMode:${parsed.mode.name}"),
        )
    }

    private fun ventDefinition(): EditableWeaponTagDefinition {
        val thresholdParameter = NumberParameter(
            id = PARAM_THRESHOLD,
            label = "Flux above",
            minValue = 0,
            maxValue = 100,
            defaultValue = 75,
            suffix = "%",
        )
        val safetyParameter = DecimalParameter(
            id = PARAM_VENT_SAFETY_FACTOR,
            label = "Safety factor",
            minValue = 0.1f,
            maxValue = 10f,
            defaultValue = 2f,
            minorStep = 0.1f,
            majorStep = 1f,
            decimalPlaces = 2,
        )
        val aggressiveParameter = ToggleParameter(PARAM_VENT_AGGRESSIVE, "Aggressive vent", false)
        return EditableWeaponTagDefinition(
            id = "ship_vent",
            family = "ShipMode:${ShipModes.VENT.name}",
            templateTag = "Vent(TF>N%,S=N)",
            acceptedTemplateTags = setOf("Vent(TF>N%,S=N)", "Vent(TF>N%)", "VentA(TF>N%)"),
            parameters = listOf(thresholdParameter, safetyParameter, aggressiveParameter),
            defaultValues = mapOf(
                PARAM_THRESHOLD to thresholdParameter.defaultValue.toString(),
                PARAM_VENT_SAFETY_FACTOR to formatVentParameter(safetyParameter.defaultValue),
                PARAM_VENT_AGGRESSIVE to aggressiveParameter.defaultValue.toString(),
            ),
            parser = { canonicalMode -> parse(canonicalMode)?.takeIf { it.definitionId == "ship_vent" } },
            builder = { values ->
                val threshold = values[PARAM_THRESHOLD]?.toIntOrNull()
                val safetyFactor = values[PARAM_VENT_SAFETY_FACTOR]?.toFloatOrNull()
                val aggressive = values[PARAM_VENT_AGGRESSIVE]?.toBooleanStrictOrNull() ?: aggressiveParameter.defaultValue
                val errors = mutableListOf<EditableTagValidationError>()
                if (threshold == null || threshold !in thresholdParameter.minValue..thresholdParameter.maxValue) {
                    errors.add(EditableTagValidationError(PARAM_THRESHOLD, "Flux above must be 0-100%."))
                }
                if (safetyFactor == null || safetyFactor !in safetyParameter.minValue..safetyParameter.maxValue) {
                    errors.add(EditableTagValidationError(PARAM_VENT_SAFETY_FACTOR, "Safety factor must be 0.1-10.0."))
                }
                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else {
                    val aggressiveSuffix = if (aggressive) ",A=T" else ""
                    EditableTagBuildResult(
                        canonicalTag = canonicalizeShipModeName(
                            "Vent(TF>$threshold%,S=${formatVentParameter(safetyFactor ?: safetyParameter.defaultValue)}$aggressiveSuffix)"
                        ),
                        errors = emptyList(),
                    )
                }
            },
        )
    }

    private fun percentThresholdDefinition(
        id: String,
        representativeTemplate: String,
        label: String,
        mode: ShipModes,
        prefix: String,
        suffix: String,
        defaultThresholdProvider: () -> Int,
    ): EditableWeaponTagDefinition {
        val defaultThreshold = defaultThresholdProvider()
        val parameter = NumberParameter(
            id = PARAM_THRESHOLD,
            label = label,
            minValue = 0,
            maxValue = 100,
            defaultValue = defaultThreshold,
            suffix = "%",
        )
        return EditableWeaponTagDefinition(
            id = id,
            family = "ShipMode:${mode.name}",
            templateTag = representativeTemplate,
            parameters = listOf(parameter),
            defaultValues = mapOf(PARAM_THRESHOLD to defaultThreshold.toString()),
            parser = { canonicalMode -> parse(canonicalMode)?.takeIf { it.definitionId == id } },
            builder = { values ->
                val threshold = values[PARAM_THRESHOLD]?.toIntOrNull()
                if (threshold == null || threshold !in parameter.minValue..parameter.maxValue) {
                    EditableTagBuildResult(
                        canonicalTag = null,
                        errors = listOf(EditableTagValidationError(PARAM_THRESHOLD, "$label must be ${parameter.minValue}-${parameter.maxValue}%."))
                    )
                } else {
                    EditableTagBuildResult(
                        canonicalTag = canonicalizeShipModeName("$prefix$threshold$suffix"),
                        errors = emptyList(),
                    )
                }
            },
        )
    }

    private fun multiThresholdRetreatDefinition(
        id: String,
        representativeTemplate: String,
        labelPrefix: String,
        mode: ShipModes,
        canonicalPrefix: String,
    ): EditableWeaponTagDefinition {
        val defaultThresholds = multiThresholdRetreatDefaults
        val thresholdParameters = defaultThresholds.mapIndexed { index, default ->
            NumberParameter(thresholdParameterId(index), "$labelPrefix threshold ${index + 1}", 0, 100, default, "%")
        }
        val parameters = thresholdParameters.flatMapIndexed { index, parameter ->
            listOf(
                ToggleParameter(enabledParameterId(index), "Enable $labelPrefix threshold ${index + 1}", true),
                parameter,
            )
        } + ToggleParameter(PARAM_DIRECT_RETREAT, "Direct retreat", false)
        fun enabled(values: Map<String, String>, id: String, fallback: Boolean): Boolean =
            values[id]?.toBooleanStrictOrNull() ?: fallback
        fun threshold(values: Map<String, String>, parameter: NumberParameter): Int? =
            values[parameter.id]?.toIntOrNull()?.takeIf { it in parameter.minValue..parameter.maxValue }
        return EditableWeaponTagDefinition(
            id = id,
            family = "ShipMode:${mode.name}",
            templateTag = representativeTemplate,
            parameters = parameters,
            defaultValues = defaultThresholds.flatMapIndexed { index, threshold ->
                listOf(
                    enabledParameterId(index) to true.toString(),
                    thresholdParameterId(index) to threshold.toString(),
                )
            }.toMap() + mapOf(PARAM_DIRECT_RETREAT to false.toString()),
            parser = { canonicalMode -> parse(canonicalMode)?.takeIf { it.definitionId == id } },
            builder = { values ->
                val errors = mutableListOf<EditableTagValidationError>()
                val slotDefinitions = thresholdParameters.mapIndexed { index, parameter ->
                    enabledParameterId(index) to parameter
                }
                val slots = slotDefinitions.map { (enabledId, parameter) ->
                    val isEnabled = enabled(values, enabledId, true)
                    val value = threshold(values, parameter)
                    if (value == null) {
                        errors.add(
                            EditableTagValidationError(
                                parameter.id,
                                "${parameter.label} must be ${parameter.minValue}-${parameter.maxValue}%.",
                            )
                        )
                    }
                    if (isEnabled) value else null
                }
                if (slots.all { it == null }) {
                    errors.add(EditableTagValidationError(enabledParameterId(0), "At least one $labelPrefix retreat threshold must be enabled."))
                }
                if (errors.isNotEmpty()) {
                    EditableTagBuildResult(canonicalTag = null, errors = errors)
                } else {
                    val directRetreatSuffix = if (enabled(values, PARAM_DIRECT_RETREAT, false)) ",DR=T" else ""
                    EditableTagBuildResult(
                        canonicalTag = canonicalizeShipModeName(
                            "$canonicalPrefix${slots.joinToString("/") { it?.toString() ?: "-" }}%$directRetreatSuffix)"
                        ),
                        errors = emptyList(),
                    )
                }
            },
        )
    }

    private fun personalityOverrideDefinition(): EditableWeaponTagDefinition {
        val options = listOf("Timid", "Cautious", "Steady", "Aggressive", "Reckless")
            .map { TagParameterOption(it.lowercase(), it) }
        val parameter = ChoiceParameter(
            id = PARAM_PERSONALITY,
            label = "Personality",
            options = options,
            defaultOptionId = "steady",
        )
        return EditableWeaponTagDefinition(
            id = "ship_personality_override",
            family = "ShipMode:${ShipModes.PERSONALITY_OVERRIDE.name}",
            templateTag = "Personality(Steady)",
            parameters = listOf(parameter),
            defaultValues = mapOf(PARAM_PERSONALITY to parameter.defaultOptionId),
            parser = { canonicalMode -> parse(canonicalMode)?.takeIf { it.definitionId == "ship_personality_override" } },
            builder = { values ->
                val personality = values[PARAM_PERSONALITY]
                    ?.let(::shipModePersonalityLabel)
                if (personality == null) {
                    EditableTagBuildResult(
                        canonicalTag = null,
                        errors = listOf(EditableTagValidationError(PARAM_PERSONALITY, "Personality must be Timid, Cautious, Steady, Aggressive, or Reckless."))
                    )
                } else {
                    EditableTagBuildResult(
                        canonicalTag = canonicalizeShipModeName("Personality($personality)"),
                        errors = emptyList(),
                    )
                }
            },
        )
    }

    private fun enabledParameterId(index: Int): String =
        "$PARAM_MULTI_ENABLED_PREFIX${index + 1}"

    private fun thresholdParameterId(index: Int): String =
        "$PARAM_MULTI_THRESHOLD_PREFIX${index + 1}"

    private fun formatVentParameter(value: Float): String {
        var rendered = String.format(Locale.US, "%.2f", value)
        while (rendered.endsWith("0") && rendered.substringAfter('.').length > 1) {
            rendered = rendered.dropLast(1)
        }
        return rendered
    }
}
