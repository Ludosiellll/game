package forma.core

import forma.core.run.AutoPause
import forma.core.run.Fix
import forma.core.run.Geo
import forma.core.run.GpsWatch
import forma.core.run.ManualLaps
import forma.core.run.PaceRange
import forma.core.run.Press
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import forma.core.run.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a runner meets out there: a sleeve pressing "lap" in the pocket, the satellites lost under trees, the app closed
 * by the phone mid-run, a watch with the laps on it.
 */
class RunFieldTest {
    private val lapM = 230
    private val work = Segment(SegmentKind.WORK, 104, PaceRange(224, 228), laps = 2.0, lapSec = 52.0)
    private val reps = RunWorkout(
        RunType.GOAL_PACE, "2 × 2 круга",
        listOf(Segment(SegmentKind.WARMUP, 60), work, Segment(SegmentKind.RECOVERY, 90), work, Segment(SegmentKind.COOLDOWN, 60)),
        lapMeters = lapM,
    )

    @Test fun aTouchInThePocketClosesNoRepAndMovesNoLine() {
        val t = RunTracker(reps)
        t.setLine(53.9, 27.56)
        t.start(0)
        var now = 0L
        while (!t.waiting(now)) now += 1000
        assertEquals(Press.Start, t.press(now))
        val go = now
        // 12 s into a 52-second lap, 30 m from the line, with a good fix right then.
        t.onFix(Fix(go + 12_000, 53.9 + 30 / 111_195.0, 27.56, 5f), go + 12_000)
        assertEquals(Press.Ignored, t.press(go + 12_000))
        assertEquals(53.9 to 27.56, t.line)
        assertTrue(t.laps.isEmpty())
        // The lap pressed on the line still counts, from the start.
        val first = assertIs<Press.Done>(t.press(go + 52_000)).lap
        assertEquals(1, first.number)
        assertEquals(52.0, first.seconds, 1e-9)
        // Another touch 8 s later (past the double-tap guard) does not end the rep after a lap.
        assertEquals(Press.Ignored, t.press(go + 60_000))
        assertEquals(1, t.laps.size)
        val second = assertIs<Press.Done>(t.press(go + 104_000)).lap
        assertEquals(2, second.number)
        assertTrue(second.closesRep)
        assertEquals(52.0, second.seconds, 1e-9)
        assertEquals(104.0, t.repTimes().first()!!, 1e-9)
    }

