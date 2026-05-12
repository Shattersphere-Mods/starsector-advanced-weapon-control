package com.dp.advancedgunnerycontrol.weaponais.tags

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.SyncSystemTriggerOption
import com.dp.advancedgunnerycontrol.typesandvalues.syncTriggerOrder
import com.dp.advancedgunnerycontrol.utils.getAutofirePlugin
import com.dp.advancedgunnerycontrol.weaponais.FiringSolution
import com.dp.advancedgunnerycontrol.weaponais.TagBasedAI
import com.dp.advancedgunnerycontrol.weaponais.WeaponDecisionSnapshot
import com.dp.advancedgunnerycontrol.weaponais.angularDistanceFromWeapon
import com.dp.advancedgunnerycontrol.weaponais.determineIfShotWillHitBySetting
import com.dp.advancedgunnerycontrol.weaponais.determineUniversalShipTarget
import com.dp.advancedgunnerycontrol.weaponais.effectiveCollRadius
import com.dp.advancedgunnerycontrol.weaponais.isAimable
import com.dp.advancedgunnerycontrol.weaponais.linearDistanceFromWeapon
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.PI

/**
 * Coordinates SyncWindow, SyncVolley, and Ambush releases for weapons in the
 * same group.
 *
 * High-level flow:
 * 1. Gather sync participants from current TagBasedAI weapon snapshots.
 * 2. Select or preserve a converged release target.
 * 3. Wait until participants are ready, in range, and aligned.
 * 4. Open a release window; protect already-started charge/burst/beam sequences.
 * 5. Optionally trigger the matching ship system before release.
 *
 * Do not broadly memoize evaluate(): target candidates and protected release
 * state can legitimately differ between TagBasedAI hooks in the same frame.
 */
enum class SyncFireMode {
    WINDOW,
    VOLLEY,
    AMBUSH
}

private const val CUSTOM_DATA_KEY = "advanced_gunnery_control_synchronized_fire"
private const val READY_EPSILON = 0.0001f
private const val READY_TOLERANCE_SECONDS = 0.01f
private const val MAX_READY_TOLERANCE_SECONDS = 0.02f
private const val SNAPSHOT_STALE_SECONDS = 0.12f
private const val MIN_RELEASE_WINDOW_SECONDS = 0.12f
private const val VOLLEY_RELEASE_WINDOW_SECONDS = 0.50f
private const val RELEASE_WINDOW_PADDING_SECONDS = 0.12f
private const val RELEASE_ALIGNMENT_SETTLE_SECONDS = 0.03f
private const val ANCHOR_ACTIVITY_GRACE_SECONDS = 0.04f
private const val ANCHOR_WINDOW_TOLERANCE_SECONDS = 0.05f
private const val AIM_POINT_ALIGNMENT_FLAT_DEGREES = 1.25f
private const val AIM_POINT_ALIGNMENT_TARGET_FRACTION = 0.25f
private const val AIM_POINT_ALIGNMENT_MAX_DEGREES = 3.00f
private const val CONVERGENCE_TARGET_PRIORITY_MULTIPLIER = 0.001f
private const val NON_CONVERGENCE_TARGET_PRIORITY_MULTIPLIER = 1000f
private const val CONVERGENCE_TARGET_SWITCH_SCORE_RATIO = 0.85f
private const val DEBUG_SYNC = false
private const val DEBUG_SYNC_INTERVAL_SECONDS = 0.25f

private data class SyncParticipant(
    val weapon: WeaponAPI,
    val snapshot: WeaponDecisionSnapshot
)

private data class SyncReleasePlan(
    val target: CombatEntityAPI,
    val solutionsByWeapon: Map<Int, FiringSolution>
)

private data class SyncTargetCandidate(
    val solution: FiringSolution,
    val priority: Float,
    val timestamp: Float
)

private val burstDelayCacheBySpec = IdentityHashMap<WeaponSpecAPI, Float>()
private val autoChargeCacheBySpec = IdentityHashMap<WeaponSpecAPI, Boolean>()

private fun now(): Float = Global.getCombatEngine()?.getTotalElapsedTime(false) ?: 0f

private fun WeaponAPI.syncId(): Int = System.identityHashCode(this)

private fun CombatEntityAPI.syncId(): Int = System.identityHashCode(this)

private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun CombatEntityAPI.debugId(): String = "${javaClass.simpleName}@${syncId()}"

private fun WeaponAPI.isInterestingSyncDebugWeapon(): Boolean {
    return id == "pulselaser" || id == "tachyonlance"
}

private fun WeaponAPI.debugSyncState(
    snapshot: WeaponDecisionSnapshot?,
    releaseSolution: FiringSolution?,
    rangeFactor: Float
): String {
    val snapshotAge = snapshot?.let { now() - it.timestamp }
    val onReleaseTarget = releaseSolution?.let { isOnTargetForSync(it, rangeFactor) }
    val tagNames = ((getAutofirePlugin() as? TagBasedAI)?.tags ?: emptyList())
        .joinToString("+") { it.javaClass.simpleName }
    return "${id}@${syncId()}{" +
            "ready=${isReadyForSync()}," +
            "ammo=${if (usesAmmo()) "$ammo/$maxAmmo req=${requiredAmmoForSyncStart()}" else "na"}," +
            "win=${fmt(expectedReleaseWindow())}," +
            "cd=${fmt(cooldownRemaining)}," +
            "charge=${fmt(chargeLevel)}," +
            "firing=$isFiring," +
            "burst=$isInBurst," +
            "burstRem=${fmt(burstFireTimeRemaining)}," +
            "snap=${snapshot != null}," +
            "snapAge=${snapshotAge?.let { fmt(it) } ?: "na"}," +
            "base=${snapshot?.baseDecision ?: "na"}," +
            "snapTarget=${snapshot?.solution?.target?.debugId() ?: "none"}," +
            "onRelease=${onReleaseTarget ?: "na"}," +
            "tags=$tagNames" +
            "}"
}

private fun WeaponAPI.canParticipateInSync(): Boolean {
    if (isDisabled || isPermanentlyDisabled || isForceDisabled || isDecorative) return false
    if (!usesAmmo() || ammo > 0) return true

    return regeneratesAmmoForSync()
}

private fun WeaponAPI.hasAmmoForSync(): Boolean {
    return !usesAmmo() || ammo >= requiredAmmoForSyncStart()
}

private fun WeaponAPI.regeneratesAmmoForSync(): Boolean {
    val tracker = ammoTracker ?: return false
    return tracker.ammoPerSecond > READY_EPSILON
}

private fun WeaponAPI.requiredAmmoForSyncStart(): Int {
    if (!usesAmmo()) return 0
    if (isBeam || !regeneratesAmmoForSync()) return 1

    val burstSize = (spec?.burstSize ?: 1).coerceAtLeast(1)
    return burstSize.coerceAtMost(maxAmmo.coerceAtLeast(1))
}

