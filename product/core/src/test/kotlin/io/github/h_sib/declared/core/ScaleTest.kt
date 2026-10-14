package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.sensor.AccessibilityServiceFact
import io.github.h_sib.declared.core.sensor.DeviceAdminFact
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.InputMethodFact
import io.github.h_sib.declared.core.sensor.InputMethodReading
import io.github.h_sib.declared.core.sensor.ListenerFact
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.OpMode
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.SpecialAccess
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Scale test (DESIGN.md section 14, M2): a synthetic phone with a thousand apps and more. The point is not a
 * benchmark number but that nothing is quadratic in the number of apps (found once already: the screen used to scan
 * the holder list for every finding card) and that the work done is exactly what the data requires.
 */
class ScaleTest {

    private class Phone(val sources: io.github.h_sib.declared.core.report.Sources, val appOps: FakeAppOpsSource, val packages: FakePackageFactsSource, val expected: Expected)

    private data class Expected(var a11y: Int = 0, var listeners: Int = 0, var admins: Int = 0, var keyboards: Int = 0, var overlay: Int = 0, var sms: Int = 0, var appOpsCandidates: Int = 0)

    /** Deterministic: the same seed gives the same phone, so a failure can be reproduced. */
    private fun phone(n: Int, seed: Long = 7): Phone {
        val random = Random(seed)
        val expected = Expected()
        val apps = ArrayList<PackageFacts>(n)
        val services = ArrayList<AccessibilityServiceFact>()
        val listeners = ArrayList<ListenerFact>()
        val admins = ArrayList<DeviceAdminFact>()
        val keyboards = ArrayList<InputMethodFact>()
        val enabledA11y = HashSet<io.github.h_sib.declared.core.sensor.ComponentId>()
        val enabledListeners = HashSet<io.github.h_sib.declared.core.sensor.ComponentId>()
        val activeAdmins = HashSet<io.github.h_sib.declared.core.sensor.ComponentId>()
        val enabledKeyboards = HashSet<io.github.h_sib.declared.core.sensor.ComponentId>()
        val opsByPackage = HashMap<String, ReadResult<Map<SpecialAccess, OpMode>>>()
        for (i in 0 until n) {
            val pkg = "app.number$i"
            val roll = random.nextInt(100)
            val builtIn = roll < 30
            val requested = HashSet<String>()
            val granted = HashSet<String>()
            if (!builtIn && random.nextInt(10) == 0) {
                requested += WatchedPermissions.OVERLAY
                expected.appOpsCandidates += 1
                if (random.nextBoolean()) {
                    opsByPackage[pkg] = ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.ALLOWED))
                    expected.overlay += 1
                } else {
                    opsByPackage[pkg] = ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.IGNORED))
                }
            }
            if (!builtIn && random.nextInt(25) == 0) {
                requested += WatchedPermissions.READ_SMS
                granted += WatchedPermissions.READ_SMS
                expected.sms += 1
            }
            if (random.nextInt(3) == 0) granted += "android.permission.CAMERA"
            apps += facts(
                pkg, system = builtIn, installing = if (random.nextInt(8) == 0) null else "com.android.vending",
                initiating = null, requested = requested, granted = granted, uid = 10000 + i,
            )
            if (random.nextInt(20) == 0) {
                val c = component(pkg, "$pkg.A11y")
                services += AccessibilityServiceFact(c, 0x21, null, "Svc $i")
                if (random.nextBoolean()) { enabledA11y += c; expected.a11y += 1 }
            }
            if (random.nextInt(10) == 0) {
                val c = component(pkg, "$pkg.Notif")
                listeners += ListenerFact(c, "Listener $i")
                if (random.nextBoolean()) { enabledListeners += c; expected.listeners += 1 }
            }
            if (random.nextInt(50) == 0) {
                val c = component(pkg, "$pkg.Admin")
                admins += DeviceAdminFact(c, 8, "Admin $i")
                if (random.nextBoolean()) { activeAdmins += c; expected.admins += 1 }
            }
            if (random.nextInt(30) == 0) {
                val c = component(pkg, "$pkg.Ime")
                keyboards += InputMethodFact(c, "Keys $i")
                if (random.nextBoolean()) { enabledKeyboards += c; expected.keyboards += 1 }
            }
        }
        val appOps = FakeAppOpsSource(byPackage = opsByPackage)
        val packages = FakePackageFactsSource(emptyMap(), listing = ReadResult.Ok(apps))
        val src = sources(
            accessibility = ReadResult.Ok(io.github.h_sib.declared.core.sensor.AccessibilityReading(services, enabledA11y, enabledA11y)),
            listeners = ReadResult.Ok(NotificationListenerReading(listeners, enabledListeners)),
            admins = ReadResult.Ok(DeviceAdminReading(admins, activeAdmins)),
            keyboards = ReadResult.Ok(InputMethodReading(keyboards, enabledKeyboards, enabledKeyboards.firstOrNull())),
            appOps = appOps,
            packages = packages,
        )
        return Phone(src, appOps, packages, expected)
    }

    @Test
    fun aThousandAppsProduceExactlyTheExpectedHoldersAndFindings() {
        val p = phone(1000)
        val report = builder(p.sources).build(34)
        assertEquals(1000, report.apps.size)
        val e = p.expected
        assertEquals(e.a11y + e.listeners + e.admins + e.keyboards + e.overlay + e.sms, report.findings.size)
        assertEquals(report.findings.size, report.holders.count { it.findingId != null })
        assertEquals(report.findings.map { it.id }.toSet().size, report.findings.size)
        // Every finding resolves to its holder and back, for all of them.
        for (f in report.findings) {
            assertEquals(f, report.finding(f.id))
            assertEquals(f.id, report.holderOf(f.id)?.findingId)
        }
        assertEquals(1000, report.tierCounts.values.sum())
    }

    @Test
    fun theWorkDoneMatchesTheDataNotTheSquareOfIt() {
        val p = phone(1000)
        builder(p.sources).build(34)
        // App-ops are read once per app that declared a special permission, and only for those.
        assertEquals(p.expected.appOpsCandidates, p.appOps.asked.size)
        // Every holder's package was in the bulk listing, so no per-package request is ever made.
        assertEquals(emptyList<String>(), p.packages.asked)
    }

    private fun medianMillis(n: Int): Double {
        val p = phone(n)
        val b = builder(p.sources)
        repeat(2) { b.build(34) }
        val runs = (0 until 5).map {
            val t0 = System.nanoTime()
            b.build(34)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        return runs[runs.size / 2]
    }

    /**
     * Linear work gives a ratio near 5 for 5x the apps and quadratic work near 25. The sizes are large enough that a
     * quadratic scan dominates the fixed costs (checked by injecting one: see DEFECT_LOG D-020), so this test can fail.
     */
    @Test
    fun fiveTimesTheAppsCostsAboutFiveTimesTheTimeAndNotTwentyFive() {
        val small = medianMillis(4000)
        val large = medianMillis(20000)
        println("scale: 4,000 apps took %.1f ms, 20,000 apps took %.1f ms, ratio %.1f".format(small, large, large / small))
        assertTrue("20,000 apps took $large ms, which is more than a phone can wait for", large < 5000)
        assertTrue("5x the apps took ${large / small}x the time (4000: $small ms, 20000: $large ms)", large / small < 12)
    }
}
