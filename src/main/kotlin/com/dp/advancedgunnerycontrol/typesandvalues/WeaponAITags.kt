package com.dp.advancedgunnerycontrol.typesandvalues

import com.dp.advancedgunnerycontrol.WeaponControlPlugin
import com.dp.advancedgunnerycontrol.gui.isElligibleForPD
import com.dp.advancedgunnerycontrol.gui.isEverythingBlacklisted
import com.dp.advancedgunnerycontrol.gui.usesAmmo
import com.dp.advancedgunnerycontrol.gui.usesAmmoNonMissile
import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.utils.FluxComparator
import com.dp.advancedgunnerycontrol.utils.FluxCondition
import com.dp.advancedgunnerycontrol.utils.FluxMetric
import com.dp.advancedgunnerycontrol.utils.agcStableShipId
import com.dp.advancedgunnerycontrol.utils.loadPersistentTags
import com.dp.advancedgunnerycontrol.utils.persistTags
import com.dp.advancedgunnerycontrol.weaponais.mapBooleanToSpecificString
import com.dp.advancedgunnerycontrol.weaponais.tags.*
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import org.lwjgl.input.Keyboard
import kotlin.math.roundToInt

// NOTE: Tag names should NOT exceed 13 characters to be able to cleanly fit on buttons!

val pdTags = listOf("PD", "NoPD", "PD(TF>N%)", "PD(SF>N%)", "PD(HF>N%)", "NoMissile")
val opportunistAmmoTags = listOf("ConserveAmmo", "Opportunist(A<N%)")
val pdAmmoTags = listOf("ConservePDAmmo", "PD(A<N%)")

private const val LEGACY_TOTAL_FLUX_TOKEN = "Fl?u?x?"
private const val OPTIONAL_TOTAL_FLUX_CAP_PATTERN = "(?:,TF<(\\d+)%)?"
private const val OPTIONAL_HOLD_SOFT_FLUX_BEAM_WINDOW_PATTERN = "(?:,Beam<(\\d+(?:\\.\\d+)?)s)?"
private const val OPTIONAL_AVOID_SHIELD_THRESHOLD_PATTERN = "(?:,S<(\\d+)%)?"
private const val OPTIONAL_TARGET_SHIELD_THRESHOLD_PATTERN = "(?:,S>(\\d+)%)?"
private const val DAMAGE_TYPE_EXCLUSION_LIST_PATTERN = "(?:K|HE|F|E|B|M|P)(?:,(?:K|HE|F|E|B|M|P))*"
private const val OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN = "(?:,Ignore<($DAMAGE_TYPE_EXCLUSION_LIST_PATTERN)>)?"
private const val SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN = "\\(Ignore<($DAMAGE_TYPE_EXCLUSION_LIST_PATTERN)>\\)"

