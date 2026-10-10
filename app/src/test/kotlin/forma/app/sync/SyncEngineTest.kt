package forma.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.BackupManager
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrackPointEntity
import forma.app.data.TrainingRepository
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two phones of one account syncing through an in-memory stand-in for the Supabase tables. The stand-in
 * formats values the way Postgres returns them (offsets instead of Z, 60 instead of 60.0, jsonb key order)
 * and enforces the workout foreign keys, so the tests cover what the real server does to the rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SyncEngineTest {
    private val user = "8f0c7c2e-5a40-4a8e-9a43-1f7d2a9c0b11"
    private lateinit var cloud: FakeCloud
    private lateinit var a: Phone
    private lateinit var b: Phone

    private val profile = Profile(
        goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0,
    )

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        cloud = FakeCloud(user)
        a = Phone("sync-test-a", ctx, 1_760_000_000_000L)
        b = Phone("sync-test-b", ctx, 1_760_000_000_000L)
    }

    @After fun tearDown() {
        a.db.close()
        b.db.close()
    }

    @Test fun newPhoneGetsEverythingBack() = runBlocking {
        a.liftSession(LocalDate.of(2026, 10, 5))
        a.run(withTrack = true)
        a.repo.logBodyWeight(79.4)

        val up = a.sync(first = true)
        assertTrue(up.uploaded > 10, "uploaded ${up.uploaded}")
        assertEquals(0, up.removed)

        val down = b.sync(first = true)
        assertTrue(down.downloaded >= up.uploaded, "downloaded ${down.downloaded} of ${up.uploaded}")
        val view = b.view()
        assertEquals(a.view(), view)
        assertTrue(view.contains("\"track\":[["), "run track restored")
        assertEquals(2, b.db.workouts().allDone().size)
        assertEquals(a.db.sync().doneSets().size, b.db.sync().doneSets().size)
        assertTrue(b.db.sync().doneSets().size >= 6)
        assertEquals(120, b.db.sync().doneRuns().single().points)
        assertTrue(b.repo.isOnboarded())
        // The restored phone prescribes from the same progress.
        val tpl = b.store.programNow()!!.templates.first().id
        val day = LocalDate.of(2026, 10, 12)
        assertEquals(
            a.repo.liftPlan(tpl, day).items.map { it.prescription },
            b.repo.liftPlan(tpl, day).items.map { it.prescription },
        )
    }

    @Test fun secondSyncFindsNothingToDo() = runBlocking {
        a.liftSession(LocalDate.of(2026, 10, 5))
        a.run(withTrack = true)
        a.sync(first = true)
        b.sync(first = true)
        // Postgres formats rows differently from the phone; fingerprints must still match.
        assertEquals(SyncResult(0, 0, 0), a.sync())
        assertEquals(SyncResult(0, 0, 0), b.sync())
    }

    @Test fun changesTravelBothWays() = runBlocking {
        a.liftSession(LocalDate.of(2026, 10, 5))
        a.sync(first = true)
        b.sync(first = true)

        b.clock += 86_400_000
        b.liftSession(LocalDate.of(2026, 10, 7))
        b.repo.logBodyWeight(78.9)
        b.sync()
        a.sync()
        assertEquals(b.view(), a.view())
        assertEquals(2, a.db.workouts().allDone().size)

        a.clock += 86_400_000
        a.run(withTrack = false)
        a.sync()
        b.sync()
        assertEquals(a.view(), b.view())
    }

    @Test fun deletedWorkoutDisappearsEverywhere() = runBlocking {
        a.liftSession(LocalDate.of(2026, 10, 5))
        val runId = a.run(withTrack = true)
        a.sync(first = true)
        b.sync(first = true)
        val uid = a.db.workouts().get(runId)!!.uid

        a.db.workouts().delete(runId)
        val r = a.sync()
        assertEquals(2, r.removed) // the workout and its run row
        b.sync()
        assertNull(b.db.sync().workoutByUid(uid))
        assertEquals(a.view(), b.view())
        assertEquals(1, b.db.workouts().allDone().size)
    }

    @Test fun restoringAnOldFileDoesNotDeleteFromTheCloud() = runBlocking {
        a.liftSession(LocalDate.of(2026, 10, 5))
        a.sync(first = true)
        val old = BackupManager(a.repo).snapshot()

        a.clock += 86_400_000
        a.liftSession(LocalDate.of(2026, 10, 7))
        a.sync()
        assertEquals(2, cloud.live("workouts"))

        BackupManager(a.repo).restore(old)
        assertEquals(1, a.db.workouts().allDone().size)
        val r = a.sync()
        assertEquals(0, r.removed)
        assertEquals(2, a.db.workouts().allDone().size)
        assertEquals(2, cloud.live("workouts"))
    }

    @Test fun firstSyncTakesTheCloudCopyAndKeepsLocalExtras() = runBlocking {
        a.liftSession(LocalDate.of(2026, 10, 5))
        a.sync(first = true)

        // A new phone set up from scratch before signing in, with a weigh-in the cloud does not have.
        b.repo.completeOnboarding(profile.copy(goal = Goal.STRENGTH, bodyWeightKg = 90.0))
        b.db.state().upsertBodyWeight(forma.app.data.BodyWeightEntity(LocalDate.of(2026, 9, 1).toEpochDay(), 89.0))
        b.sync(first = true)

        assertEquals(Goal.HYBRID, b.store.profileNow()!!.goal) // the account's profile wins
        assertEquals(1, b.db.workouts().allDone().size)
        val days = b.db.state().bodyWeights().map { it.epochDay }.toSet()
        assertTrue(days.size >= 2, "local body weight kept next to the cloud's: $days")
        a.sync()
        assertEquals(b.view(), a.view())
    }

    @Test fun setsWaitForTheirWorkout() = runBlocking {
        val tb = SyncTables(b.db, b.store)
        tb.workouts.pull(cloud, b.db.sync()) // nothing yet
        a.liftSession(LocalDate.of(2026, 10, 5))
        a.sync(first = true)
        // B downloads sets before it has seen their workout: it fetches the workout and retries.
        val applied = tb.sets.pull(cloud, b.db.sync())
        assertTrue(applied > 0)
        val sets = b.db.sync().doneSets()
        assertEquals(applied, sets.size)
        val local = b.db.workouts().allDone().single()
        assertTrue(sets.all { it.set.workoutId == local.id })
    }

    @Test fun serverRejectsSetsOfAnUnknownWorkout() = runBlocking {
        val row = JsonObject(mapOf("user_id" to JsonPrimitive(user), "id" to JsonPrimitive("s1"), "workout_id" to JsonPrimitive("nope")))
        assertFailsWith<IllegalStateException> { cloud.upsert("sets", "user_id,id", listOf(row)) }
        Unit
    }

    @Test fun fingerprintIgnoresFormatting() {
        val phone = forma.app.data.AppJson.parseToJsonElement("""{"b":[1.0,{"y":2,"x":"t"}],"a":60.0,"c":null,"d":true}""")
        val server = forma.app.data.AppJson.parseToJsonElement("""{"a":60,"c":null,"d":true,"b":[1,{"x":"t","y":2.00}]}""")
        assertEquals(contentHash(phone), contentHash(server))
        val other = forma.app.data.AppJson.parseToJsonElement("""{"a":60.5,"c":null,"d":true,"b":[1,{"x":"t","y":2}]}""")
        assertNotEquals(contentHash(phone), contentHash(other))
        val text = forma.app.data.AppJson.parseToJsonElement("""{"a":"60"}""")
        val number = forma.app.data.AppJson.parseToJsonElement("""{"a":60}""")
        assertNotEquals(contentHash(text), contentHash(number))
    }

    // ---------------- helpers ----------------

    private inner class Phone(name: String, ctx: Context, var clock: Long) {
        val db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name).also { runBlocking { it.clear() } }
        val repo = TrainingRepository(db, store, now = { clock })
        private val engine = SyncEngine(cloud, db, store)

        suspend fun sync(first: Boolean = false) = engine.run(user, first)

        suspend fun liftSession(day: LocalDate) {
            if (!repo.isOnboarded()) repo.completeOnboarding(profile)
            val tpl = store.programNow()!!.templates.first().id
            val id = repo.startLift(tpl, day)
            val plan = repo.planOf(db.workouts().get(id)!!)!!
            for (item in plan.items.take(3)) {
                val w = item.prescription.weight ?: 20.0
                repeat(2) {
                    clock += 120_000
                    repo.logSet(id, item, w, item.prescription.reps, 2)
                }
            }
            clock += 30 * 60_000
            repo.finishLift(id, 7)
        }

        suspend fun run(withTrack: Boolean): Long {
            if (!repo.isOnboarded()) repo.completeOnboarding(profile)
            val w = repo.runFor(SessionKind.RUN_EASY)
            val start = clock
            val points = if (!withTrack) emptyList() else (0 until 120).map { i ->
                TrackPointEntity(0, start + i * 5_000L, 55.75 + i * 0.0001, 37.61 + i * 0.00013, 3.7f + (i % 5) * 0.4f)
            }
            clock += w.totalSeconds * 1000L
            return repo.saveRun(w, RunResult(1.0, 5, w.totalSeconds, distanceM = 4100.0), start, points).first
        }

        /** Everything the phone would upload, by global key, in canonical form. */
        suspend fun view(): String = SyncTables(db, store).all.map { dump(it) }.joinToString("\n")

        private suspend fun <T> dump(t: SyncTable<T>): String =
            t.local().map { "${t.key(it)} ${canonicalText(t.encode(it))}" }.sorted().joinToString("\n", prefix = "## ${t.name}\n")
    }
}

