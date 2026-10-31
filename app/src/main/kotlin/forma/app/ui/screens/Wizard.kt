package forma.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Banner
import forma.app.ui.components.BodyMap
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.app.ui.theme.titleText
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.model.Split
import forma.core.plan.Accents
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Templates
import forma.core.text.Ru
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The program wizard (Denis, 31.10, after an app he liked: «сделать похоже, улучшить, сделать круче и анимировать,
 * сделать еще более универсальным»): one question a screen, the gym and running both, how the week is divided and up to
 * two groups worked more, then the answers gathered with an animation and the program shown — the body lit by its weekly
 * sets, each session, the running, the week — before it is saved.
 */
private enum class Step { INTRO, SIDES, GYM, RUN, LEVEL, RUN_LEVEL, DAYS, SPLIT, ACCENTS, ABOUT, ANALYZE, RESULT }

/** The questions the answers so far ask: a side not trained asks nothing of itself; [first]: the first launch. */
private fun steps(d: ProfileDraft, first: Boolean): List<Step> = buildList {
    add(Step.INTRO)
    add(Step.SIDES)
    if (d.aim.lifts) add(Step.GYM)
    if (d.aim.runs) add(Step.RUN)
    if (d.aim.lifts) add(Step.LEVEL)
    if (d.aim.runs) add(Step.RUN_LEVEL)
    add(Step.DAYS)
    if (d.aim.lifts) {
        add(Step.SPLIT)
        add(Step.ACCENTS)
    }
    if (first) add(Step.ABOUT)
    add(Step.ANALYZE)
    add(Step.RESULT)
}

/** The first launch: the wizard, then a look round the app. */
@Composable
fun OnboardingScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    ProgramWizard(Profile(), first = true, onBack = null) { profile ->
        repo.completeOnboarding(profile)
        nav.replaceAll(Screen.Tour(first = true))
    }
}

/** From the plan: the program built again from the answers, the current ones filled in. */
@Composable
fun ProgramWizardScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val profile = repo.store.profile.collectAsState(initial = null).value ?: return
    ProgramWizard(profile, first = false, onBack = { nav.back() }) { p ->
        repo.updateProfile(p)
        nav.back()
    }
}

@Composable
fun ProgramWizard(start: Profile, first: Boolean, onBack: (() -> Unit)?, onDone: suspend (Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    val draft = rememberSaveable(saver = ProfileDraft.Saver) { ProfileDraft(start, blankBody = first) }
    var at by rememberSaveable { mutableStateOf(Step.INTRO) }
    var forward by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    // Which of the five answers about the gym was picked (they map to three levels): shown as picked again.
    var level by rememberSaveable { mutableIntStateOf(-1) }
    val list = steps(draft, first)
    val questions = list.filter { it != Step.INTRO && it != Step.ANALYZE && it != Step.RESULT }
    fun go(to: Step, ahead: Boolean) {
        forward = ahead
        at = to
    }
    fun next() = list.getOrNull(list.indexOf(at) + 1)?.let { go(it, true) }
    fun back() {
        val i = list.indexOf(at)
        // From the result the way back skips the animation.
        val to = list.getOrNull(i - 1)?.let { if (it == Step.ANALYZE) list.getOrNull(i - 2) else it }
        if (to == null || at == Step.INTRO) onBack?.invoke() else go(to, false)
    }
    androidx.activity.compose.BackHandler(enabled = at != Step.INTRO || onBack != null) { back() }
    val problem = when (at) {
        Step.RUN -> draft.goalProblem
        Step.RUN_LEVEL -> draft.raceProblem
        Step.DAYS -> draft.weekProblem
        Step.ABOUT -> draft.aboutProblem
        Step.RESULT -> draft.problem
        else -> null
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (at in questions) StepDots(questions.indexOf(at), questions.size, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
        AnimatedContent(
            at, Modifier.weight(1f).fillMaxWidth(), label = "wizard",
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320)) { it / 3 * dir } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(240)) { -it / 4 * dir } + fadeOut(tween(200)))
            },
        ) { step ->
            if (step == Step.ANALYZE) Analyze(draft) { go(Step.RESULT, true) }
            else Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    Step.INTRO -> Intro(first)
                    Step.SIDES -> SidesStep(draft)
                    Step.GYM -> GymStep(draft)
                    Step.RUN -> RunStep(draft)
                    Step.LEVEL -> LevelStep(draft, level) { level = it }
                    Step.RUN_LEVEL -> RunLevelStep(draft)
                    Step.DAYS -> WizardDays(draft)
                    Step.SPLIT -> SplitStep(draft)
                    Step.ACCENTS -> AccentsStep(draft)
                    Step.ABOUT -> AboutYou(draft)
                    Step.RESULT -> ResultStep(draft)
                    Step.ANALYZE -> Unit
                }
            }
        }
        if (at != Step.ANALYZE) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (problem != null) Text(problem, style = T.secondary(13.sp).copy(color = F.c.bad))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (at != Step.INTRO || onBack != null) GhostButton(if (at == Step.INTRO) "Отмена" else "Назад", { back() }, Modifier.weight(1f), height = 54.dp)
                PrimaryButton(
                    when (at) {
                        Step.INTRO -> "Собрать программу"
                        Step.RESULT -> if (first) "Начать" else "Сохранить программу"
                        else -> "Дальше"
                    },
                    {
                        if (at != Step.RESULT) next()
                        else if (!saving) {
                            saving = true
                            scope.launch { onDone(draft.toProfile(start)) }
                        }
                    },
                    Modifier.weight(2f), enabled = problem == null && !saving, icon = if (at == Step.INTRO) Ic.right else null,
                )
            }
        }
    }
}

