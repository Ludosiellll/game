package forma.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import forma.app.data.TrainingRepository
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Today's date that stays right while a screen is open: read again whenever the app comes to the front and just after
 * midnight (the app is often left open on Today overnight).
 */
@Composable
fun rememberToday(repo: TrainingRepository): LocalDate {
    var today by remember { mutableStateOf(repo.today()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                today = repo.today()
                val now = LocalDateTime.now()
                delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 2_000)
            }
        }
    }
    return today
}
