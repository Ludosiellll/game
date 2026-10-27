package forma.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import forma.app.data.Settings
import forma.app.data.TrainingRepository
import forma.app.ui.components.Hairline
import forma.app.sync.Cloud
import forma.app.ui.screens.BodyScreen
import forma.app.ui.screens.CalculatorsScreen
import forma.app.ui.screens.CloudScreen
import forma.app.ui.screens.CloudSignInScreen
import forma.app.ui.screens.ExerciseScreen
import forma.app.ui.screens.HistoryItemScreen
import forma.app.ui.screens.HistoryScreen
import forma.app.ui.screens.LiftDoneScreen
import forma.app.ui.screens.MoreScreen
import forma.app.ui.screens.OnboardingScreen
import forma.app.ui.screens.RELEASES
import forma.app.ui.screens.WhatsNewDialog
import forma.app.ui.screens.UpdateDialog
import forma.app.ui.screens.offerUpdate
import forma.app.update.Updater
import forma.app.ui.screens.unseenReleases
import kotlinx.coroutines.launch
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.ProfileScreen
import forma.app.ui.screens.ProgressScreen
import forma.app.ui.screens.RunActiveScreen
import forma.app.ui.screens.RunDoneScreen
import forma.app.ui.screens.RunSetupScreen
import forma.app.ui.screens.SettingsScreen
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Ic
import forma.app.ui.theme.Looks
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText

val LocalRepo = staticCompositionLocalOf<TrainingRepository> { error("repository not provided") }
val LocalNav = staticCompositionLocalOf<Navigator> { error("navigator not provided") }
val LocalSettings = staticCompositionLocalOf { Settings() }

/** Null in previews and tests: cloud screens then show "not configured". */
val LocalCloud = staticCompositionLocalOf<Cloud?> { null }

/** Who answers the coach's questions when not the cloud: a stand-in in tests (null — the cloud, when signed in). */
val LocalCoachApi = staticCompositionLocalOf<forma.app.sync.CoachApi?> { null }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppRoot() {
    val repo = LocalRepo.current
    val settings by repo.store.settings.collectAsState(initial = null)
    var onboarded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { onboarded = repo.isOnboarded() }
    val s = settings ?: return
    val ready = onboarded ?: return
    // A first install (the questionnaire still ahead) has nothing new to say: every release so far counts as seen.
    LaunchedEffect(ready) {
        if (!ready && s.newsSeen < RELEASES.last().id) repo.updateSettings { it.copy(newsSeen = RELEASES.last().id) }
    }
    val scope = rememberCoroutineScope()
    // Saved with the instance state: rotation, a theme or font change, split screen keep the screen and the stack.
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator(if (ready) Screen.Today else Screen.Onboarding) }
    // Each screen in the stack keeps its own state (search text, typed weights, the open tab) while others are on top.
    val holder = rememberSaveableStateHolder()
    val ids = nav.stack.map { it.id }.toSet()
    val known = remember { mutableSetOf<Long>() }
    LaunchedEffect(ids) {
        (known - ids).forEach { holder.removeState(it) }
        known.clear()
        known += ids
    }
    FormaTheme(Looks.byId(s.look)) {
        val view = LocalView.current
        val dark = F.c.dark
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalSettings provides s) {
            BackHandler(enabled = nav.canGoBack) { nav.back() }
            Column(Modifier.fillMaxSize().background(F.c.bg)) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(targetState = nav.currentEntry, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { entry ->
                        holder.SaveableStateProvider(entry.id) { ScreenFor(entry.screen) }
                    }
                }
                // Typing (the coach's chat): the keyboard takes the bar's place, the field sits right on it.
                if (nav.current in TABS && !WindowInsets.isImeVisible) BottomBar(nav)
            }
            // After an update: what is new, once, over the app — not over a run going on or the first questions.
            val news = unseenReleases(s.newsSeen)
            val busy = nav.current is Screen.RunActive || nav.current is Screen.Onboarding || nav.current is Screen.Tour
            if (ready && news.isNotEmpty() && !busy) {
                WhatsNewDialog(news) { scope.launch { repo.updateSettings { it.copy(newsSeen = RELEASES.last().id) } } }
            }
            // A newer version published: offered on opening the app — never over a run or a session going on (installing
            // closes the app), nor before what is new in this one was said.
            LaunchedEffect(Unit) { Updater.check() }
            val update by Updater.state.collectAsState()
            val later by Updater.later.collectAsState()
            val asked by Updater.asked.collectAsState()
            val running by forma.app.run.RunSession.ui.collectAsState()
            val session by remember { repo.db.workouts().observeActive() }.collectAsState(initial = null)
            if (offerUpdate(ready, news.isEmpty(), busy, later, running != null, session != null || nav.current is Screen.Workout, asked, update)) {
                UpdateDialog(update) { Updater.later.value = true }
            }
        }
    }
}

