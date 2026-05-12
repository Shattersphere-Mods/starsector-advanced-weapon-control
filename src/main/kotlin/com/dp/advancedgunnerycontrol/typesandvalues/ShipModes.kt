package com.dp.advancedgunnerycontrol.typesandvalues

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.utils.InShipShipModeStorage
import com.dp.advancedgunnerycontrol.utils.agcStableShipId
import com.dp.advancedgunnerycontrol.utils.doesShipHaveLocalShipModes
import com.dp.advancedgunnerycontrol.weaponais.shipais.*
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.combat.ai.BasicShipAI
import java.lang.ref.WeakReference
import java.util.Locale

enum class ShipModes {
    DEFAULT, FORCE_AUTOFIRE, PERSONALITY_OVERRIDE, PRE_AIM, SHIELDS_OFF, VENT, VENT_AGGRESSIVE,
    RETREAT, CR_RETREAT, HULL_RETREAT, NO_SYSTEM, SHIELDS_UP, SPAM_SYSTEM, CHARGE, SHIELDS_UP_PLUS,
    STAY_AWAY, FAR_AWAY, NEVER_VENT
}

const val defaultShipMode = "DEFAULT"

private val canonicalShipModeFromString = mapOf(
    "DEFAULT" to ShipModes.DEFAULT,
    "ForceAutoFire" to ShipModes.FORCE_AUTOFIRE,
    "Personality(Steady)" to ShipModes.PERSONALITY_OVERRIDE,
    "PreAim" to ShipModes.PRE_AIM,
    "LowShield(TF>50%)" to ShipModes.SHIELDS_OFF,
    "Vent(TF>75%,S=2.0)" to ShipModes.VENT,
    "Vent(TF>25%,S=0.25)" to ShipModes.VENT,
    "Run(HP<50%)" to ShipModes.RETREAT,
    "RunCR(CR<50%)" to ShipModes.CR_RETREAT,
    "RunHP(HP<50%)" to ShipModes.HULL_RETREAT,
    "DisableSystem" to ShipModes.NO_SYSTEM,
    "ShieldUp(TF<90%)" to ShipModes.SHIELDS_UP,
    "SpamSystem" to ShipModes.SPAM_SYSTEM,
    "Charge" to ShipModes.CHARGE,
    "ShieldUp+" to ShipModes.SHIELDS_UP_PLUS,
    "StayAway" to ShipModes.STAY_AWAY,
    "FarAway" to ShipModes.FAR_AWAY,
    "NeverVent" to ShipModes.NEVER_VENT,
)

private val legacyShipModeAliases = mapOf(
    "ForceAF" to ShipModes.FORCE_AUTOFIRE,
    "LowShield" to ShipModes.SHIELDS_OFF,
    "LowShields" to ShipModes.SHIELDS_OFF,
    "Vent(TF>75%)" to ShipModes.VENT,
    "Vent(Flx>75%)" to ShipModes.VENT,
    "Vent(Flux>75%)" to ShipModes.VENT,
    "VentA(TF>25%)" to ShipModes.VENT_AGGRESSIVE,
    "VntA(Flx>25%)" to ShipModes.VENT_AGGRESSIVE,
    "VntA(Flux>25%)" to ShipModes.VENT_AGGRESSIVE,
    "RunCR(CR<75/50/25%)" to ShipModes.CR_RETREAT,
    "RunHP(HP<75/50/25%)" to ShipModes.HULL_RETREAT,
    "NoSystem" to ShipModes.NO_SYSTEM,
    "ShieldUp" to ShipModes.SHIELDS_UP,
    "ShieldsUp" to ShipModes.SHIELDS_UP,
    "ShieldsUp+" to ShipModes.SHIELDS_UP_PLUS
)