/** The questions as dots on a line: done ticked, this one numbered and dark, the rest outlined. */
@Composable
private fun StepDots(at: Int, of: Int, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        repeat(of) { i ->
            val done = i < at
            val now = i == at
            val fill by animateColorAsState(if (done || now) F.c.accent else Color.Transparent, tween(300), label = "dot")
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(fill).border(1.5.dp, if (done || now) F.c.accent else F.c.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Ic.check, null, tint = F.c.onAccent, modifier = Modifier.size(12.dp))
                else Text("${i + 1}", style = T.body(11.sp, FontWeight.Bold).copy(color = if (now) F.c.onAccent else F.c.text3))
            }
            if (i < of - 1) {
                val line by animateColorAsState(if (done) F.c.accent else F.c.line, tween(300), label = "line")
                Box(Modifier.weight(1f).height(2.dp).background(line))
            }
        }
    }
}

@Composable
private fun Question(title: String, text: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(titleText(title), style = T.title(30.sp))
        if (text != null) Text(text, style = T.secondary(15.sp))
    }
}

/** One answer as a card: a mark, its name and what it means; picked — outlined, the tick in, a little bounce. */
@Composable
private fun Answer(mark: String?, title: String, text: String?, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1f else 0.985f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "pick")
    val edge by animateColorAsState(if (selected) F.c.accent else F.c.line, tween(220), label = "edge")
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().scale(scale).clip(shape).background(if (selected) F.c.surface2 else Color.Transparent).border(if (selected) 2.dp else 1.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (mark != null) Text(mark, style = T.body(24.sp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = T.body(16.sp, FontWeight.ExtraBold), modifier = Modifier.weight(1f, fill = false))
                if (badge != null) Text(
                    badge, style = T.body(11.sp, FontWeight.Bold).copy(color = F.c.onAccent),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(F.c.strength).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (text != null) Text(text, style = T.secondary(13.sp))
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (selected) F.c.accent else Color.Transparent).border(1.5.dp, if (selected) F.c.accent else F.c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Ic.check, null, tint = F.c.onAccent, modifier = Modifier.size(14.dp)) }
    }
}

