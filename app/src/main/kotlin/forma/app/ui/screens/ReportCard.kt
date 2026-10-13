package forma.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import forma.app.BuildConfig
import forma.app.data.BackupManager
import forma.app.data.TrainingReport
import forma.app.ui.LocalRepo
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Every session, set and lap as a readable file: to send to an AI for an analysis or to read oneself. */
@Composable
fun ReportCard() {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun text(): String {
        val b = BackupManager(repo).snapshot()
        return withContext(Dispatchers.Default) { TrainingReport.build(b, LocalDate.now(), BuildConfig.VERSION_NAME) }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                val t = text()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(t.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось открыть файл")
                }
                "Файл сохранён."
            }.getOrElse { "Не получилось сохранить: ${it.message}" }
        }
    }
    FCard {
        Text("Выгрузка для разбора", style = T.secondary(13.sp))
        Text(
            "Все тренировки — каждый подход и круг, что предлагало приложение и что оно решило после, нагрузка и прогресс — " +
                "одним текстовым файлом. Отправь его ИИ на разбор или читай сам.",
            style = T.body(14.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Сохранить файл", { saver.launch(TrainingReport.fileName(LocalDate.now())) }, Modifier.weight(1f))
            GhostButton("Отправить", {
                scope.launch {
                    message = runCatching {
                        val t = text()
                        val file = withContext(Dispatchers.IO) {
                            File(context.cacheDir, "reports").apply { mkdirs() }.resolve(TrainingReport.fileName(LocalDate.now())).apply { writeText(t) }
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Форма — тренировки").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Отправить выгрузку").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        null
                    }.getOrElse { "Не получилось: ${it.message}" }
                }
            }, Modifier.weight(1f))
        }
        message?.let { Text(it, style = T.secondary(13.sp)) }
    }
}