val shipModeFromString = canonicalShipModeFromString + legacyShipModeAliases
val shipModeToString = mapOf(
    ShipModes.DEFAULT to "DEFAULT",
    ShipModes.FORCE_AUTOFIRE to "ForceAutoFire",
    ShipModes.PERSONALITY_OVERRIDE to "Personality(Steady)",
    ShipModes.PRE_AIM to "PreAim",
    ShipModes.SHIELDS_OFF to "LowShield(TF>50%)",
    ShipModes.VENT to "Vent(TF>75%,S=2.0)",
    ShipModes.VENT_AGGRESSIVE to "Vent(TF>25%,S=0.25)",
    ShipModes.RETREAT to "Run(HP<50%)",
    ShipModes.CR_RETREAT to "RunCR(CR<50%)",
    ShipModes.HULL_RETREAT to "RunHP(HP<50%)",
    ShipModes.NO_SYSTEM to "DisableSystem",
    ShipModes.SHIELDS_UP to "ShieldUp(TF<90%)",
    ShipModes.SPAM_SYSTEM to "SpamSystem",
    ShipModes.CHARGE to "Charge",
    ShipModes.SHIELDS_UP_PLUS to "ShieldUp+",
    ShipModes.STAY_AWAY to "StayAway",
    ShipModes.FAR_AWAY to "FarAway",
    ShipModes.NEVER_VENT to "NeverVent",
)

data class ParsedShipMode(
    val mode: ShipModes,
    val canonicalName: String,
    val personality: String? = null,
    val fluxThreshold: Float? = null,
    val hullThreshold: Float? = null,
    val crThresholds: List<Float?> = emptyList(),
    val hullThresholds: List<Float?> = emptyList(),
    val directRetreat: Boolean? = null,
    val ventSafetyFactor: Float? = null,
    val aggressiveVent: Boolean = false,
)

private val ventModeRegex = Regex("""^Vent\(TF>(\d{1,3})%(?:,S=(\d+(?:\.\d{1,2})?))?(?:,A=([TF]))?\)$""")
private val aggressiveVentModeRegex = Regex("""^VentA\(TF>(\d{1,3})%\)$""")
private val compactAggressiveVentModeRegex = Regex("""^VntA\((?:Flx|Flux)>(\d{1,3})%\)$""")
private val retreatModeRegex = Regex("""^Run\(HP<(\d{1,3})%\)$""")
private val singleCrRetreatModeRegex = Regex("""^RunCR\(CR<(\d{1,3})%(?:,DR=([TF]))?\)$""")
private val singleHullRetreatModeRegex = Regex("""^RunHP\(HP<(\d{1,3})%(?:,DR=([TF]))?\)$""")
private val crRetreatModeRegex = Regex("""^RunCR\(CR<((?:\d{1,3}|-)/(?:\d{1,3}|-)/(?:\d{1,3}|-))%(?:,DR=([TF]))?\)$""")
private val hullRetreatModeRegex = Regex("""^RunHP\(HP<((?:\d{1,3}|-)/(?:\d{1,3}|-)/(?:\d{1,3}|-))%(?:,DR=([TF]))?\)$""")
private val lowShieldModeRegex = Regex("""^LowShield\(TF>(\d{1,3})%\)$""")
private val shieldUpModeRegex = Regex("""^ShieldUp\(TF<(\d{1,3})%\)$""")
private val shieldUpPlusModeRegex = Regex("""^ShieldUp\+\(TF<(\d{1,3})%\)$""")
private val personalityModeRegex = Regex("""^Personality\(([^)]+)\)$""", RegexOption.IGNORE_CASE)

private fun formatVentSafetyFactor(value: Float): String {
    var rendered = String.format(Locale.US, "%.2f", value)
    while (rendered.endsWith("0") && rendered.substringAfter('.').length > 1) {
        rendered = rendered.dropLast(1)
    }
    return rendered
}

private val personalityIdToLabel = linkedMapOf(
    "timid" to "Timid",
    "cautious" to "Cautious",
    "steady" to "Steady",
    "aggressive" to "Aggressive",
    "reckless" to "Reckless",
)

fun shipModePersonalityLabel(personalityId: String): String? =
    personalityIdToLabel[personalityId.trim().lowercase()]

fun shipModeDisplayName(modeName: String): String {
    val parsed = parseShipMode(modeName) ?: return canonicalizeShipModeName(modeName)
    if (parsed.mode == ShipModes.PERSONALITY_OVERRIDE) {
        val personality = parsed.personality ?: return parsed.canonicalName
        return "Personality ($personality)"
    }
    return parsed.canonicalName
}

