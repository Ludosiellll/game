package forma.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import forma.core.model.Exercise
import forma.core.model.Profile
import forma.core.plan.Program
import forma.core.run.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Settings(
    /** Design direction id from the design board: carbon, light, poster, tone. */
    val look: String = "carbon",
    val voice: Boolean = true,
    val beeps: Boolean = true,
    val vibration: Boolean = true,
    val gps: Boolean = true,
    val autoRest: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Easy, long and free runs on the road stop the clock at a standstill (traffic lights) and go on with running. */
    val autoPause: Boolean = true,
    /** Per-exercise load step overrides, kg. */
    val steps: Map<String, Double> = emptyMap(),
    /** Training paused (vacation, illness) from this epoch day … */
    val pauseFrom: Long? = null,
    /** … to this one, inclusive. */
    val pauseUntil: Long? = null,
    /** Pauses before the last one ([from, until] epoch days): their days stay out of the missed ones too. */
    val pastPauses: List<List<Long>> = emptyList(),
    /** Exercises replaced for good: plan exercise id → the one done instead. Survives plan changes. */
    val swaps: Map<String, String> = emptyMap(),
    /** Sets of an exercise for good: plan exercise id → how many, instead of the plan's number. */
    val sets: Map<String, Int> = emptyMap(),
    /** Exercises taken out of the plan for good (plan exercise ids); the plan screen brings them back. */
    val removed: Set<String> = emptySet(),
    /** Exercises added to a lifting session for good: template id → exercise ids, after the plan's own. */
    val added: Map<String, List<String>> = emptyMap(),
    /** Rep range of an exercise for good: plan exercise id → [min, max], instead of the plan's. */
    val reps: Map<String, List<Int>> = emptyMap(),
    /** Rest between sets of an exercise for good: plan exercise id → seconds, instead of the plan's. */
    val rest: Map<String, Int> = emptyMap(),
    /** The own plan put aside when a friend's program was copied: "Вернуть мою программу" brings it back as it was. */
    val ownPlan: OwnPlan? = null,
    /** The start line of the stadium (lat, lon) learnt from the lap presses: GPS laps count from the first one next time. */
    val trackLine: List<Double>? = null,
    /** (cadence, stride m) pairs measured where GPS was good: the stride model for distance by steps. */
    val strideSamples: List<List<Double>> = emptyList(),
    /** Run type → how many times its "what it gives the body" was shown after a run: read a few times, it folds away. */
    val bodySeen: Map<String, Int> = emptyMap(),
    /**
     * How the person felt on a day, by epoch day ([Feel] names): every session of that day follows it (a lighter one for
     * "so-so"), and the export shows it. The last [FEEL_DAYS] days are kept.
     */
    val feels: Map<Long, String> = emptyMap(),
    /** A short note per exercise ("сиденье 4, хват шире"): exercise id → note, shown in the workout. */
    val notes: Map<String, String> = emptyMap(),
    /**
     * Plan exercises (ids) whose swap for a single-joint exercise was made with its sets and reps as an added one's
     * ([TrainingRepository.demotes]). Swaps made before that rule keep the plan's numbers: a plan the person shaped stays.
     */
    val demoted: Set<String> = emptySet(),
    /** «Идеальный план» changes the person said not to propose (change ids, [forma.core.plan.PlanAdvisor.Change.id]). */
    val adviceHidden: Set<String> = emptySet(),
    /** The first look round the app was seen (after the questionnaire, or skipped). */
    val tourSeen: Boolean = false,
    /** Screens whose first-visit hint was closed with «Понятно» ([forma.app.ui.screens.Hints]). */
    val hintsSeen: Set<String> = emptySet(),
    /** What the coach (the AI) keeps in mind about the person: likes, dislikes, the gym's kit, old injuries. */
    val coachNotes: List<String> = emptyList(),
    /** The coach's own count of strides after easy runs, on the days the plan has them, until a day. */
    val coachStrides: CoachTerm? = null,
    /** The coach's long easy run: the last easy run of the week is a long one, until a day (the long run's own limits hold). */
    val coachLong: CoachTerm? = null,
) {
    /** Whether [day] is in a pause, the last one or one before it: no sessions are expected then. */
    fun pausedOn(day: java.time.LocalDate): Boolean {
        val d = day.toEpochDay()
        val last = pauseUntil?.let { d in (pauseFrom ?: it)..it } == true
        return last || pastPauses.any { it.size == 2 && d in it[0]..it[1] }
    }

    /**
     * The settings with a new pause [from]–[until]: the last one is kept among [pastPauses] (the most recent
     * [PAST_PAUSES]) unless the new one covers it.
     */
    fun withPause(from: Long, until: Long): Settings {
        val last = pauseUntil?.let { listOf(pauseFrom ?: it, it) }?.takeIf { it[0] < from || it[1] > until }
        val kept = (pastPauses + listOfNotNull(last)).filter { it.size == 2 && it[0] <= it[1] }.distinct().takeLast(PAST_PAUSES)
        return copy(pauseFrom = from, pauseUntil = until, pastPauses = kept)
    }

    /** How the person said they felt on [day]; [Feel.UNSET] when they did not. */
    fun feelOn(day: java.time.LocalDate): Feel =
        feels[day.toEpochDay()]?.let { name -> Feel.entries.firstOrNull { it.name == name } } ?: Feel.UNSET

    /** The same settings with [feel] for [day] (unset: forgotten), older days than [FEEL_DAYS] dropped. */
    fun withFeel(day: java.time.LocalDate, feel: Feel): Settings {
        val d = day.toEpochDay()
        val kept = feels.filterKeys { it > d - FEEL_DAYS && it != d }
        return copy(feels = if (feel == Feel.UNSET) kept else kept + (d to feel.name))
    }

    companion object {
        const val FEEL_DAYS = 180
        const val PAST_PAUSES = 30
    }
}