val holdTotalFluxRegex = Regex("HoldFire\\(TF>(\\d+)%\\)")
private val holdTotalFluxWithCapCleanupRegex = Regex("HoldFire\\(TF>(\\d+)%,TF<(\\d+)%\\)")
val holdTotalFluxAliasRegex = Regex("Hold\\(TF>(\\d+)%\\)")
val holdTotalFluxLegacyRegex = Regex("Hold(?:FT)?\\($LEGACY_TOTAL_FLUX_TOKEN>(\\d+)%\\)")
val holdSoftFluxRegex = Regex("HoldFire\\(SF>(\\d+)%$OPTIONAL_TOTAL_FLUX_CAP_PATTERN$OPTIONAL_HOLD_SOFT_FLUX_BEAM_WINDOW_PATTERN\\)")
val holdSoftFluxAliasRegex = Regex("Hold\\(SF>(\\d+)%\\)")
val holdHardFluxRegex = Regex("HoldFire\\(HF>(\\d+)%\\)")
private val holdHardFluxWithCapCleanupRegex = Regex("HoldFire\\(HF>(\\d+)%,TF<(\\d+)%\\)")
val holdHardFluxAliasRegex = Regex("Hold\\(HF>(\\d+)%\\)")
val holdSoftFluxLegacyRegex = Regex("HoldSFT\\($LEGACY_TOTAL_FLUX_TOKEN>(\\d+)%\\)")
val forceFireTotalFluxRegex = Regex("Force\\(TF<(\\d+)%$OPTIONAL_TOTAL_FLUX_CAP_PATTERN\\)")
val forceFireTotalFluxLegacyRegex = Regex("Force(?:F|FT)\\($LEGACY_TOTAL_FLUX_TOKEN<(\\d+)%\\)")
val forceFireSoftFluxRegex = Regex("Force\\(SF<(\\d+)%$OPTIONAL_TOTAL_FLUX_CAP_PATTERN\\)")
val forceFireHardFluxRegex = Regex("Force\\(HF<(\\d+)%$OPTIONAL_TOTAL_FLUX_CAP_PATTERN\\)")
val forceFireSoftFluxLegacyRegex = Regex("ForceSFT\\($LEGACY_TOTAL_FLUX_TOKEN<(\\d+)%\\)")
val avoidShieldDamageExclusionRegex = Regex("AvoidShield$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
val avoidShieldThresholdRegex = Regex("AvoidShield\\(S<(\\d+)%$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val avoidShieldTotalFluxRegex = Regex("AvoidShield\\(TF>(\\d+)%$OPTIONAL_AVOID_SHIELD_THRESHOLD_PATTERN$OPTIONAL_TOTAL_FLUX_CAP_PATTERN$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val avoidShieldSoftFluxRegex = Regex("AvoidShield\\(SF>(\\d+)%$OPTIONAL_AVOID_SHIELD_THRESHOLD_PATTERN$OPTIONAL_TOTAL_FLUX_CAP_PATTERN$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val avoidShieldHardFluxRegex = Regex("AvoidShield\\(HF>(\\d+)%$OPTIONAL_AVOID_SHIELD_THRESHOLD_PATTERN$OPTIONAL_TOTAL_FLUX_CAP_PATTERN$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val avoidShieldTotalFluxLegacyRegex = Regex("(?:AvShldFT|AvdShieldsFT)\\($LEGACY_TOTAL_FLUX_TOKEN<(\\d+)%\\)")
val avoidShieldSoftFluxLegacyRegex = Regex("(?:AvShldSFT|AvShlddSFT|AvdShieldsSFT)\\($LEGACY_TOTAL_FLUX_TOKEN<(\\d+)%\\)")
val targetShieldDamageExclusionRegex = Regex("TargetShield$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
val targetShieldThresholdRegex = Regex("TargetShield\\(S>(\\d+)%$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val targetShieldTotalFluxRegex = Regex("TargetShield\\(TF>(\\d+)%$OPTIONAL_TARGET_SHIELD_THRESHOLD_PATTERN$OPTIONAL_TOTAL_FLUX_CAP_PATTERN$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val targetShieldSoftFluxRegex = Regex("TargetShield\\(SF>(\\d+)%$OPTIONAL_TARGET_SHIELD_THRESHOLD_PATTERN$OPTIONAL_TOTAL_FLUX_CAP_PATTERN$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val targetShieldHardFluxRegex = Regex("TargetShield\\(HF>(\\d+)%$OPTIONAL_TARGET_SHIELD_THRESHOLD_PATTERN$OPTIONAL_TOTAL_FLUX_CAP_PATTERN$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val targetShieldTotalFluxLegacyRegex = Regex("(?:TgtShldFT|TgtShieldsFT)\\($LEGACY_TOTAL_FLUX_TOKEN<(\\d+)%\\)")
val targetShieldSoftFluxLegacyRegex = Regex("(?:TgtShldSFT|TgtShieldsSFT)\\($LEGACY_TOTAL_FLUX_TOKEN<(\\d+)%\\)")
val pdSoftFluxRegex = Regex("PD\\(SF>(\\d+)%\\)")
val burstPDSoftFluxAliasRegex = Regex("BurstPD\\(SF>(\\d+)%\\)")
val burstPDSoftFluxLegacyRegex = Regex("BurstPDSFT\\($LEGACY_TOTAL_FLUX_TOKEN<(\\d+)%\\)")
val pdTotalFluxRegex = Regex("PD\\(TF>(\\d+)%\\)")
val pdHardFluxRegex = Regex("PD\\(HF>(\\d+)%\\)")
val pdTotalFluxLegacyRegex = Regex("PD\\($LEGACY_TOTAL_FLUX_TOKEN>(\\d+)%\\)")
val opportunistAmmoRegex = Regex("Opportunist\\(A<(\\d+)%(?:,K<(\\d+)%,HE<(\\d+)%,TH<(\\d+)%)?\\)")
val opportunistTunedRegex = Regex("Opportunist\\(K<(\\d+)%,HE<(\\d+)%,TH<(\\d+)%\\)")
val conserveAmmoRegex = Regex("ConserveAmmo\\(A<(\\d+)%\\)")
val pdAmmoRegex = Regex("PD\\(A<(\\d+)%\\)")
val conservePDAmmoRegex = Regex("ConservePDAmmo\\(A<(\\d+)%\\)")
val conservePDAmmoShortRegex = Regex("CnsrvPDAmmo\\(A<(\\d+)%\\)")
val noPdWasteRegex = Regex("AvoidPD\\(Waste>(\\d+)%(?:,Cap<(\\d+))?\\)")
val noPdHealthRegex = Regex("AvoidPD\\(H<(\\d+)\\)")
val noPdWasteLegacyRegex = Regex("NoPD\\(Waste>(\\d+)%\\)")
val noPdHealthLegacyRegex = Regex("NoPD\\(H<(\\d+)\\)")
val ignoreMinorPDRegex = Regex("IgnoreMinorPD\\(H<(\\d+)\\)")
val avoidArmorRegex = Regex("(?:AvoidArmor|AvdArmor)\\((\\d+)%$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val panicFireRegex = Regex("Panic\\(H<(\\d+)%\\)")
val rangeRegex = Regex("Range<(\\d+)%")
val rofRegex = Regex("LowRoF\\((\\d+)%\\)")

//val prioPdRegex = Regex("PrioP[dD]\\((\\d+)\\)")
//val prioFightersRegex = Regex("PrioFighter\\((\\d+)\\)")
//val prioMissilesRegex = Regex("PrioMissile\\((\\d+)\\)")
val prioSmallRegex = Regex("PrioSmall\\((\\d+)\\)")
val prioSmallLegacyRegex = Regex("(?:PrioPD|PrioritizePD|PrioritisePD)\\((\\d+)\\)")
val prioFighterRegex = Regex("PrioFighter\\((\\d+)\\)")
val prioMissileRegex = Regex("PrioMissile\\((\\d+)\\)")
val prioShipRegex = Regex("PrioShip\\((\\d+)\\)")
val prioShipsRegex = Regex("PrioShips\\((\\d+)\\)")
val prioFocusedRegex = Regex("PrioFocused\\((\\d+)\\)")
val prioWoundedDamageExclusionRegex = Regex("PrioWounded$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
val targetPhaseDamageExclusionRegex = Regex("TargetPhase$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
val avoidPhasedDamageExclusionRegex = Regex("AvoidPhased$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
val prioShieldsDamageExclusionRegex = Regex("PrioShields$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
val prioShieldsRegex = Regex("PrioShields\\((\\d+)$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val prioHullDamageExclusionRegex = Regex("PrioHull$SIMPLE_DAMAGE_TYPE_EXCLUSION_PATTERN")
val prioHullRegex = Regex("PrioHull\\((\\d+)$OPTIONAL_DAMAGE_TYPE_EXCLUSION_PATTERN\\)")
val prioCloseRegex = Regex("PrioClose\\((\\d+)\\)")
val prioFarRegex = Regex("PrioFar\\((\\d+)\\)")

private data class ThresholdCanonicalizationRule(
    val canonicalRegex: Regex,
    val legacyRegex: Regex,
    val canonicalPrefix: String,
    val invertLegacyThreshold: Boolean = false
)

private val thresholdCanonicalizationRules = listOf(
    ThresholdCanonicalizationRule(forceFireTotalFluxRegex, forceFireTotalFluxLegacyRegex, "Force(TF<"),
    ThresholdCanonicalizationRule(forceFireSoftFluxRegex, forceFireSoftFluxLegacyRegex, "Force(SF<"),
    ThresholdCanonicalizationRule(avoidShieldTotalFluxRegex, avoidShieldTotalFluxLegacyRegex, "AvoidShield(TF>", invertLegacyThreshold = true),
    ThresholdCanonicalizationRule(avoidShieldSoftFluxRegex, avoidShieldSoftFluxLegacyRegex, "AvoidShield(SF>", invertLegacyThreshold = true),
    ThresholdCanonicalizationRule(targetShieldTotalFluxRegex, targetShieldTotalFluxLegacyRegex, "TargetShield(TF>", invertLegacyThreshold = true),
    ThresholdCanonicalizationRule(targetShieldSoftFluxRegex, targetShieldSoftFluxLegacyRegex, "TargetShield(SF>", invertLegacyThreshold = true),
    ThresholdCanonicalizationRule(pdSoftFluxRegex, burstPDSoftFluxLegacyRegex, "PD(SF>", invertLegacyThreshold = true),
    ThresholdCanonicalizationRule(pdTotalFluxRegex, pdTotalFluxLegacyRegex, "PD(TF>")
)

fun extractRegexThreshold(regex: Regex, name: String): Float {
    return (regex.matchEntire(name)?.groupValues?.get(1)?.toFloat() ?: 0f) / 100f
}

private fun extractOptionalRegexTotalFluxCap(regex: Regex, name: String): Float? {
    val match = regex.matchEntire(name) ?: return null
    return match.groupValues.getOrNull(2)
        ?.takeIf { it.isNotBlank() }
        ?.toFloatOrNull()
        ?.let { it / 100f }
}

private fun extractOptionalHoldSoftFluxBeamWindow(name: String): Float? {
    val match = holdSoftFluxRegex.matchEntire(name) ?: return null
    return match.groupValues.getOrNull(3)
        ?.takeIf { it.isNotBlank() }
        ?.toFloatOrNull()
}

private fun extractOptionalShieldRegexTotalFluxCap(regex: Regex, name: String): Float? {
    val match = regex.matchEntire(name) ?: return null
    return match.groupValues.getOrNull(3)
        ?.takeIf { it.isNotBlank() }
        ?.toFloatOrNull()
        ?.let { it / 100f }
}

private fun extractOptionalShieldThreshold(regex: Regex, name: String): Float? {
    val match = regex.matchEntire(name) ?: return null
    return match.groupValues.getOrNull(2)
        ?.takeIf { it.isNotBlank() }
        ?.toFloatOrNull()
        ?.let { it / 100f }
}

private fun extractOptionalThresholdDamageTypeExclusions(regex: Regex, name: String): DamageTypeExclusions {
    val match = regex.matchEntire(name) ?: return DamageTypeExclusions.NONE
    return DamageTypeExclusions.fromTokens(match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() })
        ?: DamageTypeExclusions.NONE
}

private fun extractOptionalShieldDamageTypeExclusions(regex: Regex, name: String): DamageTypeExclusions {
    val match = regex.matchEntire(name) ?: return DamageTypeExclusions.NONE
    return DamageTypeExclusions.fromTokens(match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() })
        ?: DamageTypeExclusions.NONE
}

private fun extractSimpleDamageTypeExclusions(regex: Regex, name: String): DamageTypeExclusions {
    val match = regex.matchEntire(name) ?: return DamageTypeExclusions.NONE
    return DamageTypeExclusions.fromTokens(match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() })
        ?: DamageTypeExclusions.NONE
}

private fun extractPriorityDamageTypeExclusions(regex: Regex, name: String): DamageTypeExclusions {
    val match = regex.matchEntire(name) ?: return DamageTypeExclusions.NONE
    return DamageTypeExclusions.fromTokens(match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() })
        ?: DamageTypeExclusions.NONE
}

private fun canonicalizeAvoidArmorTag(tag: String): String? {
    val match = avoidArmorRegex.matchEntire(tag) ?: return null
    val damageTypeSuffix = DamageTypeExclusions.fromTokens(match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() })
        ?.suffix()
        .orEmpty()
    return "AvoidArmor(${match.groupValues[1]}%$damageTypeSuffix)"
}

private fun extractRawRegexThreshold(regex: Regex, name: String): Float {
    return regex.matchEntire(name)?.groupValues?.get(1)?.toFloat() ?: 0f
}

private fun extractOptionalRawRegexSecondValue(regex: Regex, name: String): Float? {
    return regex.matchEntire(name)?.groupValues?.getOrNull(2)
        ?.takeIf { it.isNotBlank() }
        ?.toFloatOrNull()
}

fun extractRegexThresholdAsPercentageString(regex: Regex, name: String): String {
    return "${(extractRegexThreshold(regex, name) * 100f).toInt()}%"
}

private fun invertThreshold(threshold: Float): Float = (1f - threshold).coerceIn(0f, 1f)

private fun invertedRegexThreshold(regex: Regex, name: String): Float = invertThreshold(extractRegexThreshold(regex, name))

private fun thresholdAsPercent(threshold: Float): Int = (threshold.coerceIn(0f, 1f) * 100f).roundToInt()

private fun invertedRegexThresholdAsPercentageString(regex: Regex, name: String): String {
    return "${thresholdAsPercent(invertThreshold(extractRegexThreshold(regex, name)))}%"
}

private fun settingAsActivationThreshold(settingValue: Float): String {
    return "${thresholdAsPercent(invertThreshold(settingValue))}%"
}

private fun compactAliasRegex(pattern: String): Regex = Regex(pattern, RegexOption.IGNORE_CASE)

private val compactTagAliasReplacements = listOf(
    compactAliasRegex("total\\s*(?:flux|flx)") to "TF",
    compactAliasRegex("soft\\s*(?:flux|flx)") to "SF",
    compactAliasRegex("hard\\s*(?:flux|flx)") to "HF",
    compactAliasRegex("(?<=\\()(?:flux|flx)(?=[<>])") to "TF",
    compactAliasRegex("avoid|avd|no") to "Avoid",
    compactAliasRegex("target|tgt") to "Target",
    compactAliasRegex("missiles|mssles|missile|mssle") to "Missile",
    compactAliasRegex("fighters|fghtrs|fighter|fghtr") to "Fighter",
    compactAliasRegex("waste") to "Waste",
    compactAliasRegex("overloaded|overload") to "Overloaded",
    compactAliasRegex("phased|phase") to "Phase",
    compactAliasRegex("wounded|wound") to "Wounded",
    compactAliasRegex("healthy|health") to "Healthy",
    compactAliasRegex("force|frce") to "Force",
    compactAliasRegex("autofire|af") to "AutoFire",
    compactAliasRegex("shields|shield|shld") to "Shield",
    compactAliasRegex("armoured|armored|armour|armor|armr") to "Armor",
    compactAliasRegex("ships|ship|shp") to "Ship",
)

private fun normalizeCompactTagAliases(tag: String): String {
    var normalized = tag.trim().replace(Regex("\\s+"), "")
    compactTagAliasReplacements.forEach { (regex, replacement) ->
        normalized = regex.replace(normalized, replacement)
    }
    normalized = Regex("AvoidPD\\(W>", RegexOption.IGNORE_CASE).replace(normalized, "AvoidPD(Waste>")
    return normalized
}

private object WeaponTagNameCache {
    private data class PairKey(val candidate: String, val existing: String)

    private val canonicalByRaw = mutableMapOf<String, String>()
    private val templateByRaw = mutableMapOf<String, String>()
    private val tooltipByRaw = mutableMapOf<String, String>()
    private val incompatibleByRawPair = mutableMapOf<PairKey, Boolean>()

    fun canonical(raw: String, build: () -> String): String =
        canonicalByRaw.getOrPut(raw, build)

    fun template(raw: String, build: () -> String): String =
        templateByRaw.getOrPut(raw, build)

    fun tooltip(raw: String, build: () -> String): String =
        tooltipByRaw.getOrPut(raw, build)

    fun incompatiblePair(candidate: String, existing: String, build: () -> Boolean): Boolean =
        incompatibleByRawPair.getOrPut(PairKey(candidate, existing), build)

    fun clear() {
        canonicalByRaw.clear()
        templateByRaw.clear()
        tooltipByRaw.clear()
        incompatibleByRawPair.clear()
    }
}

fun clearWeaponTagNameCaches() {
    WeaponTagNameCache.clear()
}

private val canonicalSimpleWeaponTags = setOf(
    "PD",
    "PrioSmall",
    "PrioBig",
    "NoPD",
    "TargetFighter",
    "AvoidShield",
    "TargetShield",
    "AvoidShield+",
    "TargetShield+",
    "NoFighter",
    "ConserveAmmo",
    "ConservePDAmmo",
    "Opportunist",
    "AvoidDebris",
    "TargetBig",
    "TargetSmall",
    "ForceAutoFire",
    "DoNotShoot",
    "AvoidPhased",
    "TargetPhase",
    "NoShield",
    "NoMissile",
    "TargetOverloaded",
    "LowRoF",
    "Burst",
    "BurstCancel",
    "SyncWindow",
    "SyncVolley",
    "Ambush",
    "DisableTags",
    "PrioFighter",
    "PrioMissile",
    "PrioShip",
    "PrioWounded",
    "PrioWoundedPD",
    "PrioHealthy",
    "PrioFocused",
    "PrioShields",
    "PrioHull",
    "PrioClose",
    "PrioFar",
    "BlockBeams",
    "CustomAI",
    "PrioDense",
)

private fun canonicalizeThresholdTag(
    inputTag: String,
    canonicalRegex: Regex,
    legacyRegex: Regex,
    canonicalPrefix: String,
    invertLegacyThreshold: Boolean = false
): String? {
    if (canonicalRegex.matches(inputTag)) return inputTag
    if (!legacyRegex.matches(inputTag)) return null
    val threshold = if (invertLegacyThreshold) {
        invertedRegexThresholdAsPercentageString(legacyRegex, inputTag)
    } else {
        extractRegexThresholdAsPercentageString(legacyRegex, inputTag)
    }
    return "$canonicalPrefix$threshold)"
}

private fun parseCanonicalFluxCondition(
    tag: String,
    regex: Regex,
    metric: FluxMetric,
    comparator: FluxComparator,
    requireSoftFluxCap: Boolean = false,
    totalFluxCapExtractor: (Regex, String) -> Float? = ::extractOptionalRegexTotalFluxCap,
): FluxCondition? {
    if (!regex.matches(tag)) return null
    return FluxCondition(
        metric = metric,
        comparator = comparator,
        threshold = extractRegexThreshold(regex, tag),
        requireTotalFluxBelowSoftFluxCap = requireSoftFluxCap,
        totalFluxCap = totalFluxCapExtractor(regex, tag)
    )
}

private fun fluxConditionWording(condition: FluxCondition): String {
    val metric = when (condition.metric) {
        FluxMetric.TOTAL -> "total flux"
        FluxMetric.SOFT -> "soft flux"
        FluxMetric.HARD -> "hard flux"
    }
    val comparator = when (condition.comparator) {
        FluxComparator.GREATER_THAN -> "greater than"
        FluxComparator.LESS_THAN -> "below"
        FluxComparator.GREATER_OR_EQUAL -> "greater than or equal to"
        FluxComparator.LESS_OR_EQUAL -> "below or equal to"
    }
    val threshold = "${thresholdAsPercent(condition.threshold)}%"
    val base = "$metric is $comparator $threshold"
    val cap = condition.totalFluxCap
        ?: Settings.softFluxTotalFluxCap().takeIf { condition.requireTotalFluxBelowSoftFluxCap }
        ?: return base
    return "$base and total flux is below ${thresholdAsPercent(cap)}%"
}

private fun fluxConditionThresholdPercent(condition: FluxCondition): String = "${thresholdAsPercent(condition.threshold)}%"

fun shouldTagBeDisabled(groupIndex: Int, sh: FleetMemberAPI, tag: String): Boolean {
    return tagDisabledReasonForGroup(groupIndex, sh, tag) != null
}

fun tagDisabledReasonForGroup(groupIndex: Int, sh: FleetMemberAPI, tag: String): String? {
    val modTag = tagNameToRegexName(tag)
    if (isEverythingBlacklisted(groupIndex, sh)) {
        return "this weapon group has no AGC-supported weapons."
    }
    if (pdTags.contains(modTag) && !isElligibleForPD(groupIndex, sh)) {
        return "PD tags require a point defence weapon in this group."
    }
    if (opportunistAmmoTags.contains(modTag) && !usesAmmo(groupIndex, sh)) {
        return "ammo tags require a weapon that uses ammo."
    }
    if (pdAmmoTags.contains(modTag) && !usesAmmoNonMissile(groupIndex, sh)) {
        return "PD ammo tags require a non-missile weapon that uses ammo."
    }
    return null
}

fun disabledTagsForGroup(groupIndex: Int, sh: FleetMemberAPI, tags: List<String>): Set<String> {
    if (tags.isEmpty()) return emptySet()
    if (isEverythingBlacklisted(groupIndex, sh)) return tags.toSet()
    val pdEligible = isElligibleForPD(groupIndex, sh)
    val ammoEligible = usesAmmo(groupIndex, sh)
    val nonMissileAmmoEligible = usesAmmoNonMissile(groupIndex, sh)
    return tags
        .filter { tag ->
            val modTag = tagNameToRegexName(tag)
            (modTag in pdTags && !pdEligible) ||
                (modTag in opportunistAmmoTags && !ammoEligible) ||
                (modTag in pdAmmoTags && !nonMissileAmmoEligible)
        }
        .toSet()
}

fun priorityBoilerplateText(): String = "\nIncreases priority by a factor of ${Settings.prioXModifier()} (adjustable in Settings.editme)." +
        "\nCombine multiple Prio-tags to de-prioritize everything else."

private fun pdTargetingRestrictionTooltip(): String = "Restricts targeting to fighters and missiles."

private fun tooltipWithActivationCondition(base: String, condition: String): String {
    return "$base\nActivation condition: $condition."
}

private fun holdFireTooltip(
    tag: String,
    regex: Regex,
    metric: FluxMetric,
    comparator: FluxComparator,
    requireSoftFluxCap: Boolean = false,
): String {
    return "Fires only while ${
        fluxConditionWording(parseCanonicalFluxCondition(tag, regex, metric, comparator, requireSoftFluxCap)!!)
    }."
}

private fun forceFireTooltip(
    tag: String,
    regex: Regex,
    metric: FluxMetric,
    comparator: FluxComparator,
    requireSoftFluxCap: Boolean = false,
): String {
    return "ForceFire: ignores firing restrictions from other tags while ${
        fluxConditionWording(parseCanonicalFluxCondition(tag, regex, metric, comparator, requireSoftFluxCap)!!)
    }.\nNote: this bypasses firing restrictions, not targeting restrictions."
}

private fun noPdHealthTooltip(canonicalTag: String): String {
    return "Does not target fighters or missiles when this weapon estimates their health is below ${
        extractRawRegexThreshold(noPdHealthRegex, canonicalTag).toInt()
    }. Poor damage matchups count armor or shields as tougher; strong matchups do not lower the estimate."
}

private fun noPdWasteTooltip(canonicalTag: String): String {
    val wastePercent = extractRawRegexThreshold(noPdWasteRegex, canonicalTag).toInt()
    val cleanupCap = extractOptionalRawRegexSecondValue(noPdWasteRegex, canonicalTag)
        ?: Settings.noPDWasteCleanupDamageCap()
    val examplePacketDamage = 500
    val exampleMinimumTargetHealth = (examplePacketDamage * (1f - wastePercent / 100f)).roundToInt()
    return "Does not target fighters or missiles when this weapon would waste more than $wastePercent% of its estimated attack-packet damage. " +
        "For example, with a $examplePacketDamage damage packet, it refuses targets that need less than $exampleMinimumTargetHealth damage. " +
        "Weapons with attack-packet damage at or below ${formatTooltipNumber(cleanupCap)} ignore this waste check, so low-damage PD can still clean up weak targets."
}

private fun lowRofTooltip(canonicalTag: String): String {
    val factorPercent = extractRawRegexThreshold(rofRegex, canonicalTag).toInt().coerceAtLeast(1)
    val shotsPerSecond = 100f / factorPercent.toFloat()
    val secondsPerShot = factorPercent.toFloat() / 100f
    return "Multiplies the weapon's normal firing delay by $factorPercent%. " +
        "A weapon that normally fires once per second would fire ${formatTooltipNumber(shotsPerSecond)} times per second instead, or once every ${formatTooltipNumber(secondsPerShot)} seconds."
}

private fun formatTooltipNumber(value: Float): String {
    val rounded = (value * 100f).roundToInt() / 100f
    val asInt = rounded.roundToInt()
    if (kotlin.math.abs(rounded - asInt.toFloat()) < 0.001f) return asInt.toString()
    val oneDecimal = (rounded * 10f).roundToInt() / 10f
    if (kotlin.math.abs(rounded - oneDecimal) < 0.001f) return oneDecimal.toString()
    return rounded.toString()
}

private fun priorityMultiplierTooltip(targetText: String, regex: Regex, canonicalTag: String): String {
    return "Prioritizes $targetText when present.\nIncreases priority by a factor of ${
        extractRawRegexThreshold(regex, canonicalTag).toInt()
    }.\nCombine multiple Prio-tags to de-prioritize everything else."
}

private fun baseTagTooltip(canonicalTag: String): String? = when (canonicalTag) {
    "AvoidShield" -> "Prioritizes targets with no shields, flanked shields, high flux, or shields turned off.\nFighter shields will ${
        mapBooleanToSpecificString(Settings.ignoreFighterShield(), "", "not")
    } be ignored (configurable in settings)." +
        "\nNo targeting restrictions."

    "TargetShield" -> "Prioritizes targeting shields. Stops firing against enemies with very high flux or no useful shield target." +
        "\nCan target, but not fire at, unshielded targets. Combine with Force tags if this weapon should still shoot unshielded targets.\nFighter shields will ${
            mapBooleanToSpecificString(Settings.ignoreFighterShield(), "", "not")
        } be ignored (configurable in settings)." +
        "\nTip: Keep one kinetic weapon on default to keep up pressure."

    "TargetShield+" -> "As TargetShield, but more aggressive." +
        "\nOnly stops shooting when flanking shields or shields are disabled." +
        "\nFighter shields will ${mapBooleanToSpecificString(Settings.ignoreFighterShield(), "", "not")} be ignored (configurable in settings)."

    "AvoidShield+" -> "As AvoidShield, but less aggressive." +
        "\nOnly shoots when flanking shields or shields are disabled." +
        "\nFighter shields will ${mapBooleanToSpecificString(Settings.ignoreFighterShield(), "", "not")} be ignored (configurable in settings)."

    "ConserveAmmo" -> "Weapon is much more hesitant to fire when ammo is below ${(Settings.conserveAmmo() * 100f).toInt()}%." +
        "\nNo targeting restrictions."

    "ConservePDAmmo" -> "When ammo is below ${(Settings.conservePDAmmo() * 100f).toInt()}%, only fires at fighters and missiles." +
        "\nFor non-PD weapons, only fighters are valid in that case." +
        "\nNo targeting restrictions."

    "Merge" -> "Press [${Keyboard.getKeyName(Settings.mergeHotkey())}] to merge all weapons with this tag into current weapon group. " +
        "\nFor player controlled ship only! Press [${Keyboard.getKeyName(Settings.mergeHotkey())}] again to undo." +
        "\nUse this tag to unleash big manually aimed barrages at your enemies!"

    "DisableTags" -> "Press [${Keyboard.getKeyName(Settings.disableTagsHotkey())}] to toggle all other AGC tags off for the currently selected weapon group." +
        "\nPress [${Keyboard.getKeyName(Settings.disableTagsHotkey())}] again to restore the group's saved tags." +
        "\nFor player controlled ships only. The tag list is preserved; only in-combat tag behavior is suppressed."

    "PrioFighter" -> "Prioritizes fighters over other targets when fighters are present.${priorityBoilerplateText()}"
    "PrioMissile" -> "Prioritizes missiles over other targets when missiles are present.${priorityBoilerplateText()}"
    "PrioShip" -> "Prioritizes non-fighter ships over other targets when ships are present.${priorityBoilerplateText()}"
    "PrioShields" -> "Prioritizes targets whose shields are likely to catch this weapon's shot.${priorityBoilerplateText()}"
    "PrioHull" -> "Prioritizes targets whose shields are absent, disabled, flanked, or stressed.${priorityBoilerplateText()}"
    "PrioClose" -> "Prioritizes nearby targets without restricting fire.${priorityBoilerplateText()}"
    "PrioFar" -> "Prioritizes distant in-range targets without restricting fire.${priorityBoilerplateText()}"
    else -> tagTooltips[canonicalTag]
}

val tagTooltips = mapOf(
    "PD" to pdTargetingRestrictionTooltip(),
    "PrioSmall" to "Prioritizes missiles, fighters, and smaller ships over larger ships.",
    "PrioBig" to "Prioritizes larger ships over smaller ships.\nNo targeting restrictions.",
    "NoPD" to "Forbids targeting missiles and prioritizes ships over fighters.",
    "TargetFighter" to "Restricts targeting to fighters.",
    "AvoidShield" to "Prioritizes targets with no shields, flanked shields, high flux, or shields turned off.\nFighter shields will ${
        mapBooleanToSpecificString(
            Settings.ignoreFighterShield(),
            "",
            "not"
        )
    } be ignored (configurable in settings)." +
            "\nNo targeting restrictions.",
    "TargetShield" to "Prioritizes targeting shields. Stops firing against enemies with very high flux or no useful shield target." +
            "\nCan target, but not fire at, unshielded targets. Combine with Force tags if this weapon should still shoot unshielded targets.\nFighter shields will ${
        mapBooleanToSpecificString(
            Settings.ignoreFighterShield(),
            "",
            "not"
        )
    } be ignored (configurable in settings)." +
            "\nTip: Keep one kinetic weapon on default to keep up pressure.",
    "TargetShield+" to "As TargetShield, but more aggressive." +
            "\nOnly stops shooting when flanking shields or shields are disabled." +
            "\nFighter shields will ${
        mapBooleanToSpecificString(
            Settings.ignoreFighterShield(),
            "",
            "not"
        )
    } be ignored (configurable in settings).",
    "AvoidShield+" to "As AvoidShield, but less aggressive." +
            "\nOnly shoots when flanking shields or shields are disabled." +
            "\nFighter shields will ${
        mapBooleanToSpecificString(
            Settings.ignoreFighterShield(),
            "",
            "not"
        )
    } be ignored (configurable in settings).",
    "NoFighter" to "Forbids targeting fighters.",
    "ConserveAmmo" to "Weapon is much more hesitant to fire when ammo is below ${(Settings.conserveAmmo() * 100f).toInt()}%." +
            "\nNo targeting restrictions.",
    "ConservePDAmmo" to "When ammo is below ${(Settings.conservePDAmmo() * 100f).toInt()}%, only fires at fighters and missiles." +
            "\nFor non-PD weapons, only fighters are valid in that case." +
            "\nNo targeting restrictions.",
    "Opportunist" to "Makes the weapon much more hesitant to fire and forbids targeting missiles and fighters. Useful for limited-ammo weapons.",
    "AvoidDebris" to "Does not fire when the shot is blocked by debris or asteroids." +
            "\nNote: This only affects the custom AI and the Opportunist mode already includes this option.",
    "TargetBig" to "Restricts targeting to larger ships.\nNo priority weighting.",
    "TargetSmall" to "Restricts targeting to smaller ships.\nNo priority weighting.",
    "ForceAutoFire" to "Forces AI-controlled ships to keep this weapon group on autofire, similar to the ForceAutoFire ship mode for all groups." +
            "\nNote: This modifies the ShipAI because the API cannot directly set a weapon group to autofire." +
            "\n      The ShipAI may still try to select this weapon group, but will be forced to deselect it again.",
    "DoNotShoot" to "Prevents this weapon group from firing unless another tag explicitly forces fire.\nUseful for weapons that should only fire under specific Force tag conditions.",
    "AvoidPhased" to "Ignores phase ships unless they cannot avoid the shot by phasing due to flux or cooldown." +
            "\nBest used on high-impact weapons; set some rapid-fire or beam weapons to TargetPhase to keep pressure on phase ships." +
            "\nNo targeting restrictions.",
    "TargetPhase" to "Restricts targeting to phase ships and prioritizes them. Does not care whether the target is currently phased." +
            "\nUseful for rapid-fire or beam weapons to keep up pressure on enemy phase coils." +
            "\nNo additional targeting restrictions beyond phase-ship targeting.",
    "ShipTarget" to "Restricts targeting to the selected ship target (R key). For AI-controlled ships, restricts targeting to the ShipAI maneuver target.",
    "NoMissile" to "Forbids targeting missiles.",
    "TargetOverloaded" to "Only targets and fires at overloaded or venting ships.",
    "NoShield" to "Simplified AvoidShield. Only fires at targets with no shields or shields turned off.",
    "Merge" to "Press [${Keyboard.getKeyName(Settings.mergeHotkey())}] to merge all weapons with this tag into current weapon group. " +
            "\nFor player controlled ship only! Press [${Keyboard.getKeyName(Settings.mergeHotkey())}] again to undo." +
            "\nUse this tag to unleash big manually aimed barrages at your enemies!",
    "DisableTags" to "Press [${Keyboard.getKeyName(Settings.disableTagsHotkey())}] to toggle all other AGC tags off for the currently selected weapon group." +
            "\nPress [${Keyboard.getKeyName(Settings.disableTagsHotkey())}] again to restore the group's saved tags." +
            "\nFor player controlled ships only. The tag list is preserved; only in-combat tag behavior is suppressed.",
    "SyncWindow" to "Synchronizes tagged weapons in the same weapon group into firing windows. " +
            "All tagged weapons wait until every tagged weapon is ready and on target, then fire together. " +
            "Fast weapons may keep firing until the longest-burst tagged weapon finishes, then the group waits to sync again.",
    "SyncVolley" to "Synchronizes tagged weapons in the same weapon group for one firing decision. " +
            "All tagged weapons wait until every tagged weapon is ready and on target, begin firing together, then wait to sync again. " +
            "Intrinsic weapon bursts and beams are allowed to finish naturally.",
    "Ambush" to "Tagged weapons in the same weapon group wait until every tagged weapon is ready and on the same target, then open fire together. " +
            "After the ambush starts, weapons keep prioritizing that target, but weapons that can no longer bear on it may fire at other valid targets. " +
            "The ambush resets when the target is lost by the whole group.",
    "PrioFighter" to "Prioritizes fighters over other targets when fighters are present.${priorityBoilerplateText()}",
    "PrioMissile" to "Prioritizes missiles over other targets when missiles are present.${priorityBoilerplateText()}",
    "PrioShip" to "Prioritizes non-fighter ships over other targets when ships are present.${priorityBoilerplateText()}",
    "PrioWounded" to "Prioritizes targets that have already taken hull damage.",
    "PrioWoundedPD" to "Prioritizes lower-effective-health PD targets. Fighter effective health uses the same weapon-relative durability estimate as AvoidPD(Waste>N%); missiles use remaining hitpoints.",
    "PrioHealthy" to "Prioritizes targets with high hull.",
    "PrioFocused" to "Prioritizes enemy ships that other allied ships are already targeting.",
    "PrioShields" to "Prioritizes targets whose shields are likely to catch this weapon's shot.${priorityBoilerplateText()}",
    "PrioHull" to "Prioritizes targets whose shields are absent, disabled, flanked, or stressed.${priorityBoilerplateText()}",
    "PrioClose" to "Prioritizes nearby targets without restricting fire.${priorityBoilerplateText()}",
    "PrioFar" to "Prioritizes distant in-range targets without restricting fire.${priorityBoilerplateText()}",
    "BlockBeams" to "Shoots enemies that are hitting this ship with beams, even when out of range. Intended mainly for the SVC Ink Spitter gun.",
    "CustomAI" to "Disables vanilla weapon AI for this group without adding other behavior. Useful for weapons that need fully custom AI handling.",
    "PrioDense" to "Prioritizes target-rich areas: big targets and targets with many other targets nearby. Useful for AoE weapons."
)

fun canonicalizeWeaponTagName(tag: String): String =
    WeaponTagNameCache.canonical(tag) { canonicalizeWeaponTagNameUncached(tag) }

private fun canonicalizeWeaponTagNameUncached(tag: String): String {
    canonicalizeKnownWeaponTagName(tag)?.let { return it }
    val normalized = normalizeCompactTagAliases(tag)
    if (normalized != tag) {
        canonicalizeKnownWeaponTagName(normalized)?.let { return it }
    }
    return tag
}

private fun canonicalizeKnownWeaponTagName(tag: String): String? {
    if (tag in canonicalSimpleWeaponTags) return tag
    when {
        holdTotalFluxRegex.matches(tag) -> return tag
        holdTotalFluxWithCapCleanupRegex.matches(tag) -> return "HoldFire(TF>${extractRegexThresholdAsPercentageString(holdTotalFluxWithCapCleanupRegex, tag)})"
        holdTotalFluxAliasRegex.matches(tag) -> return "HoldFire(TF>${extractRegexThresholdAsPercentageString(holdTotalFluxAliasRegex, tag)})"
        holdTotalFluxLegacyRegex.matches(tag) -> return "HoldFire(TF>${extractRegexThresholdAsPercentageString(holdTotalFluxLegacyRegex, tag)})"
        holdSoftFluxRegex.matches(tag) -> return tag
        holdSoftFluxAliasRegex.matches(tag) -> return "HoldFire(SF>${extractRegexThresholdAsPercentageString(holdSoftFluxAliasRegex, tag)})"
        holdSoftFluxLegacyRegex.matches(tag) -> return "HoldFire(SF>${extractRegexThresholdAsPercentageString(holdSoftFluxLegacyRegex, tag)})"
        holdHardFluxRegex.matches(tag) -> return tag
        holdHardFluxWithCapCleanupRegex.matches(tag) -> return "HoldFire(HF>${extractRegexThresholdAsPercentageString(holdHardFluxWithCapCleanupRegex, tag)})"
        holdHardFluxAliasRegex.matches(tag) -> return "HoldFire(HF>${extractRegexThresholdAsPercentageString(holdHardFluxAliasRegex, tag)})"
    }

    thresholdCanonicalizationRules.forEach { rule ->
        canonicalizeThresholdTag(
            inputTag = tag,
            canonicalRegex = rule.canonicalRegex,
            legacyRegex = rule.legacyRegex,
            canonicalPrefix = rule.canonicalPrefix,
            invertLegacyThreshold = rule.invertLegacyThreshold
        )?.let { return it }
    }
    canonicalizeSyncTagOptions(tag)?.let { return it }

    return when {
        burstPDSoftFluxAliasRegex.matches(tag) -> "PD(SF>${invertedRegexThresholdAsPercentageString(burstPDSoftFluxAliasRegex, tag)})"
        pdSoftFluxRegex.matches(tag) -> tag
        pdHardFluxRegex.matches(tag) -> tag
        holdHardFluxRegex.matches(tag) -> tag
        forceFireHardFluxRegex.matches(tag) -> tag
        avoidShieldDamageExclusionRegex.matches(tag) -> tag
        avoidShieldHardFluxRegex.matches(tag) -> tag
        targetShieldDamageExclusionRegex.matches(tag) -> tag
        targetShieldHardFluxRegex.matches(tag) -> tag
        opportunistAmmoRegex.matches(tag) -> tag
        opportunistTunedRegex.matches(tag) -> tag
        conserveAmmoRegex.matches(tag) -> "Opportunist(A<${extractRegexThresholdAsPercentageString(conserveAmmoRegex, tag)})"
        pdAmmoRegex.matches(tag) -> tag
        conservePDAmmoRegex.matches(tag) -> "PD(A<${extractRegexThresholdAsPercentageString(conservePDAmmoRegex, tag)})"
        conservePDAmmoShortRegex.matches(tag) -> "PD(A<${extractRegexThresholdAsPercentageString(conservePDAmmoShortRegex, tag)})"
        noPdWasteRegex.matches(tag) -> tag
        noPdWasteLegacyRegex.matches(tag) -> "AvoidPD(Waste>${extractRegexThresholdAsPercentageString(noPdWasteLegacyRegex, tag)})"
        noPdHealthRegex.matches(tag) -> tag
        noPdHealthLegacyRegex.matches(tag) -> "AvoidPD(H<${extractRawRegexThreshold(noPdHealthLegacyRegex, tag).toInt()})"
        ignoreMinorPDRegex.matches(tag) -> "AvoidPD(H<${extractRawRegexThreshold(ignoreMinorPDRegex, tag).toInt()})"
        avoidArmorRegex.matches(tag) -> canonicalizeAvoidArmorTag(tag)
        targetPhaseDamageExclusionRegex.matches(tag) -> tag
        avoidPhasedDamageExclusionRegex.matches(tag) -> tag
        prioWoundedDamageExclusionRegex.matches(tag) -> tag
        prioSmallRegex.matches(tag) -> tag
        prioSmallLegacyRegex.matches(tag) -> "PrioSmall(${extractRawRegexThreshold(prioSmallLegacyRegex, tag).toInt()})"
        prioFighterRegex.matches(tag) -> tag
        prioMissileRegex.matches(tag) -> tag
        prioShipRegex.matches(tag) -> tag
        prioShipsRegex.matches(tag) -> "PrioShip(${extractRawRegexThreshold(prioShipsRegex, tag).toInt()})"
        prioFocusedRegex.matches(tag) -> tag
        prioShieldsDamageExclusionRegex.matches(tag) -> tag
        prioShieldsRegex.matches(tag) -> tag
        prioHullDamageExclusionRegex.matches(tag) -> tag
        prioHullRegex.matches(tag) -> tag
        prioCloseRegex.matches(tag) -> tag
        prioFarRegex.matches(tag) -> tag
        tag == "ForceAF" -> "ForceAutoFire"
        tag == "PrioPD" -> "PrioSmall"
        tag == "PrioritisePD" -> "PrioSmall"
        tag == "PrioritizePD" -> "PrioSmall"
        tag == "CnsrvPDAmmo" -> "ConservePDAmmo"
        tag == "Fighter" -> "TargetFighter"
        tag == "Overloaded" -> "TargetOverloaded"
        tag == "AvoidFighter" -> "NoFighter"
        tag == "NoFighters" -> "NoFighter"
        tag == "AvoidMissile" -> "NoMissile"
        tag == "NoMissiles" -> "NoMissile"
        tag == "BigShip" -> "TargetBig"
        tag == "BigShips" -> "TargetBig"
        tag == "SmallShip" -> "TargetSmall"
        tag == "SmallShips" -> "TargetSmall"
        tag == "PrioShips" -> "PrioShip"
        tag == "AvoidShields" -> "AvoidShield"
        tag == "TargetShields" -> "TargetShield"
        tag == "AvdShields+" -> "AvoidShield+"
        tag == "TgtShields+" -> "TargetShield+"
        tag == "AvdShieldsFT" -> "AvoidShield(TF>${settingAsActivationThreshold(Settings.avoidShieldAtTotalFlux())})"
        tag == "TgtShieldsFT" -> "TargetShield(TF>${settingAsActivationThreshold(Settings.targetShieldAtTotalFlux())})"
        tag == "ShieldOff" -> "NoShield"
        tag == "ShieldsOff" -> "NoShield"
        tag == "IgnoreMinorPD" -> "AvoidPD(H<145)"
        tag == "AvoidPD" -> "NoPD"
        tag == "AvoidPhase" -> "AvoidPhased"
        tag == "TargetPhased" -> "TargetPhase"
        else -> null
    }
}

fun canonicalizeWeaponTagNames(tags: List<String>): List<String> = tags.map { canonicalizeWeaponTagName(it) }.distinct()

fun getTagTooltip(tag: String): String =
    WeaponTagNameCache.tooltip(tag) { getTagTooltipUncached(tag) }

private fun getTagTooltipUncached(tag: String): String {
    val canonicalTag = canonicalizeWeaponTagName(tag)
    baseTagTooltip(canonicalTag)?.let {
        return withEditableParameterSummary(canonicalTag, it)
    }
    val tooltip = when {
        prioSmallRegex.matches(canonicalTag) -> priorityMultiplierTooltip(
            "missiles, fighters, and smaller ships over larger ships",
            prioSmallRegex,
            canonicalTag
        )

        prioFighterRegex.matches(canonicalTag) -> priorityMultiplierTooltip("fighters over other targets", prioFighterRegex, canonicalTag)

        prioMissileRegex.matches(canonicalTag) -> priorityMultiplierTooltip("missiles over other targets", prioMissileRegex, canonicalTag)

        prioShipRegex.matches(canonicalTag) -> priorityMultiplierTooltip("non-fighter ships over other targets", prioShipRegex, canonicalTag)

        prioFocusedRegex.matches(canonicalTag) -> priorityMultiplierTooltip("enemy ships already targeted by allied ships", prioFocusedRegex, canonicalTag)

        prioShieldsRegex.matches(canonicalTag) -> priorityMultiplierTooltip("shielded targets over exposed-hull targets", prioShieldsRegex, canonicalTag)

        prioHullRegex.matches(canonicalTag) -> priorityMultiplierTooltip("exposed-hull targets over shielded targets", prioHullRegex, canonicalTag)

        prioCloseRegex.matches(canonicalTag) -> priorityMultiplierTooltip("nearby targets over distant targets", prioCloseRegex, canonicalTag)

        prioFarRegex.matches(canonicalTag) -> priorityMultiplierTooltip("distant targets over nearby targets", prioFarRegex, canonicalTag)

        parseSyncTagOptions(canonicalTag) != null -> {
            val options = parseSyncTagOptions(canonicalTag)!!
            baseTagTooltip(options.family) ?: "Synchronizes tagged weapons in the same weapon group."
        }

        holdTotalFluxRegex.matches(canonicalTag) -> holdFireTooltip(
            canonicalTag,
            holdTotalFluxRegex,
            FluxMetric.TOTAL,
            FluxComparator.LESS_OR_EQUAL
        )

        holdSoftFluxRegex.matches(canonicalTag) -> holdFireTooltip(
            canonicalTag,
            holdSoftFluxRegex,
            FluxMetric.SOFT,
            FluxComparator.LESS_THAN,
            requireSoftFluxCap = true
        )

        holdHardFluxRegex.matches(canonicalTag) -> holdFireTooltip(
            canonicalTag,
            holdHardFluxRegex,
            FluxMetric.HARD,
            FluxComparator.LESS_OR_EQUAL
        )

        forceFireTotalFluxRegex.matches(canonicalTag) -> forceFireTooltip(
            canonicalTag,
            forceFireTotalFluxRegex,
            FluxMetric.TOTAL,
            FluxComparator.LESS_THAN
        )

        forceFireSoftFluxRegex.matches(canonicalTag) -> forceFireTooltip(
            canonicalTag,
            forceFireSoftFluxRegex,
            FluxMetric.SOFT,
            FluxComparator.LESS_THAN,
            requireSoftFluxCap = true
        )

        forceFireHardFluxRegex.matches(canonicalTag) -> forceFireTooltip(
            canonicalTag,
            forceFireHardFluxRegex,
            FluxMetric.HARD,
            FluxComparator.LESS_THAN
        )

        avoidShieldDamageExclusionRegex.matches(canonicalTag) ->
            baseTagTooltip("AvoidShield") ?: "No description available."

        avoidShieldThresholdRegex.matches(canonicalTag) -> "As AvoidShield, using target shield factor below ${
            extractRegexThresholdAsPercentageString(avoidShieldThresholdRegex, canonicalTag)
        } as the firing threshold."

        avoidShieldTotalFluxRegex.matches(canonicalTag) -> tooltipWithActivationCondition(
            baseTagTooltip("AvoidShield") ?: "No description available.",
            fluxConditionWording(
                parseCanonicalFluxCondition(canonicalTag, avoidShieldTotalFluxRegex, FluxMetric.TOTAL, FluxComparator.GREATER_THAN, totalFluxCapExtractor = ::extractOptionalShieldRegexTotalFluxCap)!!
            )
        )

        avoidShieldSoftFluxRegex.matches(canonicalTag) -> tooltipWithActivationCondition(
            baseTagTooltip("AvoidShield") ?: "No description available.",
            fluxConditionWording(
                parseCanonicalFluxCondition(canonicalTag, avoidShieldSoftFluxRegex, FluxMetric.SOFT, FluxComparator.GREATER_THAN, requireSoftFluxCap = true, totalFluxCapExtractor = ::extractOptionalShieldRegexTotalFluxCap)!!
            )
        )

        avoidShieldHardFluxRegex.matches(canonicalTag) -> tooltipWithActivationCondition(
            baseTagTooltip("AvoidShield") ?: "No description available.",
            fluxConditionWording(
                parseCanonicalFluxCondition(canonicalTag, avoidShieldHardFluxRegex, FluxMetric.HARD, FluxComparator.GREATER_THAN, totalFluxCapExtractor = ::extractOptionalShieldRegexTotalFluxCap)!!
            )
        )

        targetShieldDamageExclusionRegex.matches(canonicalTag) ->
            baseTagTooltip("TargetShield") ?: "No description available."

        targetShieldThresholdRegex.matches(canonicalTag) -> "As TargetShield, using target shield factor above ${
            extractRegexThresholdAsPercentageString(targetShieldThresholdRegex, canonicalTag)
        } as the firing threshold."

        targetShieldTotalFluxRegex.matches(canonicalTag) -> tooltipWithActivationCondition(
            baseTagTooltip("TargetShield") ?: "No description available.",
            fluxConditionWording(
                parseCanonicalFluxCondition(canonicalTag, targetShieldTotalFluxRegex, FluxMetric.TOTAL, FluxComparator.GREATER_THAN, totalFluxCapExtractor = ::extractOptionalShieldRegexTotalFluxCap)!!
            )
        )

        targetShieldSoftFluxRegex.matches(canonicalTag) -> tooltipWithActivationCondition(
            baseTagTooltip("TargetShield") ?: "No description available.",
            fluxConditionWording(
                parseCanonicalFluxCondition(canonicalTag, targetShieldSoftFluxRegex, FluxMetric.SOFT, FluxComparator.GREATER_THAN, requireSoftFluxCap = true, totalFluxCapExtractor = ::extractOptionalShieldRegexTotalFluxCap)!!
            )
        )

        targetShieldHardFluxRegex.matches(canonicalTag) -> tooltipWithActivationCondition(
            baseTagTooltip("TargetShield") ?: "No description available.",
            fluxConditionWording(
                parseCanonicalFluxCondition(canonicalTag, targetShieldHardFluxRegex, FluxMetric.HARD, FluxComparator.GREATER_THAN, totalFluxCapExtractor = ::extractOptionalShieldRegexTotalFluxCap)!!
            )
        )

        pdSoftFluxRegex.matches(canonicalTag) -> "${pdTargetingRestrictionTooltip().removeSuffix(".")} while soft flux is greater than ${
            fluxConditionThresholdPercent(
                parseCanonicalFluxCondition(canonicalTag, pdSoftFluxRegex, FluxMetric.SOFT, FluxComparator.GREATER_THAN, requireSoftFluxCap = true)!!
            )
        } and total flux is below ${(Settings.softFluxTotalFluxCap() * 100f).toInt()}%."

        pdTotalFluxRegex.matches(canonicalTag) -> "${pdTargetingRestrictionTooltip().removeSuffix(".")} while total flux is greater than ${
            fluxConditionThresholdPercent(
                parseCanonicalFluxCondition(canonicalTag, pdTotalFluxRegex, FluxMetric.TOTAL, FluxComparator.GREATER_THAN)!!
            )
        }."

        pdHardFluxRegex.matches(canonicalTag) -> "${pdTargetingRestrictionTooltip().removeSuffix(".")} while hard flux is greater than ${
            fluxConditionThresholdPercent(
                parseCanonicalFluxCondition(canonicalTag, pdHardFluxRegex, FluxMetric.HARD, FluxComparator.GREATER_THAN)!!
            )
        }."

        opportunistAmmoRegex.matches(canonicalTag) -> "While ammo is less than ${
            extractRegexThresholdAsPercentageString(opportunistAmmoRegex, canonicalTag)
        }, only fires at opportune targets. Weapons without ammo are unaffected. The opportune-target thresholds below are percentages; 50 means 0.50."

        opportunistTunedRegex.matches(canonicalTag) -> {
            val match = opportunistTunedRegex.matchEntire(canonicalTag)
            "Makes the weapon much more hesitant to fire and forbids targeting missiles and fighters. " +
                "Kinetic weapons prefer shield factor above ${match?.groupValues?.getOrNull(1) ?: "?"}%, " +
                "HE/fragmentation weapons prefer shield factor below ${match?.groupValues?.getOrNull(2) ?: "?"}%, " +
                "and trigger-happiness is ${match?.groupValues?.getOrNull(3) ?: "?"}%."
        }

        pdAmmoRegex.matches(canonicalTag) -> "Restricts targeting to fighters and missiles while ammo is below ${
            extractRegexThresholdAsPercentageString(pdAmmoRegex, canonicalTag)
        }. Weapons without ammo and missile weapons are unaffected."

        noPdWasteRegex.matches(canonicalTag) -> noPdWasteTooltip(canonicalTag)

        noPdHealthRegex.matches(canonicalTag) -> noPdHealthTooltip(canonicalTag)

        targetPhaseDamageExclusionRegex.matches(canonicalTag) ->
            baseTagTooltip("TargetPhase") ?: "No description available."

        avoidPhasedDamageExclusionRegex.matches(canonicalTag) ->
            baseTagTooltip("AvoidPhased") ?: "No description available."

        prioWoundedDamageExclusionRegex.matches(canonicalTag) ->
            baseTagTooltip("PrioWounded") ?: "No description available."

        avoidArmorRegex.matches(canonicalTag) -> "Fires when the shot is likely to hit shields (as TargetShield) OR a hull section " +
                "\nwhere the armor is low enough to achieve at least ${
                    extractRegexThresholdAsPercentageString(
                        avoidArmorRegex,
                        canonicalTag
                    )
                } " +
                "effectiveness vs armor." +
                "\nCombine with AvoidShield to also avoid shields (e.g. for frag weapons)."

        panicFireRegex.matches(canonicalTag) -> "Blindly fires without checking what the shot will hit while the ship" +
                " hull level is below ${extractRegexThresholdAsPercentageString(panicFireRegex, canonicalTag)}." +
                "\nFor AI-controlled ships, this puts the weapon group into ForceAutoFire mode once the hull threshold is reached."

        rangeRegex.matches(canonicalTag) -> "Only targets and fires at targets closer than ${
            extractRegexThresholdAsPercentageString(
                rangeRegex, canonicalTag
            )
        } of weapon range." +
                "\nUseful for weapons (especially missiles) with slow projectiles, such as sabots" +
                " or shotgun-style weapons, such as the devastator cannon." +
                "\nNote: This does not modify the actual range of the weapon, it only affects autofire behavior!"

        rofRegex.matches(canonicalTag) -> lowRofTooltip(canonicalTag)

        prioShieldsDamageExclusionRegex.matches(canonicalTag) ->
            baseTagTooltip("PrioShields") ?: "No description available."

        prioHullDamageExclusionRegex.matches(canonicalTag) ->
            baseTagTooltip("PrioHull") ?: "No description available."

        else -> "No description available."
    }
    return withEditableParameterSummary(canonicalTag, tooltip)
}

