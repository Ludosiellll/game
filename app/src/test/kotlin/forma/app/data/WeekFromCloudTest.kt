package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Profile
import forma.core.plan.Program
import forma.core.plan.SessionKind
import forma.core.run.RunState
import forma.core.run.RunType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The week of 19.10 of Denis and Egor as it is in the cloud on 23.10. Both: a free run on 12.10, «Верх · Б» on 13.10,
 * «Верх · А» on 15.10, then nothing until Thursday 22.10 («Верх · А» and a run), and on Friday «Низ» and a run for
 * Saturday. Egor took Denis's program on Thursday and had one run on record, Denis two.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WeekFromCloudTest {
    private val egorProfile = """{"aim":{"run":"ENDURANCE","lift":"POWERBUILDING","emphasis":"BALANCED"},"sex":"MALE","goal":"HYBRID","name":"","runGoal":{"date":null,"meters":1500,"seconds":330},"heightCm":180,"runLevel":"BASE","birthYear":2007,"lapMeters":230,"doubleDays":true,"experience":"INTERMEDIATE","bodyWeightKg":55,"trainingDays":[1,2,4,6],"sessionMinutes":45}"""
    private val egorProgram = """{"aim":{"run":"ENDURANCE","lift":"POWERBUILDING","emphasis":"BALANCED"},"goal":"HYBRID","week":{"days":[{"day":1,"sessions":[{"kind":"RUN_QUALITY","templateId":null}]},{"day":2,"sessions":[{"kind":"LIFT","templateId":"upper_b"}]},{"day":4,"sessions":[{"kind":"RUN_QUALITY","templateId":null},{"kind":"LIFT","templateId":"upper_a"}]},{"day":6,"sessions":[{"kind":"LIFT","templateId":"lower_a"},{"kind":"RUN_EASY","templateId":null}]}],"notes":[]},"planner":2,"runCaps":{"easyMax":45,"longMax":90},"templates":[{"id":"upper_a","focus":"UPPER","slots":[{"rir":2,"role":"MAIN","sets":3,"repMax":8,"repMin":5,"restSec":150,"increment":null,"exerciseId":"chest_press"},{"rir":2,"role":"MAIN","sets":3,"repMax":8,"repMin":5,"restSec":150,"increment":null,"exerciseId":"pullup"}],"title":"Верх · А"},{"id":"lower_a","focus":"LOWER","slots":[{"rir":2,"role":"MAIN","sets":3,"repMax":8,"repMin":5,"restSec":150,"increment":null,"exerciseId":"squat"},{"rir":2,"role":"SECONDARY","sets":3,"repMax":10,"repMin":8,"restSec":120,"increment":null,"exerciseId":"leg_ext"}],"title":"Низ"},{"id":"upper_b","focus":"UPPER","slots":[{"rir":2,"role":"MAIN","sets":3,"repMax":8,"repMin":5,"restSec":150,"increment":null,"exerciseId":"machine_shoulder_press"},{"rir":2,"role":"MAIN","sets":3,"repMax":8,"repMin":5,"restSec":150,"increment":null,"exerciseId":"pullup"}],"title":"Верх · Б"}],"blockWeeks":5,"copiedFrom":"Ludosiel","experience":"INTERMEDIATE","startEpochDay":20745,"createdEpochDay":20748}"""
    private val denisProfile = """{"aim": {"run": "ENDURANCE", "lift": "POWERBUILDING", "emphasis": "BALANCED"}, "sex": "MALE", "goal": "HYBRID", "gymDays": [2, 4, 6], "runDays": [4, 6, 1], "runGoal": {"date": null, "meters": 1500, "seconds": 330}, "heightCm": 183, "runLevel": "BASE", "birthYear": 2008, "lapMeters": 230, "restingHr": 65, "doubleDays": true, "experience": "ADVANCED", "bodyWeightKg": 70.0, "trainingDays": [2, 4, 6, 1], "sessionMinutes": 75}"""
    private val zone = ZoneId.systemDefault()
    private val mon = LocalDate.of(2026, 10, 12)
    private val thu = mon.plusDays(10)
    private val fri = mon.plusDays(11)
    private val sat = mon.plusDays(12)
    private var clock = 0L
    private lateinit var db: FormaDatabase

    @After fun tearDown() { if (::db.isInitialized) db.close() }

    private fun at(d: LocalDate, h: Int = 9) { clock = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli() }
    private fun ms(d: LocalDate, h: Int = 18) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun person(name: String, egor: Boolean): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name)
        store.clear()
        at(mon.plusDays(1))
        val repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(AppJson.decodeFromString(Profile.serializer(), if (egor) egorProfile else denisProfile))
        if (egor) store.setProgram(AppJson.decodeFromString(Program.serializer(), egorProgram))
        // As on the morning of 22.10: Egor's one run on 12.10, Denis's two (24 and 2 minutes).
        val runs = if (egor) """{"runs":1,"vdot":null,"recent":[{"day":20738,"minutes":22}],"walkStage":-1,"lastRunDay":20738,"easyMinutes":25,"longMinutes":35,"qualityCount":0}"""
        else """{"runs":2,"vdot":null,"recent":[{"day":20738,"minutes":24},{"day":20738,"minutes":2}],"walkStage":-1,"lastRunDay":20738,"easyMinutes":25,"longMinutes":40,"qualityCount":0}"""
        store.setRunState(AppJson.decodeFromString(RunState.serializer(), runs))
        db.workouts().insert(WorkoutEntity(epochDay = mon.toEpochDay(), kind = Kind.RUN, templateId = "FREE", title = "Свободный бег", startedAt = ms(mon), finishedAt = ms(mon), status = Status.DONE, rpe = 7, durationSec = 1304))
        for ((d, t) in listOf(mon.plusDays(1) to "upper_b", mon.plusDays(3) to "upper_a")) {
            db.workouts().insert(WorkoutEntity(epochDay = d.toEpochDay(), kind = Kind.LIFT, templateId = t, title = t, startedAt = ms(d), finishedAt = ms(d), status = Status.DONE, rpe = 8, durationSec = 4600))
        }
        repo
    }

    /**
     * Back after 10 days without running, both on Thursday: Denis got an easy run (his trial was due and is not run cold),
     * Egor race-pace reps (no trial due yet). Now both an easy one, said why; the hard ones come back after it.
     */
    @Test fun theFirstRunAfterTheSameBreakIsEasyForBoth() = runBlocking<Unit> {
        val egor = person("cloud-egor", egor = true)
        at(thu)
        val e = egor.runFor(SessionKind.RUN_QUALITY, thu)
        assertEquals(RunType.EASY, e.type, e.title)
        assertTrue("После перерыва не начинаем с тяжёлой пробежки" in e.note, e.note)
        // Run that day: the next hard day is hard again.
        db.workouts().insert(WorkoutEntity(epochDay = thu.toEpochDay(), kind = Kind.RUN, templateId = "EASY", title = "Лёгкий бег", startedAt = ms(thu, 14), finishedAt = ms(thu, 15), status = Status.DONE, rpe = 6, durationSec = 1500))
        egor.store.setRunState(egor.store.runStateNow().copy(lastRunDay = thu.toEpochDay(), runs = 2))
        at(mon.plusDays(14))
        assertNotEquals(RunType.EASY, egor.runFor(SessionKind.RUN_QUALITY, mon.plusDays(14)).type)
        db.close()
        val denis = person("cloud-denis", egor = false)
        at(thu)
        val d = denis.runFor(SessionKind.RUN_QUALITY, thu)
        assertEquals(RunType.EASY, d.type)
        assertTrue("После перерыва не начинаем с контрольного" in d.note, d.note)
    }

    /**
     * Friday «Низ» and a run, Saturday off: they count for Saturday at once, on Friday already — not «Низ» as a catch-up of
     * Tuesday's «Верх · Б», which stays missed. Saturday shows nothing left to do.
     */
    @Test fun saturdayDoneOnFridayCountsForSaturday() = runBlocking<Unit> {
        val denis = person("cloud-ahead", egor = false)
        db.workouts().insert(WorkoutEntity(epochDay = thu.toEpochDay(), kind = Kind.LIFT, templateId = "upper_a", title = "Верх · А", startedAt = ms(thu, 12), finishedAt = ms(thu, 13), status = Status.DONE, rpe = 6, durationSec = 5600))
        db.workouts().insert(WorkoutEntity(epochDay = thu.toEpochDay(), kind = Kind.RUN, templateId = "EASY", title = "Лёгкий бег", startedAt = ms(thu, 14), finishedAt = ms(thu, 15), status = Status.DONE, rpe = 7, durationSec = 1517))
        db.workouts().insert(WorkoutEntity(epochDay = fri.toEpochDay(), kind = Kind.LIFT, templateId = "lower_a", title = "Низ", startedAt = ms(fri, 14), finishedAt = ms(fri, 15), status = Status.DONE, rpe = 5, durationSec = 2820))
        db.workouts().insert(WorkoutEntity(epochDay = fri.toEpochDay(), kind = Kind.RUN, templateId = "FREE", title = "Свободный бег", startedAt = ms(fri, 16), finishedAt = ms(fri, 17), status = Status.DONE, rpe = 4, durationSec = 1015))
        for (day in listOf(fri, sat)) {
            at(day, 20)
            val week = denis.weekFlow(mon.plusDays(7)).first()
            val saturday = week.first { it.date == sat }
            assertTrue(saturday.planned.all { saturday.isDone(it) }, "$day: $saturday")
            assertEquals(listOf("Низ", "Свободный бег"), saturday.planned.map { saturday.doneLaterFor(it)?.title })
            val tuesday = week.first { it.date == mon.plusDays(8) }
            assertEquals(listOf(SessionKind.LIFT), tuesday.missed(day).map { it.kind })
            // Friday's two are Saturday's, said so on Friday's card.
            assertEquals(setOf(sat), week.first { it.date == fri }.catchUps.values.toSet())
        }
        // Done on Saturday after all: Saturday's own counts, Friday's «Низ» makes up Tuesday's gym.
        db.workouts().insert(WorkoutEntity(epochDay = sat.toEpochDay(), kind = Kind.LIFT, templateId = "lower_a", title = "Низ", startedAt = ms(sat, 12), finishedAt = ms(sat, 13), status = Status.DONE, rpe = 6, durationSec = 2800))
        val week = denis.weekFlow(mon.plusDays(7)).first()
        val saturday = week.first { it.date == sat }
        assertEquals("Низ", saturday.doneFor(saturday.planned.first { it.kind == SessionKind.LIFT })?.title)
        assertNull(saturday.doneLaterFor(saturday.planned.first { it.kind == SessionKind.LIFT }))
        assertTrue(week.first { it.date == mon.plusDays(8) }.missed(sat).isEmpty())
    }
}
