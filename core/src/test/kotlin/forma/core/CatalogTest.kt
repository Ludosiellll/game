package forma.core

import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.Exercise
import forma.core.model.ExerciseSearch
import forma.core.model.LoadKind
import forma.core.model.Muscle
import forma.core.model.Pattern
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {
    @AfterTest fun clean() = Catalog.setCustom(emptyList())

    private fun first(q: String, muscle: Muscle? = null, equipment: Equipment? = null) =
        ExerciseSearch.search(q, muscle = muscle, equipment = equipment).firstOrNull()?.id

    @Test fun catalogIsConsistent() {
        val ids = Catalog.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids")
        assertTrue(Catalog.builtIn.size >= 120, "${Catalog.builtIn.size}")
        // Ids already stored in history and the cloud never disappear.
        val stored = listOf(
            "squat", "front_squat", "deadlift", "rdl", "bench", "incline_bench", "ohp", "row", "hip_thrust", "db_bench", "db_incline",
            "db_shoulder", "db_row", "goblet_squat", "db_lunge", "bulgarian", "db_curl", "hammer_curl", "lateral_raise",
            "db_overhead_ext", "leg_press", "hack_squat", "leg_ext", "leg_curl", "calf_raise", "lat_pulldown", "cable_row",
            "chest_press", "pec_deck", "pushdown", "face_pull", "cable_curl", "pullup", "dip", "pushup", "back_ext", "leg_raise", "plank",
        )
        assertTrue(stored.all { it in ids }, stored.filter { it !in ids }.toString())
        for (ex in Catalog.builtIn) {
            assertTrue(ex.pattern != Pattern.OTHER, ex.id)
            assertTrue(ex.primary.isNotEmpty(), ex.id)
            if (ex.load == LoadKind.EXTERNAL) assertTrue(ex.startRatio > 0 && ex.step > 0, ex.id)
            if (ex.load == LoadKind.BODYWEIGHT_PLUS) assertTrue(ex.bodyShare in 0.1..1.0, ex.id)
            if (ex.bar != null) assertEquals(Equipment.BARBELL, ex.equipment, ex.id)
        }
    }

    @Test fun searchFindsWhatPeopleType() {
        assertEquals("squat", first("присед"))
        assertEquals("squat", first("Приседания со штангой"))
        assertEquals("deadlift", first("становая"))
        assertEquals("deadlift", first("становуха"))
        assertEquals("bench", first("жим лёжа"))
        assertEquals("bench", first("жим лежа"))
        assertEquals("rdl", first("rdl"))
        assertEquals("ohp", first("армейский"))
        assertEquals("lat_pulldown", first("верхний блок"))
        assertEquals("pec_deck", first("бабочка"))
        assertEquals("hip_thrust", first("ягодичный мост"))
        // Word endings, typos, the wrong keyboard layout.
        assertEquals("db_curl", first("бицепс гантелями"))
        assertTrue(ExerciseSearch.search("присидания").any { it.id == "squat" })
        assertEquals("squat", first("ghbctl"))
        assertEquals("pullup", first("подтягивания"))
        // Words about the load or little words do not hide the exercise.
        assertEquals("pullup", first("подтягивания со своим весом"))
        assertEquals("pullup", first("подтягивания без веса"))
        assertEquals("pullup", first("подтягивания на турнике"))
        assertEquals("pullup", first("подтягивания с  доп весом"))
        assertEquals("pullup", first("подтягивания с поясом"))
        // A word no pull-up has ("гантелей" belongs to dumbbell lifts): the exercise the first words name still shows.
        assertEquals("pullup", first("подтягивания с гантелей"))
        assertEquals("pushup", first("отжимания с весом"))
        assertEquals("belt_squat", first("присед с поясом"))
        assertEquals("machine_triceps_ext", first("разгибания на трицепс в тренажере"))
        assertEquals("leg_press", first("жим ногами в тренажёре"))
        assertEquals("bench", first("жим лёжа со штангой"))
        // A real exercise word still has to match: no barbell pull-ups.
        assertTrue(forma.core.model.ExerciseSearch.search("подтягивания", equipment = Equipment.BARBELL).isEmpty())
        // Nothing but filler finds nothing.
        assertTrue(forma.core.model.ExerciseSearch.search("со своим").isEmpty())
        // Muscles and equipment as words.
        assertTrue(ExerciseSearch.search("трицепс").take(5).all { Muscle.TRICEPS in it.primary }, ExerciseSearch.search("трицепс").take(5).toString())
        assertTrue(ExerciseSearch.search("гиря").any { it.id == "kb_swing" })
        // Nonsense finds nothing.
        assertTrue(ExerciseSearch.search("абракадабра").isEmpty())
    }

    /** A typo in the one word typed must not hand the top to the shortest name with a similar word ("Сплит-приседания"). */
    @Test fun typosStillFindTheExerciseMeant() {
        for (q in listOf("приседаня", "присидания", "приседанея", "приседание", "присядания", "приседенаия")) assertEquals("squat", first(q), q)
        assertEquals("pushup", first("отжиманя"))
        assertEquals("pullup", first("подтягиваня"))
        assertEquals("deadlift", first("становя тяга"))
        assertEquals("lat_pulldown", first("тяга верхнго блока"))
        // Another form of a one-word synonym is the synonym.
        assertEquals("db_lunge", first("выпад"))
        assertEquals("db_lunge", first("выпады"))
        // The exact word keeps its order; a near miss does not reach unrelated exercises.
        assertEquals("squat", first("приседания"))
        assertTrue(ExerciseSearch.search("приседаня").take(6).all { it.pattern == Catalog["squat"].pattern || "присед" in it.name.lowercase() })
    }

    @Test fun filtersNarrowTheList() {
        val legs = ExerciseSearch.search("", muscle = Muscle.QUADS)
        assertTrue(legs.isNotEmpty() && legs.all { Muscle.QUADS in it.primary || Muscle.QUADS in it.secondary })
        val dumbbellChest = ExerciseSearch.search("жим", muscle = Muscle.CHEST, equipment = Equipment.DUMBBELL)
        assertTrue(dumbbellChest.isNotEmpty() && dumbbellChest.all { it.equipment == Equipment.DUMBBELL })
        assertEquals("db_bench", dumbbellChest.first().id)
    }

    @Test fun alternativesKeepTheMovement() {
        val alt = Catalog.alternatives(Catalog["bench"])
        assertTrue(alt.take(6).all { it.pattern == Pattern.HORIZONTAL_PUSH }, alt.take(6).toString())
        assertEquals(Equipment.BARBELL, alt.first().equipment)
        assertTrue(Catalog.alternatives(Catalog["pullup"]).first().pattern == Pattern.VERTICAL_PULL)
    }

    @Test fun ownExercisesJoinTheCatalog() {
        val mine = Exercise("custom_landmine", "Жим лэндмайн", Equipment.BARBELL, listOf(Muscle.SHOULDERS), pattern = Pattern.VERTICAL_PUSH, custom = true)
        Catalog.setCustom(listOf(mine, Catalog["bench"].copy(name = "подмена")))
        assertEquals(mine, Catalog["custom_landmine"])
        // A built-in exercise cannot be replaced.
        assertEquals("Жим штанги лёжа", Catalog["bench"].name)
        assertEquals("custom_landmine", first("лэндмайн"))
        assertTrue(Catalog.alternatives(Catalog["ohp"]).any { it.id == "custom_landmine" })
        // History of a deleted exercise still opens.
        Catalog.setCustom(emptyList())
        assertEquals("landmine", Catalog["custom_landmine"].name)
    }
}
