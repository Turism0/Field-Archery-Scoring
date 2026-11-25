package uk.co.fieldarcheryscoring.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import uk.co.fieldarcheryscoring.data.local.ArrowSetEntity
import uk.co.fieldarcheryscoring.data.local.BowEntity
import uk.co.fieldarcheryscoring.data.local.EquipmentDao
import uk.co.fieldarcheryscoring.data.local.SyncState
import uk.co.fieldarcheryscoring.data.remote.ApiException
import uk.co.fieldarcheryscoring.data.remote.FieldArcheryApi
import uk.co.fieldarcheryscoring.domain.ArrowSet
import uk.co.fieldarcheryscoring.domain.Bow
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.domain.BowType
import uk.co.fieldarcheryscoring.domain.FletchMaterial
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Equipment (3.2.24, Pete): the archer's bows and arrows, kept on the phone and synced with the account (private to the
 * archer). Deleting keeps them, flagged, so rounds shot with them still show their names.
 */
class EquipmentRepository(
    private val dao: EquipmentDao,
    private val clock: Clock = Clock.systemUTC(),
    /** Called after any change that needs uploading (asks the background sync to run). */
    private val onChanged: () -> Unit = {},
) {
    /** Every bow and arrow set showing, deleted ones too (for names on old rounds); pickers leave the deleted out. */
    val bows: Flow<List<Bow>> = dao.observeBows().map { list -> list.mapNotNull { it.toDomain() } }
    val arrows: Flow<List<ArrowSet>> = dao.observeArrows().map { list -> list.map { it.toDomain() } }

    fun newId(): String = UUID.randomUUID().toString()

    suspend fun saveBow(b: Bow) {
        dao.upsertBow(b.toEntity(Instant.now(clock)))
        onChanged()
    }

    suspend fun saveArrows(a: ArrowSet) {
        dao.upsertArrows(a.toEntity(Instant.now(clock)))
        onChanged()
    }

    suspend fun deleteBow(id: String) {
        val b = dao.getBow(id) ?: return
        val now = Instant.now(clock)
        dao.upsertBow(b.copy(deleted = true, updatedAt = now, syncState = SyncState.PENDING))
        // 3.2.30 (Pete): the bow comes off any arrows linked to it (synced like any other change to the arrows).
        dao.arrowsWithBow(id).forEach { a ->
            dao.upsertArrows(a.copy(bowIds = (split(a.bowIds) - id).joinToString(","), updatedAt = now, syncState = SyncState.PENDING))
        }
        onChanged()
    }

    suspend fun deleteArrows(id: String) {
        val a = dao.getArrows(id) ?: return
        dao.upsertArrows(a.copy(deleted = true, updatedAt = Instant.now(clock), syncState = SyncState.PENDING))
        onChanged()
    }

    /**
     * Sync for [token] (from the round sync): changes on this phone go up (bows first, so arrows' bows are known), then
     * the account's equipment comes down; the newest edit wins. An older server without equipment: nothing happens.
     * [stillSignedIn] stops anything landing on a phone that's been signed out meanwhile.
     */
    suspend fun sync(api: FieldArcheryApi, token: String, stillSignedIn: () -> Boolean) {
        try {
            for (b in dao.pendingBows()) upload({ dao.markBowSync(b.id, b.updatedAt.toEpochMilli(), it) }) { api.putBow(token, b.id, b.toJson()) }
            for (a in dao.pendingArrows()) upload({ dao.markArrowsSync(a.id, a.updatedAt.toEpochMilli(), it) }) { api.putArrows(token, a.id, a.toJson()) }
            val all = api.equipment(token)
            if (!stillSignedIn()) return
            all.optJSONArray("bows")?.let { arr -> for (i in 0 until arr.length()) runCatching { applyBow(arr.getJSONObject(i)) } }
            all.optJSONArray("arrows")?.let { arr -> for (i in 0 until arr.length()) runCatching { applyArrows(arr.getJSONObject(i)) } }
        } catch (e: ApiException) {
            if (e.status == 404 || e.status == 405) return   // a server from before 3.2.24
            throw e
        }
    }

    private suspend fun upload(mark: suspend (String) -> Int, send: suspend () -> Unit) {
        try {
            send()
            mark(SyncState.SYNCED.name)
        } catch (e: ApiException) {
            // Refused (invalid, or someone else's id): not again until it's edited.
            if (e.status in 400..499 && e.status !in listOf(401, 404, 405, 426, 429)) mark(SyncState.FAILED.name) else throw e
        }
    }

    private suspend fun applyBow(j: JSONObject) {
        val id = j.getString("bowId")
        val at = Instant.parse(j.getString("clientUpdatedUtc"))
        val local = dao.getBow(id)
        if (local?.hiddenOwner != null) return
        if (local != null && !local.updatedAt.isBefore(at)) return
        dao.upsertBow(
            BowEntity(
                id = id, type = j.getString("type"), make = j.str("make"), model = j.str("model"), poundageLb = j.dbl("poundageLb"),
                bowStyles = j.optJSONArray("bowStyles").strings().joinToString(","), updatedAt = at, syncState = SyncState.SYNCED,
                deleted = j.optBoolean("isDeleted"),
            ),
        )
    }

    private suspend fun applyArrows(j: JSONObject) {
        val id = j.getString("arrowId")
        val at = Instant.parse(j.getString("clientUpdatedUtc"))
        val local = dao.getArrows(id)
        if (local?.hiddenOwner != null) return
        if (local != null && !local.updatedAt.isBefore(at)) return
        dao.upsertArrows(
            ArrowSetEntity(
                id = id, make = j.str("make"), model = j.str("model"), lengthIn = j.dbl("lengthIn"), arrowGrains = j.dbl("arrowGrains"),
                spine = j.str("spine"), fletchCount = if (j.isNull("fletchCount")) null else j.optInt("fletchCount"),
                fletchMaterial = j.str("fletchMaterial"), fletchLengthIn = j.dbl("fletchLengthIn"), fletchColours = j.str("fletchColours"),
                pointGrains = j.dbl("pointGrains"), bowIds = j.optJSONArray("bowIds").strings().joinToString(","), updatedAt = at,
                syncState = SyncState.SYNCED, deleted = j.optBoolean("isDeleted"),
            ),
        )
    }
}

