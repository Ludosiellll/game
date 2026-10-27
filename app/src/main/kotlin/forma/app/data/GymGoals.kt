package forma.app.data

import forma.core.analytics.Stats
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.LiftGoal
import forma.core.model.LoadKind
import forma.core.plan.Focus
import forma.core.plan.Program
import forma.core.strength.GymGoal
import forma.core.strength.SlotRole
import forma.core.text.Ru
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** The sets, reps and rest kept under one key of the settings (a plan exercise, or one added to a day). */
@Serializable
data class KeyNumbers(val key: String, val sets: Int? = null, val reps: List<Int>? = null, val rest: Int? = null)

/**
 * The plan changes made for a goal in the gym, to take back when the goal goes: the numbers under each key before and
 * after, and the day the lift was added to (if it was). Taken back only where they are still as the goal left them.
 */
@Serializable
data class GoalPlan(val exerciseId: String, val before: List<KeyNumbers>, val after: List<KeyNumbers>, val addedTo: String? = null)

/**
 * A goal in the gym in the app ([GymGoal] is the reckoning): which lifts it can be set on, how the plan trains the lift
 * for it — a heavy day and a second, lighter day of it — and the way to it from the person's sessions.
 */
object GymGoals {
    /** A goal is a weight on a lift of many muscles with a load on it: a barbell, dumbbells or a machine. */
    fun suits(exerciseId: String): Boolean = Catalog.find(exerciseId)?.let { it.compound && it.load == LoadKind.EXTERNAL && !it.archived } == true

    /** The lifts of the plan now a goal can be set on, the main ones first. */
    suspend fun candidates(repo: TrainingRepository): List<String> =
        CoachActions.places(repo).filter { suits(it.exerciseId) }
            .sortedBy { if (it.tuned.role == SlotRole.MAIN) 0 else if (it.tuned.role == SlotRole.SECONDARY) 1 else 2 }
            .map { it.exerciseId }.distinct()

    /** The plan's changes for a goal on a lift: what each says, and the settings with them. */
    class Proposal(val lines: List<String>, val edit: (Settings) -> Settings)

    private fun numbers(s: Settings, key: String) = KeyNumbers(key, s.sets[key], s.reps[key], s.rest[key])

    private fun schemeLine(title: String, name: String, sc: GymGoal.Scheme, why: String) =
        "«$title»: $name — ${sc.sets} × ${sc.repMin}–${sc.repMax}, отдых ${Ru.clock(sc.restSec)} — $why"

    /**
     * How the plan trains [exerciseId] for a goal: its place on a main day gets heavy sets ([GymGoal.heavy]), a place
     * on another day the second, lighter day ([GymGoal.VOLUME]); with none, the lift is added to another day of the same
     * half of the body. Null when the lift is not in the plan.
     */
    fun propose(places: List<CoachActions.Place>, program: Program, exp: Experience, exerciseId: String): Proposal? {
        val mine = places.filter { it.exerciseId == exerciseId }
        if (mine.isEmpty()) return null
        val name = Catalog[exerciseId].name
        val heavy = mine.firstOrNull { it.tuned.role == SlotRole.MAIN } ?: mine.first()
        val hs = GymGoal.heavy(exp)
        // Another day with its own numbers: the same plan exercise on two days keeps one set of numbers for both.
        val second = mine.firstOrNull { it.templateId != heavy.templateId && it.key != heavy.key }
        val sharing = mine.filter { it.templateId != heavy.templateId && it.key == heavy.key }.map { it.title }.distinct()
        val lower = Catalog[exerciseId].lowerBody
        // A day of the same half of the body without the lift (an upper day for a press, a lower one for a squat).
        val other = if (second != null || sharing.isNotEmpty()) null else program.templates.firstOrNull { t ->
            t.id != heavy.templateId && mine.none { it.templateId == t.id } && (t.focus == Focus.FULL || (t.focus == Focus.LOWER) == lower)
        }
        val v = GymGoal.VOLUME
        val lines = mutableListOf(schemeLine(heavy.title, name, hs, "тяжёлый день: сильнее всего максимум растёт от тяжёлых подходов."))
        when {
            second == null && sharing.isNotEmpty() -> lines += "В «${sharing.joinToString("», «")}» тоже $name — у него в плане одни числа на все дни, поэтому и там будет тяжёлая схема. " +
                "Если тяжело восстанавливаться — замени его там похожим упражнением."
            second != null -> lines += schemeLine(second.title, name, v, "второй день: больше качественных подходов и практики движения.")
            other != null -> lines += "«${other.title}»: + $name ${v.sets} × ${v.repMin}–${v.repMax}, отдых ${Ru.clock(v.restSec)} — второй день с ним в неделе: больше качественных подходов."
            else -> lines += "Второго дня с $name в неделе нет: одна тренировка с ним — расти будет медленнее."
        }
        val edit = { s: Settings ->
            var n = TrainingRepository.withTune(s, heavy.key, heavy.planned, hs.sets, hs.repMin to hs.repMax, hs.restSec)
            if (second != null) n = TrainingRepository.withTune(n, second.key, second.planned, v.sets, v.repMin to v.repMax, v.restSec)
            if (other != null) {
                TrainingRepository.withAdded(n, other.id, exerciseId)?.let { added ->
                    val key = TrainingRepository.addedKey(other.id, exerciseId)
                    n = TrainingRepository.withTune(added, key, TrainingRepository.addedSpec(exerciseId), v.sets, v.repMin to v.repMax, v.restSec)
                }
            }
            val keys = listOfNotNull(heavy.key, second?.key, other?.let { TrainingRepository.addedKey(it.id, exerciseId) })
            n.copy(goalPlan = GoalPlan(exerciseId, keys.map { numbers(s, it) }, keys.map { numbers(n, it) }, other?.id?.takeIf { exerciseId !in s.added[it].orEmpty() }))
        }
        return Proposal(lines, edit)
    }

