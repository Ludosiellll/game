package forma.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import forma.app.ui.components.GhostButton
import forma.app.ui.components.PrimaryButton
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.app.update.Updater

/**
 * Whether the update window is shown: a newer version (or one on its way in), the app set up and what is new in this
 * version said, not «Позже» in this run. Installing closes the app: never during a run; with a gym session open only
 * when asked for by hand (its sets are saved and it goes on after); one already being installed stays on screen.
 */
internal fun offerUpdate(
    ready: Boolean, newsSaid: Boolean, busy: Boolean, later: Boolean, running: Boolean, lifting: Boolean, asked: Boolean, state: Updater.State,
): Boolean = state !is Updater.State.Idle && ready && newsSaid && !busy && !later &&
    (state is Updater.State.Installing || !running && (!lifting || asked))

/**
 * «Вышло обновление»: what is new in plain words and «Обновить» — downloaded and installed in place, the progress shown.
 * The first time Android wants the app allowed to install: the settings open, and on return it goes on by itself.
 */
@Composable
fun UpdateDialog(state: Updater.State, onLater: () -> Unit) {
    if (state is Updater.State.Idle) return
    val context = LocalContext.current
    val busy = state is Updater.State.Downloading || state is Updater.State.Installing
    fun go(u: forma.app.update.Update) = Updater.start(context, u)
    // Downloaded for the phone's own installer (Xiaomi): it opens by itself once, again on «Открыть установщик».
    if (state is Updater.State.Ready) {
        androidx.compose.runtime.LaunchedEffect(state) { Updater.openInstaller(context) }
    }
    // Back from the system's settings with installs allowed: the update goes on without another tap.
    if (state is Updater.State.NeedsPermission) {
        androidx.lifecycle.compose.LifecycleResumeEffect(state) {
            if (Updater.canInstall(context)) go(state.update)
            onPauseOrDispose {}
        }
    }
    val tall = LocalConfiguration.current.screenHeightDp
    Dialog(onDismissRequest = { if (!busy) onLater() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !busy, dismissOnBackPress = !busy)) {
        Column(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = (tall * 0.88f).dp)
                .clip(RoundedCornerShape(F.shape.radius)).background(F.c.surface),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(F.c.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Ic.up, null, tint = F.c.accent, modifier = Modifier.size(22.dp))
                    }
                    Column {
                        Text("ОБНОВЛЕНИЕ", style = T.body(12.sp, FontWeight.Bold).copy(color = F.c.accent, letterSpacing = 2.sp))
                        Text("Вышла новая версия", style = T.title(24.sp))
                    }
                }
                when (state) {
                    is Updater.State.Available -> Notes(state.update.notes)
                    is Updater.State.NeedsPermission -> Text(
                        "Чтобы Splity обновлялось само, разреши ему ставить приложения: откроются настройки — включи переключатель " +
                            "«Разрешить из этого источника» и вернись сюда. Это нужно один раз.",
                        style = T.body(15.sp),
                    )
                    is Updater.State.Downloading -> {
                        Text("Скачиваю… ${(state.done * 100).toInt()}\u00a0%", style = T.body(15.sp))
                        Bar(state.done)
                    }
                    is Updater.State.Installing -> Text(
                        "Ставлю обновление. Приложение закроется и обновится — потом открой его снова (придёт уведомление). " +
                            "Если телефон спросит «Установить?», нажми «Установить».",
                        style = T.body(15.sp),
                    )
                    is Updater.State.Ready -> Text(
                        "Обновление скачано и проверено. Сейчас откроется установщик телефона — нажми в нём «Обновить» " +
                            "(или «Установить»). Не появился или закрыл — нажми «Открыть установщик».",
                        style = T.body(15.sp),
                    )
                    is Updater.State.Failed -> {
                        Text(state.why, style = T.body(15.sp).copy(color = F.c.warn))
                        state.detail?.let { Text(it, style = T.secondary(12.sp)) }
                    }
                    Updater.State.Idle -> {}
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is Updater.State.Available -> {
                        PrimaryButton("Обновить", { go(state.update) }, Modifier.fillMaxWidth())
                        GhostButton("Позже", onLater, Modifier.fillMaxWidth())
                    }
                    is Updater.State.NeedsPermission -> {
                        PrimaryButton("Открыть настройки", {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }, Modifier.fillMaxWidth())
                        GhostButton("Позже", onLater, Modifier.fillMaxWidth())
                    }
                    is Updater.State.Ready -> {
                        PrimaryButton("Открыть установщик", { if (!Updater.openInstaller(context)) go(state.update) }, Modifier.fillMaxWidth())
                        GhostButton("Позже", onLater, Modifier.fillMaxWidth())
                    }
                    is Updater.State.Failed -> {
                        if (state.system) {
                            PrimaryButton("Открыть установщик", { if (!Updater.openInstaller(context)) go(state.update) }, Modifier.fillMaxWidth())
                            GhostButton("Попробовать ещё раз", { go(state.update) }, Modifier.fillMaxWidth())
                        } else {
                            PrimaryButton("Попробовать ещё раз", { go(state.update) }, Modifier.fillMaxWidth())
                        }
                        GhostButton("Позже", onLater, Modifier.fillMaxWidth())
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Notes(notes: List<String>) {
    if (notes.isEmpty()) {
        Text("Что нового — расскажу сразу после обновления.", style = T.body(15.sp))
        return
    }
    Text("Что нового:", style = T.body(15.sp, FontWeight.Bold))
    notes.forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("•", style = T.body(15.sp).copy(color = F.c.accent))
            Text(line, style = T.secondary(15.sp))
        }
    }
}

@Composable
private fun Bar(done: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(F.c.line)) {
        Box(Modifier.fillMaxWidth(done.coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(F.c.accent))
    }
}