/** A change of the coach's that lasts until [until] (epoch day, inclusive): [value] as the change says. */
@Serializable
data class CoachTerm(val value: Int, val until: Long) {
    fun on(day: java.time.LocalDate): Boolean = day.toEpochDay() <= until
}

/**
 * A change the coach made or proposed and the person took: when, what it said, and how to take it back ([undo], an
 * internal call of [CoachActions]); [undone] once taken back.
 */
@Serializable
data class CoachLogEntry(
    val id: Long,
    val at: Long,
    val text: String,
    val undo: forma.app.sync.CoachCall? = null,
    val undone: Boolean = false,
    /** Why the coach made it, as it said. */
    val why: String? = null,
)

/** Where a change of the coach's stands in the talk: made, waiting for «Принять», not wanted, not possible, taken back. */
@Serializable
enum class CoachActState { DONE, PENDING, DECLINED, REFUSED, UNDONE }

/** A change the coach chose, as the talk keeps it: the call (to make it on «Принять»), what it does, why, where it stands. */
@Serializable
data class CoachChatAct(
    val call: forma.app.sync.CoachCall,
    val text: String,
    val why: String? = null,
    val state: CoachActState,
    /** The log entry of the change once made ([CoachLogEntry.id]): «Вернуть» goes through it. */
    val logId: Long? = null,
)

/**
 * One message of the talk with the coach, kept on the phone: the person's or the coach's, with the changes the coach
 * chose. [said]: what the model is told it said next time (its text and what the app did with its calls).
 */
@Serializable
data class CoachChatMsg(
    val id: Long,
    val mine: Boolean,
    val text: String,
    val acts: List<CoachChatAct> = emptyList(),
    val said: String? = null,
)

/**
 * One's own program as it was before a friend's was copied: the days and aim of the profile, the program, and the
 * edits of the plan made on it (swaps, removed and added exercises, sets, reps, rest).
 */
@Serializable
data class OwnPlan(
    val profile: Profile,
    val program: Program,
    val swaps: Map<String, String> = emptyMap(),
    val sets: Map<String, Int> = emptyMap(),
    val removed: Set<String> = emptySet(),
    val added: Map<String, List<String>> = emptyMap(),
    val reps: Map<String, List<Int>> = emptyMap(),
    val rest: Map<String, Int> = emptyMap(),
    val demoted: Set<String> = emptySet(),
) {
    /** [s] with these edits back in and nothing put aside any more. */
    fun editsInto(s: Settings): Settings =
        s.copy(swaps = swaps, sets = sets, removed = removed, added = added, reps = reps, rest = rest, demoted = demoted, ownPlan = null)

    companion object {
        /** The own plan of [profile] and [program] with the edits in [s]. */
        fun of(profile: Profile, program: Program, s: Settings) =
            OwnPlan(profile, program, s.swaps, s.sets, s.removed, s.added, s.reps, s.rest, s.demoted)
    }
}

