package com.dp.advancedgunnerycontrol.gui

import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.dp.advancedgunnerycontrol.utils.WeaponCompositionPresetPeekResult
import com.dp.advancedgunnerycontrol.utils.WeaponPresetBackend
import com.dp.advancedgunnerycontrol.utils.WeaponPresetScope
import com.dp.advancedgunnerycontrol.utils.agcStableShipId
import com.dp.advancedgunnerycontrol.utils.getWeaponCompositionPresetKey
import com.dp.advancedgunnerycontrol.utils.peekWeaponPreset
import com.fs.starfarer.api.fleet.FleetMemberAPI

class PresetPeekCache {
    companion object {
        fun peek(
            presetPeekCache: PresetPeekCache?,
            member: FleetMemberAPI,
            groupIndex: Int,
            loadoutIndex: Int,
            scope: WeaponPresetScope,
            backend: WeaponPresetBackend,
            file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME,
        ): WeaponCompositionPresetPeekResult {
            return presetPeekCache?.peek(member, groupIndex, loadoutIndex, scope, backend, file)
                ?: peekWeaponPreset(member, groupIndex, loadoutIndex, scope, backend, file)
        }
    }

    private data class Key(
        val stableShipId: String,
        val rawMemberId: String,
        val hullId: String,
        val groupIndex: Int,
        val loadoutIndex: Int,
        val scope: WeaponPresetScope,
        val backend: WeaponPresetBackend,
        val weaponKey: String,
        val file: String,
    )

    private val resultsByKey = mutableMapOf<Key, WeaponCompositionPresetPeekResult>()

    fun peek(
        member: FleetMemberAPI,
        groupIndex: Int,
        loadoutIndex: Int,
        scope: WeaponPresetScope,
        backend: WeaponPresetBackend,
        file: String = Values.WEAPON_COMP_TAG_PRESETS_JSON_FILE_NAME,
    ): WeaponCompositionPresetPeekResult {
        val key = Key(
            stableShipId = agcStableShipId(member),
            rawMemberId = member.id?.trim().orEmpty(),
            hullId = member.hullId.orEmpty(),
            groupIndex = groupIndex,
            loadoutIndex = loadoutIndex,
            scope = scope,
            backend = backend,
            weaponKey = getWeaponCompositionPresetKey(member, groupIndex).orEmpty(),
            file = file,
        )
        return resultsByKey.getOrPut(key) {
            peekWeaponPreset(member, groupIndex, loadoutIndex, scope, backend, file)
        }
    }

    fun clear() {
        resultsByKey.clear()
    }
}