    @Test fun aFreeRunWithATargetTakesNoTwentySecondLap() {
        // 75 s a lap of 230 m: about 5:26 a km.
        val own = RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = lapM, targetPace = PaceRange(322, 330))
        val t = RunTracker(own)
        t.start(0)
        assertEquals(Press.Start, t.press(2_000))
        assertEquals(Press.Ignored, t.press(20_000))
        val lap = assertIs<Press.Done>(t.press(77_000)).lap
        assertEquals(75.0, lap.seconds, 1e-9)
        // Without a target only what no one runs is too short: 230 m in 18 s (12.8 m/s) is a touch, in 25 s a lap.
        val free = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = lapM))
        free.start(0)
        assertEquals(Press.Start, free.press(2_000))
        assertEquals(Press.Ignored, free.press(20_000))
        assertIs<Press.Done>(free.press(27_000))
        // And a double tap is still one press.
        assertEquals(Press.Ignored, free.press(29_000))
    }

    private fun easyRun(): RunTracker {
        val t = RunTracker(RunWorkout(RunType.EASY, "Лёгкий бег", listOf(Segment(SegmentKind.RUN, 1800, PaceRange(330, 360)))))
        t.setStride(0.2, 0.0055)
        t.start(0)
        // A minute at 180 steps a minute.
        for (sec in 0..60) t.onSteps(1000L + 3 * sec, sec * 1000L)
        return t
    }

    @Test fun aGapAfterTheAppWasClosedIsFilledByTheSteps() {
        val snap = easyRun().snapshot(60_000)
        // Back on its own 200 s later by the clock of the phone; the run had been saved at its 60th second.
        val t = RunTracker.restore(snap, 500_000)
        t.resume(500_000)
        assertEquals(60_000, t.activeMs(500_000))
        val before = t.distanceM
        // 30 s of the gap at 180 a minute: all of it was running.
        assertEquals(30_000, t.fillGap(30_000, 90, 500_000))
        assertEquals(90_000, t.activeMs(500_000))
        assertEquals(snap.steps + 90, t.steps)
        assertEquals(90 * (0.2 + 0.0055 * 180), t.distanceM - before, 1e-6)

        // Half of a gap standing (a traffic light): half of it counts.
        val half = RunTracker.restore(snap, 500_000).also { it.resume(500_000) }
        assertEquals(15_000, half.fillGap(30_000, 45, 500_000))
        // Steps unknown (no permission, the phone restarted): nothing is guessed.
        val none = RunTracker.restore(snap, 500_000).also { it.resume(500_000) }
        assertEquals(0, none.fillGap(30_000, null, 500_000))
        assertEquals(60_000, none.activeMs(500_000))
        // A paused run is not filled.
        val paused = RunTracker.restore(snap, 500_000)
        assertEquals(0, paused.fillGap(30_000, 90, 500_000))
    }

    @Test fun theStepCounterIsKeptForTheGap() {
        val t = easyRun()
        assertEquals(1180L, t.stepTotal)
    }

    @Test fun autoPauseTrustsNoFixFarOffAndNoStandingWhileStepsCome() {
        // Under trees: speed 0 from a fix 99 m off — running all the same.
        val trees = AutoPause()
        for (s in 0..30) assertNull(trees.update(0f, s * 1000L, running = true, accuracy = 99f))
        // A good fix reads 0 but the steps come: no pause.
        val a = AutoPause()
        for (s in 0..20) {
            assertNull(a.steps(1000L + 3 * s, s * 1000L))
            assertNull(a.update(0.2f, s * 1000L, running = true, accuracy = 5f))
        }
        // The steps stop at 20 s: the pause comes 6 s later, from the last step.
        for (s in 21..25) assertNull(a.update(0.1f, s * 1000L, running = true, accuracy = 5f))
        assertEquals(AutoPause.Act.Pause(20_000), a.update(0.1f, 26_000, running = true, accuracy = 5f))
        // A shuffle at the light is not running again.
        assertNull(a.steps(1062, 30_000))
        assertNull(a.steps(1064, 33_000))
        // Steps again, no GPS at all: a few seconds of them and the run goes on.
        assertNull(a.steps(1067, 40_000))
        assertNull(a.steps(1070, 41_000))
        assertNull(a.steps(1073, 42_000))
        assertNull(a.steps(1076, 43_000))
        assertEquals(AutoPause.Act.Resume, a.steps(1079, 44_000))
        assertTrue(!a.paused)
        // Paused, a bad fix with a speed does not resume it.
        val b = AutoPause()
        for (s in 0..6) b.update(0f, s * 1000L, running = true, accuracy = 5f)
        assertTrue(b.paused)
        repeat(3) { assertNull(b.update(3f, 10_000L + it * 1000, running = false, accuracy = 60f)) }
        assertNull(b.update(3f, 20_000, running = false, accuracy = 8f))
        assertEquals(AutoPause.Act.Resume, b.update(3f, 21_000, running = false, accuracy = 8f))
    }

    @Test fun gpsLostAndBackAreSaidOnceEachWay() {
        val g = GpsWatch()
        // Nothing before the satellites are found.
        assertNull(g.tick(20_000, running = true))
        val said = mutableListOf<Pair<Long, GpsWatch.Say>>()
        fun second(ms: Long, fix: Boolean?) {
            if (fix != null) g.fix(ms, fix)
            g.tick(ms, running = true)?.let { said += ms to it }
        }
        for (s in 20..30) second(s * 1000L, true)
        // Under trees: fixes far off, then none.
        for (s in 31..40) second(s * 1000L, false)
        for (s in 41..50) second(s * 1000L, null)
        assertEquals(listOf(45_000L to GpsWatch.Say.LOST), said)
        // Back for five seconds.
        for (s in 51..60) second(s * 1000L, true)
        assertEquals(56_000L to GpsWatch.Say.BACK, said.last())
        // Lost again soon after: not said, nor its return.
        for (s in 61..80) second(s * 1000L, null)
        for (s in 81..90) second(s * 1000L, true)
        assertEquals(2, said.size)
        // A long stand at a light is no loss.
        for (s in 91..200) g.tick(s * 1000L, running = false)
        assertNull(g.tick(201_000, running = true))
    }

    @Test fun lapsFromAWatchGoToTheRepsAsTheButtonWould() {
        assertEquals(listOf(52.0, 51.5, 53.0), ManualLaps.parse("52 51,5 53"))
        assertEquals(listOf(52.0, 51.0, 53.0), ManualLaps.parse("52, 51, 53"))
        assertEquals(listOf(65.0, 64.5), ManualLaps.parse("1:05 1:04.5"))
        assertEquals(emptyList<Double>(), ManualLaps.parse("  "))
        assertNull(ManualLaps.parse("52 5l"))
        val laps = ManualLaps.assign(reps, listOf(52.0, 53.0, 51.0, 52.0, 70.0))
        assertEquals(listOf(1, 1, 2, 2, null), laps.map { it.rep })
        assertEquals(listOf(1, 2, 1, 2, 1), laps.map { it.number })
        assertEquals(listOf(false, true, false, true, false), laps.map { it.closesRep })
        assertEquals(Track.lapSec(224.0, lapM), laps.first().fast!!, 1e-9)
        assertEquals(2, ManualLaps.repsDone(laps))
        // The reps' pace from their laps: 208 s for 920 m.
        assertEquals(208.0 / 0.92, ManualLaps.repPace(laps, lapM)!!, 1e-9)
        // A rep of 6.5 laps starts with the half.
        val long = reps.copy(segments = listOf(Segment(SegmentKind.WORK, 340, PaceRange(224, 228), laps = 6.5, lapSec = 52.0)))
        val half = ManualLaps.assign(long, listOf(26.0, 52.0))
        assertEquals(0.5, half.first().part, 1e-9)
        assertEquals(7, half.first().of)
    }

    /** Denis's goal pace on 230 m: 50.6 s a lap, three laps a rep. */
    private val three = RunWorkout(
        RunType.GOAL_PACE, "1 × 3 круга",
        listOf(Segment(SegmentKind.WARMUP, 60), Segment(SegmentKind.WORK, 152, PaceRange(218, 222), laps = 3.0, lapSec = 50.6), Segment(SegmentKind.COOLDOWN, 60)),
        lapMeters = lapM,
    )

    /** [t] at the start press of its rep; returns that moment. */
    private fun onTheLine(t: RunTracker): Long {
        t.start(0)
        var now = 0L
        while (!t.waiting(now)) now += 1000
        assertEquals(Press.Start, t.press(now))
        return now
    }

    /** A press at [ms] with a good fix [metresOff] north of the line (53.9, 27.56) right then. */
    private fun pressNear(t: RunTracker, ms: Long, metresOff: Double): Press {
        t.onFix(Fix(ms, 53.9 + metresOff / 111_195.0, 27.56, 5f), ms)
        return t.press(ms)
    }

    @Test fun aTouchMidLapThatPassesForALapMovesNoLine() {
        val t = RunTracker(three)
        t.setLine(53.9, 27.56)
        val go = onTheLine(t)
        // 35 s into a 50.6-second lap, 40 m off: long enough to pass for a lap (over 0,6 of it), too short to say where
        // the line is. A touch at 30 s is no lap at all now: under 0,6 of the lap the rep is run at.
        assertIs<Press.Done>(pressNear(t, go + 35_000, 40.0))
        assertEquals(53.9 to 27.56, t.line)
        // The press on the line 15,6 s later is the line: the touch's lap takes its time, a whole lap — and that press,
        // 2 m off, moves the line toward it a little, as any press on the line does.
        val fixed = assertIs<Press.Done>(pressNear(t, go + 50_600, 2.0))
        assertEquals(50.6, fixed.lap.seconds, 0.01)
        assertEquals(1, t.laps.size)
        val line = assertNotNull(t.line)
        assertEquals(0.6, Geo.haversine(53.9, 27.56, line.first, line.second), 0.1)
        // The next lap, on the line: a lap of its own.
        assertIs<Press.Done>(pressNear(t, go + 101_200, 2.0))
        assertEquals(2, t.laps.size)
        assertEquals(50.6, t.laps.last().seconds, 0.01)
    }

    @Test fun aNewStadiumTakesTwoPressesThatAgree() {
        val t = RunTracker(three)
        // The line from the last session: another stadium, 5.5 km off.
        t.setLine(53.95, 27.56)
        val go = onTheLine(t)
        assertIs<Press.Done>(pressNear(t, go + 50_600, 0.0))
        assertEquals(53.95 to 27.56, t.line)
        assertIs<Press.Done>(pressNear(t, go + 101_200, 3.0))
        val line = assertNotNull(t.line)
        assertTrue(Geo.haversine(53.9, 27.56, line.first, line.second) < 3.0, "$line")
    }

    @Test fun aPressThatTimesAGpsLapTeachesNoLine() {
        val t = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = lapM))
        t.setLine(53.9, 27.56)
        t.start(0)
        // Out 115 m and back over the line at 3.07 m/s, a fix a second; the crossing is not pressed.
        for (sec in 1..83) {
            val m = if (sec <= 37) 3.07 * sec else 230 - 3.07 * sec
            t.onFix(Fix(sec * 1000L, 53.9 + m / 111_195.0, 27.56, 5f, 3.07f), sec * 1000L)
            t.autoLap(sec * 1000L)
        }
        assertTrue(t.laps.single().auto, "${t.laps}")
        // The press 9 s late, 28 m past the line, times that lap; where it happened is not the line.
        val late = assertIs<Press.Done>(pressNear(t, 84_000, 230 - 3.07 * 84)).lap
        assertFalse(late.auto)
        assertEquals(53.9 to 27.56, t.line)
    }

    /**
     * The kept line is 28 m before where he presses: every lap GPS closes there and his press comes 9 s later. Once is a
     * late press; twice at the same spot is the line, and it moves there.
     */
    @Test fun latePressesAtTheSameSpotMoveAWrongLine() {
        val r = lapM / (2 * Math.PI)
        val v = 3.07
        fun at(sec: Double): Pair<Double, Double> {
            val a = v * sec / r
            return (53.9 + r * (1 - Math.cos(a)) / 111_195.0) to (27.56 + r * Math.sin(a) / (111_195.0 * Math.cos(Math.toRadians(53.9))))
        }
        val t = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = lapM))
        t.setLine(53.9, 27.56)
        t.start(0)
        val lap = lapM / v
        val presses = mutableListOf<Pair<Double, Double>>()
        for (sec in 1..170) {
            val (la, lo) = at(sec.toDouble())
            t.onFix(Fix(sec * 1000L, la, lo, 5f, v.toFloat()), sec * 1000L)
            t.autoLap(sec * 1000L)
            if (sec == (lap + 9).toInt() || sec == (2 * lap + 9).toInt()) {
                // Each times a lap GPS has timed and said already: nothing new to say, yet it says where the line is.
                assertIs<Press.Timed>(t.press(sec * 1000L))
                presses += la to lo
                if (presses.size == 1) assertEquals(53.9 to 27.56, t.line, "one late press moves nothing")
            }
        }
        val line = assertNotNull(t.line)
        assertTrue(Geo.haversine(line.first, line.second, presses.last().first, presses.last().second) < 5.0, "line $line, presses $presses")
    }

    @Test fun stepsGiveTheDistanceOnlyWithAKnownStride() {
        assertTrue(easyRun().stepsGiveDistance())
        val t = RunTracker(RunWorkout(RunType.EASY, "Лёгкий бег", listOf(Segment(SegmentKind.RUN, 1800, PaceRange(330, 360)))))
        t.start(0)
        for (sec in 0..60) t.onSteps(1000L + 3 * sec, sec * 1000L)
        assertNotNull(t.cadence())
        assertFalse(t.stepsGiveDistance())
    }

    @Test fun noGpsAfterARestartIsSaidOnce() {
        val g = GpsWatch()
        g.await(0)
        val said = mutableListOf<Pair<Long, GpsWatch.Say>>()
        // Ten seconds still paused do not count; then a minute of running with no fix at all.
        for (s in 0..10) assertNull(g.tick(s * 1000L, running = false))
        for (s in 11..80) g.tick(s * 1000L, running = true)?.let { said += s * 1000L to it }
        assertEquals(listOf(70_000L to GpsWatch.Say.NONE), said)
        // GPS comes after all: said.
        for (s in 81..90) {
            g.fix(s * 1000L, true)
            g.tick(s * 1000L, running = true)?.let { said += s * 1000L to it }
        }
        assertEquals(86_000L to GpsWatch.Say.BACK, said.last())
        // Any fix within the minute, a weak one too: GPS answers, nothing to say.
        val weak = GpsWatch()
        weak.await(0)
        weak.fix(20_000, false)
        assertTrue((1..120).none { weak.tick(it * 1000L, running = true) != null })
    }
}
