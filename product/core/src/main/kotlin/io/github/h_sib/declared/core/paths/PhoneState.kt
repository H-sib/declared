package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.sensor.ActiveNetwork
import io.github.h_sib.declared.core.sensor.AdvancedProtectionState
import io.github.h_sib.declared.core.sensor.DeviceFactsReading
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.UserCaReading
import io.github.h_sib.declared.core.sensor.WifiReading
import io.github.h_sib.declared.core.sensor.WifiSecurity
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The conditions the attack-path rules need, as values rather than as sentences (STAGE2_DESIGN.md section 6). They are written
 * in the polarity of the weakness ("there is no screen lock"), so a defence that is present reads FALSE and the engine needs no
 * negation. One place decides what each means and when it is unreadable.
 */
enum class StateKey {
    NO_SCREEN_LOCK,
    ADVANCED_PROTECTION_OFF,
    USB_DEBUGGING_ON,
    WIRELESS_DEBUGGING_ON,
    DEVELOPER_OPTIONS_ON,
    ON_OPEN_WIFI,
    PRIVATE_DNS_OFF,
    WEB_PROXY_SET,
    USER_CA_PRESENT,
    VPN_ACTIVE,
}

/** [reason] is set when [value] is UNKNOWN: what could not be read, in words that can be shown. */
data class StateValue(val value: Tri, val confidence: Confidence, val reason: String? = null) {
    init {
        require(value != Tri.UNKNOWN || !reason.isNullOrBlank()) { "an unknown state must say why" }
    }

    companion object {
        fun known(flag: Boolean, confidence: Confidence = Confidence.HIGH) = StateValue(Tri.of(flag), confidence)

        fun unknown(reason: String) = StateValue(Tri.UNKNOWN, Confidence.LOW, reason)
    }
}

data class PhoneState(
    val values: Map<StateKey, StateValue>,
    /** Whole days since the phone's reported security update, or null when it could not be read or understood. */
    val patchAgeDays: Int?,
    val patchReason: String?,
) {
    fun of(key: StateKey): StateValue = values[key] ?: StateValue.unknown("This was not read.")

    companion object {
        /** A phone nothing was read from: every condition unknown. Used when a report was built without conditions. */
        val UNREAD = PhoneState(StateKey.entries.associateWith { StateValue.unknown("This was not read.") }, null, "This was not read.")
    }
}

object PhoneStates {
    fun build(
        device: DeviceFactsReading,
        network: ReadResult<ActiveNetwork>,
        wifi: ReadResult<WifiReading>,
        userCas: ReadResult<UserCaReading>,
        today: LocalDate,
    ): PhoneState {
        val v = LinkedHashMap<StateKey, StateValue>()

        v[StateKey.NO_SCREEN_LOCK] = when (val r = device.screenLock) {
            is ReadResult.Ok -> StateValue.known(!r.value)
            else -> StateValue.unknown(reasonOf(r, "the screen lock"))
        }
        v[StateKey.ADVANCED_PROTECTION_OFF] = when (val ap = device.advancedProtection) {
            is AdvancedProtectionState.Known -> StateValue.known(!ap.enabled)
            // There is no mode to turn on, so its absence cannot be a weakness this app could point at: not applicable, not unknown.
            AdvancedProtectionState.Unsupported -> StateValue.known(false)
            AdvancedProtectionState.NotPermitted -> StateValue.unknown("Android did not let this app ask whether Advanced Protection is on.")
            is AdvancedProtectionState.Failed -> StateValue.unknown("Advanced Protection could not be read: ${ap.reason}")
        }
        v[StateKey.USB_DEBUGGING_ON] = flag(device.usbDebugging, "USB debugging")
        v[StateKey.WIRELESS_DEBUGGING_ON] = flag(device.wirelessDebugging, "wireless debugging")
        v[StateKey.DEVELOPER_OPTIONS_ON] = flag(device.developerOptions, "developer options")

        val net = (network as? ReadResult.Ok)?.value
        val netReason = if (net == null) reasonOf(network, "the network") else null
        v[StateKey.WEB_PROXY_SET] = when {
            net == null -> StateValue.unknown(netReason!!)
            !net.present -> StateValue.known(false)
            else -> StateValue.known(net.httpProxy != null)
        }
        v[StateKey.VPN_ACTIVE] = when {
            net == null -> StateValue.unknown(netReason!!)
            else -> StateValue.known(net.present && Transport.VPN in net.transports)
        }
        v[StateKey.PRIVATE_DNS_OFF] = when {
            net == null -> StateValue.unknown(netReason!!)
            !net.present -> StateValue.known(false) // nothing is connected, so there is no traffic for this weakness to touch
            net.privateDnsActive == null -> StateValue.unknown("Android did not say whether Private DNS is in use on this connection.")
            else -> StateValue.known(!net.privateDnsActive)
        }
        v[StateKey.ON_OPEN_WIFI] = when (wifi) {
            is ReadResult.Ok -> when {
                !wifi.value.connected -> StateValue.known(false)
                wifi.value.security == null || wifi.value.security == WifiSecurity.UNKNOWN ->
                    StateValue.unknown(wifi.value.note ?: "Android did not say how this Wi-Fi network is secured.")
                else -> StateValue.known(wifi.value.security == WifiSecurity.OPEN)
            }
            else -> StateValue.unknown(reasonOf(wifi, "the Wi-Fi network"))
        }
        v[StateKey.USER_CA_PRESENT] = when (userCas) {
            // MEDIUM, as in the Stage 1 panel: this reading has only been checked on one emulator image.
            is ReadResult.Ok -> StateValue.known(userCas.value.sha256.isNotEmpty(), Confidence.MEDIUM)
            else -> StateValue.unknown(reasonOf(userCas, "the certificate authorities"))
        }

        var age: Int? = null
        var patchReason: String? = null
        when (val p = device.securityPatch) {
            is ReadResult.Ok -> {
                val text = p.value?.trim().orEmpty()
                val date = try { LocalDate.parse(text) } catch (e: DateTimeParseException) { null }
                if (date == null) {
                    patchReason = if (text.isEmpty()) "The phone reports no security update date." else "The security update date \"$text\" is not in a form this app can read."
                } else {
                    age = ChronoUnit.DAYS.between(date, today).toInt().coerceAtLeast(0)
                }
            }
            else -> patchReason = reasonOf(p, "the security update date")
        }
        return PhoneState(v, age, patchReason)
    }

    private fun flag(r: ReadResult<Int?>, what: String): StateValue = when (r) {
        // A setting that does not exist is "not set", which for every one of these is off.
        is ReadResult.Ok -> StateValue.known(r.value == 1)
        else -> StateValue.unknown(reasonOf(r, what))
    }

    private fun reasonOf(r: ReadResult<*>, what: String): String = when (r) {
        is ReadResult.Refused -> "Android refused to let this app read $what: ${r.reason}"
        is ReadResult.Failed -> "Reading $what failed: ${r.reason}"
        is ReadResult.Ok -> error("not an error result")
    }
}