private fun withEditableParameterSummary(canonicalTag: String, tooltip: String): String {
    val parsed = EditableWeaponTagDefinitions.parse(canonicalTag) ?: return tooltip
    val definition = EditableWeaponTagDefinitions.definitionById(parsed.definitionId) ?: return tooltip
    val defaults = EditableWeaponTagDefinitions.defaultValuesFor(definition)
    val rows = EditableWeaponTagDefinitions.visibleParameters(definition, parsed.parameterValues).mapNotNull { parameter ->
        if (!showEditableParameterSummaryRow(definition, parsed, parameter)) return@mapNotNull null
        val value = parsed.parameterValues[parameter.id] ?: defaults[parameter.id] ?: return@mapNotNull null
        editableParameterSummaryRow(parameter, value)
    }
    if (rows.isEmpty()) return tooltip
    return tooltip.trimEnd() + "\n\n" + rows.joinToString("\n") { "- $it" }
}

private fun showEditableParameterSummaryRow(
    definition: EditableWeaponTagDefinition,
    parsed: ParsedEditableWeaponTag,
    parameter: EditableTagParameterDefinition,
): Boolean {
    if (definition.id != "hold_fire_flux_threshold") return true
    val isSoftFlux = parsed.parameterValues[EditableWeaponTagDefinitions.PARAM_FLUX_METRIC] == "SF"
    val ignoreIfBeamed = parsed.parameterValues[EditableWeaponTagDefinitions.PARAM_IGNORE_IF_BEAMED]
        ?.toBooleanStrictOrNull() == true
    return when (parameter.id) {
        EditableWeaponTagDefinitions.PARAM_IGNORE_IF_BEAMED -> isSoftFlux && ignoreIfBeamed
        EditableWeaponTagDefinitions.PARAM_BEAM_WINDOW -> isSoftFlux && ignoreIfBeamed
        else -> true
    }
}

