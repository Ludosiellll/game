package forma.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import forma.core.model.Profile
import forma.core.plan.Program
import forma.core.run.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
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
    /** Per-exercise load step overrides, kg. */
    val steps: Map<String, Double> = emptyMap(),
)

/** Lifting state that is not per exercise. */
@Serializable
data class LiftState(
    /** Monday (epoch day) of a lighter week the app scheduled itself after repeated setbacks. */
    val reactiveDeloadStart: Long? = null,
)

private val Context.store by preferencesDataStore("forma")

class SettingsStore(private val context: Context) {
    private object Keys {
        val profile = stringPreferencesKey("profile")
        val program = stringPreferencesKey("program")
        val runState = stringPreferencesKey("run_state")
        val settings = stringPreferencesKey("settings")
        val lift = stringPreferencesKey("lift_state")
    }

    private fun <T> read(key: Preferences.Key<String>, s: KSerializer<T>): Flow<T?> =
        context.store.data.map { p -> p[key]?.let { runCatching { AppJson.decodeFromString(s, it) }.getOrNull() } }

    private suspend fun <T> write(key: Preferences.Key<String>, s: KSerializer<T>, value: T) {
        context.store.edit { it[key] = AppJson.encodeToString(s, value) }
    }

    val profile: Flow<Profile?> = read(Keys.profile, Profile.serializer())
    val program: Flow<Program?> = read(Keys.program, Program.serializer())
    val runState: Flow<RunState?> = read(Keys.runState, RunState.serializer())
    val settings: Flow<Settings> = read(Keys.settings, Settings.serializer()).map { it ?: Settings() }
    val liftState: Flow<LiftState> = read(Keys.lift, LiftState.serializer()).map { it ?: LiftState() }

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
        context.store.edit { it.clear() }
    }
}
