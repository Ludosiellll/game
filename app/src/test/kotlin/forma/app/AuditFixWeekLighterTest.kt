package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.Feel
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.sync.CoachCall
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayPlan
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.SessionOrder
import forma.core.plan.WeekPlan
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audit of 24 November, the week and its lighter weeks (group C): a lighter week asked of the coach stays where it
 * was asked and is lighter in the gym without blocks too, a pause comes back without taking later changes with it, a
 * sickness in a holiday, the week's numbers, the plan copied from a friend.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixWeekLighterTest {
    private var db: FormaDatabase? = null
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 23)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(d: LocalDate) {
        clock = d.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun open(profile: Profile, name: String) = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, name)
        store.clear()
        repo = TrainingRepository(db!!, store, now = { clock++ })
        repo.completeOnboarding(profile)
    }

    @After fun tearDown() { db?.close() }

    private val denis = Profile(
        goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 73.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
    )

    private val novice = Profile(
        goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 70.0,
    )

    /** Denis's week as in the coach's tests: the second week of a 4-week block, no session in the weeks before it. */
    private fun denisWeek(name: String) = runBlocking {
        open(denis, name)
        val p = store.programNow()!!
        val t = p.templates.first()
        store.setProgram(
            p.copy(
                templates = listOf(t.copy(id = "upper_a", title = "Верх", focus = Focus.UPPER), t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER)),
                startEpochDay = monday.minusWeeks(1).toEpochDay(), createdEpochDay = monday.minusWeeks(6).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_a"))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, "upper_a"))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 12, easyMinutes = 30, lastRunDay = monday.toEpochDay() - 2))
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun ready(day: LocalDate, name: String, vararg a: Pair<String, Any>) =
        assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(name, *a), day), "$name ${a.toList()}")

    private suspend fun refused(day: LocalDate, name: String, vararg a: Pair<String, Any>) =
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(name, *a), day), "$name ${a.toList()}")

    // ---------- the lighter week asked of the coach ----------

    /**
     * lighter-week-asked-is-moved, coach-lighter-week-reverted: two short weeks behind, «сделай эту неделю лёгкой» taken,
     * and «Сегодня» opens (it moves a lighter week the block did not earn): the week asked for stays lighter, «Вернуть» works.
     */
    @Test fun aLighterWeekAskedForThisWeekStaysWhenTodayOpens() = runBlocking<Unit> {
        denisWeek("audit-c-lighter-this")
        val entry = CoachActions.make(repo, ready(monday, CoachActions.LIGHTER, "when" to "this"))
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
        repo.moveUnearnedLighterWeek(monday)
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday), "the week asked for is not moved on")
        assertNull(store.liftStateNow().deloadMovedFrom)
        assertTrue(CoachActions.undo(repo, entry), "taken back as it was made")
        assertFalse(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
        assertNull(store.liftStateNow().askedDeloadStart)
    }

    /** lighter-week-asked-is-moved: the same with next week asked for, checked on its Monday. */
    @Test fun aLighterWeekAskedForNextWeekStaysOnItsMonday() = runBlocking<Unit> {
        denisWeek("audit-c-lighter-next")
        val next = monday.plusWeeks(1)
        val entry = CoachActions.make(repo, ready(monday, CoachActions.LIGHTER, "when" to "next"))
        at(next)
        repo.moveUnearnedLighterWeek(next)
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, next), "the week asked for is not moved on")
        assertNull(store.liftStateNow().deloadMovedFrom)
        assertTrue(CoachActions.undo(repo, entry))
        assertNull(store.liftStateNow().askedDeloadStart)
    }

    /**
     * lighter-week-asked-is-moved: on Monday the app moved the planned lighter week on (the block mostly missed); «сделай эту
     * неделю лёгкой» then: the week is lighter, stays so, and the cycle card says a lighter week, not «сдвинулась».
     */
    @Test fun aLighterWeekAskedForAfterTheAppMovedItStaysAndSaysSo() = runBlocking<Unit> {
        denisWeek("audit-c-lighter-after-move")
        store.setProgram(store.programNow()!!.copy(startEpochDay = monday.minusWeeks(3).toEpochDay()))
        repo.moveUnearnedLighterWeek(monday)
        assertEquals(monday.toEpochDay(), store.liftStateNow().deloadMovedFrom, "the precondition: moved on")
        val entry = CoachActions.make(repo, ready(monday, CoachActions.LIGHTER, "when" to "this"))
        repo.moveUnearnedLighterWeek(monday)
        val p = store.programNow()!!
        assertTrue(ProgramGenerator.isDeloadWeek(p, monday))
        val lift = store.liftStateNow()
        assertNull(lift.deloadMovedFrom)
        val words = forma.app.ui.screens.cycleWords(p, monday, lift.reactiveDeloadStart, lift.deloadMovedFrom)!!.second
        assertTrue(words.startsWith("лёгкая неделя."), words)
        // Taken back: as the app had left it, moved on.
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(monday.toEpochDay(), store.liftStateNow().deloadMovedFrom)
        assertFalse(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
    }

    /**
     * novice-lighter-week-gym-unchanged, novice-lighter-week-no-gym: a novice has no blocks; the lighter week asked for is
     * lighter in the gym all the same, as the card says, and asked again it is refused.
     */
    @Test fun aNovicesLighterWeekIsLighterInTheGymToo() = runBlocking<Unit> {
        open(novice, "audit-c-lighter-novice")
        val program = store.programNow()!!
        assertEquals(0, program.blockWeeks)
        val lift = program.week.days.flatMap { it.sessions }.first { it.kind == SessionKind.LIFT }.templateId!!
        assertFalse(repo.liftPlan(lift, monday).deload)
        val r = ready(monday, CoachActions.LIGHTER, "when" to "this")
        assertFalse("блок" in r.text, "no blocks for a novice: ${r.text}")
        val entry = CoachActions.make(repo, r)
        assertTrue(repo.liftPlan(lift, monday).deload, "half the sets and lighter weights, as the card says")
        assertTrue(repo.liftPlan(lift, monday.plusDays(4)).deload)
        assertFalse(repo.liftPlan(lift, monday.plusWeeks(1)).deload, "only that week")
        refused(monday, CoachActions.LIGHTER, "when" to "this")
        assertTrue(CoachActions.undo(repo, entry))
        assertFalse(repo.liftPlan(lift, monday).deload)
    }

    // ---------- «Вернуть» on a pause ----------

    /**
     * undo-pause-restores-stale-block: a pause of three days on Monday, a lighter week asked for on Tuesday, «Вернуть» on
     * the pause on Wednesday: the pause goes, the lighter week set since stays.
     */
    @Test fun takingAPauseBackKeepsALighterWeekSetSince() = runBlocking<Unit> {
        denisWeek("audit-c-pause-back")
        val pause = CoachActions.make(repo, ready(monday, CoachActions.PAUSE, "days" to 3))
        at(monday.plusDays(1))
        CoachActions.make(repo, ready(monday.plusDays(1), CoachActions.LIGHTER, "when" to "this"))
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
        at(monday.plusDays(2))
        assertTrue(CoachActions.undo(repo, pause))
        assertFalse(repo.paused(store.settingsNow(), monday.plusDays(2)), "the pause is taken back")
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday), "the lighter week set since stays")
        assertEquals(monday.toEpochDay(), store.liftStateNow().askedDeloadStart)
    }

    /** undo-pause-restores-stale-block: what the pause itself moved (a long one restarts the block) still comes back. */
    @Test fun aLongPauseTakenBackPutsTheBlockAndTheLighterWeekBack() = runBlocking<Unit> {
        denisWeek("audit-c-pause-long")
        CoachActions.make(repo, ready(monday, CoachActions.LIGHTER, "when" to "next"))
        val start = store.programNow()!!.startEpochDay
        val pause = CoachActions.make(repo, ready(monday, CoachActions.PAUSE, "days" to 10))
        assertNull(store.liftStateNow().askedDeloadStart, "ten days away are rest enough")
        assertTrue(CoachActions.undo(repo, pause))
        assertEquals(start, store.programNow()!!.startEpochDay)
        assertEquals(monday.plusWeeks(1).toEpochDay(), store.liftStateNow().askedDeloadStart)
    }

    // ---------- sick in a holiday ----------

    /**
     * sick-pause-inside-pause-stuck: a pause of two weeks, «заболел» to the coach on its third day with a pause of three
     * days: no second pause inside the first — the card keeps the holiday's end, and «Вернуться сегодня» ends it.
     */
    @Test fun sickInAHolidayPutsNoPauseInsideIt() = runBlocking<Unit> {
        denisWeek("audit-c-sick-holiday")
        repo.pause(monday, monday.plusDays(13))
        val sick = monday.plusDays(2)
        at(sick)
        val r = ready(sick, CoachActions.SICK, "pause_days" to 3)
        assertTrue("до ${forma.core.text.Ru.date(monday.plusDays(13))}" in r.text, r.text)
        CoachActions.make(repo, r)
        val s = store.settingsNow()
        assertEquals(forma.app.data.Feel.SICK, s.feelOn(sick))
        assertEquals(listOf(monday.toEpochDay(), monday.plusDays(13).toEpochDay()), s.pauseOn(monday.plusDays(5)), "the card's date is the holiday's end")
        val back = monday.plusDays(5)
        at(back)
        repo.resume(back)
        assertFalse(repo.paused(store.settingsNow(), back), "back today")
        assertTrue(repo.paused(store.settingsNow(), monday.plusDays(4)), "the days before stay a pause")
    }

    /**
     * sick-pause-inside-pause-stuck: a short pause left inside a holiday by the version before (the holiday among the
     * past pauses): the card says the holiday's end, not a day gone by, and «Вернуться сегодня» ends both.
     */
    @Test fun backTodayEndsEveryPauseThatHoldsTheDay() = runBlocking<Unit> {
        denisWeek("audit-c-nested-pause")
        repo.pause(monday, monday.plusDays(13))
        repo.pause(monday.plusDays(2), monday.plusDays(4))
        val back = monday.plusDays(5)
        assertEquals(monday.plusDays(13).toEpochDay(), store.settingsNow().pauseOn(back)?.get(1))
        at(back)
        repo.resume(back)
        val s = store.settingsNow()
        assertFalse(repo.paused(s, back), "back today")
        assertFalse(repo.paused(s, monday.plusDays(10)))
        assertTrue(repo.paused(s, monday.plusDays(3)), "the days before stay a pause")
    }

    /**
     * sick-pause-inside-pause-stuck: «Болею» in a holiday taken back («Снять отметку «Болею»»): the mark goes, the
     * holiday stays — its days are not made missed, and the block does not start again this week.
     */
    @Test fun notSickInAHolidayKeepsTheHoliday() = runBlocking<Unit> {
        denisWeek("audit-c-not-sick-holiday")
        repo.pause(monday, monday.plusDays(13))
        val sick = monday.plusDays(2)
        at(sick)
        CoachActions.make(repo, ready(sick, CoachActions.SICK, "pause_days" to 0))
        val start = store.programNow()!!.startEpochDay
        assertFalse(repo.sickPauseOn(store.settingsNow(), monday.plusDays(3)), "a holiday, not the sickness's pause")
        at(monday.plusDays(3))
        repo.notSick(monday.plusDays(3))
        val s = store.settingsNow()
        assertEquals(forma.app.data.Feel.UNSET, s.feelOn(sick))
        assertTrue(repo.paused(s, monday.plusDays(3)) && repo.paused(s, monday.plusDays(13)), "the holiday stays")
        assertEquals(start, store.programNow()!!.startEpochDay, "the block starts after the holiday, as set")
    }

    // ---------- the week's numbers ----------

    /**
     * week-count-before-plan: other days in the seventh week of the plan: the week is laid out again and catch-up starts
     * today, but «Неделя 7» stays «Неделя 7».
     */
    @Test fun otherDaysKeepTheWeekNumber() = runBlocking<Unit> {
        denisWeek("audit-c-week-number")
        val wednesday = monday.plusDays(2)
        val before = ProgramGenerator.programWeek(store.programNow()!!, wednesday)
        assertEquals(7, before)
        at(wednesday)
        repo.updateProfile(store.profileNow()!!.copy(trainingDays = setOf(1, 3, 5, 6)))
        val p = store.programNow()!!
        assertEquals(wednesday.toEpochDay(), p.createdEpochDay, "the new week's catch-up starts today")
        assertEquals(before, ProgramGenerator.programWeek(p, wednesday))
        assertEquals(before + 1, ProgramGenerator.programWeek(p, monday.plusWeeks(1)))
    }

    /**
     * day-screen-order-says-trial: a trial due, today Tuesday: it is this Thursday's. Next Thursday's order card speaks of
     * the hard run that comes after it — as that day's run card does — not of a trial.
     */
    @Test fun aLaterDoubleDaysOrderIsNotTheTrial() = runBlocking<Unit> {
        denisWeek("audit-c-order-trial")
        store.setRunState(store.runStateNow().copy(vdot = 47.0, testSoon = true, lastRunDay = null))
        val tuesday = monday.plusDays(1)
        at(tuesday)
        val thursday = monday.plusDays(3)
        assertEquals(thursday, repo.trialDay(tuesday))
        assertEquals(SessionOrder.Run.TRIAL, repo.dayOrder(thursday)!!.day.run)
        assertEquals(SessionOrder.Run.HARD, repo.dayOrder(thursday.plusWeeks(1))!!.day.run)
    }

    // ---------- after being sick ----------

    private suspend fun done(d: LocalDate, kind: String, template: String) {
        val at = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db!!.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = kind, templateId = template, title = template, startedAt = at, finishedAt = at + 3_600_000,
                status = Status.DONE, rpe = 5, durationSec = 3600, load = 300.0,
            ),
        )
    }

    /**
     * after-sick-banner-other-side: a «Только бег» runner back from being sick, her two easy runs done: no first gym session
     * to make lighter, nothing left to say; a «Только зал» plan has no easy runs to wait for.
     */
    @Test fun afterSickSaysOnlyWhatThePlanHas() = runBlocking<Unit> {
        open(novice.copy(aim = Aim(LiftFocus.NONE, RunFocus.ENDURANCE, Emphasis.RUN)), "audit-c-sick-runner")
        assertTrue(store.programNow()!!.week.days.none { d -> d.sessions.any { it.kind == SessionKind.LIFT } })
        store.editSettings { it.withFeel(monday, Feel.SICK) }
        done(monday.plusDays(2), Kind.RUN, "EASY")
        done(monday.plusDays(4), Kind.RUN, "EASY")
        assertEquals(0, repo.easyAfterSick(monday.plusDays(10)))
        assertFalse(repo.liftAfterSick(monday.plusDays(10)), "no gym in the plan, nothing to make lighter")

        db?.close()
        open(novice.copy(aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM)), "audit-c-sick-lifter")
        val program = store.programNow()!!
        assertTrue(program.week.days.none { d -> d.sessions.any { it.isRun } })
        store.editSettings { it.withFeel(monday, Feel.SICK) }
        done(monday.plusDays(2), Kind.LIFT, program.templates.first().id)
        assertFalse(repo.liftAfterSick(monday.plusDays(10)))
        assertEquals(0, repo.easyAfterSick(monday.plusDays(10)), "no runs in the plan to go easy")
    }

    // ---------- «Мышцы за 7 дней» ----------

    /**
     * muscles-so-so-day-counted-full: «Так себе» on Tuesday, «Верх» done as the app gave it (half the sets): the day's plan
     * is the session as given, so the muscles read as planned — not «Мало» for a week.
     */
    @Test fun aSoSoGymDayIsPlannedAsItWasGiven() = runBlocking<Unit> {
        denisWeek("audit-c-muscles-so-so")
        val tuesday = monday.plusDays(1)
        at(tuesday)
        store.editSettings { it.withFeel(tuesday, Feel.LOW) }
        val id = repo.startLift("upper_a", tuesday, forceDeload = true)
        val plan = repo.planOf(db!!.workouts().get(id)!!)!!
        assertTrue(plan.deload)
        for (item in plan.items) repeat(item.prescription.sets) {
            repo.logSet(id, item, item.prescription.weight ?: 20.0, item.prescription.reps, item.prescription.rir)
        }
        repo.finishLift(id, 6)
        val given = Muscle.entries.associateWith { 0.0 }.toMutableMap()
        for (item in plan.items) {
            val ex = Catalog[item.exerciseId]
            ex.primary.forEach { given[it] = given.getValue(it) + item.prescription.sets }
            ex.secondary.forEach { given[it] = given.getValue(it) + item.prescription.sets * 0.5 }
        }
        assertEquals(given.toMap(), repo.plannedSets(tuesday, tuesday))
        // A «Так себе» day not trained yet asks for the lighter session too.
        val thursday = monday.plusDays(3)
        store.editSettings { it.withFeel(thursday, Feel.LOW) }
        assertEquals(repo.plannedSets(tuesday, tuesday), repo.plannedSets(thursday, thursday))
    }

    // ---------- a friend's program ----------

    /**
     * gaps:gym-6: Egor trains with Denis on Denis's program. Taken, it brings the machine steps Denis set (a stack 9, 14,
     * 19 …: шаг 5 от 9) for its exercises; taken again two weeks later (Denis changed something), Egor's block and its
     * lighter week go on as they were, «Неделя N» too.
     */
    @Test fun aFriendsProgramBringsItsStepsAndTakenAgainKeepsTheCycle() = runBlocking<Unit> {
        open(
            Profile(
                goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 80.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
            "audit-c-copy-again",
        )
        val his = ProgramGenerator.generate(denis, monday.minusWeeks(8))
        val machine = his.templates.flatMap { it.slots }.first().exerciseId
        val other = his.templates.flatMap { it.slots }.map { it.exerciseId }.first { it != machine }
        val theirs = TrainingRepository.shareable(his, forma.app.data.Settings(steps = mapOf(machine to 5.0, "not_in_his_plan" to 2.0), stepsFrom = mapOf(machine to 9.0)))
        // Egor's «шаг 4 от нуля» on Denis's stack of 9, 14, 19 gives way to Denis's; his own step of another exercise of
        // the program stays his own when Denis has none for it.
        repo.updateSettings { it.copy(steps = it.steps + (machine to 4.0) + (other to 1.25)) }
        assertTrue(repo.copyProgram(theirs, "Денис"))
        val s = store.settingsNow()
        assertEquals(5.0, s.steps[machine])
        assertEquals(9.0, s.stepsFrom[machine])
        assertEquals(1.25, s.steps[other])
        assertNull(s.steps["not_in_his_plan"], "only the steps of the exercises taken")
        val copied = store.programNow()!!
        assertEquals(5, copied.blockWeeks, "Egor's own block length")
        // Two weeks on, the same program taken again: the block goes on, its lighter week where it was.
        val later = monday.plusWeeks(2).plusDays(5)
        at(later)
        assertTrue(repo.copyProgram(theirs, "Денис"))
        val again = store.programNow()!!
        assertEquals(copied.startEpochDay, again.startEpochDay)
        assertEquals(ProgramGenerator.programWeek(copied, later), ProgramGenerator.programWeek(again, later))
        assertEquals(later.toEpochDay(), again.createdEpochDay, "what the new copy held earlier this week was never asked")
    }
}