/** The app's mark drawn in: the plate's half and the running lanes, [t] 0 → 1 (lanes sweep in, the plate turns). */
@Composable
fun MarkDrawing(t: Float, modifier: Modifier) {
    val ink = F.c.text
    val hot = F.c.strength
    val bg = F.c.bg
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.9f
        val c = Offset(size.width / 2f, size.height / 2f)
        rotate(-25f * (1f - t), c) {
            // The plate's half, with its two windows and the hub.
            drawArc(ink, 90f, 180f, true, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r))
            // Its two windows: short arcs cut through the plate between the rim and the hub.
            listOf(200f, 120f).forEach { a ->
                val rr = r * 0.66f
                drawArc(bg, a, 40f, false, topLeft = Offset(c.x - rr, c.y - rr), size = Size(2 * rr, 2 * rr), style = Stroke(r * 0.26f))
            }
        }
        // The lanes, each sweeping in after the one outside it.
        listOf(0.87f, 0.6f, 0.34f).forEachIndexed { i, k ->
            val p = ((t * 1.6f) - i * 0.25f).coerceIn(0f, 1f)
            val rr = r * k
            drawArc(hot, -90f, 180f * p, false, topLeft = Offset(c.x - rr, c.y - rr), size = Size(2 * rr, 2 * rr), style = Stroke(r * 0.13f))
        }
        drawCircle(bg, r * 0.24f, c)
        drawCircle(ink, r * 0.17f, c)
        drawCircle(bg, r * 0.09f, c)
    }
}

@Composable
private fun Intro(first: Boolean) {
    val nav = LocalNav.current
    val cloud = LocalCloud.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(1100)) }
    Spacer(Modifier.height(24.dp))
    MarkDrawing(t.value, Modifier.fillMaxWidth().height(200.dp))
    Text(titleText(if (first) "Соберём твою программу" else "Соберём программу заново"), style = T.screenTitle())
    Text(
        "Пара минут: чем занимаешься, сколько дней, как делить тренировки и на что упор. Потом приложение само подстраивает веса, " +
            "подходы и бег под то, как у тебя идёт.",
        style = T.secondary(15.sp),
    )
    Text("Займёт около 2 минут", style = T.secondary(13.sp).copy(color = F.c.text3))
    if (first && cloud?.configured == true) {
        Banner("Уже тренировался в Splity (раньше «Форма»)? Войди, и твои данные вернутся из облака", F.c.accent, icon = Ic.cloud) {
            nav.push(Screen.CloudSignIn(restore = true))
        }
    }
}

@Composable
private fun SidesStep(d: ProfileDraft) {
    Question("Чем занимаешься?", "Можно поменять потом в любой момент.")
    val sides = sidesOf(d.aim)
    var keptLift by rememberSaveable { mutableStateOf(if (d.lift == LiftFocus.NONE) LiftFocus.POWERBUILDING else d.lift) }
    var keptRun by rememberSaveable { mutableStateOf(if (d.run == RunFocus.NONE) RunFocus.HEALTH else d.run) }
    fun pick(s: Sides) {
        if (d.lift != LiftFocus.NONE) keptLift = d.lift
        if (d.run != RunFocus.NONE) keptRun = d.run
        d.lift = if (s == Sides.RUN) LiftFocus.NONE else keptLift
        d.run = if (s == Sides.GYM) RunFocus.NONE else keptRun
    }
    Answer("🏋️‍♂️🏃", "Зал и бег", "Сила и выносливость в одной неделе. Тяжёлые ноги и тяжёлый бег — в разные дни.", sides == Sides.BOTH) { pick(Sides.BOTH) }
    Answer("🏋️‍♂️", "Только зал", "Веса, подходы и отдых — приложение ведёт по каждой тренировке.", sides == Sides.GYM) { pick(Sides.GYM) }
    Answer("🏃", "Только бег", "Лёгкий бег, интервалы, длинные пробежки, стадион.", sides == Sides.RUN) { pick(Sides.RUN) }
}