private fun WeaponAPI.isReadyForSync(): Boolean {
    val readyTolerance = maxOf(
        READY_TOLERANCE_SECONDS,
        (Global.getCombatEngine()?.elapsedInLastFrame ?: 0f) * 0.5f
    ).coerceAtMost(MAX_READY_TOLERANCE_SECONDS)
    return canParticipateInSync() &&
            hasAmmoForSync() &&
            cooldownRemaining <= readyTolerance &&
            !isFiring &&
            !isInBurst
}

private fun WeaponAPI.isOnTargetForSync(solution: FiringSolution, rangeFactor: Float): Boolean {
    val targetRadius = effectiveCollRadius(solution.target)
    val distance = linearDistanceFromWeapon(solution.aimPoint, this)
    val triggerHappiness = Settings.customAITriggerHappiness()
    val rangeAllowance = range * rangeFactor
    if (distance - targetRadius > rangeAllowance) return false
    if (!isAimable(this)) return true

    val effectiveCollisionRadius = targetRadius * triggerHappiness + 10f * triggerHappiness
    val arcAllowance = effectiveCollisionRadius / maxOf(distance, 1f) * 180f / PI.toFloat()
    val centralAimAllowance = maxOf(
        AIM_POINT_ALIGNMENT_FLAT_DEGREES,
        AIM_POINT_ALIGNMENT_TARGET_FRACTION * arcAllowance
    ).coerceAtMost(AIM_POINT_ALIGNMENT_MAX_DEGREES)
    val centralAimDistance = angularDistanceFromWeapon(solution.aimPoint, this) * 180f / PI.toFloat()
    return distanceFromArc(solution.aimPoint) <= arcAllowance &&
            centralAimDistance <= centralAimAllowance &&
            determineIfShotWillHitBySetting(
                solution.target,
                solution.aimPoint,
                effectiveCollisionRadius,
                this
            )
}

private fun WeaponAPI.canEventuallyBearOnSyncTarget(solution: FiringSolution, rangeFactor: Float): Boolean {
    val targetRadius = effectiveCollRadius(solution.target)
    val distance = linearDistanceFromWeapon(solution.aimPoint, this)
    val rangeAllowance = range * rangeFactor
    if (distance - targetRadius > rangeAllowance) return false
    if (!isAimable(this)) return true

    val triggerHappiness = Settings.customAITriggerHappiness()
    val effectiveCollisionRadius = targetRadius * triggerHappiness + 10f * triggerHappiness
    val arcAllowance = effectiveCollisionRadius / maxOf(distance, 1f) * 180f / PI.toFloat()
    return distanceFromArc(solution.aimPoint) <= arcAllowance
}

// Release activity and protected sequence detection
private fun WeaponAPI.isReleaseActivityActive(): Boolean {
    return isFiring || isInBurst
}

private fun WeaponAPI.requiresProtectedReleaseWindow(): Boolean {
    val spec = spec ?: return false
    if (isBeam) {
        return isBurstBeam ||
                spec.isBurstBeam ||
                spec.beamChargeupTime > READY_EPSILON ||
                spec.burstDuration > READY_EPSILON
    }

    return requiresFullCharge() ||
            spec.chargeTime > READY_EPSILON ||
            spec.burstSize > 1
}

private fun WeaponAPI.isProtectedReleaseSequenceActive(): Boolean {
    return requiresProtectedReleaseWindow() && isReleaseActivityActive()
}

private fun WeaponAPI.isContinuousBeamForSync(): Boolean {
    return isBeam && !isBurstBeam && !requiresProtectedReleaseWindow()
}

private fun WeaponSpecAPI.burstDelayForSync(): Float {
    return burstDelayCacheBySpec.getOrPut(this) {
        runCatching {
            val method = javaClass.getMethod("getBurstDelay")
            (method.invoke(this) as? Number)?.toFloat() ?: 0f
        }.getOrDefault(0f)
    }
}

private fun WeaponSpecAPI.isAutoChargeForSync(): Boolean {
    return autoChargeCacheBySpec.getOrPut(this) {
        runCatching {
            val method = javaClass.getMethod("isAutoCharge")
            method.invoke(this) == true
        }.getOrDefault(false)
    }
}

private fun WeaponAPI.isAutoChargeProjectileForSync(): Boolean {
    return !isBeam && spec?.isAutoChargeForSync() == true
}

private fun WeaponSpecAPI.projectileBurstDurationForSync(): Float {
    val projectileCount = burstSize.coerceAtLeast(1)
    return (projectileCount - 1).coerceAtLeast(0) * burstDelayForSync()
}

private fun WeaponSpecAPI.beamActiveDurationForSync(): Float {
    if (burstDuration > READY_EPSILON) return burstDuration
    return VOLLEY_RELEASE_WINDOW_SECONDS
}

private fun WeaponAPI.expectedReleaseWindow(): Float {
    if (isContinuousBeamForSync()) return VOLLEY_RELEASE_WINDOW_SECONDS

    val spec = spec ?: return MIN_RELEASE_WINDOW_SECONDS
    val firingSequence = if (isBeam) {
        spec.beamChargeupTime + spec.beamActiveDurationForSync()
    } else {
        spec.chargeTime + spec.projectileBurstDurationForSync()
    }

    return maxOf(MIN_RELEASE_WINDOW_SECONDS, firingSequence + RELEASE_WINDOW_PADDING_SECONDS)
}

private fun WeaponAPI.canStartAndFinishWithinSyncWindow(releaseEndsAt: Float): Boolean {
    if (isContinuousBeamForSync()) return true
    return releaseEndsAt - now() >= expectedReleaseWindow() - ANCHOR_WINDOW_TOLERANCE_SECONDS
}

private fun List<WeaponAPI>.matchesSyncSystemTrigger(trigger: SyncSystemTriggerOption): Boolean {
    return when (trigger) {
        SyncSystemTriggerOption.ACCELERATED_AMMO_FEEDER ->
            any { it.type == WeaponAPI.WeaponType.BALLISTIC }
        SyncSystemTriggerOption.HIGH_ENERGY_FOCUS ->
            any { it.type == WeaponAPI.WeaponType.ENERGY }
        SyncSystemTriggerOption.LIDAR_ARRAY ->
            any { it.type == WeaponAPI.WeaponType.BALLISTIC && it.size == WeaponAPI.WeaponSize.LARGE }
        SyncSystemTriggerOption.TEMPORAL_SHELL,
        SyncSystemTriggerOption.ENTROPY_AMPLIFIER -> true
    }
}

private fun ShipAPI.canTriggerSyncSystem(): Boolean {
    val system = system ?: return false
    if (isShipSystemDisabled) return false
    if (fluxTracker?.isOverloadedOrVenting == true) return false
    if (system.isActive || system.isOn || system.isChargeup || system.isChargedown) return false
    if (system.isCoolingDown || system.isOutOfAmmo) return false
    return system.canBeActivated()
}