    /** [s] with the plan changes of the goal taken back where they are still as the goal left them. */
    fun revert(s: Settings): Settings {
        val g = s.goalPlan ?: return s
        var n = s
        val beforeBy = g.before.associateBy { it.key }
        // Each number on its own: the sets the person changed since stay theirs, the reps the goal set go back.
        for (after in g.after) {
            val key = after.key
            val b = beforeBy[key] ?: KeyNumbers(key)
            if (n.sets[key] == after.sets) n = n.copy(sets = b.sets?.let { n.sets + (key to it) } ?: (n.sets - key))
            if (n.reps[key] == after.reps) n = n.copy(reps = b.reps?.let { n.reps + (key to it) } ?: (n.reps - key))
            if (n.rest[key] == after.rest) n = n.copy(rest = b.rest?.let { n.rest + (key to it) } ?: (n.rest - key))
        }
        g.addedTo?.let { t ->
            val key = TrainingRepository.addedKey(t, g.exerciseId)
            val list = n.added[t].orEmpty()
            // Added for the goal and left as it was: it goes with the goal.
            if (g.exerciseId in list && numbers(n, key) == (beforeBy[key] ?: KeyNumbers(key))) {
                n = n.copy(added = if (list.size == 1) n.added - t else n.added + (t to list - g.exerciseId))
            }
        }
        return n.copy(goalPlan = null)
    }

    /** The goal's way now: the lift's best estimated max of each session, the person's level. Null without a goal. */
    suspend fun path(repo: TrainingRepository, goal: LiftGoal? = null): GymGoal.Path? {
        val profile = repo.store.profileNow() ?: return null
        val g = goal ?: profile.liftGoal ?: return null
        val ex = Catalog.find(g.exerciseId) ?: return null
        val rows = repo.setRows().first()
        val bw = repo.bodyWeightLookup().first()
        return GymGoal.path(g, Stats.e1rmSeries(rows, ex, bw), profile.experience, repo.today().toEpochDay())
    }

    /**
     * The test of [goal]'s max on its day: from the max the way measures ([path]), on the lift's step in this gym. Null
     * when the lift has not been done (nothing to test from).
     */
    suspend fun testDay(repo: TrainingRepository, goal: LiftGoal): GymGoal.TestDay? {
        val max = path(repo, goal)?.current ?: repo.db.state().stat(goal.exerciseId)?.e1rm ?: return null
        val step = repo.store.settingsNow().steps[goal.exerciseId] ?: Catalog[goal.exerciseId].stepAt(max)
        return GymGoal.testDay(max, GymGoal.target(goal), step)
    }

    /** The way to [goal] in words, for the screens and the coach. */
    suspend fun words(repo: TrainingRepository, goal: LiftGoal? = null): String? {
        val g = goal ?: repo.store.profileNow()?.liftGoal ?: return null
        val p = path(repo, g) ?: return null
        val ex = Catalog[g.exerciseId]
        return GymGoal.words(g, p, ex.name, ex.step, if (ex.perHand) " (каждая гантель)" else "")
    }
}
