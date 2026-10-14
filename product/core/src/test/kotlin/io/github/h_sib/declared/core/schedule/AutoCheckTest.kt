package io.github.h_sib.declared.core.schedule

import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.state.ChangeItem
import io.github.h_sib.declared.core.state.ChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCheckTest {
    private fun item(title: String, attention: Attention, vararg keys: String) = ChangeItem(attention, ChangeKind.NEW_FINDING, title, keys.toList())

    @Test
    fun `automatic checks are off until the person turns them on`() {
        assertFalse(AutoConfig().enabled)
        assertEquals(AutoCheck.DEFAULT_MINUTES, AutoConfig().intervalMinutes)
    }

    @Test
    fun `a typed interval is any whole number of minutes, hours or days Android can run`() {
        assertEquals(Interval.Valid(15), AutoCheck.parse("15", IntervalUnit.MINUTES))
        assertEquals(Interval.Valid(90), AutoCheck.parse(" 90 ", IntervalUnit.MINUTES))
        assertEquals(Interval.Valid(3 * 60), AutoCheck.parse("3", IntervalUnit.HOURS))
        assertEquals(Interval.Valid(10 * 24 * 60), AutoCheck.parse("10", IntervalUnit.DAYS))
        assertEquals(Interval.Valid(365 * 24 * 60), AutoCheck.parse("365", IntervalUnit.DAYS))
    }

    @Test
    fun `an interval Android would refuse is explained, not accepted`() {
        assertEquals(Interval.TooShort, AutoCheck.parse("14", IntervalUnit.MINUTES))
        assertEquals(Interval.TooShort, AutoCheck.parse("0", IntervalUnit.HOURS))
        assertEquals(Interval.TooShort, AutoCheck.parse("-5", IntervalUnit.DAYS))
        assertEquals(Interval.TooLong, AutoCheck.parse("366", IntervalUnit.DAYS))
        assertEquals(Interval.TooLong, AutoCheck.parse("99999999999", IntervalUnit.DAYS))
        assertEquals(Interval.NotANumber, AutoCheck.parse("", IntervalUnit.HOURS))
        assertEquals(Interval.NotANumber, AutoCheck.parse("2.5", IntervalUnit.HOURS))
        assertEquals(Interval.NotANumber, AutoCheck.parse("soon", IntervalUnit.DAYS))
        assertNull(AutoCheck.intervalError(Interval.Valid(60)))
        for (bad in listOf(Interval.TooShort, Interval.TooLong, Interval.NotANumber)) assertTrue(AutoCheck.intervalError(bad)!!.endsWith("."))
    }

    @Test
    fun `every preset is something that can run`() {
        for (p in AutoCheck.presets) assertTrue("$p", p in AutoCheck.MIN_MINUTES..AutoCheck.MAX_MINUTES)
    }

    @Test
    fun `the interval is described in the largest whole unit`() {
        assertEquals("every 15 minutes", AutoCheck.describe(15))
        assertEquals("every 90 minutes", AutoCheck.describe(90))
        assertEquals("every hour", AutoCheck.describe(60))
        assertEquals("every 6 hours", AutoCheck.describe(360))
        assertEquals("every day", AutoCheck.describe(1440))
        assertEquals("every 7 days", AutoCheck.describe(10080))
        assertEquals("every 100 minutes", AutoCheck.describe(100))
    }

    @Test
    fun `a choice to tap has a short label`() {
        assertEquals(listOf("15 minutes", "1 hour", "6 hours", "12 hours", "1 day", "7 days"), AutoCheck.presets.map(AutoCheck::label))
        assertEquals("90 minutes", AutoCheck.label(90))
    }

    @Test
    fun `a stored interval comes back as the number and unit it was typed in`() {
        assertEquals(2 to IntervalUnit.HOURS, AutoCheck.split(120))
        assertEquals(3 to IntervalUnit.DAYS, AutoCheck.split(3 * 1440))
        assertEquals(90 to IntervalUnit.MINUTES, AutoCheck.split(90))
        for (m in listOf(15, 90, 120, 1440, 4321)) {
            val (n, u) = AutoCheck.split(m)
            assertEquals(Interval.Valid(m), AutoCheck.parse(n.toString(), u))
        }
    }

    @Test
    fun `a stored value is kept inside what can run`() {
        assertEquals(15, AutoCheck.clamp(1))
        assertEquals(15, AutoCheck.clamp(-40))
        assertEquals(AutoCheck.MAX_MINUTES, AutoCheck.clamp(Int.MAX_VALUE))
        assertEquals(90, AutoCheck.clamp(90))
    }

    @Test
    fun `only changes that need a look are announced, and a quiet note never is`() {
        val quiet = listOf(item("A note", Attention.NOTE, "a"))
        assertFalse(AutoCheck.decide(quiet, emptySet()).notify)
        val loud = listOf(item("Look first", Attention.LOOK_FIRST, "b"), item("Look", Attention.LOOK, "c"), item("A note", Attention.NOTE, "a"))
        val d = AutoCheck.decide(loud, emptySet())
        assertTrue(d.notify)
        assertEquals(2, d.newCount)
    }

    @Test
    fun `the same change is announced once, and again if it comes back`() {
        val a = item("Proxy set", Attention.LOOK, "p")
        val first = AutoCheck.decide(listOf(a), emptySet())
        assertTrue(first.notify)
        val second = AutoCheck.decide(listOf(a), first.told)
        assertFalse("already told", second.notify)
        assertEquals(setOf(AutoCheck.key(a)), second.told)
        val gone = AutoCheck.decide(emptyList(), second.told)
        assertFalse(gone.notify)
        assertTrue("nothing is remembered once it is no longer a change", gone.told.isEmpty())
        assertTrue("a change that comes back is news again", AutoCheck.decide(listOf(a), gone.told).notify)
    }

    @Test
    fun `a new change next to an old one is announced as one new change`() {
        val a = item("Old", Attention.LOOK, "o")
        val b = item("New", Attention.LOOK_FIRST, "n")
        val told = AutoCheck.decide(listOf(a), emptySet()).told
        val d = AutoCheck.decide(listOf(a, b), told)
        assertTrue(d.notify)
        assertEquals(1, d.newCount)
        assertEquals(setOf(AutoCheck.key(a), AutoCheck.key(b)), d.told)
    }

    @Test
    fun `the notification says how many and never which app, and uses no dash`() {
        assertEquals("1 change is worth a look.", AutoCheck.notificationText(1))
        assertEquals("3 changes are worth a look.", AutoCheck.notificationText(3))
        for (t in listOf(AutoCheck.NOTIFICATION_TITLE, AutoCheck.notificationText(1), AutoCheck.notificationText(5))) {
            assertTrue(t, t.none { it == '—' || it == '–' })
            assertFalse(t, listOf("safe", "protected", "threat").any { t.lowercase().contains(it) })
        }
    }
}
