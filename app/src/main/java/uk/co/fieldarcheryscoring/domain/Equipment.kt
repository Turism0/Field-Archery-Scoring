package uk.co.fieldarcheryscoring.domain

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Equipment (3.2.24, Pete): an archer's bows and arrows. A bow is shot in one or more bow styles; arrows are used with
 * one or more bows. New round offers the bows for the bow style picked and the arrows for the bow picked, starting on
 * the last ones used with that bow style; a choice with fewer than two options isn't shown (the only one is used).
 * Synced with the account, private to the archer. The same rules as the web app's domain/equipment.ts.
 */
enum class BowType(val code: String, val displayName: String) {
    LONGBOW("LONGBOW", "Longbow"),
    FLATBOW("FLATBOW", "Flatbow"),
    RECURVE("RECURVE", "Recurve"),
    COMPOUND("COMPOUND", "Compound"),
    CROSSBOW("CROSSBOW", "Crossbow"),
    HORSEBOW("HORSEBOW", "Horse bow"),
    SELFBOW("SELFBOW", "Self bow"),
    OTHER("OTHER", "Other");

    companion object {
        fun fromCode(code: String?): BowType? = entries.firstOrNull { it.code == code }
    }
}

enum class FletchMaterial(val code: String, val displayName: String) {
    FEATHER("FEATHER", "Feathers"),
    VANE("VANE", "Vanes"),
    OTHER("OTHER", "Other");

    companion object {
        fun fromCode(code: String?): FletchMaterial? = entries.firstOrNull { it.code == code }
    }
}

data class Bow(
    val id: String,
    val type: BowType,
    val make: String? = null,
    val model: String? = null,
    val poundageLb: Double? = null,
    val bowStyles: Set<BowStyle>,
    val updatedAtMillis: Long = 0,
    val deleted: Boolean = false,
)

data class ArrowSet(
    val id: String,
    val make: String? = null,
    val model: String? = null,
    val lengthIn: Double? = null,
    val arrowGrains: Double? = null,
    val spine: String? = null,
    val fletchCount: Int? = null,
    val fletchMaterial: FletchMaterial? = null,
    val fletchLengthIn: Double? = null,
    val fletchColours: String? = null,
    val pointGrains: Double? = null,
    val bowIds: Set<String>,
    val updatedAtMillis: Long = 0,
    val deleted: Boolean = false,
)

enum class SpineKind(val displayName: String) { CARBON("Carbon"), WOOD("Wood"), ALUMINIUM("Aluminium") }

data class Spine(val text: String, val kind: SpineKind)

/** What New round offers and starts on for a bow style. */
data class EquipmentChoice(
    val bows: List<Bow>,
    val bowId: String?,
    val arrows: List<ArrowSet>,
    val arrowId: String?,
) {
    /** Only worth asking with two or more to choose from. */
    val showBows: Boolean get() = bows.size >= 2
    val showArrows: Boolean get() = arrows.size >= 2
}

object Equipment {
    const val MAX_TEXT = 60
    const val MAX_COLOURS = 100
    /** A choice of "None" in a picker (so it doesn't go back to the last or only one). */
    const val NONE = ""

