package forma.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import forma.app.data.AppJson
import forma.core.plan.SessionKind
import forma.core.run.RunWorkout
import kotlinx.serialization.Serializable

/** Screens. Tabs are roots; everything else is pushed on top. Serializable: the back stack survives rotation. */
@Serializable
sealed interface Screen {
    @Serializable data object Today : Screen
    @Serializable data object Plan : Screen
    @Serializable data object Progress : Screen
    @Serializable data object More : Screen
    @Serializable data object Onboarding : Screen
    @Serializable data class Workout(val workoutId: Long) : Screen
    @Serializable data class LiftDone(val workoutId: Long) : Screen
    @Serializable data class RunSetup(val workout: RunWorkout, val kind: SessionKind?) : Screen
    @Serializable data object RunActive : Screen
    @Serializable data class RunDone(val workoutId: Long) : Screen
    /** A run done without the phone, entered by hand. */
    @Serializable data class RunManual(val workout: RunWorkout) : Screen
    /** A day of the plan in full: each session, why it is there and why on that day. */
    @Serializable data class Day(val epochDay: Long) : Screen
    @Serializable data class Exercise(val exerciseId: String) : Screen
    @Serializable data object History : Screen
    @Serializable data class HistoryItem(val workoutId: Long) : Screen
    @Serializable data object Calculators : Screen
    @Serializable data object Settings : Screen
    @Serializable data object Profile : Screen
    @Serializable data object Body : Screen
    @Serializable data object Cloud : Screen
    /** [restore]: opened from the first launch to bring an existing copy back. */
    @Serializable data class CloudSignIn(val restore: Boolean) : Screen
    @Serializable data class Library(val mode: LibraryMode = LibraryMode.Browse) : Screen
    @Serializable data object Friends : Screen
    @Serializable data class Friend(val userId: String, val name: String) : Screen
    @Serializable data class Chat(val userId: String, val name: String) : Screen
    /** «Идеальный план»: the gym plan as it is and as it could be, changes on choice. */
    @Serializable data object IdealPlan : Screen
    @Serializable data object MyExercises : Screen
    @Serializable data object GymGoal : Screen
    /** The first look round the app; [first] — right after the questionnaire (then the main screen follows). */
    @Serializable data class Tour(val first: Boolean) : Screen
    @Serializable data object Help : Screen
    @Serializable data class Coach(val question: String? = null, val workoutId: Long? = null) : Screen
}

/** What the exercise library was opened for. */
@Serializable
sealed interface LibraryMode {
    @Serializable data object Browse : LibraryMode
    /** Replace the exercise at [position] of a running session (today or for good). */
    @Serializable data class SwapInWorkout(val workoutId: Long, val position: Int, val exerciseId: String) : LibraryMode
    /** Replace a plan exercise for good; [planExerciseId] is the plan's own choice, [currentId] what is done now. */
    @Serializable data class SwapInPlan(val planExerciseId: String, val currentId: String) : LibraryMode
    /** Add an exercise to a running session (today or for good). */
    @Serializable data class AddToWorkout(val workoutId: Long) : LibraryMode
    /** Add an exercise to every session of a plan's template. */
    @Serializable data class AddToPlan(val templateId: String) : LibraryMode
    /** Replace an exercise added to a template for good. */
    @Serializable data class SwapAdded(val templateId: String, val exerciseId: String) : LibraryMode
}

/** One screen in the back stack; [id] keys its saved state (two entries of the same screen keep their own). */
@Serializable
data class NavEntry(val id: Long, val screen: Screen)

class Navigator private constructor(entries: List<NavEntry>, private var nextId: Long) {
    constructor(start: Screen) : this(listOf(NavEntry(0, start)), 1)

    val stack: SnapshotStateList<NavEntry> = mutableStateListOf<NavEntry>().apply { addAll(entries) }
    val currentEntry: NavEntry get() = stack.last()
    val current: Screen get() = stack.last().screen
    val canGoBack: Boolean get() = stack.size > 1

    private fun entry(s: Screen) = NavEntry(nextId++, s)

    fun push(s: Screen) { stack.add(entry(s)) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceAll(s: Screen) { stack.clear(); stack.add(entry(s)) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = entry(s) }
    fun tab(s: Screen) { stack.clear(); stack.add(entry(s)) }

    companion object {
        @Serializable
        private data class Saved(val entries: List<NavEntry>, val nextId: Long)

        /** The whole back stack as one string in the saved instance state. */
        val Saver: Saver<Navigator, String> = Saver(
            save = { AppJson.encodeToString(Saved.serializer(), Saved(it.stack.toList(), it.nextId)) },
            restore = { text ->
                runCatching { AppJson.decodeFromString(Saved.serializer(), text) }.getOrNull()
                    ?.takeIf { it.entries.isNotEmpty() }?.let { Navigator(it.entries, it.nextId) }
            },
        )
    }
}

/**
 * A place to open asked from outside the app's screens (a notification): the root opens it, the screen it names reads
 * and clears it. "messages" — the conversations of «Люди».
 */
object Opens {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
