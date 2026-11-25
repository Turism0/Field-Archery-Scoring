package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Equipment (3.2.24). The same cases as the web app's test/equipment.test.ts. */
class EquipmentTest {
    private fun sp(s: String) = Equipment.spine(s)?.let { it.text to it.kind }

    @Test fun `carbon spines`() {
        assertEquals("500" to SpineKind.CARBON, sp("500"))
        assertEquals("500" to SpineKind.CARBON, sp(" 0.500 "))
        assertEquals("340" to SpineKind.CARBON, sp(".340"))
        assertEquals("1000" to SpineKind.CARBON, sp("1000"))
        assertEquals("400" to SpineKind.CARBON, sp("Spine 400"))
    }

    @Test fun `wood spines`() {
        assertEquals("45-50" to SpineKind.WOOD, sp("45-50"))
        assertEquals("45-50" to SpineKind.WOOD, sp("45/50"))
        assertEquals("45-50" to SpineKind.WOOD, sp("45 to 50"))
        assertEquals("45-50" to SpineKind.WOOD, sp("45–50#"))
        assertEquals("50-55" to SpineKind.WOOD, sp("50 55 lbs"))
        assertEquals("45" to SpineKind.WOOD, sp("45#"))
        assertEquals("55" to SpineKind.WOOD, sp("55"))
        assertEquals("100-105" to SpineKind.WOOD, sp("100-105"))
    }

    @Test fun `aluminium spines`() {
        assertEquals("2213" to SpineKind.ALUMINIUM, sp("2213"))
        assertEquals("2213" to SpineKind.ALUMINIUM, sp("22-13"))
        assertEquals("1618" to SpineKind.ALUMINIUM, sp("16 18"))
        assertEquals("1816" to SpineKind.ALUMINIUM, sp("1816"))
    }

    @Test fun `nonsense is not a spine`() {
        assertNull(Equipment.spine(""))
        assertNull(Equipment.spine("   "))
        assertNull(Equipment.spine("stiff"))
        assertNull(Equipment.spine("5"))
        assertNull(Equipment.spine("50-45"))
        assertNull(Equipment.spine("12345"))
        assertNull(Equipment.spine("500#"))
    }

    private val longbow = Bow("b1", BowType.LONGBOW, "Border", "Hex 7", 45.5, setOf(BowStyle.LONGBOW, BowStyle.HUNTING_TACKLE))
    private val flat = Bow("b2", BowType.FLATBOW, "Bodnik", null, 40.0, setOf(BowStyle.AMERICAN_FLATBOW, BowStyle.HUNTING_TACKLE))
    private val gone = Bow("b3", BowType.LONGBOW, "Old", null, 50.0, setOf(BowStyle.LONGBOW), deleted = true)
    private val bows = listOf(longbow, flat, gone)
    private val cedar = ArrowSet("a1", "Port Orford", "Cedar", spine = "45-50", bowIds = setOf("b1", "b2"))
    private val pine = ArrowSet("a2", "Sitka", "Pine", bowIds = setOf("b1"))
    private val arrows = listOf(cedar, pine)

    @Test fun `names and details`() {
        assertEquals("Border Hex 7", Equipment.name(longbow))
        assertEquals("Longbow · 45.5 lb", Equipment.detail(longbow))
        assertEquals("Border Hex 7 (45.5 lb)", Equipment.label(longbow))
        assertEquals("Recurve", Equipment.name(Bow("x", BowType.RECURVE, bowStyles = setOf(BowStyle.BAREBOW))))
        val full = cedar.copy(lengthIn = 29.5, arrowGrains = 480.0, pointGrains = 125.0, fletchCount = 3,
            fletchMaterial = FletchMaterial.FEATHER, fletchLengthIn = 5.0, fletchColours = "barred grey")
        assertEquals("29.5″ · 480 gr · spine 45-50 · 125 gr point · 3 feathers, 5″, barred grey", Equipment.detail(full))
        assertEquals("Port Orford Cedar (45-50)", Equipment.label(cedar))
        assertEquals("1 feather", Equipment.fletches(ArrowSet("x", bowIds = setOf("b"), fletchCount = 1, fletchMaterial = FletchMaterial.FEATHER)))
    }

