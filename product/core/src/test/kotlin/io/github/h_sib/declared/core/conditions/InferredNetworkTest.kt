package io.github.h_sib.declared.core.conditions

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.FactKind
import io.github.h_sib.declared.core.sensor.ActiveNetwork
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.WifiReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** When Android will not name the connection in use (Android 17, for an app without internet permission) the readings say they were worked out. */
class InferredNetworkTest {
    private val at = Instant.parse("2026-10-09T10:00:00Z")

    private fun output(inferred: Boolean) = mapNetwork(
        ReadResult.Ok(ActiveNetwork(true, setOf(Transport.WIFI), true, true, false, false, null, "10.0.0.2:3128", inferred)),
        ReadResult.Ok(WifiReading(connected = true, security = null)),
        ReadResult.Failed("not under test"),
        at,
    )

    @Test
    fun anExactReadingIsHighConfidenceAndSaysNothingAboutGuessing() {
        val o = output(inferred = false)
        val facts = o.facts.filter { it.kind == FactKind.CONNECTION || it.kind == FactKind.HTTP_PROXY }.mapNotNull { it.observation }
        assertEquals(2, facts.size)
        assertTrue(facts.all { it.confidence == Confidence.HIGH })
        assertFalse(facts.any { f -> f.limits.contains(INFERRED_NETWORK) })
    }

    @Test
    fun anInferredReadingIsMediumConfidenceAndSaysHowItWasWorkedOut() {
        val o = output(inferred = true)
        val facts = o.facts.filter { it.kind == FactKind.CONNECTION || it.kind == FactKind.HTTP_PROXY }.mapNotNull { it.observation }
        assertEquals(2, facts.size)
        assertTrue(facts.all { it.confidence == Confidence.MEDIUM })
        assertTrue(facts.all { f -> f.limits.contains(INFERRED_NETWORK) })
        assertTrue("the proxy finding is still made", o.findings.any { it.id == "condition:http-proxy" })
    }

    @Test
    fun theWordsHaveNoBannedWordOrDash() {
        val lower = INFERRED_NETWORK.lowercase()
        for (w in listOf("safe", "secure", "score", "rating", "malicious", "hacked")) assertFalse(w, Regex("(?<![a-z])$w(?![a-z])").containsMatchIn(lower))
        assertFalse(INFERRED_NETWORK.any { it.code == 0x2014 || it.code == 0x2013 })
    }
}
