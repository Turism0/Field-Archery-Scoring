package uk.co.fnurble.nfas.data.sync

import org.json.JSONArray
import org.json.JSONObject
import uk.co.fnurble.nfas.data.AccountStore
import uk.co.fnurble.nfas.data.local.AnimalEntity
import uk.co.fnurble.nfas.data.local.AnimalState
import uk.co.fnurble.nfas.data.local.RoundDao
import uk.co.fnurble.nfas.data.local.RoundData
import uk.co.fnurble.nfas.data.local.RoundEntity
import uk.co.fnurble.nfas.data.local.SyncState
import uk.co.fnurble.nfas.data.local.TargetEntity
import uk.co.fnurble.nfas.data.remote.ApiException
import uk.co.fnurble.nfas.data.remote.NfasApi
import uk.co.fnurble.nfas.domain.AccountRole
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.Hit
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetType
import java.time.Instant
import java.time.LocalDate

/**
 * Two-way round sync for the signed-in archer:
 *  0. refresh this account's role (for the Admin menu);
 *  1. upload: finished rounds and deletions waiting on this phone;
 *  2. download: this archer's rounds changed on the server since the last download
 *     (a new or reset phone gets everything; a second phone picks up the other's edits).
 * Both directions use "newest edit wins", by the time the round was last changed on a phone.
 */
class RoundSyncer(
    private val dao: RoundDao,
    private val api: NfasApi,
    private val accounts: AccountStore,
) {
    enum class Outcome { DONE, NOT_SIGNED_IN, RETRY_LATER, SIGNED_OUT, FAILED }

    /**
     * What happened, for the home screen's pull-to-refresh message.
     * [message] explains a problem; [skipped] counts downloaded rounds this app version couldn't read.
     */
    data class Report(val outcome: Outcome, val message: String? = null, val skipped: Int = 0)

    suspend fun syncAll(): Report {
        val account = accounts.account.value ?: return Report(Outcome.NOT_SIGNED_IN)
        return try {
            // Role first (one small call), so the Admin menu appears or goes on the next sync.
            accounts.setRole(AccountRole.fromCode(api.me(account.token).optString("role")))
            upload(account.token)
            val skipped = download(account.token)
            accounts.setLastSync(Instant.now())
            Report(Outcome.DONE, skipped = skipped)
        } catch (e: ApiException) {
            when {
                // No signal, server down or busy: WorkManager tries again later.
                e.isNetwork || e.status == 429 || e.status >= 500 || e.status < 0 -> Report(Outcome.RETRY_LATER, e.message)
                // Token revoked or expired: sign out on this phone so Profile asks to sign in again.
                e.status == 401 -> {
                    accounts.clear()
                    Report(Outcome.SIGNED_OUT, e.message)
                }
                // Anything else (e.g. the server is an older version without the download list).
                else -> Report(Outcome.FAILED, "${e.message} (${e.status})")
            }
        }
    }

    private suspend fun upload(token: String) {
        for (id in dao.pendingUploadIds()) {
            val data = dao.loadRound(id) ?: continue
            val editedAt = data.round.updatedAt.toEpochMilli()
            try {
                if (data.round.deleted) api.deleteRound(token, id) else api.uploadRound(token, id, data.toJson())
                dao.markSync(id, editedAt, SyncState.SYNCED.name)
            } catch (e: ApiException) {
                // Refused (invalid, or another account's round): don't retry until it's edited again.
                if (e.status in 400..499 && e.status != 401 && e.status != 429) {
                    dao.markSync(id, editedAt, SyncState.FAILED.name)
                } else {
                    throw e
                }
            }
        }
    }

    /** Returns how many downloaded rounds couldn't be read. */
    private suspend fun download(token: String): Int {
        var skipped = 0
        val saved = accounts.pullCursor()
        // Start a few minutes before the last cursor: a change saved on the server just as we
        // last downloaded can't be missed, and re-reading a round we already have is harmless.
        var since: String? = saved?.let { Instant.parse(it).minusSeconds(OVERLAP_SECONDS).toString() }
        var afterId: String? = null
        var newest: Instant? = saved?.let(Instant::parse)

        do {
            val page = api.changedRounds(token, since, afterId)
            val rounds = page.getJSONArray("rounds")
            for (i in 0 until rounds.length()) {
                val json = rounds.getJSONObject(i)
                // Skip (and count) a round this version of the app can't read.
                if (runCatching { applyRemote(json) }.isFailure) skipped++
                since = json.getString("serverUpdatedUtc")
                afterId = json.getString("roundId")
                val changed = Instant.parse(since)
                if (newest == null || changed.isAfter(newest)) newest = changed
            }
        } while (page.optBoolean("more"))

        newest?.let { accounts.setPullCursor(it.toString()) }
        return skipped
    }

    /** Applies one downloaded round, unless this phone has the same or a newer edit of it. */
    private suspend fun applyRemote(json: JSONObject) {
        val id = json.getString("roundId")
        val remoteEditedAt = Instant.parse(json.getString("clientUpdatedUtc"))
        val local = dao.getRound(id)
        if (local != null && !local.updatedAt.isBefore(remoteEditedAt)) return

        if (json.getBoolean("isDeleted")) {
            // Deleted on another phone: hide it here too (nothing to do if we never had it).
            if (local != null) {
                dao.upsertRound(local.copy(deleted = true, updatedAt = remoteEditedAt, syncState = SyncState.SYNCED))
            }
            return
        }
        val data = json.toRoundData()
        dao.replaceRound(data.round, data.targets, data.animals)
    }

    private companion object {
        const val OVERLAP_SECONDS = 300L
    }
}