/** Lifting state that is not per exercise. */
@Serializable
data class LiftState(
    /** Monday (epoch day) of a lighter week the app scheduled itself after repeated setbacks. */
    val reactiveDeloadStart: Long? = null,
)

/** One DataStore per file and process (DataStore forbids two instances on the same file). */
private val stores = mutableMapOf<String, DataStore<Preferences>>()

private fun Context.namedStore(name: String): DataStore<Preferences> = synchronized(stores) {
    stores.getOrPut(name) { PreferenceDataStoreFactory.create { applicationContext.preferencesDataStoreFile(name) } }
}

/** Profile, program, running state and settings as JSON documents. [name] other than the default is for tests. */
class SettingsStore(context: Context, name: String = "forma") {
    private val store = context.namedStore(name)

    private object Keys {
        val profile = stringPreferencesKey("profile")
        val program = stringPreferencesKey("program")
        val runState = stringPreferencesKey("run_state")
        val settings = stringPreferencesKey("settings")
        val lift = stringPreferencesKey("lift_state")
        val custom = stringPreferencesKey("custom_exercises")
        val lastRun = stringPreferencesKey("last_run")
        val coachLog = stringPreferencesKey("coach_log")
        val coachReviews = stringPreferencesKey("coach_reviews")
        val coachChat = stringPreferencesKey("coach_chat")
    }

    /** Documents copied to the cloud, by cloud key, with the type that must decode them. */
    private val synced: Map<String, Pair<Preferences.Key<String>, KSerializer<*>>> = mapOf(
        "profile" to (Keys.profile to Profile.serializer()),
        "program" to (Keys.program to Program.serializer()),
        "run_state" to (Keys.runState to RunState.serializer()),
        "settings" to (Keys.settings to Settings.serializer()),
        "lift_state" to (Keys.lift to LiftState.serializer()),
        "custom_exercises" to (Keys.custom to ListSerializer(Exercise.serializer())),
    )

    private fun <T> read(key: Preferences.Key<String>, s: KSerializer<T>): Flow<T?> =
        store.data.map { p -> p[key]?.let { runCatching { AppJson.decodeFromString(s, it) }.getOrNull() } }

    private suspend fun <T> write(key: Preferences.Key<String>, s: KSerializer<T>, value: T) {
        store.edit { it[key] = AppJson.encodeToString(s, value) }
    }

    /** Raw JSON of every stored document that the cloud copies. */
    suspend fun syncDocs(): Map<String, String> {
        val p = store.data.first()
        return synced.mapNotNull { (cloudKey, v) -> p[v.first]?.let { cloudKey to it } }.toMap().let { docs ->
            // The program goes up with its templates as done here (swaps, own sets...): a friend copying it gets those.
            val program = docs["program"]?.let { runCatching { AppJson.decodeFromString(Program.serializer(), it) }.getOrNull() }
            if (program == null) docs
            else docs + ("program" to AppJson.encodeToString(Program.serializer(), TrainingRepository.shareable(program, settingsNow())))
        }
    }

    /** Stores a document that came from the cloud. False when the key is unknown or the JSON does not fit the type. */
    suspend fun putSyncDoc(cloudKey: String, json: String): Boolean {
        val (key, serializer) = synced[cloudKey] ?: return false
        if (runCatching { AppJson.decodeFromString(serializer, json) }.isFailure) return false
        store.edit { it[key] = json }
        return true
    }

    suspend fun removeSyncDoc(cloudKey: String) {
        val key = synced[cloudKey]?.first ?: return
        store.edit { it.remove(key) }
    }

    val profile: Flow<Profile?> = read(Keys.profile, Profile.serializer())
    val program: Flow<Program?> = read(Keys.program, Program.serializer())
    val runState: Flow<RunState?> = read(Keys.runState, RunState.serializer())
    val settings: Flow<Settings> = read(Keys.settings, Settings.serializer()).map { it ?: Settings() }
    val liftState: Flow<LiftState> = read(Keys.lift, LiftState.serializer()).map { it ?: LiftState() }
    /** The user's own exercises, removed ones included (their history must still open). */
    val customExercises: Flow<List<Exercise>> = read(Keys.custom, ListSerializer(Exercise.serializer())).map { it.orEmpty() }
    suspend fun customNow() = customExercises.first()
    suspend fun setCustom(list: List<Exercise>) = write(Keys.custom, ListSerializer(Exercise.serializer()), list)