    /** 45 → "45", 45.5 → "45.5", 29.25 → "29.25". */
    fun num(d: Double): String {
        val r = (d * 100).roundToLong() / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.UK, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    fun clean(s: String?): String? = s?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    // ---- Names --------------------------------------------------------------------------------------------------

    /** "Border Hex 7", or the bow type when it has neither. */
    fun name(b: Bow): String = listOfNotNull(clean(b.make), clean(b.model)).joinToString(" ").ifEmpty { b.type.displayName }

    /** "Longbow · 45.5 lb". */
    fun detail(b: Bow): String = listOfNotNull(b.type.displayName, b.poundageLb?.let { "${num(it)} lb" }).joinToString(" · ")

    /** "Border Hex 7 (45.5 lb)": for pickers and scorecards. */
    fun label(b: Bow): String = name(b) + (b.poundageLb?.let { " (${num(it)} lb)" } ?: "")

    fun name(a: ArrowSet): String = listOfNotNull(clean(a.make), clean(a.model)).joinToString(" ").ifEmpty { "Arrows" }

    /** "3 feathers, 5″, barred grey". */
    fun fletches(a: ArrowSet): String? {
        val what = when {
            a.fletchCount != null && a.fletchMaterial != null && a.fletchMaterial != FletchMaterial.OTHER ->
                "${a.fletchCount} ${a.fletchMaterial.displayName.lowercase().let { if (a.fletchCount == 1) it.dropLast(1) else it }}"
            a.fletchCount != null -> "${a.fletchCount} fletch${if (a.fletchCount == 1) "" else "es"}"
            a.fletchMaterial != null -> a.fletchMaterial.displayName
            else -> null
        }
        val parts = listOfNotNull(what, a.fletchLengthIn?.let { "${num(it)}″" }, clean(a.fletchColours))
        return parts.joinToString(", ").ifEmpty { null }
    }

    /** "29.5″ · 480 gr · spine 45-50 · 125 gr point · 3 feathers, 5″, barred grey". */
    fun detail(a: ArrowSet): String = listOfNotNull(
        a.lengthIn?.let { "${num(it)}″" },
        a.arrowGrains?.let { "${num(it)} gr" },
        a.spine?.let { "spine $it" },
        a.pointGrains?.let { "${num(it)} gr point" },
        fletches(a),
    ).joinToString(" · ")

    /** "Port Orford Cedar (45-50)". */
    fun label(a: ArrowSet): String = name(a) + (a.spine?.let { " ($it)" } ?: "")

    /** A round's bow and arrows for its scorecard ("Border Hex 7 (45.5 lb) · Port Orford Cedar (45-50)"); null = none. */
    fun roundLine(r: Round, bows: List<Bow>, arrows: List<ArrowSet>): String? {
        val bow = r.bowId?.let { id -> bows.firstOrNull { it.id == id } }
        val arrow = r.arrowId?.let { id -> arrows.firstOrNull { it.id == id } }
        return listOfNotNull(bow?.let(::label), arrow?.let(::label)).joinToString(" · ").ifEmpty { null }
    }

    // ---- Spine --------------------------------------------------------------------------------------------------

    /**
     * Tidies a spine into the usual way of writing it: carbon as the deflection (500; "0.500" or ".500" → 500), wood as
     * the pound range (45-50; "45/50", "45 to 50", "45–50#" → 45-50; a single "45#" → 45), aluminium as the size
     * (2213; "22-13", "22 13" → 2213). Null when it's blank or doesn't look like any of them.
     */
    fun spine(input: String?): Spine? {
        var s = input?.trim()?.lowercase() ?: return null
        val pounds = Regex("#|lbs?|pounds?").containsMatchIn(s)
        s = s.replace("spine", "").replace(Regex("#|lbs?|pounds?"), "")
            .replace(Regex("\\s*(?:to|[-–—/])\\s*"), "-").trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        Regex("^0?\\.(\\d{3})$").find(s)?.let { m ->
            val v = m.groupValues[1].toInt()
            return if (v in 150..999) Spine(v.toString(), SpineKind.CARBON) else null
        }
        Regex("^(\\d{2,3})[- ](\\d{2,3})$").find(s)?.let { m ->
            val (at, bt) = m.destructured
            val a = at.toInt()
            val b = bt.toInt()
            if (a < b && a >= 20 && b <= 120) return Spine("$a-$b", SpineKind.WOOD)
            if (at.length == 2 && bt.length == 2 && aluminium(a, b)) return Spine("$at$bt", SpineKind.ALUMINIUM)
            return null
        }
        if (!Regex("^\\d{2,4}$").matches(s)) return null
        val v = s.toInt()
        if (pounds) return if (v in 20..120) Spine(v.toString(), SpineKind.WOOD) else null
        return when (s.length) {
            4 -> if (aluminium(s.take(2).toInt(), s.takeLast(2).toInt())) Spine(s, SpineKind.ALUMINIUM)
                 else if (v in 150..2000) Spine(v.toString(), SpineKind.CARBON) else null
            3 -> if (v >= 150) Spine(v.toString(), SpineKind.CARBON) else if (v <= 120) Spine(v.toString(), SpineKind.WOOD) else null
            else -> if (v >= 20) Spine(v.toString(), SpineKind.WOOD) else null
        }
    }

    /** Aluminium sizes: outside diameter in 64ths of an inch (10–29), wall in thousandths (04–30). */
    private fun aluminium(diameter: Int, wall: Int) = diameter in 10..29 && wall in 4..30

    // ---- Choosing -------------------------------------------------------------------------------------------------

    fun bowsFor(bows: List<Bow>, style: BowStyle?): List<Bow> =
        if (style == null) emptyList()
        else bows.filter { !it.deleted && style in it.bowStyles }.sortedWith(compareBy({ name(it).lowercase() }, { it.poundageLb ?: 0.0 }))

    fun arrowsFor(arrows: List<ArrowSet>, bowId: String?): List<ArrowSet> =
        if (bowId == null) emptyList()
        else arrows.filter { !it.deleted && bowId in it.bowIds }.sortedBy { label(it).lowercase() }

    /**
     * The bows and arrows to offer for [style], starting on [bowId] / [arrowId] if they still fit (what the archer has
     * picked so far), else the last ones used with that bow style ([lastBowId] / [lastArrowId]), else the only one.
     * [NONE] picked: none.
     */
    fun choose(
        bows: List<Bow>, arrows: List<ArrowSet>, style: BowStyle?,
        bowId: String? = null, arrowId: String? = null, lastBowId: String? = null, lastArrowId: String? = null,
    ): EquipmentChoice {
        val forStyle = bowsFor(bows, style)
        val bow = if (bowId == NONE) null else listOf(bowId, lastBowId).firstOrNull { id -> id != null && forStyle.any { it.id == id } }
            ?: forStyle.singleOrNull()?.id
        val forBow = arrowsFor(arrows, bow)
        val arrow = if (arrowId == NONE) null else listOf(arrowId, lastArrowId).firstOrNull { id -> id != null && forBow.any { it.id == id } }
            ?: forBow.singleOrNull()?.id
        return EquipmentChoice(forStyle, bow, forBow, arrow)
    }

    /** The bow and arrows of your most recently started round with [style] that had a bow (for New round's defaults). */
    fun lastUsed(rounds: List<Round>, style: BowStyle?): Pair<String?, String?> =
        rounds.filter { it.bowStyle == style && it.bowId != null }.maxByOrNull { it.createdAtMillis }
            ?.let { it.bowId to it.arrowId } ?: (null to null)

    // ---- Forms ----------------------------------------------------------------------------------------------------

    /** A decimal typed by the archer ("45.5", "45,5", "45 lb"); null when blank; [bad] when it isn't a number. */
    fun number(text: String): Double? {
        val t = text.trim().lowercase().replace(",", ".").replace(Regex("(lbs?|gr|grains?|in|\"|″|')$"), "").trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull() ?: Double.NaN
    }

    private fun check(problems: MutableList<String>, v: Double?, min: Double, max: Double, what: String) {
        if (v == null) return
        if (v.isNaN()) problems += "$what should be a number."
        else if (v < min || v > max) problems += "$what must be between ${num(min)} and ${num(max)}."
    }

    data class BowForm(
        val type: BowType? = null,
        val make: String = "",
        val model: String = "",
        val poundage: String = "",
        val bowStyles: Set<BowStyle> = emptySet(),
    ) {
        /** The problems, or none when it can be saved. */
        fun problems(): List<String> {
            val p = mutableListOf<String>()
            if (type == null) p += "Pick the bow type."
            if (clean(make) == null && clean(model) == null) p += "Give the bow a make or model."
            if (make.trim().length > MAX_TEXT || model.trim().length > MAX_TEXT) p += "Make and model can be up to $MAX_TEXT characters."
            check(p, number(poundage), 5.0, 250.0, "Poundage")
            if (bowStyles.isEmpty()) p += "Pick the bow styles you shoot it in."
            return p
        }

        fun toBow(id: String, now: Long): Bow? = if (problems().isNotEmpty()) null else Bow(
            id, type!!, clean(make), clean(model), number(poundage), bowStyles, now,
        )

        companion object {
            fun of(b: Bow) = BowForm(b.type, b.make.orEmpty(), b.model.orEmpty(), b.poundageLb?.let(::num).orEmpty(), b.bowStyles)
        }
    }

    data class ArrowForm(
        val make: String = "",
        val model: String = "",
        val length: String = "",
        val arrowWeight: String = "",
        val spine: String = "",
        val fletchCount: String = "",
        val fletchMaterial: FletchMaterial? = null,
        val fletchLength: String = "",
        val fletchColours: String = "",
        val pointWeight: String = "",
        val bowIds: Set<String> = emptySet(),
    ) {
        fun problems(): List<String> {
            val p = mutableListOf<String>()
            if (clean(make) == null && clean(model) == null) p += "Give the arrows a make or model."
            if (make.trim().length > MAX_TEXT || model.trim().length > MAX_TEXT) p += "Make and model can be up to $MAX_TEXT characters."
            check(p, number(length), 10.0, 40.0, "Arrow length")
            check(p, number(arrowWeight), 50.0, 2000.0, "Arrow weight")
            check(p, number(pointWeight), 10.0, 1000.0, "Point weight")
            check(p, number(fletchLength), 0.5, 9.0, "Fletch length")
            val n = number(fletchCount)
            if (n != null && (n.isNaN() || n != Math.floor(n) || n < 1 || n > 8)) p += "Fletches must be a whole number from 1 to 8."
            if (fletchColours.trim().length > MAX_COLOURS) p += "Fletch colours can be up to $MAX_COLOURS characters."
            if (spine.isNotBlank() && spine(spine) == null) p += "Spine should look like 500 (carbon), 45-50 (wood) or 2213 (aluminium)."
            if (bowIds.isEmpty()) p += "Pick the bows you use them with."
            return p
        }

        fun toArrows(id: String, now: Long): ArrowSet? = if (problems().isNotEmpty()) null else ArrowSet(
            id, clean(make), clean(model), number(length), number(arrowWeight), spine(spine)?.text,
            number(fletchCount)?.toInt(), fletchMaterial, number(fletchLength), clean(fletchColours), number(pointWeight), bowIds, now,
        )

        companion object {
            fun of(a: ArrowSet) = ArrowForm(
                a.make.orEmpty(), a.model.orEmpty(), a.lengthIn?.let(::num).orEmpty(), a.arrowGrains?.let(::num).orEmpty(),
                a.spine.orEmpty(), a.fletchCount?.toString().orEmpty(), a.fletchMaterial, a.fletchLengthIn?.let(::num).orEmpty(),
                a.fletchColours.orEmpty(), a.pointGrains?.let(::num).orEmpty(), a.bowIds,
            )
        }
    }
}