/** The round in the API's JSON shape (see server/README.md). */
internal fun RoundData.toJson(): JSONObject {
    val animalsByTarget = animals.groupBy { it.targetNo }
    val targetArray = JSONArray()
    targets.sortedBy { it.targetNo }.forEach { t ->
        val animalArray = JSONArray()
        animalsByTarget[t.targetNo].orEmpty().sortedBy { it.animalIndex }.forEach { a ->
            animalArray.put(
                JSONObject()
                    .put("index", a.animalIndex)
                    .put("state", a.state.name)
                    .put("arrowNo", a.arrowNo ?: JSONObject.NULL)
                    .put("hit", a.hit?.name ?: JSONObject.NULL)
                    .put("points", a.points),
            )
        }
        targetArray.put(
            JSONObject()
                .put("targetNo", t.targetNo)
                .put("type", t.type.name)
                .put("overridePoints", t.overridePoints ?: JSONObject.NULL)
                .put("points", t.points)
                .put("animals", animalArray),
        )
    }
    return JSONObject()
        .put("shootDate", round.shootDate.toString())
        .put("targetCount", round.targetCount)
        .put("archerClass", if (round.archerClass == ArcherClass.LADIES) "L" else "G")
        .put("ageGroup", round.ageGroup.name)
        .put("bowStyle", round.bowStyle.code)
        .put("status", round.status.name)
        .put("totalPoints", round.totalPoints)
        .put("clientCreatedUtc", round.createdAt.toString())
        .put("clientUpdatedUtc", round.updatedAt.toString())
        .put("targets", targetArray)
}

/** A round downloaded from the server, as phone database rows (marked as already synced). */
internal fun JSONObject.toRoundData(): RoundData {
    val id = getString("roundId")
    val editedAt = Instant.parse(getString("clientUpdatedUtc"))
    val round = RoundEntity(
        id = id,
        shootDate = LocalDate.parse(getString("shootDate")),
        targetCount = getInt("targetCount"),
        archerClass = if (getString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS,
        ageGroup = AgeGroup.valueOf(getString("ageGroup")),
        bowStyle = requireNotNull(BowStyle.fromCode(getString("bowStyle"))) { "Unknown bow style" },
        status = RoundStatus.valueOf(getString("status")),
        totalPoints = getInt("totalPoints"),
        createdAt = Instant.parse(getString("clientCreatedUtc")),
        updatedAt = editedAt,
        syncState = SyncState.SYNCED,
        deleted = false,
    )
    val targets = mutableListOf<TargetEntity>()
    val animals = mutableListOf<AnimalEntity>()
    val targetArray = getJSONArray("targets")
    for (i in 0 until targetArray.length()) {
        val t = targetArray.getJSONObject(i)
        val targetNo = t.getInt("targetNo")
        targets += TargetEntity(
            roundId = id,
            targetNo = targetNo,
            type = TargetType.valueOf(t.getString("type")),
            overridePoints = if (t.isNull("overridePoints")) null else t.getInt("overridePoints"),
            points = t.getInt("points"),
            updatedAt = editedAt,
            syncState = SyncState.SYNCED,
        )
        val animalArray = t.getJSONArray("animals")
        for (j in 0 until animalArray.length()) {
            val a = animalArray.getJSONObject(j)
            animals += AnimalEntity(
                roundId = id,
                targetNo = targetNo,
                animalIndex = a.getInt("index"),
                state = AnimalState.valueOf(a.getString("state")),
                arrowNo = if (a.isNull("arrowNo")) null else a.getInt("arrowNo"),
                hit = if (a.isNull("hit")) null else Hit.valueOf(a.getString("hit")),
                points = a.getInt("points"),
            )
        }
    }
    return RoundData(round, targets, animals)
}
