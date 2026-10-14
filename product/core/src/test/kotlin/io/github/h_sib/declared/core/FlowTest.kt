package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.Load
import io.github.h_sib.declared.core.flow.Screen
import io.github.h_sib.declared.core.flow.reduce
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FlowTest {

    private fun reportWith(vararg pkgs: String): Report {
        val services = pkgs.map { service(it) }
        return builder(
            sources(
                accessibility = ReadResult.Ok(reading(services, services.map { it.component }.toSet())),
                packages = FakePackageFactsSource(pkgs.associateWith { ReadResult.Ok(facts(it)) }),
            ),
        ).build(34)
    }

    private fun idOf(pkg: String) = "accessibility:$pkg/$pkg.Service"

    private fun ready(vararg pkgs: String) = reduce(AppState(), Event.ReportLoaded(reportWith(*pkgs)))

    @Test
    fun startsLoadingOnTheInventory() {
        val s = AppState()
        assertEquals(Load.Loading, s.load)
        assertEquals(Screen.Inventory, s.screen)
        assertFalse(s.canGoBack)
    }

    @Test
    fun loadedThenOpenAndBack() {
        val s = reduce(ready("a.b"), Event.OpenFinding(idOf("a.b")))
        assertEquals(Screen.FindingDetail(idOf("a.b")), s.screen)
        assertTrue(s.canGoBack)
        assertEquals(Screen.Inventory, reduce(s, Event.Back).screen)
    }

    @Test
    fun openingAnUnknownFindingOrBeforeLoadingDoesNothing() {
        assertEquals(AppState(), reduce(AppState(), Event.OpenFinding("x")))
        val s = ready("a.b")
        assertEquals(s, reduce(s, Event.OpenFinding("nope")))
    }

    @Test
    fun openingTheSameFindingTwiceDoesNotStackIt() {
        val once = reduce(ready("a.b"), Event.OpenFinding(idOf("a.b")))
        assertEquals(once, reduce(once, Event.OpenFinding(idOf("a.b"))))
    }

    @Test
    fun theAppsListOpensOnceAndGoesBack() {
        val s = ready("a.b")
        val apps = reduce(s, Event.OpenApps)
        assertEquals(Screen.Apps, apps.screen)
        assertEquals(apps, reduce(apps, Event.OpenApps))
        assertEquals(Screen.Inventory, reduce(apps, Event.Back).screen)
        assertEquals(AppState(), reduce(AppState(), Event.OpenApps))
    }

    @Test
    fun backOnTheRootStays() {
        val s = ready("a.b")
        assertEquals(s, reduce(s, Event.Back))
    }

    @Test
    fun aRefreshThatRemovesTheOpenFindingPopsBackToTheInventory() {
        val open = reduce(ready("a.b", "c.d"), Event.OpenFinding(idOf("c.d")))
        val after = reduce(open, Event.ReportLoaded(reportWith("a.b")))
        assertEquals(Screen.Inventory, after.screen)
        assertEquals(listOf<Screen>(Screen.Inventory), after.stack)
    }

    @Test
    fun aRefreshThatKeepsTheOpenFindingStaysOnIt() {
        val open = reduce(ready("a.b"), Event.OpenFinding(idOf("a.b")))
        assertEquals(open.screen, reduce(open, Event.ReportLoaded(reportWith("a.b"))).screen)
    }

    @Test
    fun refreshKeepsTheOldReportVisibleAndAFailedRefreshSaysSo() {
        val s = reduce(ready("a.b"), Event.Refresh)
        assertTrue(s.refreshing)
        assertTrue(s.load is Load.Ready)
        val failed = reduce(s, Event.LoadFailed("boom"))
        assertFalse(failed.refreshing)
        assertEquals("boom", failed.refreshError)
        assertTrue(failed.load is Load.Ready)
    }

    @Test
    fun aFirstLoadFailureIsFailedAndRefreshRetries() {
        val failed = reduce(AppState(), Event.LoadFailed("boom"))
        assertEquals(Load.Failed("boom"), failed.load)
        assertEquals(Load.Loading, reduce(failed, Event.Refresh).load)
    }

    @Test
    fun refreshWhileAlreadyRefreshingOrLoadingIsIgnored() {
        assertEquals(AppState(), reduce(AppState(), Event.Refresh))
        val s = reduce(ready("a.b"), Event.Refresh)
        assertEquals(s, reduce(s, Event.Refresh))
    }

    /** Property: whatever the user and the platform do, the stack is never empty and always starts at the Inventory. */
    @Test
    fun randomEventSequencesKeepTheInvariants() {
        val reports = listOf(reportWith(), reportWith("a.b"), reportWith("a.b", "c.d"), reportWith("c.d"))
        val ids = listOf(idOf("a.b"), idOf("c.d"), "missing")
        val random = Random(20261009)
        repeat(2000) { run ->
            var s = AppState()
            repeat(60) { step ->
                val event: Event = when (random.nextInt(7)) {
                    0 -> Event.ReportLoaded(reports[random.nextInt(reports.size)])
                    1 -> Event.LoadFailed("e")
                    2, 3 -> Event.OpenFinding(ids[random.nextInt(ids.size)])
                    4 -> if (random.nextBoolean()) Event.Back else Event.OpenApps
                    else -> Event.Refresh
                }
                s = reduce(s, event)
                val where = "run $run step $step after $event: $s"
                assertTrue(where, s.stack.isNotEmpty())
                assertEquals(where, Screen.Inventory, s.stack.first())
                assertTrue(where, s.stack.drop(1).all { it is Screen.FindingDetail || it == Screen.Apps })
                val load = s.load
                if (load is Load.Ready) {
                    for (screen in s.stack) if (screen is Screen.FindingDetail) assertTrue(where, load.report.finding(screen.findingId) != null)
                } else {
                    assertEquals(where, 1, s.stack.size)
                }
            }
        }
    }
}
