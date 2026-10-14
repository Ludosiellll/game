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
) {
    /** [s] with these edits back in and nothing put aside any more. */
    fun editsInto(s: Settings): Settings =
        s.copy(swaps = swaps, sets = sets, removed = removed, added = added, reps = reps, rest = rest, ownPlan = null)

    companion object {
        /** The own plan of [profile] and [program] with the edits in [s]. */
        fun of(profile: Profile, program: Program, s: Settings) =
            OwnPlan(profile, program, s.swaps, s.sets, s.removed, s.added, s.reps, s.rest)
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

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