private fun editableParameterSummaryRow(parameter: EditableTagParameterDefinition, value: String): String {
    return when (parameter) {
        is ChoiceParameter -> {
            val label = parameter.options.firstOrNull { it.id == value }?.label ?: value
            "${parameter.label}: $label"
        }
        is ToggleParameter -> "${parameter.label}: ${if (value.toBooleanStrictOrNull() == true) "On" else "Off"}"
        is NumberParameter -> "${parameter.label}: $value${parameter.suffix}"
        is DecimalParameter -> "${parameter.label}: $value${parameter.suffix}"
        is TextParameter -> "${parameter.label}: $value"
    }
}

var unknownTagWarnCounter = 0
fun createTag(name: String, weapon: WeaponAPI): WeaponAITagBase? {
    val canonicalName = canonicalizeWeaponTagName(name)
    when {
        holdTotalFluxRegex.matches(canonicalName) -> return HoldTotalFluxTag(
            weapon,
            extractRegexThreshold(holdTotalFluxRegex, canonicalName),
            extractOptionalRegexTotalFluxCap(holdTotalFluxRegex, canonicalName)
        )
        holdSoftFluxRegex.matches(canonicalName) -> return HoldSoftFluxTag(
            weapon,
            extractRegexThreshold(holdSoftFluxRegex, canonicalName),
            extractOptionalRegexTotalFluxCap(holdSoftFluxRegex, canonicalName),
            extractOptionalHoldSoftFluxBeamWindow(canonicalName)
        )
        holdHardFluxRegex.matches(canonicalName) -> return HoldHardFluxTag(
            weapon,
            extractRegexThreshold(holdHardFluxRegex, canonicalName),
            extractOptionalRegexTotalFluxCap(holdHardFluxRegex, canonicalName)
        )
        forceFireTotalFluxRegex.matches(canonicalName) -> return ForceFireTotalFluxTag(
            weapon,
            extractRegexThreshold(forceFireTotalFluxRegex, canonicalName),
            extractOptionalRegexTotalFluxCap(forceFireTotalFluxRegex, canonicalName)
        )
        forceFireSoftFluxRegex.matches(canonicalName) -> return ForceFireSoftFluxTag(
            weapon,
            extractRegexThreshold(forceFireSoftFluxRegex, canonicalName),
            extractOptionalRegexTotalFluxCap(forceFireSoftFluxRegex, canonicalName)
        )
        forceFireHardFluxRegex.matches(canonicalName) -> return ForceFireHardFluxTag(
            weapon,
            extractRegexThreshold(forceFireHardFluxRegex, canonicalName),
            extractOptionalRegexTotalFluxCap(forceFireHardFluxRegex, canonicalName)
        )
        avoidShieldDamageExclusionRegex.matches(canonicalName) -> return AvoidShieldTag(
            weapon,
            damageTypeExclusions = extractSimpleDamageTypeExclusions(avoidShieldDamageExclusionRegex, canonicalName)
        )
        avoidShieldThresholdRegex.matches(canonicalName) -> return AvoidShieldTag(
            weapon,
            extractRegexThreshold(avoidShieldThresholdRegex, canonicalName),
            damageTypeExclusions = extractOptionalThresholdDamageTypeExclusions(avoidShieldThresholdRegex, canonicalName)
        )
        avoidShieldTotalFluxRegex.matches(canonicalName) -> return AvoidShieldTotalFluxTag(
            weapon,
            extractRegexThreshold(avoidShieldTotalFluxRegex, canonicalName),
            shieldThresholdOverride = extractOptionalShieldThreshold(avoidShieldTotalFluxRegex, canonicalName),
            totalFluxCap = extractOptionalShieldRegexTotalFluxCap(avoidShieldTotalFluxRegex, canonicalName),
            damageTypeExclusions = extractOptionalShieldDamageTypeExclusions(avoidShieldTotalFluxRegex, canonicalName)
        )
        avoidShieldSoftFluxRegex.matches(canonicalName) -> return AvoidShieldSoftFluxTag(
            weapon,
            extractRegexThreshold(avoidShieldSoftFluxRegex, canonicalName),
            shieldThresholdOverride = extractOptionalShieldThreshold(avoidShieldSoftFluxRegex, canonicalName),
            totalFluxCap = extractOptionalShieldRegexTotalFluxCap(avoidShieldSoftFluxRegex, canonicalName),
            damageTypeExclusions = extractOptionalShieldDamageTypeExclusions(avoidShieldSoftFluxRegex, canonicalName)
        )
        avoidShieldHardFluxRegex.matches(canonicalName) -> return AvoidShieldHardFluxTag(
            weapon,
            extractRegexThreshold(avoidShieldHardFluxRegex, canonicalName),
            shieldThresholdOverride = extractOptionalShieldThreshold(avoidShieldHardFluxRegex, canonicalName),
            totalFluxCap = extractOptionalShieldRegexTotalFluxCap(avoidShieldHardFluxRegex, canonicalName),
            damageTypeExclusions = extractOptionalShieldDamageTypeExclusions(avoidShieldHardFluxRegex, canonicalName)
        )
        targetShieldDamageExclusionRegex.matches(canonicalName) -> return TargetShieldTag(
            weapon,
            damageTypeExclusions = extractSimpleDamageTypeExclusions(targetShieldDamageExclusionRegex, canonicalName)
        )
        targetShieldThresholdRegex.matches(canonicalName) -> return TargetShieldTag(
            weapon,
            extractRegexThreshold(targetShieldThresholdRegex, canonicalName),
            damageTypeExclusions = extractOptionalThresholdDamageTypeExclusions(targetShieldThresholdRegex, canonicalName)
        )
        targetShieldTotalFluxRegex.matches(canonicalName) -> return TargetShieldTotalFluxTag(
            weapon,
            extractRegexThreshold(targetShieldTotalFluxRegex, canonicalName),
            shieldThresholdOverride = extractOptionalShieldThreshold(targetShieldTotalFluxRegex, canonicalName),
            totalFluxCap = extractOptionalShieldRegexTotalFluxCap(targetShieldTotalFluxRegex, canonicalName),
            damageTypeExclusions = extractOptionalShieldDamageTypeExclusions(targetShieldTotalFluxRegex, canonicalName)
        )
        targetShieldSoftFluxRegex.matches(canonicalName) -> return TargetShieldSoftFluxTag(
            weapon,
            extractRegexThreshold(targetShieldSoftFluxRegex, canonicalName),
            shieldThresholdOverride = extractOptionalShieldThreshold(targetShieldSoftFluxRegex, canonicalName),
            totalFluxCap = extractOptionalShieldRegexTotalFluxCap(targetShieldSoftFluxRegex, canonicalName),
            damageTypeExclusions = extractOptionalShieldDamageTypeExclusions(targetShieldSoftFluxRegex, canonicalName)
        )
        targetShieldHardFluxRegex.matches(canonicalName) -> return TargetShieldHardFluxTag(
            weapon,
            extractRegexThreshold(targetShieldHardFluxRegex, canonicalName),
            shieldThresholdOverride = extractOptionalShieldThreshold(targetShieldHardFluxRegex, canonicalName),
            totalFluxCap = extractOptionalShieldRegexTotalFluxCap(targetShieldHardFluxRegex, canonicalName),
            damageTypeExclusions = extractOptionalShieldDamageTypeExclusions(targetShieldHardFluxRegex, canonicalName)
        )
        pdSoftFluxRegex.matches(canonicalName) -> return BurstPDSoftFluxTag(weapon, extractRegexThreshold(pdSoftFluxRegex, canonicalName))
        pdTotalFluxRegex.matches(canonicalName) -> return PDAtTotalFluxTag(weapon, extractRegexThreshold(pdTotalFluxRegex, canonicalName))
        pdHardFluxRegex.matches(canonicalName) -> return PDAtHardFluxTag(weapon, extractRegexThreshold(pdHardFluxRegex, canonicalName))
        opportunistAmmoRegex.matches(canonicalName) -> {
            val match = opportunistAmmoRegex.matchEntire(canonicalName) ?: return null
            return ConserveAmmoTag(
                weapon,
                ammoThresholdOverride = match.groupValues[1].toFloat() / 100f,
                kineticThresholdOverride = match.groupValues.getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
                    ?.toFloatOrNull()
                    ?.let { it / 100f },
                highExplosiveThresholdOverride = match.groupValues.getOrNull(3)
                    ?.takeIf { it.isNotBlank() }
                    ?.toFloatOrNull()
                    ?.let { it / 100f },
                triggerHappinessModifierOverride = match.groupValues.getOrNull(4)
                    ?.takeIf { it.isNotBlank() }
                    ?.toFloatOrNull()
                    ?.let { it / 100f },
            )
        }
        opportunistTunedRegex.matches(canonicalName) -> {
            val match = opportunistTunedRegex.matchEntire(canonicalName) ?: return null
            return OpportunistTag(
                weapon,
                kineticThresholdOverride = match.groupValues[1].toFloat() / 100f,
                highExplosiveThresholdOverride = match.groupValues[2].toFloat() / 100f,
                triggerHappinessModifierOverride = match.groupValues[3].toFloat() / 100f,
            )
        }
        pdAmmoRegex.matches(canonicalName) -> return ConservePDAmmoTag(weapon, extractRegexThreshold(pdAmmoRegex, canonicalName))
        noPdWasteRegex.matches(canonicalName) -> return NoPDWasteTag(
            weapon,
            extractRegexThreshold(noPdWasteRegex, canonicalName),
            cleanupDamageCapOverride = extractOptionalRawRegexSecondValue(noPdWasteRegex, canonicalName),
        )
        noPdHealthRegex.matches(canonicalName) -> return IgnoreMinorPDTag(weapon, extractRawRegexThreshold(noPdHealthRegex, canonicalName))
        avoidArmorRegex.matches(canonicalName) -> return AvoidArmorTag(
            weapon,
            extractRegexThreshold(avoidArmorRegex, canonicalName),
            damageTypeExclusions = extractOptionalThresholdDamageTypeExclusions(avoidArmorRegex, canonicalName)
        )
        targetPhaseDamageExclusionRegex.matches(canonicalName) -> return TargetPhaseTag(
            weapon,
            extractSimpleDamageTypeExclusions(targetPhaseDamageExclusionRegex, canonicalName)
        )
        avoidPhasedDamageExclusionRegex.matches(canonicalName) -> return AvoidPhaseTag(
            weapon,
            extractSimpleDamageTypeExclusions(avoidPhasedDamageExclusionRegex, canonicalName)
        )
        prioWoundedDamageExclusionRegex.matches(canonicalName) -> return PrioritizeWoundedTag(
            weapon,
            extractSimpleDamageTypeExclusions(prioWoundedDamageExclusionRegex, canonicalName)
        )
        panicFireRegex.matches(canonicalName) -> return PanicFireTag(weapon, extractRegexThreshold(panicFireRegex, canonicalName))
        rangeRegex.matches(canonicalName) -> return RangeTag(weapon, extractRegexThreshold(rangeRegex, canonicalName))
        rofRegex.matches(canonicalName) -> return ReduceRoFTag(weapon, extractRegexThreshold(rofRegex, canonicalName))
        prioSmallRegex.matches(canonicalName) -> return PrioritizePDTag(weapon, extractRawRegexThreshold(prioSmallRegex, canonicalName))
        prioFighterRegex.matches(canonicalName) -> return PrioritizeFightersTag(weapon, extractRawRegexThreshold(prioFighterRegex, canonicalName))
        prioMissileRegex.matches(canonicalName) -> return PrioritizeMissilesTag(weapon, extractRawRegexThreshold(prioMissileRegex, canonicalName))
        prioShipRegex.matches(canonicalName) -> return PrioritizeShipTag(weapon, extractRawRegexThreshold(prioShipRegex, canonicalName))
        prioFocusedRegex.matches(canonicalName) -> return PrioritizeFocusedTag(weapon, extractRawRegexThreshold(prioFocusedRegex, canonicalName))
        prioShieldsDamageExclusionRegex.matches(canonicalName) -> return PrioritizeShieldsTag(
            weapon,
            damageTypeExclusions = extractSimpleDamageTypeExclusions(prioShieldsDamageExclusionRegex, canonicalName)
        )
        prioShieldsRegex.matches(canonicalName) -> return PrioritizeShieldsTag(
            weapon,
            extractRawRegexThreshold(prioShieldsRegex, canonicalName),
            extractPriorityDamageTypeExclusions(prioShieldsRegex, canonicalName)
        )
        prioHullDamageExclusionRegex.matches(canonicalName) -> return PrioritizeHullTag(
            weapon,
            damageTypeExclusions = extractSimpleDamageTypeExclusions(prioHullDamageExclusionRegex, canonicalName)
        )
        prioHullRegex.matches(canonicalName) -> return PrioritizeHullTag(
            weapon,
            extractRawRegexThreshold(prioHullRegex, canonicalName),
            extractPriorityDamageTypeExclusions(prioHullRegex, canonicalName)
        )
        prioCloseRegex.matches(canonicalName) -> return PrioritizeCloseTag(weapon, extractRawRegexThreshold(prioCloseRegex, canonicalName))
        prioFarRegex.matches(canonicalName) -> return PrioritizeFarTag(weapon, extractRawRegexThreshold(prioFarRegex, canonicalName))
        parseSyncTagOptions(canonicalName) != null -> {
            val options = parseSyncTagOptions(canonicalName) ?: return null
            return SynchronizedFireTag(
                weapon = weapon,
                mode = syncFireModeForName(options.family),
                requireShipTarget = options.requireShipTarget,
                systemTriggers = options.systemTriggers,
            )
        }
    }
    return when (canonicalName) {
        "PD" -> PDTag(weapon)
        "PrioSmall", "PrioPD", "PrioritizePD", "PrioritisePD" -> PrioritizePDTag(weapon)
        "PrioBig" -> PrioritizeBigTag(weapon)
        "NoPD" -> NoPDTag(weapon)
        "TargetFighter" -> FighterTag(weapon)
        "AvoidShield", "AvoidShields" -> AvoidShieldTag(weapon)
        "TargetShield", "TargetShields" -> TargetShieldTag(weapon)
        "AvoidShield+", "AvdShields+" -> AvoidShieldTag(weapon, 0.02f)
        "TargetShield+", "TgtShields+" -> TargetShieldTag(weapon, 0.01f)
        "NoFighter" -> NoFighterTag(weapon)
        "ConserveAmmo" -> ConserveAmmoTag(weapon)
        "ConservePDAmmo", "CnsrvPDAmmo" -> ConservePDAmmoTag(weapon)
        "Opportunist" -> OpportunistTag(weapon)
        "AvoidDebris" -> AvoidDebrisTag(weapon)
        "TargetBig", "BigShip", "BigShips" -> BigShipTag(weapon)
        "TargetSmall", "SmallShip", "SmallShips" -> SmallShipTag(weapon)
        "ForceAutoFire", "ForceAF" -> ForceAutofireTag(weapon)
        "DoNotShoot" -> DoNotShootTag(weapon)
        "AvoidPhased" -> AvoidPhaseTag(weapon)
        "TargetPhase" -> TargetPhaseTag(weapon)
        "ShipTarget" -> ShipTargetTag(weapon)
        "TgtShieldsFT" -> TargetShieldAtTotalFluxTag(weapon)
        "AvdShieldsFT" -> AvoidShieldAtTotalFluxTag(weapon)
        "NoMissile" -> NoMissileTag(weapon)
        "TargetOverloaded" -> OverloadTag(weapon)
        "NoShield", "ShieldOff", "ShieldsOff" -> NoShieldTag(weapon)
        "Merge" -> MergeTag(weapon)
        "DisableTags" -> DisableTagsTag(weapon)
        "SyncWindow" -> SynchronizedFireTag(weapon, SyncFireMode.WINDOW)
        "SyncVolley" -> SynchronizedFireTag(weapon, SyncFireMode.VOLLEY)
        "Ambush" -> SynchronizedFireTag(weapon, SyncFireMode.AMBUSH)
        "PrioFighter" -> PrioritizeFightersTag(weapon)
        "PrioMissile" -> PrioritizeMissilesTag(weapon)
        "PrioShip", "PrioShips" -> PrioritizeShipTag(weapon)
        "PrioWounded" -> PrioritizeWoundedTag(weapon)
        "PrioWoundedPD" -> PrioritizeWoundedPDTag(weapon)
        "PrioHealthy" -> PrioritizeHealthyTag(weapon)
        "PrioFocused" -> PrioritizeFocusedTag(weapon)
        "PrioShields" -> PrioritizeShieldsTag(weapon)
        "PrioHull" -> PrioritizeHullTag(weapon)
        "PrioClose" -> PrioritizeCloseTag(weapon)
        "PrioFar" -> PrioritizeFarTag(weapon)
        "BlockBeams" -> InterdictBeamsTag(weapon)
        "CustomAI" -> CustomAITag(weapon)
        "PrioDense" -> PrioritizeDense(weapon)
        else -> {
            unknownTagWarnCounter++
            when {
                unknownTagWarnCounter < 10 -> Global.getLogger(WeaponControlPlugin.Companion::class.java)
                    .warn("Unknown weapon tag: $canonicalName (from: $name)! Will be ignored.")

                unknownTagWarnCounter == 10 -> Global.getLogger(WeaponControlPlugin.Companion::class.java).warn(
                    "Unknown weapon tag: $canonicalName (from: $name)! Future warnings of this type will be skipped."
                )
            }
            null
        }
    }
}

