package forma.app.sync

import forma.app.data.BodyWeightEntity
import forma.app.data.ExerciseStatEntity
import forma.app.data.FormaDatabase
import forma.app.data.RunEntity
import forma.app.data.SetEntity
import forma.app.data.SetWithWorkout
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.Status
import forma.app.data.SyncCursorEntity
import forma.app.data.SyncDao
import forma.app.data.SyncRowEntity
import forma.app.data.TrackPointEntity
import forma.app.data.WorkoutEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Outcome of saving a downloaded row on the phone. */
internal enum class Write { OK, SKIP, NO_PARENT }

/**
 * One cloud table: how its rows look on the phone ([T], with global ids) and in Supabase (JSON).
 * Rows are identified per user by [keyColumn]; the server adds user_id, updated_at and deleted.
 */
internal abstract class SyncTable<T>(val name: String, val keyColumn: String, val parent: SyncTable<*>? = null) {
    /** Rows per upload request and per download page (GPS tracks make run rows large). */
    open val batch: Int = 200

    /** Rows that two phones change in turn (progress per exercise): the newer change wins, so they come down first. */
    open val newestWins: Boolean = false

    abstract fun key(row: T): String
    abstract suspend fun local(): List<T>

    /** The row as the cloud stores it, without the server-managed columns. */
    abstract suspend fun encode(row: T): JsonObject

    /** Must not depend on how the server formats JSON or on phone-only ids. */
    open suspend fun fingerprint(row: T): String = contentHash(encode(row))

    abstract fun decode(json: JsonObject): T
    abstract suspend fun write(row: T): Write
    abstract suspend fun delete(key: String)

    fun keyOf(json: JsonObject): String = json.getValue(keyColumn).jsonPrimitive.content

    /** Uploads rows that changed since the cloud last saw them; rows gone from the phone become deleted in the cloud. */
    suspend fun push(api: CloudApi, dao: SyncDao, userId: String): Pair<Int, Int> {
        val known = dao.rows(name).associate { it.key to it.hash }
        val rows = local()
        val changed = rows.mapNotNull { r ->
            val fp = fingerprint(r)
            if (known[key(r)] != fp) r to fp else null
        }
        for (chunk in changed.chunked(batch)) {
            val body = chunk.map { (r, _) ->
                JsonObject(encode(r) + mapOf("user_id" to JsonPrimitive(userId), "deleted" to JsonPrimitive(false)))
            }
            api.upsert(name, "user_id,$keyColumn", body)
            dao.putRows(chunk.map { (r, fp) -> SyncRowEntity(name, key(r), fp) })
        }
        val present = rows.mapTo(HashSet()) { key(it) }
        val gone = known.keys.filter { it !in present }
        for (chunk in gone.chunked(200)) {
            api.markDeleted(name, keyColumn, chunk)
            dao.dropRows(name, chunk)
        }
        return changed.size to gone.size
    }