    @Test fun `bows for a bow style, arrows for a bow`() {
        assertEquals(listOf("b1"), Equipment.bowsFor(bows, BowStyle.LONGBOW).map { it.id })   // not the deleted one
        assertEquals(listOf("b2", "b1"), Equipment.bowsFor(bows, BowStyle.HUNTING_TACKLE).map { it.id })   // by name
        assertEquals(emptyList<Bow>(), Equipment.bowsFor(bows, BowStyle.BAREBOW))
        assertEquals(listOf("a1"), Equipment.arrowsFor(arrows, "b2").map { it.id })
        assertEquals(listOf("a1", "a2"), Equipment.arrowsFor(arrows, "b1").map { it.id })
    }

    @Test fun `choosing hides a choice of one and uses it`() {
        val c = Equipment.choose(bows, arrows, BowStyle.AMERICAN_FLATBOW)
        assertEquals("b2", c.bowId); assertFalse(c.showBows)
        assertEquals("a1", c.arrowId); assertFalse(c.showArrows)
    }

    @Test fun `choosing starts on the last used, then what's picked`() {
        val first = Equipment.choose(bows, arrows, BowStyle.HUNTING_TACKLE)
        assertNull(first.bowId); assertTrue(first.showBows); assertFalse(first.showArrows)
        val last = Equipment.choose(bows, arrows, BowStyle.HUNTING_TACKLE, lastBowId = "b1", lastArrowId = "a2")
        assertEquals("b1", last.bowId); assertEquals("a2", last.arrowId); assertTrue(last.showArrows)
        val picked = Equipment.choose(bows, arrows, BowStyle.HUNTING_TACKLE, bowId = "b2", arrowId = "a2", lastBowId = "b1", lastArrowId = "a2")
        assertEquals("b2", picked.bowId); assertEquals("a1", picked.arrowId)   // a2 isn't used with b2: the only one
        val stale = Equipment.choose(bows, arrows, BowStyle.LONGBOW, lastBowId = "b3")
        assertEquals("b1", stale.bowId)   // the deleted one isn't offered
        assertEquals(EquipmentChoice(emptyList(), null, emptyList(), null), Equipment.choose(bows, arrows, null))
        val none = Equipment.choose(bows, arrows, BowStyle.HUNTING_TACKLE, bowId = Equipment.NONE, lastBowId = "b1")
        assertNull(none.bowId); assertTrue(none.showBows); assertEquals(emptyList<ArrowSet>(), none.arrows)
        assertNull(Equipment.choose(bows, arrows, BowStyle.AMERICAN_FLATBOW, arrowId = Equipment.NONE).arrowId)
    }

    private fun round(id: String, created: Long, bow: BowStyle, bowId: String?, arrowId: String?) = Round(
        id = id, shootDate = LocalDate.parse("2026-05-01"), targetCount = 1, archerClass = ArcherClass.GENTS, ageGroup = AgeGroup.ADULT,
        bowStyle = bow, status = RoundStatus.COMPLETED, targets = listOf(newTarget(RoundType.BIG_GAME, 1).withOverride(20)),
        createdAtMillis = created, bowId = bowId, arrowId = arrowId,
    )

    @Test fun `last used per bow style`() {
        val rs = listOf(round("r1", 1, BowStyle.HUNTING_TACKLE, "b1", "a1"), round("r2", 3, BowStyle.HUNTING_TACKLE, "b2", "a1"),
            round("r3", 4, BowStyle.HUNTING_TACKLE, null, null), round("r4", 5, BowStyle.LONGBOW, "b1", "a2"))
        assertEquals("b2" to "a1", Equipment.lastUsed(rs, BowStyle.HUNTING_TACKLE))
        assertEquals("b1" to "a2", Equipment.lastUsed(rs, BowStyle.LONGBOW))
        assertEquals(null to null, Equipment.lastUsed(rs, BowStyle.BAREBOW))
    }

    @Test fun `bow form`() {
        assertEquals(listOf("Pick the bow type.", "Give the bow a make or model."), Equipment.BowForm().problems())
        val f = Equipment.BowForm(BowType.LONGBOW, " Border ", "Hex  7", "45,5 lb", setOf(BowStyle.LONGBOW))
        assertEquals(Bow("id", BowType.LONGBOW, "Border", "Hex 7", 45.5, setOf(BowStyle.LONGBOW), 9), f.toBow("id", 9))
        assertEquals(listOf("Poundage should be a number."), f.copy(poundage = "heavy").problems())
        assertEquals(listOf("Poundage must be between 5 and 250."), f.copy(poundage = "300").problems())
        assertEquals(f.copy(make = "Border", model = "Hex 7", poundage = "45.5"), Equipment.BowForm.of(f.toBow("id", 9)!!))
    }

