package io.github.h_sib.declared.core.admin

import io.github.h_sib.declared.core.model.BitPower
import io.github.h_sib.declared.core.model.Bitmask
import io.github.h_sib.declared.core.model.DecodedPowers

/**
 * The policies a device-admin app declares it uses (`DeviceAdminInfo.USES_POLICY_*`). The platform numbers them
 * 0 to 9; the sensor turns "uses policy n" into bit n so one integer carries the set.
 */
enum class DevicePolicy(
    val id: Int,
    override val key: String,
    override val plain: String,
    override val strong: Boolean,
) : BitPower {
    LIMIT_PASSWORD(0, "limit-password", "set rules for how strong your screen lock must be", false),
    WATCH_LOGIN(1, "watch-login", "watch failed unlock attempts", true),
    RESET_PASSWORD(2, "reset-password", "change your screen lock", true),
    FORCE_LOCK(3, "force-lock", "lock the screen at any time", true),
    WIPE_DATA(4, "wipe-data", "erase everything on the phone", true),
    SET_GLOBAL_PROXY(5, "set-global-proxy", "route the phone's web traffic through a proxy", true),
    EXPIRE_PASSWORD(6, "expire-password", "make your screen lock expire", false),
    ENCRYPTED_STORAGE(7, "encrypted-storage", "require storage encryption", false),
    DISABLE_CAMERA(8, "disable-camera", "switch the camera off for every app", true),
    DISABLE_KEYGUARD_FEATURES(9, "disable-keyguard-features", "turn off lock-screen features such as fingerprint unlock", true),
    ;

    override val bit: Int get() = 1 shl id

    companion object {
        fun decode(bits: Int): DecodedPowers = Bitmask.decode(bits, entries)

        fun bitsOf(usedPolicyIds: Iterable<Int>): Int = usedPolicyIds.fold(0) { acc, id -> acc or (1 shl id) }
    }
}
