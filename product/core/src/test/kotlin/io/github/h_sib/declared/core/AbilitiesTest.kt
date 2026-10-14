package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.accessibility.Ability
import io.github.h_sib.declared.core.admin.DevicePolicy
import io.github.h_sib.declared.core.holders.attentionFor
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.BitPower
import io.github.h_sib.declared.core.model.DecodedPowers
import io.github.h_sib.declared.core.tier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbilitiesTest {

    @Test
    fun theLabFixtureMaskDecodesToWindowContentAndGestures() {
        // lab/fixtures/src/a11y declares canRetrieveWindowContent and canPerformGestures: 0x1 | 0x20 = 33.
        val d = Ability.decode(33)
        assertEquals(listOf(Ability.WINDOW_CONTENT, Ability.GESTURES), d.powers)
        assertEquals(0, d.unrecognizedBits)
        assertTrue(d.hasStrong)
    }

    @Test
    fun unknownBitsAreKeptNotDropped() {
        val d = Ability.decode(0x4 or 0x1 or 0x100)
        assertEquals(listOf(Ability.WINDOW_CONTENT), d.powers)
        assertEquals(0x104, d.unrecognizedBits)
    }

    private fun roundTrips(all: List<BitPower>, decode: (Int) -> DecodedPowers, range: Int) {
        val known = all.fold(0) { acc, a -> acc or a.bit }
        for (bits in 0 until range) {
            val d = decode(bits)
            val rebuilt = d.powers.fold(0) { acc, p -> acc or (p as BitPower).bit } or d.unrecognizedBits
            assertEquals(bits, rebuilt)
            assertEquals(0, d.unrecognizedBits and known)
        }
    }

    @Test
    fun everyAbilityBitPatternDecodesAndReassembles() = roundTrips(Ability.entries, Ability::decode, 0x400)

    @Test
    fun everyDevicePolicyBitPatternDecodesAndReassembles() = roundTrips(DevicePolicy.entries, DevicePolicy::decode, 0x800)

    @Test
    fun bitsAreDistinctPowersOfTwo() {
        for (all in listOf<List<BitPower>>(Ability.entries, DevicePolicy.entries)) {
            val bits = all.map { it.bit }
            assertEquals(bits.size, bits.toSet().size)
            assertTrue(bits.all { it != 0 && it and (it - 1) == 0 })
        }
    }

    @Test
    fun devicePolicyIdsMatchThePlatformConstants() {
        // DeviceAdminInfo.USES_POLICY_*: 0 limit-password, 1 watch-login, 2 reset-password, 3 force-lock, 4 wipe-data,
        // 5 set-global-proxy, 6 expire-password, 7 encrypted-storage, 8 disable-camera, 9 disable-keyguard-features.
        assertEquals((0..9).toList(), DevicePolicy.entries.map { it.id })
        assertEquals(1 shl 3, DevicePolicy.FORCE_LOCK.bit)
        assertEquals(listOf(DevicePolicy.FORCE_LOCK), DevicePolicy.decode(DevicePolicy.bitsOf(listOf(3))).powers)
        assertEquals(DevicePolicy.bitsOf(listOf(3, 4)), DevicePolicy.FORCE_LOCK.bit or DevicePolicy.WIPE_DATA.bit)
    }

    @Test
    fun minorAbilitiesAreNotStrong() {
        assertFalse(Ability.decode(Ability.MAGNIFICATION.bit or Ability.FINGERPRINT_GESTURES.bit).hasStrong)
        assertFalse(DevicePolicy.decode(DevicePolicy.LIMIT_PASSWORD.bit or DevicePolicy.EXPIRE_PASSWORD.bit).hasStrong)
    }

    @Test
    fun attentionRuleMatchesTheStatedTable() {
        val strong = Ability.decode(33)
        val minor = Ability.decode(Ability.MAGNIFICATION.bit)
        assertEquals(Attention.NOTE, attentionFor(Tier.PLATFORM, strong))
        assertEquals(Attention.NOTE, attentionFor(Tier.SYSTEM, strong))
        assertEquals(Attention.LOOK, attentionFor(Tier.STORE, strong))
        assertEquals(Attention.LOOK, attentionFor(Tier.OEM_OR_CARRIER_SETUP, strong))
        assertEquals(Attention.LOOK_FIRST, attentionFor(Tier.SIDELOAD, strong))
        assertEquals(Attention.LOOK_FIRST, attentionFor(Tier.ADB, strong))
        assertEquals(Attention.LOOK_FIRST, attentionFor(Tier.UNKNOWN, strong))
        for (tier in Tier.entries) assertEquals(Attention.NOTE, attentionFor(tier, minor))
        for (tier in Tier.entries) assertEquals(Attention.NOTE, attentionFor(tier, DecodedPowers.NONE))
    }
}
