package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.BuildConfig
import forma.app.data.BackupManager
import forma.app.data.TrainingReport
import forma.app.data.TrainingRepository
import forma.app.sync.CoachException
import forma.app.sync.CoachTurn
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.plan.SessionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Questions to start with: what newcomers ask, about their own day and numbers. */
private val STARTERS = listOf(
    "Почему сегодня такая тренировка?",
    "Объясни простыми словами мои цифры",
    "Как прошла неделя?",
    "Не успеваю в зал — что делать?",
    "Что съесть до и после тренировки?",
)

/** The summary sent with a question: today, the training as the readable report has it, and how the app works. */
suspend fun coachContext(repo: TrainingRepository, today: LocalDate = repo.today()): String {
    val program = repo.store.programNow()
    val todayLines = program?.week?.on(today.dayOfWeek.value).orEmpty().map { s ->
        if (s.kind == SessionKind.LIFT) {
            val plan = runCatching { repo.liftPlan(s.templateId!!, today) }.getOrNull()
            "- Силовая «${plan?.title ?: s.templateId}»" + (plan?.let { p -> ": " + p.items.joinToString("; ") { "${it.name} ${it.prescription.sets}×${it.prescription.reps}" + (it.prescription.weight?.let { w -> " ${forma.core.text.Ru.kg(w)} кг" } ?: "") } } ?: "")
        } else {
            val w = runCatching { repo.runFor(s.kind, today) }.getOrNull()
            "- Бег «${w?.title}»: ${w?.note.orEmpty()}"
        }
    }
    val b = BackupManager(repo).snapshot()
    val feel = TrainingReport.feelWords(repo.store.settingsNow().feels)
    val report = withContext(Dispatchers.Default) { TrainingReport.build(b, today, BuildConfig.VERSION_NAME, feel) }
    return buildString {
        appendLine("Сегодня ${forma.core.text.Ru.date(today)} ${today.year}.")
        appendLine(if (todayLines.isEmpty()) "По плану сегодня отдых." else "По плану сегодня:\n" + todayLines.joinToString("\n"))
        appendLine()
        appendLine(report.take(30_000))
        appendLine()
        appendLine("## Как устроено приложение")
        SCREENS.forEach { (name, text) -> appendLine("- $name: $text") }
        appendLine("## Словарь приложения")
        GLOSSARY.forEach { (word, text) -> appendLine("- $word: $text") }
    }
}

/**
 * «Спросить тренера»: questions about one's own training answered by an AI (Gemini, through the project's server
 * function) with the summary of the training. It explains and advises; the plan is the app's, changed only by the person.
 */
@Composable
fun CoachScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val cloud = LocalCloud.current
    val state = cloud?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<CoachTurn>() }
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        val c = cloud ?: run { error = CoachException.Reason.NOT_SET_UP.text; return }
        turns += CoachTurn(CoachTurn.USER, q)
        typed = ""
        error = null
        busy = true
        scope.launch {
            try {
                val answer = c.askCoach(turns.toList(), coachContext(repo))
                turns += CoachTurn(CoachTurn.MODEL, answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoachException) {
                error = e.reason.text
            } catch (e: Exception) {
                error = CoachException.Reason.FAILED.text
            } finally {
                busy = false
            }
        }
    }
    ScrollScreen {
        TopBar("Спросить тренера", "ИИ · Gemini", onBack = { nav.back() })
        Text(
            "Ответы пишет ИИ по твоим тренировкам. План он не меняет — подскажет, где это сделать. При боли или болезни — к врачу. " +
                "Вопрос и сводка тренировок (без имени и почты) уходят в Gemini от Google; на бесплатном тарифе Google может " +
                "использовать переписку для улучшения моделей.",
            style = T.secondary(12.sp),
        )
        if (cloud == null || state?.email == null) {
            FCard(gap = 8.dp) {
                Text("Тренер работает через облако: нужен вход по почте.", style = T.body(15.sp))
                GhostButton("Войти в облако", { nav.push(Screen.Cloud) }, Modifier.fillMaxWidth())
            }
        }
        if (turns.isEmpty()) {
            Text("С чего начать:", style = T.secondary(14.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                STARTERS.forEach { q -> Chip(q, false, { ask(q) }) }
            }
        }
        turns.forEach { t -> Bubble(t) }
        if (busy) Text("Думаю…", style = T.secondary(14.sp))
        error?.let { Text(it, style = T.body(14.sp).copy(color = F.c.warn)) }
        TextInput(typed, { typed = it }, "Твой вопрос", placeholder = "Например: можно ли бегать на следующий день после ног?", maxLength = 500)
        PrimaryButton(if (busy) "Думаю…" else "Спросить", { ask(typed) }, Modifier.fillMaxWidth(), enabled = !busy && typed.isNotBlank())
        if (turns.isNotEmpty()) GhostButton("Новый разговор", { turns.clear(); error = null }, Modifier.fillMaxWidth())
    }
}

/** One message: the person's on the right, the coach's on the left. */
@Composable
private fun Bubble(t: CoachTurn) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t.mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(F.shape.radiusSmall + 6.dp))
                .background(if (t.mine) F.c.accent.copy(alpha = 0.18f) else F.c.surface).padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text(t.text, style = T.body(15.sp)) }
    }
}