fun tagNameToRegexName(tag: String): String =
    WeaponTagNameCache.template(tag) { tagNameToRegexNameUncached(tag) }

private fun tagNameToRegexNameUncached(tag: String): String {
    val canonicalTag = canonicalizeWeaponTagName(tag)
    return when {
        holdTotalFluxRegex.matches(canonicalTag) -> "HoldFire(TF>N%)"
        holdSoftFluxRegex.matches(canonicalTag) -> "HoldFire(SF>N%)"
        holdHardFluxRegex.matches(canonicalTag) -> "HoldFire(HF>N%)"
        forceFireTotalFluxRegex.matches(canonicalTag) -> "Force(TF<N%)"
        forceFireSoftFluxRegex.matches(canonicalTag) -> "Force(SF<N%)"
        forceFireHardFluxRegex.matches(canonicalTag) -> "Force(HF<N%)"
        avoidShieldDamageExclusionRegex.matches(canonicalTag) -> "AvoidShield"
        avoidShieldThresholdRegex.matches(canonicalTag) -> "AvoidShield(S<N%)"
        avoidShieldTotalFluxRegex.matches(canonicalTag) -> "AvoidShield(TF>N%)"
        avoidShieldSoftFluxRegex.matches(canonicalTag) -> "AvoidShield(SF>N%)"
        avoidShieldHardFluxRegex.matches(canonicalTag) -> "AvoidShield(HF>N%)"
        targetShieldDamageExclusionRegex.matches(canonicalTag) -> "TargetShield"
        targetShieldThresholdRegex.matches(canonicalTag) -> "TargetShield(S>N%)"
        targetShieldTotalFluxRegex.matches(canonicalTag) -> "TargetShield(TF>N%)"
        targetShieldSoftFluxRegex.matches(canonicalTag) -> "TargetShield(SF>N%)"
        targetShieldHardFluxRegex.matches(canonicalTag) -> "TargetShield(HF>N%)"
        pdSoftFluxRegex.matches(canonicalTag) -> "PD(SF>N%)"
        pdTotalFluxRegex.matches(canonicalTag) -> "PD(TF>N%)"
        pdHardFluxRegex.matches(canonicalTag) -> "PD(HF>N%)"
        canonicalTag == "ConserveAmmo" -> "Opportunist(A<N%)"
        opportunistAmmoRegex.matches(canonicalTag) -> "Opportunist(A<N%)"
        opportunistTunedRegex.matches(canonicalTag) -> "Opportunist"
        canonicalTag == "ConservePDAmmo" -> "PD(A<N%)"
        pdAmmoRegex.matches(canonicalTag) -> "PD(A<N%)"
        canonicalTag == "PrioSmall(N)" -> "PrioSmall"
        prioSmallRegex.matches(canonicalTag) -> "PrioSmall"
        canonicalTag == "PrioSmall" -> "PrioSmall"
        canonicalTag == "PrioFighter(N)" -> "PrioFighter"
        prioFighterRegex.matches(canonicalTag) -> "PrioFighter"
        canonicalTag == "PrioMissile(N)" -> "PrioMissile"
        prioMissileRegex.matches(canonicalTag) -> "PrioMissile"
        canonicalTag == "PrioShip(N)" -> "PrioShip"
        prioShipRegex.matches(canonicalTag) -> "PrioShip"
        canonicalTag == "PrioFocused(N)" -> "PrioFocused"
        prioFocusedRegex.matches(canonicalTag) -> "PrioFocused"
        prioWoundedDamageExclusionRegex.matches(canonicalTag) -> "PrioWounded"
        targetPhaseDamageExclusionRegex.matches(canonicalTag) -> "TargetPhase"
        avoidPhasedDamageExclusionRegex.matches(canonicalTag) -> "AvoidPhased"
        prioShieldsDamageExclusionRegex.matches(canonicalTag) -> "PrioShields"
        canonicalTag == "PrioShields(N)" -> "PrioShields"
        prioShieldsRegex.matches(canonicalTag) -> "PrioShields"
        prioHullDamageExclusionRegex.matches(canonicalTag) -> "PrioHull"
        canonicalTag == "PrioHull(N)" -> "PrioHull"
        prioHullRegex.matches(canonicalTag) -> "PrioHull"
        canonicalTag == "PrioClose(N)" -> "PrioClose"
        prioCloseRegex.matches(canonicalTag) -> "PrioClose"
        canonicalTag == "PrioFar(N)" -> "PrioFar"
        prioFarRegex.matches(canonicalTag) -> "PrioFar"
        parseSyncTagOptions(canonicalTag) != null -> parseSyncTagOptions(canonicalTag)?.family ?: canonicalTag
        canonicalTag == "TargetBig" -> "TargetBig"
        canonicalTag == "TargetSmall" -> "TargetSmall"
        noPdWasteRegex.matches(canonicalTag) -> "AvoidPD(Waste>N%)"
        noPdHealthRegex.matches(canonicalTag) -> "AvoidPD(H<N)"
        canonicalTag == "IgnoreMinorPD" -> "AvoidPD(H<N)"
        avoidArmorRegex.matches(canonicalTag) -> "AvoidArmor"
        panicFireRegex.matches(canonicalTag) -> "Panic"
        rangeRegex.matches(canonicalTag) -> "Range"
        rofRegex.matches(canonicalTag) -> "LowRoF(N%)"
        else -> canonicalTag
    }
}

