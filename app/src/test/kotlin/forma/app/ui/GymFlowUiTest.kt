package forma.app.ui

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.ExerciseStatEntity
import forma.app.data.FormaDatabase
import forma.app.data.LiftItem
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.app.ui.screens.ExerciseScreen
import forma.app.ui.screens.LiftDoneScreen
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.screens.recordAfter
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.strength.DoneSet
import forma.core.strength.HoldReason
import forma.core.strength.NextSet
import forma.core.strength.OneRm
import forma.core.strength.Outcome
import forma.core.text.Ru
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 18, logs sets between sets on Tue/Thu/Sat: the gym screen as found in the review of 14.10, fixed. Legacy
 * graphics: with native graphics a dialog over the workout screen never lets Compose go idle under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class GymFlowUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun stopRest() = RestTimer.stop()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true, ignoreCase = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    private val profile = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(2, 4, 6), bodyWeightKg = 72.0, birthYear = 2008,
    )

    private fun repo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also { runBlocking { it.completeOnboarding(profile) } }
    }

    /** A session whose first exercise has a working weight (no first-time search): [weight]. */
    private fun startWithWeight(repo: TrainingRepository, weight: Double = 60.0): Long = runBlocking {
        val t = repo.store.programNow()!!.templates.first()
        val first = repo.liftPlan(t.id, LocalDate.now()).items.first()
        repo.db.state().upsertSlot(SlotStateEntity(first.slot.key, first.exerciseId, weight, first.slot.repMin, 0, 0, 3, 0L, false, 0, null, null))
        repo.startLift(t.id)
    }

    private fun items(repo: TrainingRepository, id: Long) = runBlocking { repo.planOf(repo.db.workouts().get(id)!!)!!.items }
    private fun setsOf(repo: TrainingRepository, id: Long) = runBlocking { repo.db.sets().forWorkout(id) }
    private fun weightOf(item: LiftItem) = item.prescription.weight ?: item.prescription.startHint ?: 20.0
    private fun log(repo: TrainingRepository, id: Long, item: LiftItem, times: Int, weight: Double = weightOf(item)) = runBlocking {
        repeat(times) { repo.logSet(id, item, weight, item.prescription.reps, item.prescription.rir) }
    }

    private fun show(repo: TrainingRepository, nav: Navigator = Navigator(Screen.Today), content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    /** A12: back to an exercise with sets, the fields start from its last set and the next set is worked out again. */
    @Test fun backToAnExerciseItStartsFromItsLastSet() {
        val repo = repo("gym-resume")
        val id = startWithWeight(repo)
        val first = items(repo, id).first()
        val done = weightOf(first) + 5.0
        log(repo, id, first, 1, done)
        show(repo) { WorkoutScreen(id) }
        assertTrue(texts("Следующий подход:").isNotEmpty(), "the next set is said again")
        assertTrue(texts(Ru.kg(done)).isNotEmpty(), "the weight field holds the last set's weight")
        compose.onNodeWithText("Подход 2 из", substring = true, ignoreCase = true).assertExists()
    }

    /** A13 and the undo: the next exercise is found after the current one, then from the start; "Отменить" takes its hint back. */
    @Test fun theNextExerciseWrapsAroundAndUndoTakesItsHintBack() {
        val repo = repo("gym-wrap")
        val id = startWithWeight(repo)
        val all = items(repo, id)
        val first = all.first()
        val last = all.last()
        all.drop(1).dropLast(1).forEach { log(repo, id, it, it.prescription.sets) }
        log(repo, id, last, last.prescription.sets - 1)
        show(repo) { WorkoutScreen(id) }
        compose.onAllNodesWithText(last.name)[0].performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertTrue(texts("Дальше — ${first.name}").isNotEmpty(), "the open exercise before it comes next")
        compose.onNodeWithText("Подход 1 из", substring = true, ignoreCase = true).assertExists()
        assertNotNull(RestTimer.rest.value, "one exercise is still open: rest")

        compose.onNodeWithText("Отменить").performClick()
        settle()
        assertTrue(texts("Дальше —").isEmpty(), "the hint of the set taken back is gone")
        assertNull(RestTimer.rest.value)
        compose.onNodeWithText("Подход ${last.prescription.sets} из", substring = true, ignoreCase = true).assertExists()
    }

    /** No rest after the very last set of the session. */
    @Test fun noRestAfterTheLastSetOfTheSession() {
        val repo = repo("gym-last")
        val id = startWithWeight(repo)
        val all = items(repo, id)
        all.dropLast(1).forEach { log(repo, id, it, it.prescription.sets) }
        log(repo, id, all.last(), all.last().prescription.sets - 1)
        show(repo) { WorkoutScreen(id) }
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertEquals(all.sumOf { it.prescription.sets }, setsOf(repo, id).size)
        assertNull(RestTimer.rest.value, "nothing to rest for")
    }

    /** A14: a swap at a position takes the "Дальше — …" hint of the exercise that was there with it. */
    @Test fun aSwapTakesTheHintOfTheExerciseBefore() {
        val repo = repo("gym-swap-hint")
        val id = startWithWeight(repo)
        val (first, second) = items(repo, id).let { it[0] to it[1] }
        log(repo, id, first, first.prescription.sets - 1)
        show(repo) { WorkoutScreen(id) }
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertTrue(texts("Дальше — ${second.name}").isNotEmpty())
        val alt = repo.alternativesFor(second.exerciseId).first { it.id != second.exerciseId && !it.custom }.id
        runBlocking { assertTrue(repo.swapExercise(id, second.position, alt, forever = false)) }
        settle()
        assertTrue(texts("Дальше —").isEmpty(), "the hint was for the exercise swapped out")
    }

    /** A10: no effort chosen for the person; the exercises not started are named. */
    @Test fun theFinishDialogAsksForTheEffortAndNamesWhatWasNotStarted() {
        val repo = repo("gym-finish")
        val id = startWithWeight(repo)
        val all = items(repo, id)
        log(repo, id, all.first(), 1)
        show(repo) { WorkoutScreen(id) }
        compose.onNodeWithText("Завершить", ignoreCase = true).performClick()
        settle()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        assertTrue(texts("Выбери оценку").isNotEmpty())
        assertTrue(texts("Не начаты:").single().contains(all[1].name))
        compose.onNodeWithText("9").performClick()
        settle()
        compose.onNodeWithText("Сохранить").assertIsEnabled()
    }

    /** LiftDone shows the sets done. */
    @Test fun theSummaryShowsTheSetsDone() {
        val repo = repo("gym-done")
        val id = startWithWeight(repo, 60.0)
        val first = items(repo, id).first()
        log(repo, id, first, 2)
        runBlocking { repo.finishLift(id, 7) }
        show(repo) { LiftDoneScreen(id) }
        assertTrue(texts("Сделано").isNotEmpty())
        val reps = first.prescription.reps
        assertTrue(texts(Texts.weightReps(Catalog[first.exerciseId], weightOf(first), "$reps, $reps")).isNotEmpty())
    }

    /** A plank to the stopwatch: "Засечь время", get into position, then the seconds count. */
    @Test fun aHoldIsTimedOnTheScreen() {
        val repo = repo("gym-hold")
        val id = startWithWeight(repo)
        runBlocking { assertTrue(repo.addToWorkout(id, "plank")) }
        show(repo) { WorkoutScreen(id) }
        compose.onAllNodesWithText(Catalog["plank"].name)[0].performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Засечь время", ignoreCase = true).performScrollTo().performClick()
        settle()
        assertTrue(texts("Приготовься").isNotEmpty())
        compose.onNodeWithText("Отмена", ignoreCase = true).performClick()
        settle()
        compose.onNodeWithText("Засечь время", ignoreCase = true).assertExists()
    }

    /** In-set record: a set above the exercise's best says so right away. */
    @Test fun aRecordIsSaidAfterTheSet() {
        val repo = repo("gym-record")
        val id = startWithWeight(repo, 60.0)
        val first = items(repo, id).first()
        runBlocking { repo.db.state().upsertStat(ExerciseStatEntity(first.exerciseId, 70.0, 20.0, 0L)) }
        show(repo) { WorkoutScreen(id) }
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertTrue(texts("Рекорд!").isNotEmpty())
    }

    @Test fun aRecordIsOnlyAboveTheBestAndTheSetsBefore() {
        val bench = Catalog["bench"]
        val p = forma.core.strength.Prescription(60.0, 5, 3, 2, calibrate = false)
        assertNotNull(recordAfter(bench, p, emptyList(), DoneSet(60.0, 5, 2), 70.0, 72.0))
        assertNull(recordAfter(bench, p, emptyList(), DoneSet(60.0, 5, 2), 120.0, 72.0), "below the best")
        assertNull(recordAfter(bench, p, listOf(DoneSet(62.5, 5, 2)), DoneSet(60.0, 5, 2), 70.0, 72.0), "below a set before it today")
        assertNull(recordAfter(bench, p, emptyList(), DoneSet(60.0, 5, 2), null, 72.0), "never done: nothing to beat")
    }

    /** Dumbbells: one weight, two of them — "20 кг ×2" wherever the weight is said. */
    @Test fun dumbbellsAreShownPerHand() {
        assertEquals("20 кг ×2", Texts.weight(Catalog["db_curl"], 20.0))
        assertEquals("60 кг", Texts.weight(Catalog["bench"], 60.0))
    }

    /**
     * A note on the exercise ("сиденье 4") stays with it and shows in the workout. (Typing it is not driven here: a
     * text field in a dialog never lets Compose go idle under Robolectric.)
     */
    @Test fun aNoteShowsWithTheExercise() {
        val repo = repo("gym-note")
        val id = startWithWeight(repo)
        val (first, second) = items(repo, id).let { it[0] to it[1] }
        show(repo) { WorkoutScreen(id) }
        assertTrue(texts("Заметка к упражнению").isNotEmpty(), "a note can be added")
        runBlocking { repo.updateSettings { it.copy(notes = mapOf(first.exerciseId to "сиденье 4, хват шире")) } }
        settle()
        assertTrue(texts("сиденье 4, хват шире").isNotEmpty())
        assertTrue(texts("Заметка к упражнению").isEmpty())
        compose.onAllNodesWithText(second.name)[0].performScrollTo().performClick()
        settle()
        assertTrue(texts("сиденье 4").isEmpty(), "the note belongs to its exercise")
    }

    /** After the end the rest stays, saying how long it has run over. */
    @Test fun aRestRunOverSaysByHowMuch() {
        val repo = repo("gym-over")
        val id = startWithWeight(repo)
        RestTimer.start(ctx, id, -40_000, beep = false, vibrate = false)
        show(repo) { WorkoutScreen(id) }
        assertTrue(texts("Отдых окончен").isNotEmpty())
        assertTrue(texts("+0:4").isNotEmpty())
    }

    /** Screen off, app closed by the system: the rest has a notification, an alarm at its end and comes back from its save. */
    @Test fun theRestOutlivesTheScreenAndTheProcess() {
        shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        RestTimer.start(ctx, 42, 90_000, beep = false, vibrate = false)
        val endAt = RestTimer.rest.value!!.endAt
        val alarm = assertNotNull(shadowOf(am).peekNextScheduledAlarm())
        assertEquals(endAt, alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals("Отдых", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(2, posted.actions.size, "+15 and Пропустить")

        // The system closes the app: the rest comes back from its save.
        RestTimer.forget()
        assertNull(RestTimer.rest.value)
        RestTimer.restore(ctx)
        assertEquals(endAt, RestTimer.rest.value?.endAt)

        // "+15" from the lock screen.
        RestReceiver().onReceive(ctx, android.content.Intent(ctx, RestReceiver::class.java).setAction(RestTimer.ACTION_PLUS))
        assertEquals(endAt + 15_000, RestTimer.rest.value?.endAt)

        // The alarm at the end, with the app closed again: the end is signalled once and the notification counts up.
        RestTimer.forget()
        val end = RestTimer.rest.value?.endAt ?: (endAt + 15_000)
        RestReceiver().onReceive(ctx, android.content.Intent(ctx, RestReceiver::class.java).setAction(RestTimer.ACTION_END).putExtra("end", end))
        assertTrue(RestTimer.rest.value!!.rang)
        assertEquals("Отдых окончен", shadowOf(nm).allNotifications.single().extras.getString(android.app.Notification.EXTRA_TITLE))

        RestReceiver().onReceive(ctx, android.content.Intent(ctx, RestReceiver::class.java).setAction(RestTimer.ACTION_SKIP))
        assertNull(RestTimer.rest.value)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        RestTimer.forget()
        RestTimer.restore(ctx)
        assertNull(RestTimer.rest.value, "skipped: nothing comes back")
    }

    /** A13 of the exercise screen: the rep table in this gym's step; the target says its rep range and day. */
    @Test fun theExerciseScreenUsesTheGymsStepAndSaysWhereTheTargetIs() {
        val repo = repo("gym-exercise")
        val t = runBlocking { repo.store.programNow()!!.templates.first() }
        val slot = t.slots.first { Catalog[it.exerciseId].load == forma.core.model.LoadKind.EXTERNAL }
        val ex = Catalog[slot.exerciseId]
        runBlocking {
            repo.setStep(ex.id, 5.0, ex.step)
            repo.db.state().upsertStat(ExerciseStatEntity(ex.id, 101.0, 101.0, 0L))
            repo.db.state().upsertSlot(SlotStateEntity(slot.key, ex.id, 80.0, slot.repMin, 0, 0, 3, 1L, false, 0, null, null))
        }
        show(repo) { ExerciseScreen(ex.id) }
        val three = floor(OneRm.loadFor(101.0, 3) / 5.0 + 1e-9) * 5.0
        assertTrue(texts(Texts.weight(ex, three)).isNotEmpty(), "rounded down to the 5 kg step")
        assertTrue(texts("Для ${slot.repMin}–${slot.repMax} повторов").single().contains("«${t.title}»"))
    }

    /** The plan sheet: rest up to 5:00 and a way to the exercise's history. */
    @Test fun thePlanSheetHasLongRestsAndTheHistory() {
        val repo = repo("gym-plan-sheet")
        val t = runBlocking { repo.store.programNow()!!.templates.first() }
        val slot = t.slots.first()
        val nav = Navigator(Screen.Plan)
        show(repo, nav) { PlanScreen() }
        compose.onAllNodesWithText(Catalog[slot.exerciseId].name)[0].performScrollTo().performClick()
        settle()
        compose.onNodeWithText("4:00").assertExists()
        compose.onNodeWithText("5:00").assertExists()
        compose.onNodeWithText("История и прогресс", ignoreCase = true).performScrollTo().performClick()
        settle()
        assertEquals(Screen.Exercise(slot.exerciseId), nav.current)
        assertFalse(texts("Отдых между подходами").isNotEmpty(), "the sheet closed")
    }

    /** Dumbbells with reps read once: "по 20 кг × 10", never "20 кг ×2 × 10"; the weight alone and the record keep "×2". */
    @Test fun dumbbellsWithRepsReadOnce() {
        val db = Catalog["db_curl"]
        val bench = Catalog["bench"]
        assertEquals("по 20 кг × 10", Texts.weightReps(db, 20.0, 10))
        assertEquals("по 20 кг × 10, 10, 9", Texts.weightReps(db, 20.0, "10, 10, 9"))
        assertEquals("60 кг × 5", Texts.weightReps(bench, 60.0, 5))
        assertEquals("30 с", Texts.weightReps(Catalog["plank"], 0.0, 30))
        assertEquals("30 кг ×2", Texts.oneRm(db, 30.1))
        assertEquals("100,5 кг", Texts.oneRm(bench, 100.4))
        val next = Texts.nextSet(db, NextSet(22.0, 10, 1, 4, 2, plus = false, calibrate = false))
        assertTrue(next.startsWith("Следующий подход: по 22 кг × 10."), next)
        listOf(Outcome.AddWeight(20.0, 22.0, 10), Outcome.Reset(24.0, 22.0, 10), Outcome.Hold(22.0, 10, HoldReason.NEAR_MISS)).forEach { o ->
            val t = Texts.outcome(o, db)
            assertTrue("по 22 кг × 10" in t, t)
            assertFalse("×2 ×" in t || "до по" in t, t)
        }
    }

    /** A plank has no weight and no reserve to mark: after a hold it is not asked for "сколько ещё мог". */
    @Test fun aHoldHasItsOwnHint() {
        assertEquals(
            "Следующий подход: 30 с. Удержишь во всех подходах — в следующий раз дольше.",
            Texts.nextSet(Catalog["plank"], NextSet(0.0, 30, 0, null, 2, plus = false, calibrate = false)),
        )
    }

    /** The very last set of the session can be a record too: said, though nothing comes after it. */
    @Test fun aRecordOnTheLastSetOfTheSessionIsSaid() {
        val repo = repo("gym-record-last")
        val id = startWithWeight(repo, 60.0)
        val all = items(repo, id)
        val first = all.first()
        all.drop(1).forEach { log(repo, id, it, it.prescription.sets) }
        log(repo, id, first, first.prescription.sets - 1)
        runBlocking { repo.db.state().upsertStat(ExerciseStatEntity(first.exerciseId, 20.0, 20.0, 0L)) }
        show(repo) { WorkoutScreen(id) }
        // Heavier than the sets before it today.
        repeat(2) { compose.onNodeWithContentDescription("Больше вес").performClick() }
        settle()
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertEquals(all.sumOf { it.prescription.sets }, setsOf(repo, id).size, "the last set of the session")
        assertTrue(texts("Рекорд!").isNotEmpty())
    }

    /** Each hold has its own stopwatch: one started on the plank does not carry over to the side plank. */
    @Test fun eachHoldHasItsOwnStopwatch() {
        val repo = repo("gym-holds")
        val id = startWithWeight(repo)
        runBlocking {
            assertTrue(repo.addToWorkout(id, "plank"))
            assertTrue(repo.addToWorkout(id, "side_plank"))
        }
        show(repo) { WorkoutScreen(id) }
        compose.onAllNodesWithText(Catalog["plank"].name)[0].performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Засечь время", ignoreCase = true).performScrollTo().performClick()
        settle()
        assertTrue(texts("Приготовься").isNotEmpty())
        compose.onAllNodesWithText(Catalog["side_plank"].name)[0].performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Засечь время", ignoreCase = true).assertExists()
    }

    /**
     * The end of a rest with the app not on the screen (the screen off, the phone in a pocket): a notification of its own
     * that sounds, once; none while the app is on the screen, where its own beep is heard.
     */
    @Test fun theEndOfARestBehindTheScreenSoundsByItself() {
        shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun alerts() = shadowOf(nm).allNotifications.filter { it.channelId == RestTimer.END_CHANNEL }
        fun end(at: Long) = RestReceiver().onReceive(ctx, android.content.Intent(ctx, RestReceiver::class.java).setAction(RestTimer.ACTION_END).putExtra("end", at))
        val was = RestTimer.onScreen
        try {
            RestTimer.onScreen = { false }
            RestTimer.start(ctx, 42, 60_000, beep = true, vibrate = true)
            val countdown = shadowOf(nm).allNotifications.single()
            assertTrue(countdown.timeoutAfter > 0, "the countdown goes by itself after the rest, even with the app closed")
            val endAt = RestTimer.rest.value!!.endAt
            end(endAt)
            assertEquals("Отдых окончен", alerts().single().extras.getString(android.app.Notification.EXTRA_TITLE))
            val channel = nm.getNotificationChannel(RestTimer.END_CHANNEL)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertNotNull(channel.sound, "it sounds by itself")
            // Once: the app and the alarm both getting to the end.
            nm.cancelAll()
            end(endAt)
            assertTrue(alerts().isEmpty())
            // "+15" after the end: a new end, the signal of the old one is gone.
            RestTimer.start(ctx, 42, 60_000, beep = true, vibrate = true)
            end(RestTimer.rest.value!!.endAt)
            assertEquals(1, alerts().size)
            RestReceiver().onReceive(ctx, android.content.Intent(ctx, RestReceiver::class.java).setAction(RestTimer.ACTION_PLUS))
            assertTrue(alerts().isEmpty())

            // On the screen: the app's own beep, no notification over it.
            RestTimer.onScreen = { true }
            RestTimer.start(ctx, 42, 60_000, beep = true, vibrate = true)
            end(RestTimer.rest.value!!.endAt)
            assertTrue(RestTimer.rest.value!!.rang)
            assertTrue(alerts().isEmpty())
        } finally {
            RestTimer.onScreen = was
        }
    }

    /** A rest goes with its session: one finished or deleted while the app was closed is cleared at the start, as is an old one. */
    @Test fun theRestGoesWithItsSession() {
        RestTimer.start(ctx, 42, 90_000, beep = false, vibrate = false)
        RestTimer.forget()
        runBlocking { RestTimer.restore(ctx) { true } }
        assertNotNull(RestTimer.rest.value, "the session still going: the rest comes back")
        RestTimer.forget()
        runBlocking { RestTimer.restore(ctx) { false } }
        assertNull(RestTimer.rest.value)
        RestTimer.forget()
        RestTimer.restore(ctx)
        assertNull(RestTimer.rest.value, "cleared, not only hidden")

        // Ended long ago: cleared at the start.
        RestTimer.start(ctx, 42, -(RestTimer.OVER_SHOWN_MS + 60_000), beep = false, vibrate = false)
        RestTimer.forget()
        runBlocking { RestTimer.restore(ctx) { true } }
        assertNull(RestTimer.rest.value)

        // Finished or deleted from a screen: only its own session's rest stops.
        RestTimer.start(ctx, 42, 90_000, beep = false, vibrate = false)
        RestTimer.stopFor(ctx, 7)
        assertNotNull(RestTimer.rest.value)
        RestTimer.stopFor(ctx, 42)
        assertNull(RestTimer.rest.value)
    }

    /** Exact alarms allowed in the settings during a rest: back on the screen, its end is set again, exact. */
    @Test fun exactAlarmsAllowedDuringARestSetItsEndAgain() {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            RestTimer.start(ctx, 42, 90_000, beep = false, vibrate = false)
            assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, shadowOf(am).peekNextScheduledAlarm()!!.windowLengthMs)
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            RestTimer.reschedule(ctx)
            assertEquals(ShadowAlarmManager.WINDOW_EXACT, shadowOf(am).scheduledAlarms.single().windowLengthMs)
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
        }
    }
}
