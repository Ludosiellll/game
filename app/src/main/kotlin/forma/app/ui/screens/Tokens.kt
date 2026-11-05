package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.sync.CoachException
import forma.app.sync.TokenUsage
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.text.Ru
import java.time.Instant
import java.time.ZoneId

/**
 * «Токены ИИ» (Denis, 5 November: «только я и Егор … сколько токенов осталось, сколько потрачено, на что»): what is left
 * on the coach's DeepSeek key, who of the two spent how much, and its last requests. [load] is the cloud's by default.
 */
@Composable
fun TokensScreen(load: (suspend () -> TokenUsage)? = null) {
    val nav = LocalNav.current
    val cloud = LocalCloud.current
    val fetch = load ?: cloud?.let { c -> suspend { c.tokenUsage() } }
    var usage by remember { mutableStateOf<TokenUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        error = null
        if (fetch == null) {
            error = CoachException.Reason.NOT_SIGNED_IN.text
            return@LaunchedEffect
        }
        try {
            usage = fetch()
        } catch (e: CoachException) {
            error = e.reason.text
        }
    }
    ScrollScreen {
        TopBar("Токены ИИ", "Ключ DeepSeek: тренер для тебя и Егора", onBack = { nav.back() })
        val u = usage
        when {
            u == null && error == null -> Text("Загружаю…", style = T.secondary(14.sp))
            u == null -> Text(error!!, style = T.secondary(14.sp).copy(color = F.c.warn))
        }
        if (u != null) {
            FCard(gap = 8.dp) {
                SectionLabel("Остаток")
                val left = u.tokensLeft
                Text(if (left != null) "≈ ${tokens(left)} токенов" else "Остаток не пришёл", style = T.title(26.sp))
                if (left != null && u.tokensAll > 0) {
                    val spent = (u.tokensAll - left).coerceAtLeast(0.0)
                    val share = (left / u.tokensAll).coerceIn(0.0, 1.0)
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(F.c.surface2)) {
                        Box(Modifier.fillMaxWidth(share.toFloat()).height(8.dp).background(F.c.accent))
                    }
                    Text("Из ${tokens(u.tokensAll)}. Потрачено ${tokens(spent)} (${pct(spent / u.tokensAll)}).", style = T.secondary(13.sp))
                }
            }
            if (u.people.isNotEmpty()) FCard(gap = 0.dp) {
                SectionLabel("Кто сколько потратил")
                u.people.forEachIndexed { i, p ->
                    if (i > 0) Hairline()
                    Text((if (p.me) "Ты" else p.name), style = T.body(15.sp, androidx.compose.ui.text.font.FontWeight.Bold), modifier = Modifier.padding8())
                    Text(
                        "${p.requests} ${Ru.plural(p.requests, "вопрос", "вопроса", "вопросов")} · ${tokens(p.units / u.rate)} токенов" +
                            " · за 7 дней ${p.weekRequests} · ${tokens(p.weekUnits / u.rate)}",
                        style = T.secondary(13.sp),
                    )
                }
                Text("Считается с 5 ноября, когда подключили DeepSeek.", style = T.secondary(12.sp).copy(color = F.c.text3), modifier = Modifier.padding8())
            }
            FCard(gap = 0.dp) {
                SectionLabel("Последние запросы")
                if (u.log.isEmpty()) Text("Пока ни одного.", style = T.secondary(13.sp), modifier = Modifier.padding8())
                u.log.forEachIndexed { i, l ->
                    if (i > 0) Hairline()
                    // The seller's log marks a failure by an error text or a status like «error» / 429.
                    val st = l.status?.lowercase()
                    val ok = l.error.isNullOrBlank() && st !in setOf("error", "failed", "failure", "fail") && (st?.toIntOrNull() ?: 200) < 400
                    Text(atText(l.at) + (l.ms?.let { " · ${dec(it / 1000.0, 1)} с" } ?: ""), style = T.body(14.sp), modifier = Modifier.padding8())
                    Text(
                        listOfNotNull(
                            l.input?.let { "вопрос и данные $it" },
                            l.output?.let { "ответ $it" },
                            l.units?.let { "списано ${tokens(it / u.rate)} токенов" },
                            if (ok) null else "ошибка" + (l.error?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: l.status?.let { ": $it" } ?: ""),
                        ).joinToString(" · "),
                        style = T.secondary(13.sp).let { if (ok) it else it.copy(color = F.c.bad) },
                    )
                }
            }
            FCard(gap = 6.dp) {
                SectionLabel("Как тратить меньше")
                Text(
                    "С каждым вопросом тренеру уходят сводка тренировок и вся переписка, поэтому длинная переписка делает каждый вопрос дороже. " +
                        "Новая тема — начни переписку заново. Не больше 6 вопросов в секунду и 120 в минуту. " +
                        "Если DeepSeek не ответит или токены кончатся, тренер ответит через Gemini.",
                    style = T.secondary(13.sp),
                )
            }
        }
        GhostButton("Обновить", { round++ }, Modifier.fillMaxWidth())
    }
}

private fun Modifier.padding8() = this.then(Modifier.padding(top = 8.dp))

/** «99,98 млн», «12,5 тыс.», «420»: the decimal comma on the number only, the unit keeps its dot. */
internal fun tokens(x: Double): String = when {
    x >= 1_000_000 -> "${dec(x / 1_000_000, 2)} млн"
    x >= 1_000 -> "${dec(x / 1_000, 1)} тыс."
    else -> dec(x, 0)
}

private fun dec(x: Double, digits: Int) = "%.${digits}f".format(java.util.Locale.ROOT, x).replace('.', ',')

private fun pct(x: Double) = if (x < 0.001 && x > 0) "меньше 0,1 %" else "${dec(x * 100, 1)} %"

/** «5 ноября, 17:34» in the phone's zone. */
private fun atText(at: String?): String = runCatching {
    val t = Instant.parse(at).atZone(ZoneId.systemDefault())
    "${Ru.date(t.toLocalDate())}, %d:%02d".format(t.hour, t.minute)
}.getOrDefault(at ?: "")