@Composable
private fun GymStep(d: ProfileDraft) {
    Question("Зачем зал?", null)
    val marks = mapOf(LiftFocus.STRENGTH to "🏆", LiftFocus.POWERBUILDING to "💪", LiftFocus.MUSCLE to "🔥", LiftFocus.GENERAL to "🌿")
    LIFT_FOCUS.filter { it.value != LiftFocus.NONE }.forEach { c -> Answer(marks[c.value], c.label, c.text, d.lift == c.value) { d.lift = c.value } }
    if (d.aim.runs) {
        SectionLabel("Что важнее", Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EMPHASIS.forEach { c -> Chip(c.label, d.emphasis == c.value, { d.emphasis = c.value }, Modifier.weight(1f)) }
        }
        Text(EMPHASIS.first { it.value == d.emphasis }.text, style = T.secondary(13.sp))
    }
}

@Composable
private fun RunStep(d: ProfileDraft) {
    Question("Зачем бег?", null)
    val marks = mapOf(RunFocus.HEALTH to "❤️", RunFocus.ENDURANCE to "🛣️", RunFocus.SPEED to "⚡")
    RUN_FOCUS.filter { it.value != RunFocus.NONE }.forEach { c -> Answer(marks[c.value], c.label, c.text, d.run == c.value) { d.run = c.value } }
    RunGoalPicker(d)
}

/** Five answers people give about the gym, said their way; they map to the three levels of the plan. */
private val LEVELS = listOf(
    Triple("🌱", "Я в зале впервые или только начинаю", Experience.NOVICE),
    Triple("🐣", "Иногда хожу, без системы", Experience.NOVICE),
    Triple("🙂", "Хожу регулярно, меньше двух лет", Experience.INTERMEDIATE),
    Triple("📋", "Регулярно и по плану, знаю свои веса", Experience.INTERMEDIATE),
    Triple("🦾", "Тренировки — часть моей жизни, больше двух лет", Experience.ADVANCED),
)

@Composable
private fun LevelStep(d: ProfileDraft, picked: Int, onPick: (Int) -> Unit) {
    Question("Ты сейчас тренируешься?", "От этого зависят первые веса, сколько подходов и как часто лёгкая неделя.")
    // Before a pick, the answer that matches the level already set.
    val shown = picked.takeIf { it >= 0 } ?: LEVELS.indexOfFirst { it.third == d.experience }
    LEVELS.forEachIndexed { i, (mark, text, exp) ->
        Answer(mark, text, null, shown == i) {
            onPick(i)
            d.experience = exp
        }
    }
}

@Composable
private fun RunLevelStep(d: ProfileDraft) {
    Question("Как с бегом сейчас?", null)
    Answer("🚶", "Пока не пробегу 20 минут", "Начнём с бега вперемешку с ходьбой", d.runLevel == RunLevel.NONE) { d.runLevel = RunLevel.NONE }
    Answer("🏃", "Пробегу 20–30 минут", "Бегаю спокойно, быстрых отрезков ещё не делал", d.runLevel == RunLevel.BASE) { d.runLevel = RunLevel.BASE }
    Answer("🏃‍♂️💨", "Бегаю регулярно", "Делаю быстрые отрезки и длинные пробежки", d.runLevel == RunLevel.TRAINED) { d.runLevel = RunLevel.TRAINED }
    if (d.raceShown) {
        SectionLabel("Лучшее время за последние 2 месяца (необязательно)", Modifier.padding(top = 6.dp))
        RaceFields(d, "Время")
    }
}

@Composable
private fun WizardDays(d: ProfileDraft) {
    Question("Сколько дней в неделю?", "Отметь дни, когда можешь. Приложение само решит, где зал, где бег, а где отдых.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DAY_SHORT.forEachIndexed { i, name ->
            val day = i + 1
            Chip(name, day in d.days, {
                d.days = if (day in d.days) d.days - day else d.days + day
                d.gymDays = null
                d.runDays = null
            }, Modifier.weight(1f), minWidth = 0.dp, kind = forma.app.ui.components.ChipKind.TOGGLE)
        }
    }
    Text("Выбрано дней: ${d.days.size}", style = T.secondary(14.sp))
    if (d.gymDays == null) DoubleDaysToggle(d)
    SessionMinutes(d)
    if (d.aim.lifts) GearPicker(d)
}