private fun CombatEntityAPI.syncSystemShipTarget(): ShipAPI? {
    // AGC intentionally spends sync-triggered ship systems only on non-fighter ship targets.
    // Self-buff systems can help against smaller targets, but spending them on missiles/fighters
    // is too wasteful for the current sync-trigger policy.
    val targetShip = this as? ShipAPI ?: return null
    return targetShip.takeUnless { it.isFighter }
}

private fun ShipAPI.useEntropyAmplifierForSyncTarget(targetShip: ShipAPI) {
    val previousTarget = shipTarget
    try {
        shipTarget = targetShip
        useSystem()
    } finally {
        shipTarget = previousTarget
    }
}

private fun triggerSyncSystemForRelease(
    participants: List<WeaponAPI>,
    triggers: Set<SyncSystemTriggerOption>,
    releaseTarget: CombatEntityAPI,
): Float {
    if (triggers.isEmpty()) return 0f
    val ship = participants.firstOrNull()?.ship ?: return 0f
    val system = ship.system ?: return 0f
    val trigger = syncTriggerOrder.firstOrNull { option ->
        option in triggers &&
                option.systemId == system.id &&
                participants.matchesSyncSystemTrigger(option)
    } ?: return 0f
    if (!ship.canTriggerSyncSystem()) return 0f

    val releaseTargetShip = releaseTarget.syncSystemShipTarget() ?: return 0f
    if (trigger == SyncSystemTriggerOption.ENTROPY_AMPLIFIER) {
        ship.useEntropyAmplifierForSyncTarget(releaseTargetShip)
    } else {
        ship.useSystem()
    }
    return system.chargeUpDur.coerceAtLeast(0f)
}

private fun WeaponAPI.hasSyncTag(mode: SyncFireMode, systemTriggers: Set<SyncSystemTriggerOption>): Boolean {
    return (getAutofirePlugin() as? TagBasedAI)?.tags?.any { tag ->
        tag is SynchronizedFireTag && tag.mode == mode && tag.systemTriggers == systemTriggers
    } == true
}

private fun WeaponAPI.syncDecisionSnapshot(): WeaponDecisionSnapshot? {
    return ((getAutofirePlugin() as? TagBasedAI)?.decisionSnapshot)
        ?.takeIf { it.weapon === this }
}

