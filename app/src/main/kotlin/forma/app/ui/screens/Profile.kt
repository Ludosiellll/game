package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.model.Experience
import forma.core.model.RunLevel
import forma.core.model.Sex
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val profile by repo.store.profile.collectAsState(initial = null)
    val base = profile ?: return
    val d = remember(base) { ProfileDraft(base) }
    ScrollScreen {
        TopBar("Цель, дни и данные", "План пересчитается, прогресс по упражнениям сохранится", onBack = { nav.back() })
        AimPicker(d)
        SectionLabel("Дни", Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_SHORT.forEachIndexed { i, name ->
                val day = i + 1
                Chip(name, day in d.days, { d.days = if (day in d.days) d.days - day else d.days + day }, Modifier.weight(1f), minWidth = 0.dp, kind = forma.app.ui.components.ChipKind.TOGGLE)
            }
        }
        DoubleDaysToggle(d)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(45, 60, 75, 90).forEach { m -> Chip("$m мин", d.minutes == m, { d.minutes = m }, Modifier.weight(1f)) }
        }
        SectionLabel("Опыт в зале")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Новичок", d.experience == Experience.NOVICE, { d.experience = Experience.NOVICE }, Modifier.weight(1f))
            Chip("Средний", d.experience == Experience.INTERMEDIATE, { d.experience = Experience.INTERMEDIATE }, Modifier.weight(1f))
            Chip("Опытный", d.experience == Experience.ADVANCED, { d.experience = Experience.ADVANCED }, Modifier.weight(1f))
        }
        SectionLabel("Бег")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("С ходьбой", d.runLevel == RunLevel.NONE, { d.runLevel = RunLevel.NONE }, Modifier.weight(1f))
            Chip("20–30 мин", d.runLevel == RunLevel.BASE, { d.runLevel = RunLevel.BASE }, Modifier.weight(1f))
            Chip("Регулярно", d.runLevel == RunLevel.TRAINED, { d.runLevel = RunLevel.TRAINED }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3.0 to "3 км", 5.0 to "5 км", 10.0 to "10 км", 21.0975 to "21,1").forEach { (km, label) ->
                Chip(label, d.raceKm == km, { d.raceKm = if (d.raceKm == km) null else km }, Modifier.weight(1f))
            }
        }
        if (d.raceKm != null) NumberField(d.raceTime, { d.raceTime = it }, "Время недавнего забега", "мм:сс", keyboard = KeyboardType.Text, placeholder = "25:30")
        RunGoalPicker(d)
        SectionLabel("О себе")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Мужчина", d.sex == Sex.MALE, { d.sex = Sex.MALE }, Modifier.weight(1f))
            Chip("Женщина", d.sex == Sex.FEMALE, { d.sex = Sex.FEMALE }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(d.birthYear, { d.birthYear = it }, "Год рождения", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
            NumberField(d.weight, { d.weight = it }, "Вес", "кг", Modifier.weight(1f))
        }
        NumberField(d.height, { d.height = it }, "Рост", "см", keyboard = KeyboardType.Number, placeholder = "—")
        NumberField(d.restingHr, { d.restingHr = it }, "Пульс в покое", "уд/мин", keyboard = KeyboardType.Number, placeholder = "—")
        PrimaryButton("Сохранить и пересчитать план", {
            scope.launch {
                repo.updateProfile(d.toProfile(base))
                nav.back()
            }
        }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = d.days.isNotEmpty())
    }
}
