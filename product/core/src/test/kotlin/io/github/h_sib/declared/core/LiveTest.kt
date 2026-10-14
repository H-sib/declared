package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.Screen
import io.github.h_sib.declared.core.flow.reduce
import io.github.h_sib.declared.core.live.LiveEvent
import io.github.h_sib.declared.core.live.LiveLog
import io.github.h_sib.declared.core.live.LiveSource
import io.github.h_sib.declared.core.live.PackageAction
import io.github.h_sib.declared.core.live.SettingScope
import io.github.h_sib.declared.core.live.Watched
import io.github.h_sib.declared.core.state.StoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveTest {
    private val t0 = Instant.parse("2026-10-09T10:00:00Z")
    private fun event(ms: Long, what: String = "x", source: LiveSource = LiveSource.SETTING) = LiveEvent(t0.plusMillis(ms), source, what)

    @Test
    fun theNewestEventIsFirstAndTheLogIsBounded() {
        var log = LiveLog()
        repeat(LiveLog.MAX_EVENTS + 40) { log = log.record(event(it * 2_000L, "e$it")) }
        assertEquals(LiveLog.MAX_EVENTS, log.events.size)
        assertEquals("e${LiveLog.MAX_EVENTS + 39}", log.events.first().what)
        assertEquals("e40", log.events.last().what)
    }

    @Test
    fun theSameAnnouncementRepeatedWithinASecondIsOneEventButLaterOrDifferentOnesAreKept() {
        val once = LiveLog().record(event(0)).record(event(300)).record(event(900))
        assertEquals(1, once.events.size)
        assertEquals(2, once.record(event(2_500)).events.size)
        assertEquals(2, LiveLog().record(event(0, "a")).record(event(100, "b")).events.size)
        assertEquals(2, LiveLog().record(event(0, "a", LiveSource.SETTING)).record(event(100, "a", LiveSource.APP)).events.size)
    }

    @Test
    fun everyWatchedSettingHasUniqueNameAndPlainWords() {
        val names = Watched.settings.map { it.scope to it.name }
        assertEquals(names.size, names.toSet().size)
        for (s in Watched.settings) {
            assertTrue(s.name, s.name.matches(Regex("[a-z0-9_]+")))
            assertTrue(s.what, s.what.length > 15 && !s.what.endsWith("."))
            assertFalse(s.what, s.what.any { it.code == 0x2014 || it.code == 0x2013 })
        }
    }

    @Test
    fun whatIsWatchedCoversEverySettingTheReportReads() {
        val global = Watched.settings.filter { it.scope == SettingScope.GLOBAL }.map { it.name }
        for (needed in listOf("adb_enabled", "development_settings_enabled", "private_dns_mode", "global_http_proxy_host", "global_http_proxy_port")) {
            assertTrue(needed, needed in global)
        }
        val secure = Watched.settings.filter { it.scope == SettingScope.SECURE }.map { it.name }
        for (needed in listOf("enabled_accessibility_services", "enabled_notification_listeners", "default_input_method")) {
            assertTrue(needed, needed in secure)
        }
    }

    @Test
    fun anUpdateIsAnnouncedOnceNotAsARemovalAndAnInstall() {
        assertNull(Watched.packageEvent(PackageAction.REMOVED, replacing = true, pkg = "a.b"))
        assertNull(Watched.packageEvent(PackageAction.ADDED, replacing = true, pkg = "a.b"))
        assertEquals("An app was updated: a.b", Watched.packageEvent(PackageAction.REPLACED, replacing = true, pkg = "a.b"))
        assertEquals("An app was installed: a.b", Watched.packageEvent(PackageAction.ADDED, replacing = false, pkg = "a.b"))
        assertEquals("An app was removed: a.b", Watched.packageEvent(PackageAction.REMOVED, replacing = false, pkg = "a.b"))
        assertTrue(Watched.packageEvent(PackageAction.CHANGED, replacing = false, pkg = "a.b")!!.endsWith("a.b"))
    }

    @Test
    fun theWordsOnTheLiveViewStateTheMeasurementAndTheLimitsAndNeverPromiseMore() {
        val all = listOf(Watched.MEASURED, Watched.LIMIT_BACKGROUND, Watched.LIMIT_HIDDEN_APPS, Watched.REREAD_NOTE)
        assertTrue(Watched.MEASURED.contains("one Android 14 emulator"))
        assertTrue(Watched.LIMIT_BACKGROUND.contains("only while the app is on screen"))
        assertTrue(Watched.LIMIT_HIDDEN_APPS.contains("never tell you that no app was installed"))
        assertTrue(Watched.REREAD_NOTE.contains("${Watched.POLL_SECONDS} seconds"))
        for (text in all) {
            assertFalse(text, text.any { it.code == 0x2014 || it.code == 0x2013 })
            for (banned in listOf("always watching", "continuous", "real-time", "real time", "protects", "is safe")) assertFalse("$banned in $text", text.lowercase().contains(banned))
        }
    }

    // ------------------------------------------------------------ in the reducer

    private fun ready(): AppState {
        val report = builder(sources(packages = FakePackageFactsSource(factsMap(facts("a.b"))))).build(34)
        val s = reduce(AppState(), Event.ReportLoaded(report))
        val saved = reduce(s, Event.StoreLoaded(StoreResult.Empty))
        return reduce(reduce(saved, Event.StartReview), Event.FinishReview(t0))
    }

    @Test
    fun theBlindSpotMapAndTheLiveViewOpenOnceAndGoBack() {
        var s = ready()
        assertEquals(Screen.Inventory, s.screen)
        s = reduce(s, Event.OpenBlindSpots)
        assertEquals(listOf(Screen.Inventory, Screen.BlindSpots), s.stack)
        assertEquals(s, reduce(s, Event.OpenBlindSpots))
        s = reduce(reduce(s, Event.Back), Event.OpenLiveView)
        assertEquals(listOf(Screen.Inventory, Screen.LiveView), s.stack)
        assertEquals(s, reduce(s, Event.OpenLiveView))
        assertEquals(Screen.Inventory, reduce(s, Event.Back).screen)
    }

    @Test
    fun neitherOpensBeforeTheFirstReviewIsDone() {
        val report = builder(sources(packages = FakePackageFactsSource(factsMap(facts("a.b"))))).build(34)
        val s = reduce(reduce(reduce(AppState(), Event.StoreLoaded(StoreResult.Empty)), Event.ReportLoaded(report)), Event.OpenBlindSpots)
        assertEquals(listOf(Screen.FirstRun), s.stack)
        assertEquals(listOf(Screen.FirstRun), reduce(s, Event.OpenLiveView).stack)
    }

    @Test
    fun watchingAndEventsAreRecordedAndSurviveRefreshAndNavigation() {
        var s = ready()
        assertFalse(s.live.watching)
        s = reduce(s, Event.WatchingChanged(true))
        s = reduce(s, Event.LiveSeen(event(0, "The default keyboard changed")))
        s = reduce(reduce(s, Event.Refresh), Event.OpenLiveView)
        assertTrue(s.live.watching)
        assertEquals(listOf("The default keyboard changed"), s.live.events.map { it.what })
        s = reduce(s, Event.WatchingChanged(false))
        assertFalse(s.live.watching)
        assertEquals(1, s.live.events.size)
    }
}
