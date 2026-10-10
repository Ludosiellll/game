package forma.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import forma.core.plan.SessionKind
import forma.core.run.RunWorkout

/** Screens. Tabs are roots; everything else is pushed on top. */
sealed interface Screen {
    data object Today : Screen
    data object Plan : Screen
    data object Progress : Screen
    data object More : Screen
    data object Onboarding : Screen
    data class Workout(val workoutId: Long) : Screen
    data class LiftDone(val workoutId: Long) : Screen
    data class RunSetup(val workout: RunWorkout, val kind: SessionKind?) : Screen
    data object RunActive : Screen
    data class RunDone(val workoutId: Long) : Screen
    data class Exercise(val exerciseId: String) : Screen
    data object History : Screen
    data class HistoryItem(val workoutId: Long) : Screen
    data object Calculators : Screen
    data object Settings : Screen
    data object Profile : Screen
    data object Body : Screen
    data object Cloud : Screen
    /** [restore]: opened from the first launch to bring an existing copy back. */
    data class CloudSignIn(val restore: Boolean) : Screen
    data class Library(val mode: LibraryMode = LibraryMode.Browse) : Screen
    data object Friends : Screen
    data class Friend(val userId: String, val name: String) : Screen
}

/** What the exercise library was opened for. */
sealed interface LibraryMode {
    data object Browse : LibraryMode
    /** Replace the exercise at [position] of a running session (today or for good). */
    data class SwapInWorkout(val workoutId: Long, val position: Int, val exerciseId: String) : LibraryMode
    /** Replace a plan exercise for good; [planExerciseId] is the plan's own choice, [currentId] what is done now. */
    data class SwapInPlan(val planExerciseId: String, val currentId: String) : LibraryMode
    /** Add an exercise to a running session. */
    data class AddToWorkout(val workoutId: Long) : LibraryMode
}

class Navigator(start: Screen) {
    val stack: SnapshotStateList<Screen> = mutableStateListOf(start)
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceAll(s: Screen) { stack.clear(); stack.add(s) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    fun tab(s: Screen) { stack.clear(); stack.add(s) }
}