private val shieldTargetingTags = listOf(
    "NoShield",
    "AvoidShield",
    "TargetShield",
    "TargetShield+",
    "AvoidShield+",
    "AvoidShield(S<N%)",
    "TargetShield(S>N%)",
    "AvoidShield(TF>N%)",
    "AvoidShield(SF>N%)",
    "AvoidShield(HF>N%)",
    "TargetShield(TF>N%)",
    "TargetShield(SF>N%)",
    "TargetShield(HF>N%)"
)

private fun shieldTagIncompatibilities(tag: String): List<String> {
    return shieldTargetingTags.filter { it != tag }
}

private const val HARD_PD_ONLY_TEMPLATE = "PD"

private fun isHardPdOnlyTemplate(template: String): Boolean = template == HARD_PD_ONLY_TEMPLATE

private fun isTargetShieldTemplate(template: String): Boolean = template.startsWith("TargetShield")

private fun isTargetBigFighterConflict(firstTemplate: String, secondTemplate: String): Boolean {
    return (firstTemplate == "TargetBig" && (secondTemplate == "TargetFighter" || isHardPdOnlyTemplate(secondTemplate))) ||
        (secondTemplate == "TargetBig" && (firstTemplate == "TargetFighter" || isHardPdOnlyTemplate(firstTemplate)))
}