fun parseShipMode(mode: String): ParsedShipMode? {
    fun canonicalVentName(
        thresholdPercent: Int,
        safetyFactor: Float,
        aggressive: Boolean,
    ): String {
        val aggressiveSuffix = if (aggressive) ",A=T" else ""
        return "Vent(TF>$thresholdPercent%,S=${formatVentSafetyFactor(safetyFactor)}$aggressiveSuffix)"
    }

    fun parsedVent(): ParsedShipMode? {
        val match = ventModeRegex.matchEntire(mode) ?: return null
        val percent = match.groupValues.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: return null
        val aggressive = match.groupValues.getOrNull(3) == "T"
        val defaultSafety = if (aggressive) Settings.aggressiveVentSafetyFactor() else Settings.ventSafetyFactor()
        val safetyFactor = match.groupValues.getOrNull(2)
            ?.takeIf { it.isNotBlank() }
            ?.toFloatOrNull()
            ?.coerceIn(0.1f, 10f)
            ?: defaultSafety.coerceIn(0.1f, 10f)
        return ParsedShipMode(
            mode = ShipModes.VENT,
            canonicalName = canonicalVentName(percent, safetyFactor, aggressive),
            fluxThreshold = percent / 100f,
            ventSafetyFactor = safetyFactor,
            aggressiveVent = aggressive,
        )
    }

    fun parsedLegacyAggressiveVent(): ParsedShipMode? {
        val percent = aggressiveVentModeRegex.matchEntire(mode)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: compactAggressiveVentModeRegex.matchEntire(mode)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val thresholdPercent = percent.coerceIn(0, 100)
        val safetyFactor = Settings.aggressiveVentSafetyFactor().coerceIn(0.1f, 10f)
        return ParsedShipMode(
            mode = ShipModes.VENT,
            canonicalName = canonicalVentName(thresholdPercent, safetyFactor, aggressive = true),
            fluxThreshold = thresholdPercent / 100f,
            ventSafetyFactor = safetyFactor,
            aggressiveVent = true,
        )
    }

    fun parsedPercent(
        regex: Regex,
        shipMode: ShipModes,
        canonicalBuilder: (Int) -> String,
        thresholdSelector: (Float) -> ParsedShipMode,
    ): ParsedShipMode? {
        val percent = regex.matchEntire(mode)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.coerceIn(0, 100)
            ?: return null
        return thresholdSelector(percent / 100f).copy(
            mode = shipMode,
            canonicalName = canonicalBuilder(percent),
        )
    }

    fun parsedMultiThresholdRetreat(
        regex: Regex,
        shipMode: ShipModes,
        canonicalPrefix: String,
        thresholdsSetter: (List<Float?>, String) -> ParsedShipMode,
    ): ParsedShipMode? {
        val match = regex.matchEntire(mode) ?: return null
        val rawThresholds = match.groupValues.getOrNull(1) ?: return null
        val slots = rawThresholds.split("/").takeIf { it.size == 3 }
            ?.map { slot ->
                if (slot == "-") null else slot.toIntOrNull()?.coerceIn(0, 100)?.div(100f)
            }
            ?: return null
        if (slots.all { it == null }) return null
        val directRetreat = when (match.groupValues.getOrNull(2)) {
            "T" -> true
            "F" -> false
            else -> null
        }
        val canonicalSlots = slots.joinToString("/") { threshold ->
            threshold?.let { (it * 100f).toInt().toString() } ?: "-"
        }
        val directRetreatSuffix = if (directRetreat == true) ",DR=T" else ""
        return thresholdsSetter(slots, "$canonicalPrefix$canonicalSlots%$directRetreatSuffix)")
            .copy(mode = shipMode, directRetreat = directRetreat)
    }

    fun parsedSingleThresholdRetreat(
        regex: Regex,
        shipMode: ShipModes,
        metric: String,
        thresholdsSetter: (List<Float?>, String) -> ParsedShipMode,
    ): ParsedShipMode? {
        val match = regex.matchEntire(mode) ?: return null
        val threshold = match.groupValues.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)?.div(100f)
            ?: return null
        val directRetreat = when (match.groupValues.getOrNull(2)) {
            "T" -> true
            "F" -> false
            else -> null
        }
        val directRetreatSuffix = if (directRetreat == true) ",DR=T" else ""
        val percent = (threshold * 100f).toInt()
        return thresholdsSetter(listOf(threshold), "Run$metric($metric<$percent%$directRetreatSuffix)")
            .copy(mode = shipMode, directRetreat = directRetreat)
    }

    fun parsedPersonality(): ParsedShipMode? {
        val personality = personalityModeRegex.matchEntire(mode)?.groupValues?.getOrNull(1)
            ?.let(::shipModePersonalityLabel)
            ?: return null
        return ParsedShipMode(
            mode = ShipModes.PERSONALITY_OVERRIDE,
            canonicalName = "Personality($personality)",
            personality = personality,
        )
    }

    return parsedPersonality() ?: parsedVent() ?: parsedLegacyAggressiveVent() ?: parsedPercent(
        regex = retreatModeRegex,
        shipMode = ShipModes.RETREAT,
        canonicalBuilder = { "Run(HP<$it%)" },
        thresholdSelector = { ParsedShipMode(ShipModes.RETREAT, "", hullThreshold = it) },
    ) ?: parsedSingleThresholdRetreat(
        regex = singleCrRetreatModeRegex,
        shipMode = ShipModes.CR_RETREAT,
        metric = "CR",
        thresholdsSetter = { slots, canonicalName ->
            ParsedShipMode(ShipModes.CR_RETREAT, canonicalName, crThresholds = slots)
        },
    ) ?: parsedMultiThresholdRetreat(
        regex = crRetreatModeRegex,
        shipMode = ShipModes.CR_RETREAT,
        canonicalPrefix = "RunCR(CR<",
        thresholdsSetter = { slots, canonicalName ->
            ParsedShipMode(ShipModes.CR_RETREAT, canonicalName, crThresholds = slots)
        },
    ) ?: parsedSingleThresholdRetreat(
        regex = singleHullRetreatModeRegex,
        shipMode = ShipModes.HULL_RETREAT,
        metric = "HP",
        thresholdsSetter = { slots, canonicalName ->
            ParsedShipMode(ShipModes.HULL_RETREAT, canonicalName, hullThresholds = slots)
        },
    ) ?: parsedMultiThresholdRetreat(
        regex = hullRetreatModeRegex,
        shipMode = ShipModes.HULL_RETREAT,
        canonicalPrefix = "RunHP(HP<",
        thresholdsSetter = { slots, canonicalName ->
            ParsedShipMode(ShipModes.HULL_RETREAT, canonicalName, hullThresholds = slots)
        },
    ) ?: parsedPercent(
        regex = lowShieldModeRegex,
        shipMode = ShipModes.SHIELDS_OFF,
        canonicalBuilder = { "LowShield(TF>$it%)" },
        thresholdSelector = { ParsedShipMode(ShipModes.SHIELDS_OFF, "", fluxThreshold = it) },
    ) ?: parsedPercent(
        regex = shieldUpModeRegex,
        shipMode = ShipModes.SHIELDS_UP,
        canonicalBuilder = { "ShieldUp(TF<$it%)" },
        thresholdSelector = { ParsedShipMode(ShipModes.SHIELDS_UP, "", fluxThreshold = it) },
    ) ?: parsedPercent(
        regex = shieldUpPlusModeRegex,
        shipMode = ShipModes.SHIELDS_UP_PLUS,
        canonicalBuilder = { "ShieldUp+(TF<$it%)" },
        thresholdSelector = { ParsedShipMode(ShipModes.SHIELDS_UP_PLUS, "", fluxThreshold = it) },
    ) ?: when (mode) {
        "LowShield", "LowShields" -> {
            val threshold = Settings.shieldOffThreshold().coerceIn(0f, 1f)
            ParsedShipMode(
                mode = ShipModes.SHIELDS_OFF,
                canonicalName = "LowShield(TF>${(threshold * 100f).toInt()}%)",
                fluxThreshold = threshold,
            )
        }
        "ShieldUp", "ShieldsUp" -> ParsedShipMode(
            mode = ShipModes.SHIELDS_UP,
            canonicalName = "ShieldUp(TF<90%)",
            fluxThreshold = 0.9f,
        )
        "ShieldUp+", "ShieldsUp+" -> ParsedShipMode(
            mode = ShipModes.SHIELDS_UP_PLUS,
            canonicalName = "ShieldUp+(TF<100%)",
            fluxThreshold = 1f,
        )
        else -> null
    } ?: shipModeFromString[mode]?.let { exactMode ->
        ParsedShipMode(
            mode = exactMode,
            canonicalName = shipModeToString[exactMode] ?: mode,
        )
    }
}