@Composable
private fun SplitStep(d: ProfileDraft) {
    Question("Как делить тренировки?", "Сколько подходов в неделю — решает цель. Сплит решает, как они разложены по дням.")
    // What «сами» picks for these days, said in the card.
    val auto = remember(d.days, d.lift, d.run, d.emphasis, d.experience, d.minutes) {
        runCatching { ProgramGenerator.generate(d.toProfile(Profile()).copy(split = Split.AUTO, accents = emptySet()), LocalDate.now()) }.getOrNull()
            ?.let { p -> splitName(p) }
    }
    val marks = mapOf(Split.AUTO to "✨", Split.FULL to "🧍", Split.UPPER_LOWER to "↕️", Split.PPL to "🔁")
    Split.entries.forEach { s ->
        Answer(
            marks[s], s.ru, if (s == Split.AUTO && auto != null) "${s.note}. Сейчас это: $auto." else s.note, d.split == s,
            badge = if (s == Split.AUTO) "советую" else null,
        ) { d.split = s }
    }
}

/** "Верх / низ", "Всё тело", "Тяни / толкай / ноги" — what the program's lifting days are. */
private fun splitName(p: Program): String {
    val ids = p.templates.map { it.id }
    return when {
        ids.isEmpty() -> "без зала"
        ids.any { it.startsWith("push") || it.startsWith("pull") } -> "тяни / толкай / ноги"
        ids.all { it.startsWith("full") } -> "всё тело"
        ids.any { it.startsWith("upper") } && ids.any { it.startsWith("lower") } -> "верх / низ"
        else -> "всё тело и верх"
    }
}

@Composable
private fun AccentsStep(d: ProfileDraft) {
    Question("На что упор?", "До двух групп мышц. Им дадим больше подходов, остальное — как обычно. Можно нажать прямо на человечка.")
    fun toggle(m: Muscle) {
        d.accents = when {
            m in d.accents -> d.accents - m
            d.accents.size >= Accents.MAX -> d.accents.drop(1).toSet() + m
            else -> d.accents + m
        }
    }
    BodyMap(emptyMap(), Modifier.fillMaxWidth().padding(horizontal = 36.dp), selected = d.accents, onTap = ::toggle, description = "Выбор акцентов")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Равномерно", d.accents.isEmpty(), { d.accents = emptySet() })
        Muscle.entries.forEach { m -> Chip(m.ru, m in d.accents, { toggle(m) }, kind = forma.app.ui.components.ChipKind.TOGGLE) }
    }
}

@Composable
private fun AboutYou(d: ProfileDraft) {
    Question(
        "О тебе",
        if (d.aim.lifts) "По весу подберём первые веса в зале. Год рождения и рост нужны, чтобы считать калории."
        else "Вес, год рождения и рост нужны, чтобы считать, сколько калорий ты тратишь.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Мужчина", d.sex == Sex.MALE, { d.sex = Sex.MALE }, Modifier.weight(1f))
        Chip("Женщина", d.sex == Sex.FEMALE, { d.sex = Sex.FEMALE }, Modifier.weight(1f))
    }
    BodyFields(d, onboarding = true)
}

/** The answers in short words, as chips. */
private fun answers(d: ProfileDraft): List<String> = buildList {
    add(sidesOf(d.aim).label)
    if (d.aim.lifts) add(LIFT_FOCUS.first { it.value == d.lift }.label)
    if (d.aim.runs) add(RUN_FOCUS.first { it.value == d.run }.label)
    if (d.aim.lifts) add(when (d.experience) { Experience.NOVICE -> "Начинаю"; Experience.INTERMEDIATE -> "Регулярно"; Experience.ADVANCED -> "Опытный" })
    add("${d.days.size} ${Ru.plural(d.days.size, "день", "дня", "дней")} в неделю")
    add("до ${d.minutes} мин")
    if (d.aim.lifts) add(d.split.ru)
    d.accents.forEach { add(it.ru) }
    d.goalMeters?.let { add(if (it >= 1000) "${Ru.one(it / 1000.0)} км" else "$it м") }
}