val tagIncompatibility = mapOf(
    "PD" to listOf("Opportunist"),
    "TargetFighter" to listOf("NoFighter", "Opportunist"),
    "NoShield" to shieldTagIncompatibilities("NoShield"),
    "AvoidShield(S<N%)" to shieldTagIncompatibilities("AvoidShield(S<N%)"),
    "TargetShield(S>N%)" to shieldTagIncompatibilities("TargetShield(S>N%)"),
    "AvoidShield" to shieldTagIncompatibilities("AvoidShield"),
    "TargetShield" to shieldTagIncompatibilities("TargetShield"),
    "TargetShield+" to shieldTagIncompatibilities("TargetShield+"),
    "AvoidShield+" to shieldTagIncompatibilities("AvoidShield+"),
    "AvoidShield(TF>N%)" to shieldTagIncompatibilities("AvoidShield(TF>N%)"),
    "AvoidShield(SF>N%)" to shieldTagIncompatibilities("AvoidShield(SF>N%)"),
    "AvoidShield(HF>N%)" to shieldTagIncompatibilities("AvoidShield(HF>N%)"),
    "TargetShield(TF>N%)" to shieldTagIncompatibilities("TargetShield(TF>N%)"),
    "TargetShield(SF>N%)" to shieldTagIncompatibilities("TargetShield(SF>N%)"),
    "TargetShield(HF>N%)" to shieldTagIncompatibilities("TargetShield(HF>N%)"),
    "NoFighter" to listOf("TargetFighter"),
    "Opportunist" to listOf("TargetFighter", "PD"),
    "SyncWindow" to listOf("SyncVolley", "Ambush"),
    "SyncVolley" to listOf("SyncWindow", "Ambush"),
    "Ambush" to listOf("SyncWindow", "SyncVolley")
)