    suspend fun profileNow() = profile.first()
    suspend fun programNow() = program.first()
    suspend fun runStateNow() = runState.first() ?: RunState()
    suspend fun settingsNow() = settings.first()
    suspend fun liftStateNow() = liftState.first()
    suspend fun setLiftState(l: LiftState) = write(Keys.lift, LiftState.serializer(), l)

    suspend fun setProfile(p: Profile) = write(Keys.profile, Profile.serializer(), p)
    suspend fun setProgram(p: Program) = write(Keys.program, Program.serializer(), p)
    suspend fun setRunState(r: RunState) = write(Keys.runState, RunState.serializer(), r)
    suspend fun setSettings(s: Settings) = write(Keys.settings, Settings.serializer(), s)

    /** The last run saved on this phone, to decide again when its rating is put right; not copied to the cloud. */
    suspend fun lastRunNow(): LastRun? = read(Keys.lastRun, LastRun.serializer()).first()
    suspend fun setLastRun(r: LastRun) = write(Keys.lastRun, LastRun.serializer(), r)

    /** What the coach changed on this phone, newest last (the last [COACH_LOG] kept); not copied to the cloud. */
    val coachLog: Flow<List<CoachLogEntry>> = read(Keys.coachLog, ListSerializer(CoachLogEntry.serializer())).map { it.orEmpty() }
    suspend fun coachLogNow(): List<CoachLogEntry> = coachLog.first()

    /** The coach's log changed by [f] in one step. */
    suspend fun editCoachLog(f: (List<CoachLogEntry>) -> List<CoachLogEntry>) {
        val s = ListSerializer(CoachLogEntry.serializer())
        store.edit { p ->
            val now = p[Keys.coachLog]?.let { runCatching { AppJson.decodeFromString(s, it) }.getOrNull() }.orEmpty()
            p[Keys.coachLog] = AppJson.encodeToString(s, f(now).takeLast(COACH_LOG))
        }
    }

    /** The talk with the coach, oldest first (the last [COACH_CHAT] messages); kept on this phone only. */
    val coachChat: Flow<List<CoachChatMsg>> = read(Keys.coachChat, ListSerializer(CoachChatMsg.serializer())).map { it.orEmpty() }
    suspend fun coachChatNow(): List<CoachChatMsg> = coachChat.first()

    /** The talk changed by [f] in one step. */
    suspend fun editCoachChat(f: (List<CoachChatMsg>) -> List<CoachChatMsg>) {
        val s = ListSerializer(CoachChatMsg.serializer())
        store.edit { p ->
            val now = p[Keys.coachChat]?.let { runCatching { AppJson.decodeFromString(s, it) }.getOrNull() }.orEmpty()
            p[Keys.coachChat] = AppJson.encodeToString(s, f(now).takeLast(COACH_CHAT))
        }
    }

    /** The coach's word on a workout, by workout id (the last [COACH_REVIEWS] kept); not copied to the cloud. */
    suspend fun coachReview(workoutId: Long): String? = read(Keys.coachReviews, reviews).first()?.get(workoutId)

    suspend fun setCoachReview(workoutId: Long, text: String) {
        store.edit { p ->
            val now = p[Keys.coachReviews]?.let { runCatching { AppJson.decodeFromString(reviews, it) }.getOrNull() }.orEmpty()
            val next = (now - workoutId).entries.sortedBy { it.key }.takeLast(COACH_REVIEWS - 1).associate { it.key to it.value } + (workoutId to text)
            p[Keys.coachReviews] = AppJson.encodeToString(reviews, next)
        }
    }

    private val reviews: KSerializer<Map<Long, String>> =
        kotlinx.serialization.builtins.MapSerializer(Long.serializer(), String.serializer())

    /** Settings changed by [f] in one step: two changes at once (a run saved while its debrief is shown) both stay. */
    suspend fun editSettings(f: (Settings) -> Settings) {
        store.edit { p ->
            val now = p[Keys.settings]?.let { runCatching { AppJson.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()
            p[Keys.settings] = AppJson.encodeToString(Settings.serializer(), f(now))
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        const val COACH_LOG = 50
        const val COACH_REVIEWS = 40
        const val COACH_CHAT = 80
    }
}