private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }
private fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun split(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

fun BowEntity.toDomain(): Bow? {
    val t = BowType.fromCode(type) ?: return null
    return Bow(id, t, make, model, poundageLb, split(bowStyles).mapNotNull { BowStyle.fromCode(it) }.toSet(), updatedAt.toEpochMilli(), deleted)
}

fun ArrowSetEntity.toDomain(): ArrowSet = ArrowSet(
    id, make, model, lengthIn, arrowGrains, spine, fletchCount, FletchMaterial.fromCode(fletchMaterial), fletchLengthIn, fletchColours,
    pointGrains, split(bowIds), updatedAt.toEpochMilli(), deleted,
)

private fun Bow.toEntity(now: Instant) = BowEntity(
    id, type.code, make, model, poundageLb, bowStyles.joinToString(",") { it.code }, now, SyncState.PENDING, deleted,
)

private fun ArrowSet.toEntity(now: Instant) = ArrowSetEntity(
    id, make, model, lengthIn, arrowGrains, spine, fletchCount, fletchMaterial?.code, fletchLengthIn, fletchColours, pointGrains,
    bowIds.joinToString(","), now, SyncState.PENDING, deleted,
)

private fun Any?.orNull(): Any = this ?: JSONObject.NULL

private fun BowEntity.toJson(): JSONObject = JSONObject()
    .put("type", type).put("make", make.orNull()).put("model", model.orNull()).put("poundageLb", poundageLb.orNull())
    .put("bowStyles", JSONArray(split(bowStyles).toList())).put("isDeleted", deleted).put("clientUpdatedUtc", updatedAt.toString())

private fun ArrowSetEntity.toJson(): JSONObject = JSONObject()
    .put("make", make.orNull()).put("model", model.orNull()).put("lengthIn", lengthIn.orNull()).put("arrowGrains", arrowGrains.orNull())
    .put("spine", spine.orNull()).put("fletchCount", fletchCount.orNull()).put("fletchMaterial", fletchMaterial.orNull())
    .put("fletchLengthIn", fletchLengthIn.orNull()).put("fletchColours", fletchColours.orNull()).put("pointGrains", pointGrains.orNull())
    .put("bowIds", JSONArray(split(bowIds).toList())).put("isDeleted", deleted).put("clientUpdatedUtc", updatedAt.toString())