fun isSupportedShipModeName(mode: String): Boolean = parseShipMode(mode) != null

fun canonicalizeShipModeName(mode: String): String = parseShipMode(mode)?.canonicalName ?: mode

fun canonicalizeShipModeNames(modes: List<String>): List<String> {
    val canonicalModes = mutableListOf<String>()
    modes.map(::canonicalizeShipModeName).forEach { mode ->
        canonicalModes.removeAll { existing -> areShipModesMutuallyExclusive(mode, existing) }
        if (mode !in canonicalModes) {
            canonicalModes += mode
        }
    }
    return canonicalModes
}

fun areShipModesMutuallyExclusive(candidate: String, existing: String): Boolean {
    val candidateMode = parseShipMode(candidate)?.mode ?: return false
    val existingMode = parseShipMode(existing)?.mode ?: return false
    return candidateMode == ShipModes.PERSONALITY_OVERRIDE &&
        existingMode == ShipModes.PERSONALITY_OVERRIDE
}

fun officialShipModeNames(): List<String> = listOf(
    "PreAim",
    "Vent(TF>75%,S=2.0)",
    "Vent(TF>25%,S=0.25)",
    "LowShield(TF>50%)",
    "ShieldUp(TF<90%)",
    "RunCR(CR<50%)",
    "RunHP(HP<50%)",
    "Personality(Steady)",
    "DisableSystem",
    "SpamSystem",
    "Charge",
    "FarAway",
    "StayAway",
    "NeverVent",
)

