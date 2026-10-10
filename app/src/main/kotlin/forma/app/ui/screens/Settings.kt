package forma.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.BuildConfig
import forma.app.data.BackupManager
import forma.app.data.Settings
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.components.Banner
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.Looks
import forma.app.ui.theme.T
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val s = LocalSettings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    fun save(f: (Settings) -> Settings) = scope.launch { repo.store.setSettings(f(repo.store.settingsNow())) }
    val backup = remember { BackupManager(repo) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { backup.export(context, uri); "Копия сохранена." }.getOrElse { "Не получилось сохранить: ${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { "Восстановлено тренировок: ${backup.import(context, uri)}." }.getOrElse { "Файл не подошёл: ${it.message}" }
        }
    }
    ScrollScreen {
        TopBar("Оформление и звук", onBack = { nav.back() })
        SectionLabel("Стиль")
        Looks.all.forEach { look ->
            val sel = look.id == s.look
            val shape = RoundedCornerShape(F.shape.radius)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(F.c.surface)
                    .border(if (sel) 2.dp else 1.dp, if (sel) F.c.accent else F.c.line, shape)
                    .clickable { save { it.copy(look = look.id) } }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    listOf(look.palette.bg, look.palette.accent, look.palette.endurance).forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(c).border(1.dp, F.c.line, CircleShape))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(look.name, style = T.body(16.sp, FontWeight.ExtraBold))
                    Text(look.note, style = T.secondary(13.sp))
                }
                if (sel) Text("✓", style = T.body(18.sp, FontWeight.ExtraBold).copy(color = F.c.accent))
            }
        }
        SectionLabel("Тренировка")
        FCard(gap = 0.dp) {
            Toggle("Таймер отдыха сам", "Запускается после каждого подхода", s.autoRest) { v -> save { it.copy(autoRest = v) } }
            Hairline()
            Toggle("Не гасить экран", "Во время тренировки в зале и пробежки", s.keepScreenOn) { v -> save { it.copy(keepScreenOn = v) } }
            Hairline()
            Toggle("Голосовые подсказки", "На пробежке: отрезки, километры, финиш", s.voice) { v -> save { it.copy(voice = v) } }
            Hairline()
            Toggle("Звуковые сигналы", "Обратный отсчёт 3-2-1, конец отдыха", s.beeps) { v -> save { it.copy(beeps = v) } }
            Hairline()
            Toggle("Вибрация", "Смена отрезка и конец отдыха", s.vibration) { v -> save { it.copy(vibration = v) } }
            Hairline()
            Toggle("GPS по умолчанию", "Дистанция и темп на улице", s.gps) { v -> save { it.copy(gps = v) } }
        }
        SectionLabel("Резервная копия")
        FCard {
            Text("Все тренировки, прогресс и настройки — в одном файле. Сохрани в облако или отправь себе.", style = T.secondary(14.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Сохранить", { exporter.launch("forma-${LocalDate.now()}.json") }, Modifier.weight(1f))
                GhostButton("Восстановить", { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.weight(1f))
            }
            Text("Восстановление заменяет данные на телефоне. Если включено облако, копия из облака потом добавится к ним.", style = T.secondary(12.sp))
        }
        message?.let { Banner(it, F.c.accent) }
        Text("Форма ${BuildConfig.VERSION_NAME}. Шрифты Manrope, Onest, Rubik, JetBrains Mono, Sofia Sans Extra Condensed — SIL Open Font License.", style = T.secondary(12.sp))
    }
}

@Composable
fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body(15.sp, FontWeight.SemiBold))
            Text(subtitle, style = T.secondary(13.sp))
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = F.c.accent, checkedThumbColor = F.c.onAccent, uncheckedTrackColor = F.c.surface2, uncheckedBorderColor = F.c.line))
    }
}
