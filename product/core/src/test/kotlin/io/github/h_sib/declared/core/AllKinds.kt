package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.admin.DevicePolicy
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.sensor.DeviceAdminFact
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.InputMethodFact
import io.github.h_sib.declared.core.sensor.InputMethodReading
import io.github.h_sib.declared.core.sensor.ListenerFact
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.OpMode
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.SpecialAccess
import io.github.h_sib.declared.core.sensor.WatchedPermissions

/**
 * One finding for every capability kind in every shape the wording can take: each tier, default or fallback app-op
 * mode, keyboard in use or not, and several device-admin policy sets (including unknown). The wording tests run over
 * all of them.
 */
fun everyShapeOfEveryKind(): List<Finding> {
    val out = mutableListOf<Finding>()
    val tiers = listOf(
        facts("p.t", platformSigned = true),
        facts("p.t", system = true),
        facts("p.t"),
        facts("p.t", installing = "com.google.android.packageinstaller"),
        facts("p.t", installing = null, initiating = "com.android.shell"),
        facts("p.t", installing = null, initiating = null),
    )
    for (base in tiers) for (mode in listOf(OpMode.ALLOWED, OpMode.DEFAULT)) for (isDefault in listOf(true, false)) {
        for (bits in listOf<Int?>(null, DevicePolicy.FORCE_LOCK.bit, 0x3ff, 0x1000)) {
            val f = base.copy(requestedWatched = WatchedPermissions.ALL, grantedWatched = WatchedPermissions.ALL)
            val listener = ListenerFact(component(f.pkg, "p.t.Notif"), "Notif")
            val admin = component(f.pkg, "p.t.Admin")
            val ime = component(f.pkg, "p.t.Ime")
            val src = sources(
                listeners = ReadResult.Ok(NotificationListenerReading(listOf(listener), setOf(listener.component))),
                admins = ReadResult.Ok(DeviceAdminReading(listOf(DeviceAdminFact(admin, bits, "Admin")), setOf(admin))),
                keyboards = ReadResult.Ok(InputMethodReading(listOf(InputMethodFact(ime, "Keys")), setOf(ime), if (isDefault) ime else null)),
                appOps = FakeAppOpsSource(otherwise = ReadResult.Ok(SpecialAccess.entries.associateWith { mode })),
                packages = FakePackageFactsSource(factsMap(f)),
            )
            out += builder(src).build(34).findings
        }
    }
    out += everyConditionShape()
    return out
}

/** Every condition finding in each of its shapes: patch ages, no lock, each debugging switch, Wi-Fi types, proxy, certificates. */
fun everyConditionShape(): List<Finding> {
    val out = mutableListOf<Finding>()
    val today = java.time.LocalDate.parse("2026-10-09")
    for (days in listOf(190L, 300L, 400L, 3000L)) {
        out += builder(sources(device = healthyDevice(patch = ReadResult.Ok(today.minusDays(days).toString())))).build(34).findings
    }
    out += builder(
        sources(
            device = healthyDevice(lock = ReadResult.Ok(false), usb = ReadResult.Ok(1), wireless = ReadResult.Ok(1), developer = ReadResult.Ok(1)),
            network = network(transports = setOf(io.github.h_sib.declared.core.sensor.Transport.WIFI), proxy = "10.0.0.2:3128"),
            wifi = ReadResult.Ok(io.github.h_sib.declared.core.sensor.WifiReading(true, io.github.h_sib.declared.core.sensor.WifiSecurity.OPEN)),
            userCas = ReadResult.Ok(io.github.h_sib.declared.core.sensor.UserCaReading(listOf("ab".repeat(32)))),
        ),
    ).build(34).findings
    out += everyIntegrityShape()
    out += everyReviewShape()
    out += builder(
        sources(
            network = network(transports = setOf(io.github.h_sib.declared.core.sensor.Transport.WIFI)),
            wifi = ReadResult.Ok(io.github.h_sib.declared.core.sensor.WifiReading(true, io.github.h_sib.declared.core.sensor.WifiSecurity.WEP)),
            userCas = ReadResult.Ok(io.github.h_sib.declared.core.sensor.UserCaReading(listOf("aa", "bb"))),
        ),
    ).build(34).findings
    return out
}