    /**
     * Downloads rows changed on the server since the last download (with a short overlap, because
     * concurrent commits can land slightly out of order). Unchanged rows are recognised by fingerprint.
     */
    suspend fun pull(api: CloudApi, dao: SyncDao): Int {
        val cursor = dao.cursor(name)?.let(Instant::parse)
        val since = cursor?.minusSeconds(OVERLAP_SECONDS)?.toString()
        val known = dao.rows(name).associateTo(HashMap()) { it.key to it.hash }
        var newest = cursor
        var applied = 0
        var parentRefreshed = false
        var after: Pair<String, String>? = null
        while (true) {
            val page = api.changes(name, keyColumn, since, after, batch)
            for (json in page) {
                val key = keyOf(json)
                if (json["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
                    delete(key)
                    if (known.remove(key) != null) dao.dropRows(name, listOf(key))
                } else {
                    val row = decode(json)
                    val fp = fingerprint(row)
                    if (known[key] != fp) {
                        var result = write(row)
                        if (result == Write.NO_PARENT && !parentRefreshed && parent != null) {
                            // The parent workout arrived after this table's previous download: fetch it and retry once.
                            parentRefreshed = true
                            parent.pull(api, dao)
                            result = write(row)
                        }
                        if (result == Write.OK) {
                            dao.putRows(listOf(SyncRowEntity(name, key, fp)))
                            known[key] = fp
                            applied++
                        }
                    }
                }
                val at = serverTime(json)
                if (at != null && (newest == null || at > newest)) newest = at
            }
            if (page.size < batch) break
            val last = page.last()
            after = (serverTime(last) ?: break).toString() to keyOf(last)
        }
        newest?.let { dao.putCursor(SyncCursorEntity(name, it.toString())) }
        return applied
    }

    companion object {
        const val OVERLAP_SECONDS = 60L
    }
}

// ---------- JSON helpers: time as ISO UTC, days as dates, phone JSON strings as jsonb ----------

internal fun ts(ms: Long): JsonPrimitive = JsonPrimitive(Instant.ofEpochMilli(ms).toString())
internal fun msOf(e: JsonElement?): Long? = e?.jsonPrimitive?.contentOrNull?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
internal fun dateOf(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()
internal fun epochDayOf(s: String): Long = LocalDate.parse(s).toEpochDay()
internal fun serverTime(json: JsonObject): Instant? = json["updated_at"]?.jsonPrimitive?.contentOrNull?.let { OffsetDateTime.parse(it).toInstant() }

/** The phone keeps JSON documents as text; the cloud keeps them as jsonb. */
internal fun docOf(text: String?): JsonElement =
    if (text == null) JsonNull else runCatching { forma.app.data.AppJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }

internal fun textOf(e: JsonElement?): String? = when {
    e == null || e is JsonNull -> null
    e is JsonPrimitive && e.isString -> e.content
    else -> e.toString()
}

private fun JsonObject.str(k: String): String = getValue(k).jsonPrimitive.content
private fun JsonObject.strOrNull(k: String): String? = get(k)?.jsonPrimitive?.contentOrNull
private fun JsonObject.dbl(k: String): Double = getValue(k).jsonPrimitive.double
private fun JsonObject.dblOrNull(k: String): Double? = get(k)?.jsonPrimitive?.doubleOrNull
private fun JsonObject.integer(k: String): Int = getValue(k).jsonPrimitive.int
private fun JsonObject.intOrNull(k: String): Int? = get(k)?.jsonPrimitive?.intOrNull

/** A document from the settings store: profile, program, run_state, settings, lift_state, custom_exercises. */
internal data class Doc(val key: String, val value: JsonElement)

internal const val CUSTOM_DOC = "custom_exercises"

/** Both lists of own exercises, by id; for an exercise in both, the [incoming] one. */
internal fun mergeCustom(local: String?, incoming: String): String {
    val serializer = kotlinx.serialization.builtins.ListSerializer(forma.core.model.Exercise.serializer())
    val mine = local?.let { runCatching { forma.app.data.AppJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    val theirs = runCatching { forma.app.data.AppJson.decodeFromString(serializer, incoming) }.getOrNull() ?: return incoming
    val ids = theirs.mapTo(HashSet()) { it.id }
    val extra = mine.filter { it.id !in ids }
    return if (extra.isEmpty()) incoming else forma.app.data.AppJson.encodeToString(serializer, theirs + extra)
}

/** A finished run with the global id of its workout. The track is loaded only for uploads. */
internal data class RunRow(
    val run: RunEntity,
    val workoutUid: String,
    val points: Int,
    val firstT: Long?,
    val lastT: Long?,
    val track: List<TrackPointEntity>? = null,
)

/** The seven cloud tables, parents before children. */
internal class SyncTables(db: FormaDatabase, private val store: SettingsStore) {
    private val dao = db.sync()

    val appState = object : SyncTable<Doc>("app_state", "key") {
        override fun key(row: Doc) = row.key
        override suspend fun local() = store.syncDocs().map { (k, text) -> Doc(k, docOf(text)) }
        override suspend fun encode(row: Doc) = buildJsonObject {
            put("key", row.key)
            put("value", row.value)
        }
        override fun decode(json: JsonObject) = Doc(json.str("key"), json.getValue("value"))
        override suspend fun write(row: Doc): Write {
            val text = textOf(row.value) ?: return Write.SKIP
            // Own exercises made on two phones: both lists together (the cloud's version of the same one wins).
            val merged = if (row.key == CUSTOM_DOC) mergeCustom(store.syncDocs()[CUSTOM_DOC], text) else text
            return if (store.putSyncDoc(row.key, merged)) Write.OK else Write.SKIP
        }
        override suspend fun delete(key: String) = store.removeSyncDoc(key)
    }

    val bodyWeights = object : SyncTable<BodyWeightEntity>("body_weights", "day") {
        override fun key(row: BodyWeightEntity) = dateOf(row.epochDay)
        override suspend fun local() = dao.bodyWeights()
        override suspend fun encode(row: BodyWeightEntity) = buildJsonObject {
            put("day", dateOf(row.epochDay))
            put("kg", row.kg)
        }
        override fun decode(json: JsonObject) = BodyWeightEntity(epochDayOf(json.str("day")), json.dbl("kg"))
        override suspend fun write(row: BodyWeightEntity): Write { dao.putBodyWeight(row); return Write.OK }
        override suspend fun delete(key: String) = dao.deleteBodyWeight(epochDayOf(key))
    }

    val slots = object : SyncTable<SlotStateEntity>("slot_states", "slot_key") {
        override fun key(row: SlotStateEntity) = row.slotKey
        override suspend fun local() = dao.slots()
        override suspend fun encode(row: SlotStateEntity) = buildJsonObject {
            put("slot_key", row.slotKey)
            put("exercise_id", row.exerciseId)
            put("weight", row.weight)
            put("reps", row.reps)
            put("stalls", row.stalls)
            put("misses", row.misses)
            put("sessions", row.sessions)
            put("changed_at", ts(row.updatedAt))
            put("linear", row.linear)
            put("steady", row.steady)
            put("cut_from", row.cutFrom)
            put("cut_reps", row.cutReps)
        }
        override fun decode(json: JsonObject) = SlotStateEntity(
            json.str("slot_key"), json.str("exercise_id"), json.dblOrNull("weight"), json.integer("reps"),
            json.integer("stalls"), json.integer("misses"), json.integer("sessions"), msOf(json["changed_at"]) ?: 0L,
            json["linear"]?.jsonPrimitive?.booleanOrNull ?: true, json.intOrNull("steady") ?: 0,
            json.dblOrNull("cut_from"), json.intOrNull("cut_reps"),
        )
        override val newestWins = true
        override suspend fun write(row: SlotStateEntity): Write {
            val local = dao.slot(row.slotKey)
            if (local != null && local.updatedAt > row.updatedAt) return Write.SKIP
            dao.putSlot(row)
            return Write.OK
        }
        override suspend fun delete(key: String) = dao.deleteSlot(key)
    }

    val stats = object : SyncTable<ExerciseStatEntity>("exercise_stats", "exercise_id") {
        override fun key(row: ExerciseStatEntity) = row.exerciseId
        override suspend fun local() = dao.stats()
        override suspend fun encode(row: ExerciseStatEntity) = buildJsonObject {
            put("exercise_id", row.exerciseId)
            put("e1rm", row.e1rm)
            put("best", row.best)
            put("changed_at", ts(row.updatedAt))
        }
        override fun decode(json: JsonObject) =
            ExerciseStatEntity(json.str("exercise_id"), json.dbl("e1rm"), json.dbl("best"), msOf(json["changed_at"]) ?: 0L)
        override val newestWins = true
        override suspend fun write(row: ExerciseStatEntity): Write {
            val local = dao.stat(row.exerciseId)
            if (local != null && local.updatedAt > row.updatedAt) return Write.SKIP
            dao.putStat(row)
            return Write.OK
        }
        override suspend fun delete(key: String) = dao.deleteStat(key)
    }

    val workouts = object : SyncTable<WorkoutEntity>("workouts", "id") {
        override fun key(row: WorkoutEntity) = row.uid
        override suspend fun local() = dao.doneWorkouts()
        override suspend fun encode(row: WorkoutEntity) = buildJsonObject {
            put("id", row.uid)
            put("day", dateOf(row.epochDay))
            put("kind", row.kind)
            put("template_id", row.templateId)
            put("title", row.title)
            put("started_at", ts(row.startedAt))
            put("finished_at", row.finishedAt?.let(::ts) ?: JsonNull)
            put("rpe", row.rpe)
            put("duration_sec", row.durationSec)
            put("load", row.load)
            put("deload", row.deload)
            put("plan", docOf(row.planJson))
            put("summary", docOf(row.summaryJson))
            put("note", row.note)
        }
        override fun decode(json: JsonObject) = WorkoutEntity(
            epochDay = epochDayOf(json.str("day")), kind = json.str("kind"), templateId = json.strOrNull("template_id"),
            title = json.str("title"), startedAt = msOf(json["started_at"]) ?: 0L, finishedAt = msOf(json["finished_at"]),
            status = Status.DONE, rpe = json.intOrNull("rpe"), durationSec = json.intOrNull("duration_sec"), load = json.dblOrNull("load"),
            deload = json["deload"]?.jsonPrimitive?.booleanOrNull ?: false, planJson = textOf(json["plan"]),
            summaryJson = textOf(json["summary"]), note = json.strOrNull("note"), uid = json.str("id"),
        )
        override suspend fun write(row: WorkoutEntity): Write {
            val existing = dao.workoutByUid(row.uid)
            if (existing != null) dao.updateWorkout(row.copy(id = existing.id)) else dao.insertWorkout(row.copy(id = 0))
            return Write.OK
        }
        override suspend fun delete(key: String) = dao.deleteWorkout(key)
    }

    val sets = object : SyncTable<SetWithWorkout>("sets", "id", parent = workouts) {
        override fun key(row: SetWithWorkout) = row.set.uid
        // Only sets of workouts the cloud has: one finished during this sync goes up with the next.
        override suspend fun local(): List<SetWithWorkout> {
            val inCloud = dao.rows(workouts.name).mapTo(HashSet()) { it.key }
            return dao.doneSets().filter { it.workoutUid in inCloud }
        }
        override suspend fun encode(row: SetWithWorkout) = buildJsonObject {
            val s = row.set
            put("id", s.uid)
            put("workout_id", row.workoutUid)
            put("exercise_id", s.exerciseId)
            put("slot_key", s.slotKey)
            put("position", s.position)
            put("weight", s.weight)
            put("reps", s.reps)
            put("rir", s.rir)
            put("target_weight", s.targetWeight)
            put("target_reps", s.targetReps)
            put("done_at", ts(s.doneAt))
        }
        override fun decode(json: JsonObject) = SetWithWorkout(
            SetEntity(
                workoutId = 0, exerciseId = json.str("exercise_id"), slotKey = json.str("slot_key"), position = json.integer("position"),
                weight = json.dbl("weight"), reps = json.integer("reps"), rir = json.intOrNull("rir"),
                targetWeight = json.dblOrNull("target_weight"), targetReps = json.intOrNull("target_reps"),
                doneAt = msOf(json["done_at"]) ?: 0L, uid = json.str("id"),
            ),
            json.str("workout_id"),
        )
        override suspend fun write(row: SetWithWorkout): Write {
            val w = dao.workoutByUid(row.workoutUid) ?: return Write.NO_PARENT
            val existing = dao.setByUid(row.set.uid)
            if (existing != null) dao.updateSet(row.set.copy(id = existing.id, workoutId = w.id))
            else dao.insertSet(row.set.copy(id = 0, workoutId = w.id))
            return Write.OK
        }
        override suspend fun delete(key: String) = dao.deleteSet(key)
    }

    val runs = object : SyncTable<RunRow>("runs", "workout_id", parent = workouts) {
        override val batch = 10

        override fun key(row: RunRow) = row.workoutUid
        override suspend fun local(): List<RunRow> {
            val inCloud = dao.rows(workouts.name).mapTo(HashSet()) { it.key }
            return dao.doneRuns().filter { it.workoutUid in inCloud }.map { RunRow(it.run, it.workoutUid, it.points, it.firstT, it.lastT) }
        }

        private fun base(row: RunRow) = buildJsonObject {
            val r = row.run
            put("workout_id", row.workoutUid)
            put("type", r.type)
            put("plan", docOf(r.planJson))
            put("completed", r.completed)
            put("moving_sec", r.movingSec)
            put("distance_m", r.distanceM)
            put("avg_pace", r.avgPace)
            put("work_distance_m", r.workDistanceM)
            put("work_pace", r.workPace)
            put("vdot_after", r.vdotAfter)
        }

        override suspend fun encode(row: RunRow): JsonObject {
            val points = row.track ?: dao.points(row.run.workoutId)
            val track = if (points.isEmpty()) JsonNull else JsonArray(
                points.map { JsonArray(listOf(JsonPrimitive(it.t), JsonPrimitive(it.lat), JsonPrimitive(it.lon), JsonPrimitive(it.accuracy))) },
            )
            return JsonObject(base(row) + ("track" to track))
        }

        // Points never change after a run is saved, so their count and time span stand in for them.
        override suspend fun fingerprint(row: RunRow) =
            contentHash(JsonObject(base(row) + ("track" to JsonPrimitive("${row.points}|${row.firstT}|${row.lastT}"))))

        override fun decode(json: JsonObject): RunRow {
            val points = (json["track"] as? JsonArray).orEmpty().map { p ->
                val a = p.jsonArray
                TrackPointEntity(0, a[0].jsonPrimitive.long, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double, a[3].jsonPrimitive.float)
            }
            val run = RunEntity(
                workoutId = 0, type = json.str("type"), planJson = textOf(json["plan"]) ?: "{}", completed = json.dbl("completed"),
                movingSec = json.integer("moving_sec"), distanceM = json.dblOrNull("distance_m"), avgPace = json.dblOrNull("avg_pace"),
                workDistanceM = json.dblOrNull("work_distance_m"), workPace = json.dblOrNull("work_pace"), vdotAfter = json.dblOrNull("vdot_after"),
            )
            return RunRow(run, json.str("workout_id"), points.size, points.minOfOrNull { it.t }, points.maxOfOrNull { it.t }, points)
        }

        override suspend fun write(row: RunRow): Write {
            val w = dao.workoutByUid(row.workoutUid) ?: return Write.NO_PARENT
            dao.replaceRun(row.run.copy(workoutId = w.id), row.track.orEmpty().map { it.copy(workoutId = w.id) })
            return Write.OK
        }

        override suspend fun delete(key: String) {
            val w = dao.workoutByUid(key) ?: return
            dao.deleteRun(w.id)
            dao.deletePoints(w.id)
        }
    }

    val all: List<SyncTable<*>> = listOf(appState, bodyWeights, slots, stats, workouts, sets, runs)
}