fun classicShipModeNames(): List<String> = officialShipModeNames()

fun fullShipModeNames(): List<String> = officialShipModeNames()

fun classicShipModeList(): List<ShipModes> = classicShipModeNames().mapNotNull { parseShipMode(it)?.mode }.distinct()

fun fullShipModeList(): List<ShipModes> = fullShipModeNames().mapNotNull { parseShipMode(it)?.mode }.distinct()

val detailedShipModeDescriptions = mapOf(
    ShipModes.DEFAULT to "Clears other ship modes and restores default ship AI.",
    ShipModes.FORCE_AUTOFIRE to "Forces autofire for all weapon groups. Use this when ships need to obey weapon tags literally." +
            "\nUse with caution and combine with HoldFire tags to prevent the ship from fluxing out.",
    ShipModes.PERSONALITY_OVERRIDE to "Overrides the ship AI personality while this ship mode is active. The personality can be edited in the custom ship-mode editor.",
    ShipModes.PRE_AIM to "Lets custom weapon AI aim at the ship's current target even when that target is out of weapon range. This minimizes time-to-bear without changing fire permission.",
    ShipModes.SHIELDS_OFF to "Turns shields off when ship flux exceeds ${(Settings.shieldOffThreshold() * 100f).toInt()}%. " +
            "Make sure you have enough armor/PD to pull this off.",
    ShipModes.VENT to "Vents when ship flux exceeds ${(Settings.ventFluxThreshold() * 100f).toInt()}%. The ship tries to evaluate " +
            "the situation and only vent if it believes" +
            " that it will survive doing so.\nThe ship will feel safer if it has high armor/hull and enemies lack high DPS HE weapons" +
            " and finisher missiles, or if there are many allies nearby. Works best on big, heavily armored ships.",
    ShipModes.VENT_AGGRESSIVE to "Legacy aggressive vent alias for ${shipModeToString[ShipModes.VENT]}. It vents at " +
            "${(Settings.aggressiveVentFluxThreshold() * 100f).toInt()}% flux and with much less concern for" +
            " the ship's survival. It will also prevent the AI from backing off while venting. Use with caution!",
    ShipModes.RETREAT to "Orders the ship to retreat when hull is below ${(Settings.retreatHullThreshold() * 100f).toInt()}%. This uses one command point.",
    ShipModes.CR_RETREAT to "Orders the ship to retreat when combat readiness crosses an enabled threshold. By default this checks 75%, 50%, and 25% CR, and each threshold can be disabled in the custom ship-mode editor.",
    ShipModes.HULL_RETREAT to "Orders the ship to retreat when hull crosses an enabled threshold. By default this checks 75%, 50%, and 25% hull, and each threshold can be disabled in the custom ship-mode editor.",
    ShipModes.NO_SYSTEM to "Prevents the ship from using its ship system.",
    ShipModes.SHIELDS_UP to "Keeps shields up while flux is below 90% and enemies are within weapon range.",
    ShipModes.SHIELDS_UP_PLUS to "Keeps shields up until flux reaches 100%.",
    ShipModes.STAY_AWAY to "Moves backward away from enemies that get too close.",
    ShipModes.FAR_AWAY to "Experimental. Tries to stay far away from all enemies." +
            "\nWhen relevant enemy ships are nearby, the ship will analyze the enemy ship density and select a point " +
            "where there are fewest enemies both at the point and on the route to the point and try to move there.",
    ShipModes.SPAM_SYSTEM to "Uses the ship system whenever it is available.",
    ShipModes.CHARGE to "Accelerates toward the current target until all weapons are in range." +
            "\nCaution: may cause suicidal behavior.",
    ShipModes.NEVER_VENT to "Prevents the ship from actively venting flux.",
).withDefault { it.toString() }

