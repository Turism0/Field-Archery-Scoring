package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the scoring screen shows (3.2.20). The same cases as the web's scoringDisplay.test.ts. */
class ScoringDisplayTest {
    @Test fun `defaults - total, average and hits on, remaining and max off`() {
        val d = ScoringDisplay.decode(null)
        assertEquals(StatsDisplay(true, true, true, false, false), d.mine)
        assertEquals(d.mine, d.others)
        assertEquals("11100|11100", d.encode())
    }

    @Test fun `3_2_18's switch carries over to both cards`() {
        val d = ScoringDisplay.decode(null, oldShowRemaining = true)
        assertEquals("11111|11111", d.encode())
    }

    @Test fun `saved choices round trip, mine and others separately`() {
        val d = ScoringDisplay(StatsDisplay(total = false, average = false, hits = false), StatsDisplay(remaining = true))
        assertEquals("00000|11110", d.encode())
        assertEquals(d, ScoringDisplay.decode(d.encode(), oldShowRemaining = true))
        assertEquals(d.mine, d.forCard(own = true)); assertEquals(d.others, d.forCard(own = false))
    }

    @Test fun `junk falls back to the defaults`() {
        assertEquals(ScoringDisplay(), ScoringDisplay.decode("1x100|11100"))
        assertEquals(ScoringDisplay(), ScoringDisplay.decode("111"))
    }
}