    @Test fun `arrow form`() {
        val f = Equipment.ArrowForm(make = "Easton", model = "Axis", length = "29.25\"", arrowWeight = "410", spine = "0.500",
            fletchCount = "3", fletchMaterial = FletchMaterial.VANE, fletchLength = "2", pointWeight = "100 gr", bowIds = setOf("b1"))
        val a = f.toArrows("a", 1)!!
        assertEquals("500", a.spine); assertEquals(29.25, a.lengthIn!!, 0.0); assertEquals(100.0, a.pointGrains!!, 0.0); assertEquals(3, a.fletchCount)
        assertEquals(listOf("Spine should look like 500 (carbon), 45-50 (wood) or 2213 (aluminium)."), f.copy(spine = "stiff").problems())
        assertEquals(listOf("Fletches must be a whole number from 1 to 8."), f.copy(fletchCount = "2.5").problems())
        assertEquals(emptyList<String>(), f.copy(bowIds = emptySet()).problems())   // 3.2.27: arrows with no bow can be kept
        assertEquals(listOf("Give the arrows a make or model."), Equipment.ArrowForm(bowIds = setOf("b")).problems())
    }

    @Test fun `scorecard line`() {
        val r = round("r", 1, BowStyle.LONGBOW, "b1", "a1")
        assertEquals("Border Hex 7 (45.5 lb) · Port Orford Cedar (45-50)", Equipment.roundLine(r, bows, arrows))
        assertEquals("Border Hex 7 (45.5 lb)", Equipment.roundLine(r.copy(arrowId = null), bows, arrows))
        assertNull(Equipment.roundLine(r.copy(bowId = null, arrowId = null), bows, arrows))
    }

    @Test fun `numbers`() {
        assertEquals("45", Equipment.num(45.0)); assertEquals("45.5", Equipment.num(45.5)); assertEquals("29.25", Equipment.num(29.25))
    }

    @Test fun `compare by bow and arrows`() {
        val rs = listOf(round("r1", 1, BowStyle.LONGBOW, "b1", "a1"), round("r2", 2, BowStyle.LONGBOW, "b1", "a2"),
            round("r3", 3, BowStyle.LONGBOW, "b2", "a1"), round("r4", 4, BowStyle.LONGBOW, null, null))
        val byBow = Analysis.byEquipment(rs) { it.bowId }
        assertEquals(listOf("b1" to 2, "b2" to 1), byBow.map { it.id to it.rounds })
        assertEquals(20.0, byBow[0].average, 0.0001)
        assertEquals(listOf("a1" to 2, "a2" to 1), Analysis.byEquipment(rs) { it.arrowId }.map { it.id to it.rounds })
        // 3.2.29: bow + arrows pairs, so arrows used with two bows aren't averaged together
        val pairs = Analysis.byEquipment(rs, Analysis::equipmentPair)
        assertEquals(setOf("b1|a1", "b1|a2", "b2|a1"), pairs.map { it.id }.toSet())   // r4 (neither picked) left out
        assertEquals(listOf(1, 1, 1), pairs.map { it.rounds })
        assertEquals("b1|", Analysis.equipmentPair(rs[0].copy(arrowId = null)))
    }

    @Test fun `stats filter by bow and arrows`() {
        val rs = listOf(round("r1", 1, BowStyle.LONGBOW, "b1", "a1"), round("r2", 2, BowStyle.LONGBOW, "b2", "a1"))
        val today = LocalDate.parse("2026-06-01")
        val f = StatsFilter(setOf(RoundType.BIG_GAME), setOf(BowStyle.LONGBOW), StatsFilter.DEFAULT_PERIOD, bowId = "b1")
        assertEquals(listOf("r1"), rs.filter { f.matches(it, today) }.map { it.id })
        assertEquals(listOf("r1", "r2"), rs.filter { f.copy(bowId = null, arrowId = "a1").matches(it, today) }.map { it.id })
        val saved = StatsFilter.decode(f.encode())!!
        assertEquals("b1", saved.bowId)
        assertEquals("b1", StatsFilter.resolve(saved, rs, null).bowId)
        assertNull(StatsFilter.resolve(saved.copy(bowId = "gone"), rs, null).bowId)
    }
}