/** The Supabase tables of one user, in memory, with the server's formatting and foreign keys. */
class FakeCloud(private val user: String) : CloudApi {
    private val tables = mutableMapOf<String, LinkedHashMap<String, JsonObject>>()
    private var clock = Instant.parse("2026-10-10T10:00:00Z")

    private fun tick(): String {
        clock = clock.plusNanos(1_000)
        return clock.atOffset(ZoneOffset.UTC).format(PG_TIME)
    }

    fun live(table: String) = tables[table].orEmpty().values.count { it["deleted"]?.jsonPrimitive?.booleanOrNull != true }

    override suspend fun upsert(table: String, conflict: String, rows: List<JsonObject>) {
        val keyColumn = conflict.removePrefix("user_id,")
        val t = tables.getOrPut(table) { LinkedHashMap() }
        for (row in rows) {
            check(row["user_id"]?.jsonPrimitive?.content == user) { "row level security" }
            if (table == "sets" || table == "runs") {
                val parent = row["workout_id"]?.jsonPrimitive?.content
                check(tables["workouts"]?.containsKey(parent) == true) { "foreign key: workout $parent" }
            }
            val key = row.getValue(keyColumn).jsonPrimitive.content
            val stored = (t[key] ?: JsonObject(emptyMap())) + row.mapValues { (_, v) -> asPostgres(v) } +
                ("updated_at" to JsonPrimitive(tick()))
            t[key] = JsonObject(stored)
        }
    }