/** The answers gathered: the mark turns and its lanes sweep in while the answers come in one by one; then the result. */
@Composable
private fun Analyze(d: ProfileDraft, onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(2600, easing = LinearEasing))
        onDone()
    }
    val items = remember { answers(d) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val half = (items.size + 1) / 2
        AnswerChips(items.take(half), t.value, 0)
        Spacer(Modifier.height(18.dp))
        MarkDrawing((t.value * 1.3f).coerceAtMost(1f), Modifier.size(150.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                t.value < 0.4f -> "Смотрим на ответы…"
                t.value < 0.8f -> "Раскладываем неделю…"
                else -> "Считаем подходы…"
            },
            style = T.secondary(15.sp),
        )
        Spacer(Modifier.height(18.dp))
        AnswerChips(items.drop(half), t.value, half)
    }
}

@Composable
private fun AnswerChips(items: List<String>, t: Float, offset: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, text ->
            // Each answer in its turn: it grows in and its tick lights.
            val shown = ((t * 1.25f) - (offset + i) * 0.09f).coerceIn(0f, 1f) * 4f
            val a = shown.coerceIn(0f, 1f)
            Row(
                Modifier.scale(0.7f + 0.3f * a).clip(RoundedCornerShape(50)).background(F.c.surface2.copy(alpha = a))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(F.c.good.copy(alpha = a)), contentAlignment = Alignment.Center) {
                    Icon(Ic.check, null, tint = F.c.onData.copy(alpha = a), modifier = Modifier.size(10.dp))
                }
                Text(text, style = T.body(13.sp, FontWeight.SemiBold).copy(color = F.c.text.copy(alpha = a)))
            }
        }
    }
}

