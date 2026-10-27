package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.CoachTalk
import forma.app.data.Feel
import forma.app.data.FormaDatabase
import forma.app.data.LiftState
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.app.sync.CoachApi
import forma.app.sync.CoachCall
import forma.app.sync.CoachReply
import forma.app.sync.CoachTurn
import forma.app.ui.screens.coachContext
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.Lap
import forma.core.run.LapEdit
import forma.core.run.RunLog
import forma.core.run.RunResult
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coach's fourth review (an independent agent): what the model reads of today, a kept review after the facts changed,
 * the way back from a swap, the working weight in a lighter week, the longest run, a declined card in the history. Each
 * failed on 8c88b17.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachFourthReviewTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 26 Oct 2026. */
    private val day = LocalDate.of(2026, 10, 26)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class Fake(val answer: String) : CoachApi {
        var asked = 0
        override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray): CoachReply {
            asked++
            return CoachReply(answer)
        }
    }

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-fourth-review")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 70.0, lapMeters = 230, runGoal = RunGoal(1500, 330),
            ),
        )
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 4, easyMinutes = 30, lastRunDay = day.toEpochDay() - 2))
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun ready(c: CoachCall): CoachActions.Ready {
        val checked = CoachActions.prepare(repo, c)
        if (checked is CoachActions.Refused) kotlin.test.fail("$c refused: ${checked.why}")
        return assertIs<CoachActions.Ready>(checked, c.toString())
    }

    /** A plan exercise with an external weight in one rep range only, its slot set to [kg]. */
    private suspend fun weighted(kg: Double): CoachActions.Place {
        val all = CoachActions.places(repo)
        val p = all.first { p ->
            !p.added && Catalog[p.exerciseId].load == LoadKind.EXTERNAL && all.filter { it.exerciseId == p.exerciseId }.map { it.tuned.key }.distinct().size == 1
        }
        db.state().upsertSlot(SlotStateEntity(p.tuned.key, p.tuned.exerciseId, kg, p.tuned.repMin, 0, 0, 3, clock))
        return p
    }

    // ---------- 1. «Вернуть» of a swap that demoted a heavy lift loses the person's own sets ----------

    @Test fun undoOfADemotingSwapKeepsTheOwnSets() = runBlocking<Unit> {
        // A compound main/secondary lift the person set their own sets on.
        val p = CoachActions.places(repo).first { p ->
            !p.added && Catalog[p.exerciseId].compound && Catalog[p.exerciseId].load == LoadKind.EXTERNAL && p.planned.role != SlotRole.ACCESSORY
        }
        val own = p.tuned.sets + 1
        repo.tunePlan(p.key, p.planned, own, p.tuned.repMin to p.tuned.repMax, p.tuned.restSec)
        assertEquals(own, repo.store.settingsNow().sets[p.key])
        // The coach swaps it for a single-joint exercise (the swap "demotes": 3 × 10–15, own numbers dropped) and the
        // person accepts, then taps «Вернуть как было».
        val to = Catalog.all.first { !it.compound && it.load == LoadKind.EXTERNAL && !it.archived && it.id != p.exerciseId }
        val e = CoachActions.make(repo, ready(call(CoachActions.SWAP, "exercise" to p.exerciseId, "new_exercise" to to.id)))
        assertEquals(to.id, repo.store.settingsNow().swaps[p.planId])
        assertTrue(CoachActions.undo(repo, e))
        assertNull(repo.store.settingsNow().swaps[p.planId], "the swap is back")
        assertEquals(own, repo.store.settingsNow().sets[p.key], "«Вернуть» must put back the person's own $own sets of «${Catalog[p.exerciseId].name}»")
    }

    // ---------- 2. A kept review outlives the run it reviewed ----------

    @Test fun theKeptReviewGoesWhenTheCoachRatesTheRunAgain() = runBlocking<Unit> {
        val w = repo.runFor(SessionKind.RUN_EASY, day)
        val laps = listOf(76.0, 75.0, 74.0, 73.0).mapIndexed { i, s -> Lap(s, number = i + 1) }
        val (id, _) = repo.saveRun(w, RunResult(1.0, 4, 1800, distanceM = 4 * 230.0, laps = laps), clock, emptyList(), day)
        val api = Fake("Оценка 4 — легко, как и должно быть: следующий лёгкий будет на 3 мин длиннее.")
        assertEquals(api.answer, CoachTalk.review(repo, api, id, "разбери") { "" }.await())
        // The coach itself (rate_workout, AUTO) rates the same run 8: the app decides it again (shorter next time).
        CoachActions.make(repo, ready(call(CoachActions.RATE, "which" to "last_run", "rpe" to 8)))
        assertEquals(8, repo.db.workouts().get(id)!!.rpe)
        // The card still shows the old words about a run rated 4 and a longer next run.
        assertNull(repo.store.coachReview(id), "the kept review of a run rated 4 is still shown after the coach rated it 8")
    }

    @Test fun theKeptReviewGoesWhenTheLapsArePutRight() = runBlocking<Unit> {
        // A double press: 76 s split as 6 s and 70 s.
        val laps = listOf(76.0, 6.0, 70.0, 75.0, 74.0).mapIndexed { i, s -> Lap(s, number = i + 1) }
        val id = repo.saveRun(repo.freeRun(230), RunResult(1.0, 5, laps.sumOf { it.seconds }.toInt(), distanceM = 5 * 230.0, laps = laps), clock, emptyList(), day).first
        val api = Fake("Круги от 6 с до 76 с — очень неровно, второй круг слишком быстрый.")
        assertEquals(api.answer, CoachTalk.review(repo, api, id, "разбери") { "" }.await())
        // The person merges the two halves in «История → поправить круги».
        repo.editRunLaps(id, LapEdit.merge(laps, 1))
        assertEquals(4, forma.app.data.AppJson.decodeFromString(forma.app.data.RunSummary.serializer(), repo.db.workouts().get(id)!!.summaryJson!!).laps.size)
        assertNull(repo.store.coachReview(id), "the review of the laps before the fix is still shown")
        // Shown again, the card asks again, of the laps as they are now.
        CoachTalk.review(repo, api, id, "разбери") { "" }.await()
        assertEquals(2, api.asked)
    }

    // ---------- 3. The summary sent to the model says nothing of today's «Так себе» / «Болею» / pause ----------

    /** A day of this week with a gym session, as "today". */
    private suspend fun liftDay(): LocalDate {
        val program = repo.store.programNow()!!
        return (0L..6L).map { day.plusDays(it) }.first { d -> program.week.on(d.dayOfWeek.value).any { it.kind == SessionKind.LIFT } }
    }

    private fun head(ctx: String) = ctx.substringBefore("\n\n")

    @Test fun theContextSaysTodaysLowFeel() = runBlocking<Unit> {
        val today = liftDay()
        repo.updateSettings { it.withFeel(today, Feel.LOW) }
        val ctx = coachContext(repo, today)
        // The rules text always holds the words «Так себе»: only the head (today) counts.
        assertTrue("так себе" in head(ctx).lowercase(), "today's «Так себе» is not in what the model reads:\n" + head(ctx))
    }

    @Test fun theContextShowsTheLighterSessionOfALowDay() = runBlocking<Unit> {
        val today = liftDay()
        repo.updateSettings { it.withFeel(today, Feel.LOW) }
        val t = repo.store.programNow()!!.week.on(today.dayOfWeek.value).first { it.kind == SessionKind.LIFT }.templateId!!
        // What the Today screen shows and starts for a «Так себе» day (Today.kt: forceDeload = feel == LOW).
        val light = repo.liftPlan(t, today, forceDeload = true)
        val normal = repo.liftPlan(t, today)
        val item = light.items.first { it.prescription.sets != normal.items.first { n -> n.exerciseId == it.exerciseId }.prescription.sets }
        val h = head(coachContext(repo, today))
        assertTrue("${item.name} ${item.prescription.sets}×" in h, "the model is told the full session, the person sees the lighter one:\n$h")
    }

    @Test fun theContextSaysSickToday() = runBlocking<Unit> {
        val today = liftDay()
        repo.updateSettings { it.withFeel(today, Feel.SICK) }
        val h = head(coachContext(repo, today))
        assertTrue(Regex("боле(ю|ет|н)").containsMatchIn(h.lowercase()), "today's «Болею» is not in what the model reads:\n$h")
    }

    // ---------- 6. The model keeps reading a declined card as one still waiting for «Принять» ----------

    @Test fun theHistoryTellsTheModelADeclinedChangeIsDeclined() = runBlocking<Unit> {
        val p = CoachActions.places(repo).first { !it.added }
        val api = object : CoachApi {
            override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray) =
                CoachReply("Предлагаю на подход больше.", listOf(call(CoachActions.SETS, "exercise" to p.exerciseId, "sets" to p.tuned.sets + 1)))
        }
        val msg = CoachTalk.ask(repo, api, "подходов больше?") { "" }
        CoachTalk.decline(repo, msg.id, 0)
        assertEquals(forma.app.data.CoachActState.DECLINED, repo.store.coachChatNow().last().acts[0].state)
        val said = CoachTalk.history(repo.store.coachChatNow()).last().text
        assertTrue("ждёт «Принять»" !in said, "the next question goes to the model with: $said")
        assertTrue("человек отказался" in said, said)
    }

    @Test fun theContextSaysNoWorkoutOnAPauseDay() = runBlocking<Unit> {
        val today = liftDay()
        repo.pause(today, today.plusDays(3))
        assertTrue(repo.paused(repo.store.settingsNow(), today))
        val h = head(coachContext(repo, today))
        assertTrue("пауз" in h.lowercase() || "По плану сегодня:" !in h, "a paused day is told as a training day:\n$h")
    }

    // ---------- 4. In a lighter week the model is told the lighter weight as "the next weight", never the working one ----------

    @Test fun theContextSaysTheWorkingWeightInALighterWeek() = runBlocking<Unit> {
        val p = weighted(60.0)
        // A lighter week the app set itself (reactive deload): the session's weights are 10 % lighter.
        repo.store.setLiftState(LiftState(reactiveDeloadStart = day.toEpochDay()))
        val ctx = coachContext(repo, day)
        assertTrue("облегчённая" in ctx.lines().first { "(id ${p.templateId})" in it })
        val line = ctx.lines().first { "[${p.exerciseId}]" in it }
        // set_working_weight compares with 60 kg; the model reads only the lighter number.
        assertTrue(" 60 кг" in line, "the working weight (60 kg) the levers reason from is not said:\n$line")
    }

    // ---------- 5. "Самая длинная за 30 дней" in the brief counts today's run; the easy-run check does not ----------

    @Test fun theLongestRunTheBriefNamesIsTheOneTheEasyRunCheckUses() = runBlocking<Unit> {
        val st = repo.store.runStateNow()
        repo.store.setRunState(st.copy(recent = listOf(RunLog(day.toEpochDay() - 10, 30), RunLog(day.toEpochDay(), 40))))
        val ctx = coachContext(repo, day)
        assertTrue("самая длинная за 30 дней 40 мин" in ctx, ctx.lines().first { "Лёгкий бег" in it })
        // The model, told 40 min, asks for 35 (within +15 % of 30 and under 110 % of 40): refused for "110 % of the longest".
        ready(call(CoachActions.EASY, "minutes" to 35))
    }
}