    override suspend fun markDeleted(table: String, keyColumn: String, keys: List<String>) {
        val t = tables[table] ?: return
        for (k in keys) {
            val row = t[k] ?: continue
            t[k] = JsonObject(row + mapOf("deleted" to JsonPrimitive(true), "updated_at" to JsonPrimitive(tick())))
        }
    }

    override suspend fun changes(table: String, keyColumn: String, since: String?, offset: Int, limit: Int): List<JsonObject> {
        val after = since?.let(Instant::parse)
        return tables[table].orEmpty().values
            .filter { after == null || time(it) > after }
            .sortedWith(compareBy<JsonObject>({ time(it) }, { it.getValue(keyColumn).jsonPrimitive.content }))
            .drop(offset).take(limit)
    }

    private fun time(row: JsonObject) = OffsetDateTime.parse(row.getValue("updated_at").jsonPrimitive.content).toInstant()

    /** How Postgres hands values back: timestamptz with an offset, float8 without ".0", jsonb keys reordered. */
    private fun asPostgres(v: JsonElement): JsonElement = when {
        v is JsonObject -> JsonObject(v.entries.sortedWith(compareBy({ it.key.length }, { it.key })).associate { it.key to jsonb(it.value) })
        v is JsonArray -> JsonArray(v.map(::jsonb))
        v is JsonPrimitive && v.isString && ISO_INSTANT.matches(v.content) ->
            JsonPrimitive(Instant.parse(v.content).atOffset(ZoneOffset.UTC).format(PG_TIME))
        v is JsonPrimitive && !v.isString && v.content.endsWith(".0") -> JsonPrimitive(v.content.removeSuffix(".0").toLong())
        else -> v
    }

    private fun jsonb(v: JsonElement): JsonElement = when (v) {
        is JsonObject -> JsonObject(v.entries.sortedWith(compareBy({ it.key.length }, { it.key })).associate { it.key to jsonb(it.value) })
        is JsonArray -> JsonArray(v.map(::jsonb))
        else -> v
    }

    private companion object {
        val ISO_INSTANT = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$""")
        val PG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")
    }
}