/** The program as it will be: its name, the body lit by the week's sets, each session, the running, the week. */
@Composable
private fun ResultStep(d: ProfileDraft) {
    val profile = d.toProfile(Profile())
    val program = remember(profile) { runCatching { ProgramGenerator.generate(profile, LocalDate.now()) }.getOrNull() }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(700)) }
    Text(labelText("Программа готова"), style = T.label().copy(color = F.c.strength))
    if (program == null) {
        Text("Не получилось собрать программу из этих ответов. Вернись и поменяй дни.", style = T.body(15.sp).copy(color = F.c.warn))
        return
    }
    val lifts = program.week.days.flatMap { day -> day.sessions.filter { it.kind == SessionKind.LIFT } }
    val runs = program.week.days.flatMap { day -> day.sessions.filter { it.isRun } }
    val name = buildString {
        append(if (lifts.isEmpty()) "Бег" else splitName(program).replaceFirstChar { it.uppercase() })
        if (d.accents.isNotEmpty() && lifts.isNotEmpty()) append(" — " + d.accents.joinToString(", ") { it.ru.lowercase() })
    }
    Text(titleText(name), style = T.title(32.sp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOfNotNull(
            aimTitle(profile.aim),
            if (lifts.isNotEmpty()) "зал ${lifts.size}× в неделю" else null,
            if (runs.isNotEmpty()) "бег ${runs.size}× в неделю" else null,
            "до ${profile.sessionMinutes} мин",
        ).forEach { tag ->
            Text(tag, style = T.body(12.sp, FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(50)).background(F.c.surface2).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
    Column(Modifier.scale(0.96f + 0.04f * t.value), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (lifts.isNotEmpty()) {
            val target = setsTarget(profile.aim.lift)
            MuscleWeek(
                Accents.weekly(program), target, title = "Нагрузка на мышцы",
                note = "Подходы в неделю по плану. Полоса — сколько хорошо для твоей цели: ${target.first.toInt()}–${target.second.toInt()} на мышцу.",
            )
            SectionLabel("Тренировки в зале")
            val used = lifts.mapNotNull { it.templateId }.distinct().mapNotNull { id -> program.templates.firstOrNull { it.id == id } }
            used.forEach { tpl -> SessionCard(tpl) }
        }
        if (runs.isNotEmpty()) {
            SectionLabel("Бег")
            FCard(gap = 6.dp) {
                Text("${runs.size} ${Ru.plural(runs.size, "пробежка", "пробежки", "пробежек")} в неделю", style = T.body(16.sp, FontWeight.ExtraBold))
                Text(runs.groupingBy { runKind(it.kind) }.eachCount().entries.joinToString(", ") { (k, n) -> if (n > 1) "$k ×$n" else k }, style = T.secondary(14.sp))
                profile.runGoal?.let { Text("Цель: ${it.meters} м за ${Ru.clock(it.seconds)}", style = T.secondary(14.sp)) }
            }
        }
        SummaryWeek(d, program)
    }
}

private fun runKind(k: SessionKind): String = when (k) {
    SessionKind.RUN_EASY -> "лёгкий"
    SessionKind.RUN_QUALITY -> "тяжёлый"
    SessionKind.RUN_LONG -> "длинный"
    else -> "бег"
}

/** A gym session of the program: its name, about how long, how many exercises and sets; the exercises a tap away. */
@Composable
private fun SessionCard(tpl: forma.core.plan.Template) {
    var open by remember { mutableStateOf(false) }
    FCard(onClick = { open = !open }, gap = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BodyMap(
                Accents.weekly(listOf(tpl)).mapValues { (_, v) -> (v / 8.0).toFloat() }, Modifier.width(76.dp),
                description = "Мышцы тренировки «${tpl.title}»", labels = false,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(titleText(tpl.title), style = T.title(20.sp))
                Text(
                    "~${Templates.minutes(tpl)} мин · ${tpl.slots.size} ${Ru.plural(tpl.slots.size, "упражнение", "упражнения", "упражнений")} · " +
                        "${tpl.slots.sumOf { it.sets }} ${Ru.plural(tpl.slots.sumOf { it.sets }, "подход", "подхода", "подходов")}",
                    style = T.secondary(13.sp),
                )
                Text(if (open) "Скрыть упражнения" else "Упражнения ›", style = T.body(13.sp, FontWeight.SemiBold).copy(color = F.c.accent))
            }
        }
        if (open) tpl.slots.forEach { s ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(forma.core.model.Catalog.find(s.exerciseId)?.name ?: s.exerciseId, style = T.body(14.sp), modifier = Modifier.weight(1f))
                Text("${s.sets} × ${s.repMin}–${s.repMax}", style = T.secondary(13.sp))
            }
        }
    }
}

/** The week day by day, and the gym and running days set by hand if wanted. */
@Composable
private fun SummaryWeek(d: ProfileDraft, program: Program) {
    SectionLabel("Неделя")
    FCard(gap = 0.dp) {
        (1..7).forEach { day ->
            val sessions = program.week.on(day)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(DAY_SHORT[day - 1], style = T.body(15.sp, FontWeight.ExtraBold).copy(color = if (sessions.isEmpty()) F.c.text3 else F.c.text), modifier = Modifier.width(28.dp))
                Text(
                    if (sessions.isEmpty()) "Отдых" else sessions.joinToString(" + ") { sessionTitle(it, program) },
                    style = T.body(15.sp).copy(color = if (sessions.isEmpty()) F.c.text3 else F.c.text),
                )
            }
        }
    }
    if ((1..7).all { program.week.on(it).isNotEmpty() }) {
        Text("В неделе нет дня отдыха, а без него прогресс встаёт. Оставь хотя бы один свободный день.", style = T.secondary(14.sp).copy(color = F.c.warn))
    }
    program.week.notes.forEach { Text("• $it", style = T.secondary(14.sp)) }
    var editWeek by rememberSaveable { mutableStateOf(d.gymDays != null) }
    if (!editWeek) GhostButton("Поменять дни зала и бега", { editWeek = true }, Modifier.fillMaxWidth())
    else {
        val planGym = remember(program) { program.week.days.filter { day -> day.sessions.any { it.kind == SessionKind.LIFT } }.map { it.day }.toSet() }
        val planRun = remember(program) { program.week.days.filter { day -> day.sessions.any { it.isRun } }.map { it.day }.toSet() }
        WeekGrid(d, planGym, planRun)
    }
}