fun shipModeDescription(modeName: String): String {
    val parsed = parseShipMode(modeName) ?: return modeName
    val baseDescription = detailedShipModeDescriptions[parsed.mode] ?: modeName
    val customThreshold = when (parsed.mode) {
        ShipModes.SHIELDS_OFF -> parsed.fluxThreshold?.let { "Uses this custom threshold: shields off above ${(it * 100f).toInt()}% total flux." }
        ShipModes.PERSONALITY_OVERRIDE -> parsed.personality?.let { "Uses this custom personality: $it." }
        ShipModes.VENT -> parsed.fluxThreshold?.let {
            val safetyText = parsed.ventSafetyFactor?.let { safety -> " Safety factor ${formatVentSafetyFactor(safety)}." }.orEmpty()
            val aggressiveText = if (parsed.aggressiveVent) " Aggressive venting enabled." else ""
            "Uses this custom threshold: vent above ${(it * 100f).toInt()}% total flux.$safetyText$aggressiveText"
        }
        ShipModes.VENT_AGGRESSIVE -> parsed.fluxThreshold?.let { "Uses this custom threshold: aggressively vent above ${(it * 100f).toInt()}% total flux." }
        ShipModes.RETREAT -> parsed.hullThreshold?.let { "Uses this custom threshold: retreat below ${(it * 100f).toInt()}% hull." }
        ShipModes.CR_RETREAT -> parsed.crThresholds.takeIf { it.isNotEmpty() }?.let { thresholds ->
            val rendered = thresholds.map { threshold -> threshold?.let { "${(it * 100f).toInt()}%" } ?: "disabled" }
                .joinToString(" / ")
            val directRetreatText = if (parsed.directRetreat == true) " Uses direct retreat ordering." else ""
            "Uses these custom CR thresholds: $rendered.$directRetreatText"
        }
        ShipModes.HULL_RETREAT -> parsed.hullThresholds.takeIf { it.isNotEmpty() }?.let { thresholds ->
            val rendered = thresholds.map { threshold -> threshold?.let { "${(it * 100f).toInt()}%" } ?: "disabled" }
                .joinToString(" / ")
            val directRetreatText = if (parsed.directRetreat == true) " Uses direct retreat ordering." else ""
            "Uses these custom hull thresholds: $rendered.$directRetreatText"
        }
        ShipModes.SHIELDS_UP -> parsed.fluxThreshold?.let { "Uses this custom threshold: keep shields up below ${(it * 100f).toInt()}% total flux." }
        ShipModes.SHIELDS_UP_PLUS -> parsed.fluxThreshold?.let { "Uses this custom threshold: keep shields up below ${(it * 100f).toInt()}% total flux." }
        else -> null
    }
    return customThreshold?.let { "$baseDescription\n$it" } ?: baseDescription
}