fun isIncompatibleWithExistingTags(tag: String, existingTags: List<String>): Boolean {
    return blockedBySelectedTags(tag, existingTags) != null
}

fun tagIncompatibilityReason(tag: String, existingTags: List<String>): String? {
    return blockedBySelectedTags(tag, existingTags)
}

fun blockedBySelectedTags(candidate: String, selectedTags: Collection<String>): String? {
    firstIncompatibleExistingTag(candidate, selectedTags)?.let { existingTag ->
        return pairwiseIncompatibilityReason(candidate, existingTag)
    }
    return selectedTagSetBlockReason(candidate, selectedTags)
}

fun tagUnavailableReasonForWeaponGroup(
    groupIndex: Int,
    sh: FleetMemberAPI,
    tag: String,
    currentTags: List<String>,
): String? {
    tagDisabledReasonForGroup(groupIndex, sh, tag)?.let { return it }
    val otherTags = currentTags.toMutableList().apply { remove(tag) }
    return tagIncompatibilityReason(tag, otherTags)
}

private fun firstIncompatibleExistingTag(tag: String, existingTags: Collection<String>): String? {
    for (existingTag in existingTags) {
        if (isIncompatibleWithExistingTag(tag, existingTag)) return existingTag
    }
    return null
}

private fun pairwiseIncompatibilityReason(tag: String, existingTag: String): String {
    val tagTemplate = tagNameToRegexName(tag)
    val existingTemplate = tagNameToRegexName(existingTag)
    if (
        (isTargetShieldTemplate(tagTemplate) && existingTemplate == "TargetOverloaded") ||
        (tagTemplate == "TargetOverloaded" && isTargetShieldTemplate(existingTemplate))
    ) {
        return "TargetOverloaded selects defenseless ships, while TargetShield requires a useful shield target."
    }
    return "it cannot be combined with $existingTag."
}

private fun selectedTagSetBlockReason(candidate: String, selectedTags: Collection<String>): String? {
    val selectedTemplates = (selectedTags + candidate).map(::tagNameToRegexName).toSet()
    val hasPdOnly = selectedTemplates.any(::isHardPdOnlyTemplate)
    val hasNoFighter = "NoFighter" in selectedTemplates
    val hasNoMissile = "NoMissile" in selectedTemplates
    val hasNoPd = "NoPD" in selectedTemplates
    val hasTargetShield = selectedTemplates.any(::isTargetShieldTemplate)

    if (hasPdOnly && hasNoFighter && hasNoMissile) {
        return "PD with NoFighter and NoMissile has no valid targets."
    }
    if (hasPdOnly && hasNoFighter && hasNoPd) {
        return "PD and NoPD leave only fighters, and NoFighter removes them."
    }
    if (hasPdOnly && hasNoFighter && hasTargetShield) {
        return "PD + NoFighter leaves missiles, but TargetShield can only fire at ships."
    }

    return null
}

private fun syncFireModeForName(modeName: String): SyncFireMode {
    return when (modeName) {
        "SyncVolley" -> SyncFireMode.VOLLEY
        "Ambush" -> SyncFireMode.AMBUSH
        else -> SyncFireMode.WINDOW
    }
}

private fun isIncompatibleWithExistingTag(tag: String, existingTag: String): Boolean =
    WeaponTagNameCache.incompatiblePair(tag, existingTag) {
        if (EditableWeaponTagDefinitions.areMutuallyExclusive(tag, existingTag)) return@incompatiblePair true
        if (EditableWeaponTagDefinitions.sharesEditableDefinition(tag, existingTag)) return@incompatiblePair false
        val modTag = tagNameToRegexName(tag)
        val existingModTag = tagNameToRegexName(existingTag)
        if (isTargetBigFighterConflict(modTag, existingModTag)) return@incompatiblePair true
        if (
            (isTargetShieldTemplate(modTag) && existingModTag == "TargetOverloaded") ||
            (modTag == "TargetOverloaded" && isTargetShieldTemplate(existingModTag))
        ) {
            return@incompatiblePair true
        }
        tagIncompatibility[modTag]?.contains(existingModTag) == true
    }

fun createTags(names: List<String>, weapon: WeaponAPI): List<WeaponAITagBase> {
    return names.mapNotNull { createTag(it, weapon) }.filter { it.isValid() }
}

fun applySuggestedModes(ship: FleetMemberAPI, storageIndex: Int, allowOverriding: Boolean = true, shipId: String? = null) {
    val id = shipId ?: agcStableShipId(ship)
    val groups = ship.variant.weaponGroups

    groups.forEachIndexed { index, group ->
        if(allowOverriding || loadPersistentTags(id, index, storageIndex).isEmpty()){
            val weaponID = group.slots?.firstOrNull()?.let { ship.variant.getWeaponId(it) } ?: ""
            persistTags(id, index, storageIndex, getSuggestedModesForWeaponId(weaponID))
        }
    }
}

fun getSuggestedModesForWeaponId(weaponID: String) : List<String>{
    return getSuggestedModesForWeaponId(weaponID, Settings.getCurrentWeaponTagList().toSet())
}

fun getSuggestedModesForWeaponId(weaponID: String, supportedTags: Set<String>) : List<String>{
    val suggestedTags = Settings.getCurrentSuggestedTags()
    val rawTags = suggestedTags[weaponID]
        ?: suggestedTags.entries.firstOrNull { (key, _) ->
            runCatching { Regex(key).matches(weaponID) }.getOrDefault(false)
        }?.value
        ?: emptyList()
    return rawTags
        .map(::canonicalizeWeaponTagName)
        .filter { it in supportedTags }
        .distinct()
}