private val TABS = listOf(Screen.Today, Screen.Plan, Screen.Coach(), Screen.Progress, Screen.More)

@Composable
private fun ScreenFor(screen: Screen) {
    when (screen) {
        Screen.Onboarding -> OnboardingScreen()
        Screen.Today -> TodayScreen()
        Screen.Plan -> PlanScreen()
        Screen.Progress -> ProgressScreen()
        Screen.More -> MoreScreen()
        is Screen.Workout -> WorkoutScreen(screen.workoutId)
        is Screen.LiftDone -> LiftDoneScreen(screen.workoutId)
        is Screen.RunSetup -> RunSetupScreen(screen.workout, screen.kind)
        Screen.RunActive -> RunActiveScreen()
        is Screen.RunDone -> RunDoneScreen(screen.workoutId)
        is Screen.RunManual -> forma.app.ui.screens.RunManualScreen(screen.workout)
        is Screen.Day -> forma.app.ui.screens.DayScreen(java.time.LocalDate.ofEpochDay(screen.epochDay))
        is Screen.Exercise -> ExerciseScreen(screen.exerciseId)
        Screen.History -> HistoryScreen()
        is Screen.HistoryItem -> HistoryItemScreen(screen.workoutId)
        Screen.Calculators -> CalculatorsScreen()
        Screen.Settings -> SettingsScreen()
        Screen.Profile -> ProfileScreen()
        Screen.Body -> BodyScreen()
        Screen.Cloud -> CloudScreen()
        is Screen.CloudSignIn -> CloudSignInScreen(screen.restore)
        is Screen.Library -> forma.app.ui.screens.LibraryScreen(screen.mode)
        Screen.Friends -> forma.app.ui.screens.FriendsScreen()
        is Screen.Friend -> forma.app.ui.screens.FriendScreen(screen.userId, screen.name)
        Screen.IdealPlan -> forma.app.ui.screens.IdealPlanScreen()
        Screen.MyExercises -> forma.app.ui.screens.MyExercisesScreen()
        Screen.GymGoal -> forma.app.ui.screens.GymGoalScreen()
        is Screen.Tour -> forma.app.ui.screens.TourScreen(screen.first)
        Screen.Help -> forma.app.ui.screens.HelpScreen()
        is Screen.Coach -> forma.app.ui.screens.CoachScreen(screen.question, screen.workoutId)
    }
}

@Composable
private fun BottomBar(nav: Navigator) {
    Column(Modifier.fillMaxWidth().background(if (F.type.capsTitles) F.c.text else F.c.bg).navigationBarsPadding()) {
        if (!F.type.capsTitles) Hairline()
        Row(Modifier.fillMaxWidth().height(if (F.type.capsTitles) 60.dp else 72.dp)) {
            TabItem("Сегодня", Ic.home, nav.current == Screen.Today) { nav.tab(Screen.Today) }
            TabItem("План", Ic.calendar, nav.current == Screen.Plan) { nav.tab(Screen.Plan) }
            TabItem("Тренер", Ic.chat, nav.current == Screen.Coach()) { nav.tab(Screen.Coach()) }
            TabItem("Прогресс", Ic.chart, nav.current == Screen.Progress) { nav.tab(Screen.Progress) }
            TabItem("Ещё", Ic.more, nav.current == Screen.More) { nav.tab(Screen.More) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val poster = F.type.capsTitles
    val bloom = (F.look.id == "tone")
    val color = when {
        poster -> if (selected) F.c.text else F.c.bg
        selected -> if (bloom) F.c.text else F.c.accent
        else -> F.c.text3
    }
    Column(
        Modifier.weight(1f).fillMaxSize().background(if (poster && selected) F.c.accent else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (!poster) {
            Box(
                Modifier.size(width = 56.dp, height = 30.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (bloom && selected) F.c.surface2 else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (bloom && selected) F.c.accent else color, modifier = Modifier.size(22.dp)) }
        }
        Text(labelText(label), style = T.body(if (poster) 12.sp else 11.sp, if (selected) FontWeight.ExtraBold else FontWeight.SemiBold).copy(color = color))
    }
}