class SynchronizedFireTag(
    weapon: WeaponAPI,
    val mode: SyncFireMode = SyncFireMode.WINDOW,
    private val rangeFactor: Float = 1f,
    private val requireShipTarget: Boolean = false,
    internal val systemTriggers: Set<SyncSystemTriggerOption> = emptySet(),
) : WeaponAITagBase(weapon) {
    override fun isValidTarget(entity: CombatEntityAPI): Boolean {
        return requiredTargetMatchesOrInactive(entity) && super.isValidTarget(entity)
    }

    override fun isBaseAiValid(entity: CombatEntityAPI): Boolean {
        if (requireShipTarget && selectedRequiredTarget() == null) return true
        if (!requiredTargetMatchesOrInactive(entity)) return false
        val coordinator = coordinator()
        if (mode == SyncFireMode.AMBUSH) {
            if (!coordinator.releaseOpen) return false
            return coordinator.matchesReleaseTarget(entity) ||
                    coordinator.weaponHasLostReleaseTarget(weapon, rangeFactor)
        }
        return coordinator.releaseOpen && coordinator.matchesReleaseTarget(entity)
    }

    override fun overrideBaseFireDecision(solution: FiringSolution?, baseDecision: Boolean): Boolean {
        solution ?: return false
        val requiredTarget = selectedRequiredTarget()
        if (requireShipTarget && requiredTarget == null) return false
        if (requiredTarget != null && solution.target !== requiredTarget) return false
        val participants = synchronizedWeapons()
        if (participants.isEmpty()) return false

        val coordinator = coordinator()
        coordinator.evaluate(participants, mode, rangeFactor, requiredTarget, systemTriggers)

        if (coordinator.isReleasedSequenceProtected(weapon, mode)) return true
        if (mode == SyncFireMode.AMBUSH &&
            coordinator.releaseOpen &&
            !coordinator.matchesReleaseTarget(solution.target)
        ) {
            return weapon.canParticipateInSync() &&
                    weapon.hasAmmoForSync() &&
                    coordinator.weaponHasLostReleaseTarget(weapon, rangeFactor)
        }
        if (mode == SyncFireMode.AMBUSH &&
            coordinator.releaseOpen &&
            coordinator.matchesReleaseTarget(solution.target) &&
            !weapon.isOnTargetForSync(solution, rangeFactor)
        ) {
            return false
        }
        return coordinator.releaseOpen &&
                coordinator.matchesReleaseTarget(solution.target) &&
                weapon.canParticipateInSync() &&
                weapon.hasAmmoForSync()
    }

    override fun observeFiringDecision(solution: FiringSolution, baseDecision: Boolean) {
        val requiredTarget = selectedRequiredTarget()
        if (requireShipTarget && requiredTarget == null) return
        if (requiredTarget != null && solution.target !== requiredTarget) return
        val participants = synchronizedWeapons()
        if (participants.isEmpty()) return

        coordinator().evaluate(participants, mode, rangeFactor, requiredTarget, systemTriggers)
    }

    override fun overrideFiringSolution(): FiringSolution? {
        val requiredTarget = selectedRequiredTarget()
        if (requireShipTarget && requiredTarget == null) return null
        val participants = synchronizedWeapons()
        if (participants.isEmpty()) return null

        val coordinator = coordinator()
        coordinator.evaluate(participants, mode, rangeFactor, requiredTarget, systemTriggers)
        if (mode == SyncFireMode.AMBUSH) {
            return coordinator.ambushFiringSolution(weapon, rangeFactor)
        }
        return coordinator.releaseSolution(weapon)
    }

    override fun shouldFire(solution: FiringSolution): Boolean {
        val requiredTarget = selectedRequiredTarget()
        if (requireShipTarget && requiredTarget == null) return true
        if (requiredTarget != null && solution.target !== requiredTarget) return false
        val participants = synchronizedWeapons()
        if (participants.isEmpty()) return true

        val coordinator = coordinator()
        coordinator.evaluate(participants, mode, rangeFactor, requiredTarget, systemTriggers)

        if (coordinator.isReleasedSequenceProtected(weapon, mode)) return true

        if (mode == SyncFireMode.AMBUSH &&
            coordinator.releaseOpen &&
            !coordinator.matchesReleaseTarget(solution.target)
        ) {
            val shouldAllowOtherTarget = weapon.canParticipateInSync() &&
                    weapon.hasAmmoForSync() &&
                    coordinator.weaponHasLostReleaseTarget(weapon, rangeFactor)
            coordinator.debugDecision(
                weapon,
                participants,
                mode,
                rangeFactor,
                if (shouldAllowOtherTarget) "allowed-ambush-lost-target" else "blocked-ambush-focus"
            )
            return shouldAllowOtherTarget
        }
        if (mode == SyncFireMode.AMBUSH &&
            coordinator.releaseOpen &&
            coordinator.matchesReleaseTarget(solution.target) &&
            coordinator.weaponHasLostReleaseTarget(weapon, rangeFactor)
        ) {
            coordinator.debugDecision(weapon, participants, mode, rangeFactor, "blocked-ambush-target-lost")
            return false
        }

        if (!coordinator.releaseOpen ||
            !coordinator.matchesReleaseTarget(solution.target) ||
            !weapon.canParticipateInSync() ||
            !weapon.hasAmmoForSync()
        ) {
            coordinator.debugDecision(weapon, participants, mode, rangeFactor, "blocked-precheck")
            return false
        }

        if (!coordinator.allowFire(weapon, participants, mode, rangeFactor)) {
            coordinator.debugDecision(weapon, participants, mode, rangeFactor, "blocked-allowFire")
            return false
        }

        coordinator.debugDecision(weapon, participants, mode, rangeFactor, "allowed")
        return true
    }

    override fun isSynchronizedReleaseActive(solution: FiringSolution): Boolean {
        val requiredTarget = selectedRequiredTarget()
        if (requireShipTarget && requiredTarget == null) return false
        if (requiredTarget != null && solution.target !== requiredTarget) return false
        val participants = synchronizedWeapons()
        if (participants.isEmpty()) return false

        val coordinator = coordinator()
        coordinator.evaluate(participants, mode, rangeFactor, requiredTarget, systemTriggers)
        return coordinator.isReleasedSequenceProtected(weapon, mode) ||
                (coordinator.releaseOpen && coordinator.matchesReleaseTarget(solution.target))
    }

    override fun onFireAllowed(solution: FiringSolution) {
        val coordinator = coordinator()
        if ((coordinator.isReleasedSequenceProtected(weapon, mode) || coordinator.releaseOpen) &&
            coordinator.matchesReleaseTarget(solution.target)
        ) {
            weapon.setForceFireOneFrame(true)
        }
    }

    override fun advance() {
        val requiredTarget = selectedRequiredTarget()
        if (requireShipTarget && requiredTarget == null) return
        val participants = synchronizedWeapons()
        if (participants.isEmpty()) return

        val coordinator = coordinator()
        coordinator.evaluate(participants, mode, rangeFactor, requiredTarget, systemTriggers)
    }

    override fun computeTargetPriorityModifierForGroupTargetChoice(solution: FiringSolution): Float = 1f

    override fun observeTargetPriority(solution: FiringSolution, priority: Float) {
        val requiredTarget = selectedRequiredTarget()
        if (requireShipTarget && requiredTarget == null) return
        if (requiredTarget != null && solution.target !== requiredTarget) return
        val participants = synchronizedWeapons()
        if (participants.isEmpty()) return

        coordinator().observeTargetPriority(weapon, participants, mode, rangeFactor, solution, priority, requiredTarget)
    }

    override fun computeTargetPriorityModifier(solution: FiringSolution): Float {
        val requiredTarget = requiredShipTargetOrNull()
        if (requireShipTarget) {
            if (requiredTarget == null) return 1f
            if (solution.target !== requiredTarget) return NON_CONVERGENCE_TARGET_PRIORITY_MULTIPLIER
        }
        val coordinator = coordinator()
        if (coordinator.releaseOpen) {
            if (mode == SyncFireMode.AMBUSH) {
                return when {
                    coordinator.matchesReleaseTarget(solution.target) &&
                            coordinator.weaponCanPursueReleaseTarget(weapon, rangeFactor) -> 0.01f
                    coordinator.matchesReleaseTarget(solution.target) -> 100f
                    coordinator.weaponCanPursueReleaseTarget(weapon, rangeFactor) -> 100f
                    else -> 1f
                }
            }
            return if (coordinator.matchesReleaseTarget(solution.target)) 0.01f else 100f
        }
        return if (coordinator.hasConvergenceTarget()) {
            if (coordinator.matchesConvergenceTarget(solution.target)) {
                CONVERGENCE_TARGET_PRIORITY_MULTIPLIER
            } else {
                NON_CONVERGENCE_TARGET_PRIORITY_MULTIPLIER
            }
        } else {
            1f
        }
    }

    override fun isBaseAiOverridable(): Boolean = true

    override fun avoidDebris(): Boolean = false

    private fun requiredShipTargetOrNull(): CombatEntityAPI? {
        return weapon.ship?.determineUniversalShipTarget()
    }

    private fun selectedRequiredTarget(): CombatEntityAPI? {
        if (!requireShipTarget) return null
        return requiredShipTargetOrNull() ?: run {
            coordinator().clearRequiredTarget()
            null
        }
    }

    private fun requiredTargetMatchesOrInactive(entity: CombatEntityAPI): Boolean {
        val requiredTarget = selectedRequiredTarget() ?: return true
        return entity === requiredTarget
    }

    private fun synchronizedWeapons(): List<WeaponAPI> {
        val group = weapon.ship?.getWeaponGroupFor(weapon)
            ?: return listOf(weapon).filter { it.canParticipateInSync() && it.hasSyncTag(mode, systemTriggers) }

        return group.weaponsCopy.filter { participant ->
            participant.canParticipateInSync() && participant.hasSyncTag(mode, systemTriggers)
        }
    }

    private fun coordinator(): SyncGroupCoordinator {
        val ship = weapon.ship
        val groupIndex = ship?.weaponGroupsCopy?.indexOf(ship.getWeaponGroupFor(weapon)) ?: -1
        val triggerKey = syncTriggerOrder
            .filter { it in systemTriggers }
            .joinToString(",") { it.token }
        val key = "${System.identityHashCode(ship ?: weapon)}:$groupIndex:$mode:$requireShipTarget:$triggerKey"
        return coordinators().getOrPut(key) { SyncGroupCoordinator() }
    }

    private fun coordinators(): MutableMap<String, SyncGroupCoordinator> {
        val engine = Global.getCombatEngine() ?: return mutableMapOf()
        return synchronizedCoordinatorStore(engine)
    }

    private fun synchronizedCoordinatorStore(engine: CombatEngineAPI): MutableMap<String, SyncGroupCoordinator> {
        val raw = engine.customData[CUSTOM_DATA_KEY]
        val existing = raw as? MutableMap<*, *>
        if (existing != null && existing.all { (key, value) ->
                key is String && value is SyncGroupCoordinator
            }
        ) {
            @Suppress("UNCHECKED_CAST")
            return existing as MutableMap<String, SyncGroupCoordinator>
        }

        val created = mutableMapOf<String, SyncGroupCoordinator>()
        engine.customData[CUSTOM_DATA_KEY] = created
        return created
    }

    // Sync lifecycle:
    // 1. observeTargetPriority records fresh per-weapon target candidates.
    // 2. evaluate converges on a shared target, then waits briefly for it to settle.
    // 3. openReleaseWindow snapshots the settled release target and firing solutions.
    // 4. allowFire marks released weapons while the release window is open.
    // 5. started weapons are protected until their burst/charge sequence is complete.
    // 6. the coordinator waits for all participants to become ready before the next cycle.
    private class SyncGroupCoordinator {
        // Current weapons participating in this group/mode cycle.
        private var participantIds: Set<Int> = emptySet()
        // Weapons with the longest predicted release window; window mode uses them to keep the window open.
        private var anchorIds: Set<Int> = emptySet()
        private var releaseStartedAt = 0f
        private var releaseWindowSeconds = MIN_RELEASE_WINDOW_SECONDS
        // Target selected for the currently open release window.
        private var releaseTargetId = 0
        private var releaseTarget: CombatEntityAPI? = null
        private val releaseSolutionsByWeapon = mutableMapOf<Int, FiringSolution>()
        // Best shared target during the pre-release convergence phase.
        private var convergenceTargetId = 0
        private var convergenceTarget: CombatEntityAPI? = null
        private var convergenceTargetScore = Float.POSITIVE_INFINITY
        private var pendingReleaseTargetId = 0
        private var pendingReleaseReadySince = 0f
        // True after a release closes; prevents a new cycle until all participants are safely ready again.
        private var waitingForCycle = false
        private var anchorActivityObserved = false
        private var lastAnchorActivityAt = 0f
        private var cycleResetAt = -1f
        private var nextDebugAt = 0f
        private val nextDecisionDebugByWeapon = mutableMapOf<Int, Float>()
        // Per-weapon candidates observed from target-priority evaluation; stale entries are pruned by time.
        private val targetCandidatesByWeapon = mutableMapOf<Int, MutableMap<Int, SyncTargetCandidate>>()
        // Weapons permitted to fire in the current window.
        private val releasedWeaponIds = mutableSetOf<Int>()
        // Released weapons whose firing/charge/burst sequence has visibly started.
        private val releasedWeaponStartedIds = mutableSetOf<Int>()
        // Time-based protection end for each started sequence.
        private val releasedWeaponProtectionEnds = mutableMapOf<Int, Float>()

        // True only while the coordinator is allowing the group to fire at releaseTarget.
        var releaseOpen = false
            private set

        fun observeTargetPriority(
            weapon: WeaponAPI,
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            rangeFactor: Float,
            solution: FiringSolution,
            priority: Float,
            requiredTarget: CombatEntityAPI?
        ) {
            syncRequiredTarget(requiredTarget)
            syncParticipants(participants, mode, rangeFactor)
            val weaponId = weapon.syncId()
            targetCandidatesByWeapon
                .getOrPut(weaponId) { mutableMapOf() }[solution.target.syncId()] =
                SyncTargetCandidate(solution, priority, now())
            updateConvergenceTarget(participants, rangeFactor)
        }

        fun evaluate(
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            rangeFactor: Float,
            requiredTarget: CombatEntityAPI?,
            systemTriggers: Set<SyncSystemTriggerOption>,
        ) {
            // TagBasedAI calls this from several hooks in one frame. A broad per-frame cache is risky because
            // those hooks can observe new candidates, firing starts, and protected-sequence transitions.
            syncRequiredTarget(requiredTarget)
            syncParticipants(participants, mode, rangeFactor)
            updateReleasedStarts(participants)
            updateOpenRelease(participants, mode)

            if (releaseOpen && shouldCloseReleaseWindow(participants, mode, rangeFactor)) {
                closeRelease()
                debug(participants, mode, rangeFactor, "closed-release", true)
                return
            }

            if (waitingForCycle) {
                if (canStartNextCycle(participants, mode)) {
                    clearWaiting()
                    debug(participants, mode, rangeFactor, "cycle-ready", true)
                } else {
                    debug(participants, mode, rangeFactor, "waiting")
                }
                return
            }

            val currentTime = now()
            if (releaseOpen || cycleResetAt == currentTime) return

            val releasePlan = selectReleaseTarget(participants, mode, rangeFactor) ?: run {
                clearPendingReleaseTarget()
                debug(participants, mode, rangeFactor, "no-release-target")
                return
            }
            if (!hasSettledReleaseTarget(releasePlan.target)) {
                debug(participants, mode, rangeFactor, "settling-release-target")
                return
            }

            openReleaseWindow(participants, mode, releasePlan, systemTriggers)
            debug(participants, mode, rangeFactor, "opened-release", true)
        }

        fun matchesReleaseTarget(target: CombatEntityAPI): Boolean {
            return releaseTargetId == 0 || releaseTargetId == target.syncId()
        }

        fun clearRequiredTarget() {
            syncRequiredTarget(null)
        }

        fun hasConvergenceTarget(): Boolean {
            return convergenceTargetId != 0 && convergenceTarget != null
        }

        fun matchesConvergenceTarget(target: CombatEntityAPI): Boolean {
            return convergenceTargetId != 0 && convergenceTargetId == target.syncId()
        }

        fun releaseSolution(weapon: WeaponAPI): FiringSolution? {
            val target = releaseTarget ?: return null
            if (!releaseOpen) return null

            val weaponId = weapon.syncId()
            return freshReleaseSolution(weapon) ?: releaseSolutionsByWeapon[weaponId] ?: FiringSolution(target, target.location)
        }

        fun ambushFiringSolution(weapon: WeaponAPI, rangeFactor: Float): FiringSolution? {
            if (!releaseOpen) return null
            val solution = currentAmbushPursuitSolution(weapon) ?: return null
            if (!weapon.canEventuallyBearOnSyncTarget(solution, rangeFactor)) return null
            return solution
        }

        fun weaponCanPursueReleaseTarget(weapon: WeaponAPI, rangeFactor: Float): Boolean {
            val solution = currentAmbushPursuitSolution(weapon) ?: return false
            return weapon.canEventuallyBearOnSyncTarget(solution, rangeFactor)
        }

        fun weaponHasLostReleaseTarget(weapon: WeaponAPI, rangeFactor: Float): Boolean {
            return !weaponCanPursueReleaseTarget(weapon, rangeFactor)
        }

        private fun freshReleaseSolution(weapon: WeaponAPI): FiringSolution? {
            if (releaseTargetId == 0) return null
            val weaponId = weapon.syncId()
            val currentSolution = weapon.syncDecisionSnapshot()
                ?.solution
                ?.takeIf { it.target.syncId() == releaseTargetId }

            if (currentSolution != null) {
                releaseSolutionsByWeapon[weaponId] = currentSolution
                return currentSolution
            }

            val currentTime = now()
            val candidateSolution = targetCandidatesByWeapon[weaponId]
                ?.get(releaseTargetId)
                ?.takeIf { currentTime - it.timestamp <= candidateMaxAge() }
                ?.solution

            if (candidateSolution != null) {
                releaseSolutionsByWeapon[weaponId] = candidateSolution
                return candidateSolution
            }

            return null
        }

        private fun currentAmbushPursuitSolution(weapon: WeaponAPI): FiringSolution? {
            val target = releaseTarget ?: return null
            if (!releaseOpen) return null
            return freshReleaseSolution(weapon) ?: FiringSolution(target, target.location)
        }

        fun isReleasedSequenceProtected(weapon: WeaponAPI, mode: SyncFireMode): Boolean {
            val weaponId = weapon.syncId()
            if (!releasedWeaponIds.contains(weaponId)) return false
            if (mode == SyncFireMode.VOLLEY &&
                weapon.isAutoChargeProjectileForSync() &&
                releasedWeaponStartedIds.contains(weaponId)
            ) {
                return false
            }
            return weapon.isProtectedReleaseSequenceActive() &&
                    releasedWeaponStartedIds.contains(weaponId) &&
                    now() <= (releasedWeaponProtectionEnds[weaponId] ?: 0f)
        }

        fun allowFire(
            weapon: WeaponAPI,
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            rangeFactor: Float
        ): Boolean {
            if (!releaseOpen) return false
            if (isReleasedSequenceProtected(weapon, mode)) return true
            if (!weapon.canParticipateInSync() || !weapon.hasAmmoForSync()) return false
            if (releasedWeaponStartedIds.isEmpty() && now() < releaseStartedAt) return false
            if (releasedWeaponStartedIds.isEmpty() && !canStartReleaseNow(participants, rangeFactor)) return false

            return when (mode) {
                SyncFireMode.WINDOW -> allowWindowFire(weapon)
                SyncFireMode.VOLLEY -> allowVolleyFire(weapon)
                SyncFireMode.AMBUSH -> allowAmbushFire(weapon)
            }
        }

        fun debugDecision(
            weapon: WeaponAPI,
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            rangeFactor: Float,
            reason: String
        ) {
            if (!shouldDebug(participants)) return
            val currentTime = now()
            val weaponId = weapon.syncId()
            val nextAllowedAt = nextDecisionDebugByWeapon[weaponId] ?: 0f
            if (currentTime < nextAllowedAt) return

            nextDecisionDebugByWeapon[weaponId] = currentTime + DEBUG_SYNC_INTERVAL_SECONDS
            Global.getLogger(SynchronizedFireTag::class.java).info(
                "[AGC_SYNC_DEBUG] t=${fmt(currentTime)} mode=$mode weapon=${weapon.id} decision=$reason " +
                        debugSummary(participants, rangeFactor)
            )
        }

        private fun allowWindowFire(weapon: WeaponAPI): Boolean {
            if (isAnchor(weapon) &&
                hasReleaseStarted(weapon) &&
                !weapon.isContinuousBeamForSync()
            ) {
                return false
            }
            if (!isAnchor(weapon) &&
                !hasReleaseStarted(weapon) &&
                !weapon.canStartAndFinishWithinSyncWindow(releaseStartedAt + releaseWindowSeconds)
            ) {
                return false
            }

            markReleased(weapon)
            return true
        }

        private fun allowVolleyFire(weapon: WeaponAPI): Boolean {
            if (!hasReleased(weapon)) {
                markReleased(weapon)
                return true
            }

            if (weapon.isContinuousBeamForSync() && isWithinReleaseWindow()) return true
            if (!hasReleaseStarted(weapon) && isWithinReleaseWindow()) return true

            return false
        }

        private fun allowAmbushFire(weapon: WeaponAPI): Boolean {
            markReleased(weapon)
            return true
        }

        private fun syncParticipants(participants: List<WeaponAPI>, mode: SyncFireMode, rangeFactor: Float) {
            val currentIds = participants.map { it.syncId() }.toSet()
            if (currentIds == participantIds) return

            if (releaseOpen && mode == SyncFireMode.AMBUSH) {
                participantIds = currentIds
                targetCandidatesByWeapon.keys.retainAll(currentIds)
                debug(participants, mode, rangeFactor, "ambush-participants-changed", true)
                return
            }

            participantIds = currentIds
            targetCandidatesByWeapon.keys.retainAll(currentIds)
            resetCycleState()
            cycleResetAt = now()
            debug(participants, mode, rangeFactor, "participants-changed", true)
        }

        private fun updateConvergenceTarget(participants: List<WeaponAPI>, rangeFactor: Float) {
            if (releaseOpen) return

            val currentTime = now()
            val maxAge = candidateMaxAge()
            val participantIds = participants.map { it.syncId() }.toSet()
            targetCandidatesByWeapon.keys.retainAll(participantIds)
            targetCandidatesByWeapon.values.forEach { candidates ->
                candidates.entries.removeIf { currentTime - it.value.timestamp > maxAge }
            }

            val requiredTargetFilterId = requiredTargetId.takeIf { it != 0 }
            val seenTargetIds = mutableSetOf<Int>()
            var best: ScoredConvergenceTarget? = null
            var current: ScoredConvergenceTarget? = null

            for (candidates in targetCandidatesByWeapon.values) {
                for (targetId in candidates.keys) {
                    if (!seenTargetIds.add(targetId)) continue
                    if (requiredTargetFilterId != null && targetId != requiredTargetFilterId) continue

                    val scored = scoreCommonTarget(targetId, participants, rangeFactor) ?: continue
                    if (scored.targetId == convergenceTargetId) {
                        current = scored
                    }
                    if (best == null || scored.score < best!!.score) {
                        best = scored
                    }
                }
            }

            if (best == null) {
                convergenceTargetId = 0
                convergenceTarget = null
                convergenceTargetScore = Float.POSITIVE_INFINITY
                clearPendingReleaseTarget()
                return
            }

            val shouldSwitch = current == null ||
                    best.targetId == convergenceTargetId ||
                    best.score < current.score * CONVERGENCE_TARGET_SWITCH_SCORE_RATIO

            val chosen = if (shouldSwitch) best else current!!
            if (convergenceTargetId != chosen.targetId) clearPendingReleaseTarget()
            convergenceTargetId = chosen.targetId
            convergenceTarget = chosen.target
            convergenceTargetScore = chosen.score
        }

        private data class ScoredConvergenceTarget(
            val targetId: Int,
            val target: CombatEntityAPI,
            val score: Float
        )

        private fun scoreCommonTarget(
            targetId: Int,
            participants: List<WeaponAPI>,
            rangeFactor: Float
        ): ScoredConvergenceTarget? {
            var target: CombatEntityAPI? = null
            var score = 0f

            participants.forEach { participant ->
                val candidate = targetCandidatesByWeapon[participant.syncId()]?.get(targetId)
                    ?: return null
                if (!participant.canEventuallyBearOnSyncTarget(candidate.solution, rangeFactor)) return null
                if (!participant.isValidSynchronizedTarget(candidate.solution)) return null

                target = candidate.solution.target
                score += candidate.priority
            }

            return ScoredConvergenceTarget(targetId, target ?: return null, score)
        }

        private fun selectReleaseTarget(
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            rangeFactor: Float
        ): SyncReleasePlan? {
            if (participants.any { isReleasedSequenceProtected(it, mode) || it.isProtectedReleaseSequenceActive() }) return null
            if (participants.any { !it.isReadyForSync() }) return null

            val snapshots = collectFreshSnapshots(participants) ?: return null
            val candidates = releaseTargetCandidates(snapshots)
            val triedCandidateIndexes = BooleanArray(candidates.size)
            var attempts = 0
            while (attempts < candidates.size) {
                attempts++
                val bestIndex = nextReleaseCandidateIndex(candidates, snapshots, triedCandidateIndexes)
                if (bestIndex < 0) return null

                triedCandidateIndexes[bestIndex] = true
                val candidate = candidates[bestIndex]
                val releaseSolutions = releaseSolutionsForCandidate(candidate, snapshots) ?: continue
                if (allParticipantsOnPredictedAimPoints(snapshots, releaseSolutions, rangeFactor)) {
                    return SyncReleasePlan(candidate, releaseSolutions)
                }
            }
            return null
        }

        private fun releaseTargetCandidates(snapshots: List<SyncParticipant>): List<CombatEntityAPI> {
            val candidates = mutableListOf<CombatEntityAPI>()
            val candidateIds = mutableSetOf<Int>()
            val requiredTargetFilterId = requiredTargetId.takeIf { it != 0 }
            val currentConvergenceTarget = convergenceTarget
            if (currentConvergenceTarget != null && candidateIds.add(currentConvergenceTarget.syncId())) {
                candidates += currentConvergenceTarget
            }

            for (snapshot in snapshots) {
                val candidate = snapshot.snapshot.solution?.target ?: continue
                if (!requiredTargetMatchesFilter(candidate, requiredTargetFilterId)) continue
                if (candidateIds.add(candidate.syncId())) {
                    candidates += candidate
                }
            }
            return candidates
        }

        private fun requiredTargetMatchesFilter(
            candidate: CombatEntityAPI,
            requiredTargetFilterId: Int?,
        ): Boolean {
            return requiredTargetFilterId == null || candidate.syncId() == requiredTargetFilterId
        }

        private fun nextReleaseCandidateIndex(
            candidates: List<CombatEntityAPI>,
            snapshots: List<SyncParticipant>,
            triedCandidateIndexes: BooleanArray,
        ): Int {
            var bestIndex = -1
            var bestScore = Int.MIN_VALUE
            for (index in candidates.indices) {
                if (triedCandidateIndexes[index]) continue
                val score = releaseCandidatePreferenceScore(candidates[index], snapshots)
                if (bestIndex < 0 || score > bestScore) {
                    bestIndex = index
                    bestScore = score
                }
            }
            return bestIndex
        }

        private fun releaseCandidatePreferenceScore(
            candidate: CombatEntityAPI,
            snapshots: List<SyncParticipant>,
        ): Int {
            val candidateId = candidate.syncId()
            if (candidateId == convergenceTargetId) return Int.MAX_VALUE

            var matchingSnapshots = 0
            for (snapshot in snapshots) {
                if (snapshot.snapshot.solution?.target?.syncId() == candidateId) matchingSnapshots++
            }
            return matchingSnapshots
        }

        private fun releaseSolutionsForCandidate(
            candidate: CombatEntityAPI,
            snapshots: List<SyncParticipant>,
        ): Map<Int, FiringSolution>? {
            val candidateId = candidate.syncId()
            val releaseSolutions = mutableMapOf<Int, FiringSolution>()
            for (participant in snapshots) {
                val weaponId = participant.weapon.syncId()
                val solution = participant.snapshot.solution
                    ?.takeIf { it.target.syncId() == candidateId }
                    ?: targetCandidatesByWeapon[weaponId]
                        ?.get(candidateId)
                        ?.solution
                    ?: return null
                releaseSolutions[weaponId] = solution
            }
            return releaseSolutions
        }

        private fun allParticipantsOnPredictedAimPoints(
            snapshots: List<SyncParticipant>,
            releaseSolutions: Map<Int, FiringSolution>,
            rangeFactor: Float,
        ): Boolean {
            for (participant in snapshots) {
                val solution = releaseSolutions[participant.weapon.syncId()] ?: return false
                if (!participant.weapon.isOnTargetForSync(solution, rangeFactor)) return false
            }
            return true
        }

        private fun hasSettledReleaseTarget(target: CombatEntityAPI): Boolean {
            val currentTime = now()
            val targetId = target.syncId()
            if (pendingReleaseTargetId != targetId) {
                pendingReleaseTargetId = targetId
                pendingReleaseReadySince = currentTime
                return false
            }

            return currentTime - pendingReleaseReadySince >= RELEASE_ALIGNMENT_SETTLE_SECONDS
        }

        private fun clearPendingReleaseTarget() {
            pendingReleaseTargetId = 0
            pendingReleaseReadySince = 0f
        }

        private fun canStartReleaseNow(participants: List<WeaponAPI>, rangeFactor: Float): Boolean {
            return participants.all { participant ->
                val solution = releaseSolution(participant) ?: return@all false
                participant.isReadyForSync() && participant.isOnTargetForSync(solution, rangeFactor)
            }
        }

        private fun collectFreshSnapshots(participants: List<WeaponAPI>): List<SyncParticipant>? {
            val currentTime = now()
            val maxAge = candidateMaxAge()
            val snapshots = mutableListOf<SyncParticipant>()

            for (participant in participants) {
                val snapshot = participant.syncDecisionSnapshot() ?: return null
                if (currentTime - snapshot.timestamp > maxAge) return null
                snapshots.add(SyncParticipant(participant, snapshot))
            }

            return snapshots
        }

        private fun candidateMaxAge(): Float {
            return maxOf(
                SNAPSHOT_STALE_SECONDS,
                (Global.getCombatEngine()?.elapsedInLastFrame ?: 0f) * 4f
            )
        }

        private fun WeaponAPI.isValidSynchronizedTarget(solution: FiringSolution): Boolean {
            return ((getAutofirePlugin() as? TagBasedAI)?.tags ?: emptyList())
                .filterNot { it is SynchronizedFireTag }
                .all { it.isValidSynchronizedTarget(solution) }
        }

        private fun openReleaseWindow(
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            releasePlan: SyncReleasePlan,
            systemTriggers: Set<SyncSystemTriggerOption>,
        ) {
            val currentTime = now()
            val maxWindow = participants.maxOfOrNull { it.expectedReleaseWindow() } ?: MIN_RELEASE_WINDOW_SECONDS
            val systemChargeDelay = triggerSyncSystemForRelease(participants, systemTriggers, releasePlan.target)

            releaseOpen = true
            waitingForCycle = false
            releaseStartedAt = currentTime + systemChargeDelay
            lastAnchorActivityAt = currentTime
            releaseTargetId = releasePlan.target.syncId()
            releaseTarget = releasePlan.target
            releaseSolutionsByWeapon.clear()
            releaseSolutionsByWeapon.putAll(releasePlan.solutionsByWeapon)
            clearPendingReleaseTarget()
            releaseWindowSeconds = if (mode == SyncFireMode.VOLLEY) VOLLEY_RELEASE_WINDOW_SECONDS else maxWindow
            anchorIds = participants
                .filter { maxWindow - it.expectedReleaseWindow() <= ANCHOR_WINDOW_TOLERANCE_SECONDS }
                .map { it.syncId() }
                .toSet()
            releasedWeaponIds.clear()
            releasedWeaponStartedIds.clear()
            releasedWeaponProtectionEnds.clear()
            anchorActivityObserved = false
        }

        private fun closeRelease() {
            releaseOpen = false
            waitingForCycle = true
        }

        private fun clearWaiting() {
            waitingForCycle = false
            releasedWeaponIds.clear()
            releasedWeaponStartedIds.clear()
            releasedWeaponProtectionEnds.clear()
            releaseTargetId = 0
            releaseTarget = null
            releaseSolutionsByWeapon.clear()
            convergenceTargetId = 0
            convergenceTarget = null
            convergenceTargetScore = Float.POSITIVE_INFINITY
            clearPendingReleaseTarget()
            anchorIds = emptySet()
            anchorActivityObserved = false
            cycleResetAt = now()
        }

        private fun resetCycleState() {
            releaseOpen = false
            waitingForCycle = false
            releasedWeaponIds.clear()
            releasedWeaponStartedIds.clear()
            releasedWeaponProtectionEnds.clear()
            releaseTargetId = 0
            releaseTarget = null
            releaseSolutionsByWeapon.clear()
            convergenceTargetId = 0
            convergenceTarget = null
            convergenceTargetScore = Float.POSITIVE_INFINITY
            clearPendingReleaseTarget()
            anchorIds = emptySet()
            anchorActivityObserved = false
        }

        private var requiredTargetId = 0

        private fun syncRequiredTarget(requiredTarget: CombatEntityAPI?) {
            val nextRequiredTargetId = requiredTarget?.syncId() ?: 0
            if (nextRequiredTargetId == requiredTargetId) return
            requiredTargetId = nextRequiredTargetId
            targetCandidatesByWeapon.clear()
            resetCycleState()
            cycleResetAt = now()
        }

        private fun updateReleasedStarts(participants: List<WeaponAPI>) {
            participants.forEach { participant ->
                val participantId = participant.syncId()
                if (releasedWeaponIds.contains(participantId) &&
                    (!participant.isReadyForSync() || participant.isReleaseActivityActive())
                ) {
                    if (releasedWeaponStartedIds.add(participantId)) {
                        releasedWeaponProtectionEnds[participantId] = now() + participant.expectedReleaseWindow()
                    }
                }
            }
        }

        private fun updateOpenRelease(participants: List<WeaponAPI>, mode: SyncFireMode) {
            if (!releaseOpen || mode != SyncFireMode.WINDOW) return

            val currentTime = now()
            val activeAnchor = participants.any { participant ->
                anchorIds.contains(participant.syncId()) && participant.isReleaseActivityActive()
            }
            if (activeAnchor) {
                anchorActivityObserved = true
                lastAnchorActivityAt = currentTime
            }
        }

        private fun shouldCloseReleaseWindow(
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            rangeFactor: Float
        ): Boolean {
            val currentTime = now()
            if (mode == SyncFireMode.VOLLEY) {
                return currentTime - releaseStartedAt >= releaseWindowSeconds
            }
            if (mode == SyncFireMode.AMBUSH) {
                return shouldEndAmbush(participants, rangeFactor)
            }

            val reachedPredictedWindow = currentTime - releaseStartedAt >= releaseWindowSeconds
            val anchorWentQuiet = anchorActivityObserved &&
                    currentTime - lastAnchorActivityAt >= ANCHOR_ACTIVITY_GRACE_SECONDS
            val noAnchorObserved = !anchorActivityObserved && reachedPredictedWindow

            return reachedPredictedWindow || anchorWentQuiet || noAnchorObserved
        }

        private fun shouldEndAmbush(participants: List<WeaponAPI>, rangeFactor: Float): Boolean {
            if (releaseTarget == null) return true
            return participants.none { participant ->
                participant.canParticipateInSync() &&
                        weaponCanPursueReleaseTarget(participant, rangeFactor)
            }
        }

        private fun canStartNextCycle(participants: List<WeaponAPI>, mode: SyncFireMode): Boolean {
            return participants.none { isReleasedSequenceProtected(it, mode) || it.isProtectedReleaseSequenceActive() } &&
                    participants.all { it.isReadyForSync() }
        }

        private fun debug(
            participants: List<WeaponAPI>,
            mode: SyncFireMode,
            rangeFactor: Float,
            reason: String,
            force: Boolean = false
        ) {
            if (!shouldDebug(participants)) return
            val currentTime = now()
            if (!force && currentTime < nextDebugAt) return

            nextDebugAt = currentTime + DEBUG_SYNC_INTERVAL_SECONDS
            Global.getLogger(SynchronizedFireTag::class.java).info(
                "[AGC_SYNC_DEBUG] t=${fmt(currentTime)} mode=$mode event=$reason " +
                        debugSummary(participants, rangeFactor)
            )
        }

        private fun shouldDebug(participants: List<WeaponAPI>): Boolean {
            return DEBUG_SYNC && participants.any { it.isInterestingSyncDebugWeapon() }
        }

        private fun debugSummary(participants: List<WeaponAPI>, rangeFactor: Float): String {
            val target = releaseTarget
            val participantSummary = participants.joinToString(";") { participant ->
                participant.debugSyncState(
                    participant.syncDecisionSnapshot(),
                    releaseSolution(participant),
                    rangeFactor
                )
            }
            return "open=$releaseOpen waiting=$waitingForCycle " +
                    "target=${target?.debugId() ?: "none"} " +
                    "converge=${convergenceTarget?.debugId() ?: "none"} convergeScore=${fmt(convergenceTargetScore)} " +
                    "window=${fmt(releaseWindowSeconds)} elapsed=${fmt(now() - releaseStartedAt)} " +
                    "anchors=$anchorIds released=$releasedWeaponIds started=$releasedWeaponStartedIds " +
                    "participants=[$participantSummary]"
        }

        private fun markReleased(weapon: WeaponAPI) {
            val weaponId = weapon.syncId()
            releasedWeaponIds.add(weaponId)
            releasedWeaponProtectionEnds[weaponId] = now() + weapon.expectedReleaseWindow()
        }

        private fun hasReleased(weapon: WeaponAPI): Boolean {
            return releasedWeaponIds.contains(weapon.syncId())
        }

        private fun hasReleaseStarted(weapon: WeaponAPI): Boolean {
            return releasedWeaponStartedIds.contains(weapon.syncId())
        }

        private fun isAnchor(weapon: WeaponAPI): Boolean {
            return anchorIds.contains(weapon.syncId())
        }

        private fun isWithinReleaseWindow(): Boolean {
            return releaseOpen && now() - releaseStartedAt <= releaseWindowSeconds
        }
    }
}