private fun generateCommander(parsedMode: ParsedShipMode, ship: ShipAPI): ShipCommandGenerator {
    return when (parsedMode.mode) {
        ShipModes.FORCE_AUTOFIRE -> AutofireShipAI(ship)
        ShipModes.PERSONALITY_OVERRIDE -> PersonalityOverrideShipAI(ship, parsedMode.personality ?: "Steady")
        ShipModes.PRE_AIM -> PreAimShipAI(ship)
        ShipModes.SHIELDS_OFF -> ShieldOffShipAI(ship, parsedMode.fluxThreshold ?: Settings.shieldOffThreshold())
        ShipModes.VENT -> VentShipAI(
            ship,
            parsedMode.fluxThreshold ?: Settings.ventFluxThreshold(),
            parsedMode.ventSafetyFactor ?: Settings.ventSafetyFactor(),
            parsedMode.aggressiveVent,
        )
        ShipModes.VENT_AGGRESSIVE -> VentShipAI(ship, parsedMode.fluxThreshold ?: Settings.aggressiveVentFluxThreshold(), Settings.aggressiveVentSafetyFactor(), true)
        ShipModes.RETREAT -> RetreatShipAI(ship, parsedMode.hullThreshold ?: Settings.retreatHullThreshold())
        ShipModes.CR_RETREAT -> MultiThresholdRetreatShipAI(ship, parsedMode.crThresholds.filterNotNull(), RetreatThresholdMetric.CR, parsedMode.directRetreat)
        ShipModes.HULL_RETREAT -> MultiThresholdRetreatShipAI(ship, parsedMode.hullThresholds.filterNotNull(), RetreatThresholdMetric.HULL, parsedMode.directRetreat)
        ShipModes.NO_SYSTEM -> NoSystemAI(ship)
        ShipModes.SHIELDS_UP -> ShieldUpAI(ship, parsedMode.fluxThreshold ?: 0.9f)
        ShipModes.SHIELDS_UP_PLUS -> ShieldUpAI(ship, parsedMode.fluxThreshold ?: 1.1f)
        ShipModes.STAY_AWAY -> StayAwayAI(ship)
        ShipModes.FAR_AWAY -> StayFarAI(ship)
        ShipModes.SPAM_SYSTEM -> SpamSystemAI(ship)
        ShipModes.CHARGE -> ChargeShipAI(ship)
        ShipModes.NEVER_VENT -> NeverVentAI(ship)
        else -> ShipCommandGenerator(ship)
    }
}

fun shouldNotOverrideShipAI(ship: ShipAPI): Boolean{
    return ship.customData.containsKey(Values.CUSTOM_SHIP_DATA_DO_NOT_OVERWRITE_AI_KEY) || ship.shipAI?.javaClass?.name == Values.COOP_MOD_SHIP_AI_NAME
}

fun assignShipModes(modes: List<String>, ship: ShipAPI, forceAssign: Boolean = false) {
    if (ship.shipAI == null) return
    if(shouldNotOverrideShipAI(ship)) return
    // ship.resetDefaultAI()
    if (ship.customData.containsKey(Values.CUSTOM_SHIP_DATA_SHIP_AI_KEY)) {
        ship.customData.remove(Values.CUSTOM_SHIP_DATA_SHIP_AI_KEY)
    }
    val parsedShipModes = canonicalizeShipModeNames(modes).mapNotNull(::parseShipMode)
    PersonalityOverrideShipModeRuntime.syncForShipModeAssignment(ship, parsedShipModes.any { it.mode == ShipModes.PERSONALITY_OVERRIDE })
    if (!forceAssign && (parsedShipModes.any { it.mode == ShipModes.DEFAULT } || parsedShipModes.isEmpty())) return

    val baseAI = ship.shipAI ?: return

    val commanders = parsedShipModes.map { generateCommander(it, ship) }
    val customAI = CustomShipAI(baseAI, ship, commanders)
    ship.setCustomData(Values.CUSTOM_SHIP_DATA_SHIP_AI_KEY, WeakReference(customAI))
    ship.shipAI = customAI
}

fun hasCustomAI(ship: ShipAPI): Boolean {
    if (!ship.customData.containsKey(Values.CUSTOM_SHIP_DATA_SHIP_AI_KEY)) return false
    if (ship.shipAI is BasicShipAI) return false
    return (ship.customData[Values.CUSTOM_SHIP_DATA_SHIP_AI_KEY] as? WeakReference<*>)?.get() != null
}