/** Every integrity finding: unlocked, custom software, failed start-up check, each way the chain fails, patch mismatch, software keys. */
fun everyIntegrityShape(): List<Finding> {
    val now = java.time.Instant.parse("2026-10-09T12:00:00Z")
    val challenge = ByteArray(32) { 5 }
    fun record(level: Long = 1, boot: Long? = 0, locked: Boolean? = true, patch: Long? = 202609, challengeHex: String = io.github.h_sib.declared.core.integrity.hex(challenge)) =
        io.github.h_sib.declared.core.integrity.KeyAttestation(300, level, 300, level, challengeHex, boot, locked, "aa", "bb", 140000, patch, 20260905, 20260905)
    fun link(i: Int, sig: Boolean?, valid: Boolean = true) = io.github.h_sib.declared.core.integrity.ChainLink(i, "CN=c$i", "$i", sig, null, valid, null)
    fun chain(
        trusted: Boolean = true, att: io.github.h_sib.declared.core.integrity.KeyAttestation? = record(), expired: Boolean = false, revoked: Boolean = false, broken: Boolean = false,
    ) = io.github.h_sib.declared.core.integrity.ChainReport(
        listOf(link(0, !broken), link(1, true, !expired), link(2, null, !expired)), trusted, "x", false,
        if (revoked) listOf(1 to io.github.h_sib.declared.core.integrity.RevocationEntry("REVOKED", "KEY_COMPROMISE")) else emptyList(), null, att, null,
    )
    val fetched = java.time.LocalDate.of(2026, 10, 8)
    fun assess(r: io.github.h_sib.declared.core.integrity.ChainReport, challengeBytes: ByteArray = challenge) =
        io.github.h_sib.declared.core.conditions.assessIntegrity(r, challengeBytes, fetched, "2026-09-05", now, now).findings
    return assess(chain(att = record(boot = 2, locked = false))) + assess(chain(att = record(boot = 1))) + assess(chain(att = record(boot = 3))) +
        assess(chain(trusted = false)) + assess(chain(broken = true)) + assess(chain(revoked = true)) + assess(chain(expired = true)) +
        assess(chain(), ByteArray(32)) + assess(chain(att = record(patch = 202603))) + assess(chain(trusted = false, att = record(level = 0)))
}


/** The two findings that come from the user's own review: an answer of "do not recognize", and a changed signing certificate. */
fun everyReviewShape(): List<Finding> {
    val at = java.time.Instant.parse("2026-10-09T10:00:00Z")
    val out = mutableListOf<Finding>()
    for (installer in listOf<String?>(null, "com.android.vending", "com.google.android.packageinstaller")) {
        val side = facts("s.side", label = "Side", installing = installer, initiating = installer, signers = listOf("aa".repeat(32)))
        val svc = service("s.side")
        fun report(signers: List<String>) = builder(
            sources(
                accessibility = ReadResult.Ok(reading(listOf(svc), setOf(svc.component))),
                packages = FakePackageFactsSource(factsMap(side.copy(signerSha256 = signers))),
            ),
        ).build(34)
        val base = report(listOf("aa".repeat(32)))
        val key = io.github.h_sib.declared.core.state.Review.key(base.holders.single { it.active })
        val notRecognized = io.github.h_sib.declared.core.state.Review.establish(base, mapOf(key to io.github.h_sib.declared.core.state.Answer.NOT_RECOGNIZED), null, at)
        out += io.github.h_sib.declared.core.state.Review.apply(base, notRecognized, at).findings.filter { it.id.startsWith("review:") }
        val recognized = io.github.h_sib.declared.core.state.Review.establish(base, mapOf(key to io.github.h_sib.declared.core.state.Answer.RECOGNIZED), null, at)
        out += io.github.h_sib.declared.core.state.Review.apply(report(listOf("bb".repeat(32))), recognized, at).findings.filter { it.id.startsWith("review:") }
    }
    return out
}