fun getCustomShipAI(ship: ShipAPI): CustomShipAI? {
    if (!hasCustomAI(ship)) return null
    return ((ship.customData[Values.CUSTOM_SHIP_DATA_SHIP_AI_KEY] as? WeakReference<*>)?.get() as? CustomShipAI)
}

fun persistShipModes(shipId: String, loadoutIndex: Int, tags: List<String>) {
    if (shipId == "") return
    val storage = Settings.shipModeStorage.getOrNull(loadoutIndex) ?: return
    storage.modesByShip[shipId] = mutableMapOf()
    storage.modesByShip[shipId]?.set(0, canonicalizeShipModeNames(tags))
}

private fun migrateLegacyShipModeStorageIfNeeded(ship: ShipAPI): InShipShipModeStorage? {
    val existing = ship.customData[Values.CUSTOM_SHIP_DATA_SHIP_MODES_KEY] as? InShipShipModeStorage
    if (existing != null) return existing
    val legacy = ship.customData[Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY] as? InShipShipModeStorage ?: return null
    ship.setCustomData(Values.CUSTOM_SHIP_DATA_SHIP_MODES_KEY, legacy)
    ship.customData.remove(Values.CUSTOM_SHIP_DATA_WEAPONS_TAG_KEY)
    return legacy
}

private fun getLocalShipModeStorage(ship: ShipAPI): InShipShipModeStorage? {
    return (ship.customData[Values.CUSTOM_SHIP_DATA_SHIP_MODES_KEY] as? InShipShipModeStorage)
        ?: migrateLegacyShipModeStorageIfNeeded(ship)
}

private fun getOrCreateLocalShipModeStorage(ship: ShipAPI): InShipShipModeStorage {
    return getLocalShipModeStorage(ship) ?: InShipShipModeStorage().also {
        ship.setCustomData(Values.CUSTOM_SHIP_DATA_SHIP_MODES_KEY, it)
    }
}

fun saveShipModesInShip(ship: ShipAPI, tags: List<String>, storageIndex: Int) {
    getOrCreateLocalShipModeStorage(ship).modes[storageIndex] =
        canonicalizeShipModeNames(tags).toMutableList()
}

fun saveShipModes(ship: ShipAPI, loadoutIndex: Int, tags: List<String>) {
    if (Settings.enableCombatChangePersistence()) {
        val shipId = agcStableShipId(ship)
        persistShipModes(shipId, loadoutIndex, tags)
    } else {
        saveShipModesInShip(ship, tags, loadoutIndex)
    }
}

fun loadPersistedShipModes(shipId: String, loadoutIndex: Int): List<String> {
    if (shipId == "") return emptyList()
    return canonicalizeShipModeNames(
        Settings.shipModeStorage.getOrNull(loadoutIndex)?.modesByShip?.get(shipId)?.get(0) ?: emptyList()
    )
}

fun addPersistentShipMode(shipId: String, loadoutIndex: Int, mode: String){
    val modes = loadPersistedShipModes(shipId, loadoutIndex)
    val newModes = canonicalizeShipModeNames(modes + mode)
    persistShipModes(shipId, loadoutIndex, newModes)
}

fun removePersistentShipMode(shipId: String, loadoutIndex: Int, mode: String){
    val canonicalMode = canonicalizeShipModeName(mode)
    val modes = loadPersistedShipModes(shipId, loadoutIndex)
    persistShipModes(shipId, loadoutIndex, modes.filter { canonicalizeShipModeName(it) != canonicalMode })
}

fun loadShipModesFromShip(ship: ShipAPI, storageIndex: Int): List<String> {
    return canonicalizeShipModeNames(
        getLocalShipModeStorage(ship)?.modes?.get(storageIndex) ?: emptyList()
    )
}

fun loadShipModes(ship: ShipAPI, loadoutIndex: Int): List<String> {
    if (Settings.enableCombatChangePersistence()) {
        val shipId = agcStableShipId(ship)
        return loadPersistedShipModes(shipId, loadoutIndex)
    }

    migrateLegacyShipModeStorageIfNeeded(ship)
    if (!doesShipHaveLocalShipModes(ship, loadoutIndex)) {
        val shipId = agcStableShipId(ship)
        return loadPersistedShipModes(shipId, loadoutIndex)
    }

    return loadShipModesFromShip(ship, loadoutIndex)
}
