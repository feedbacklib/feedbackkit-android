package io.github.feedbacklib.android.sample

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Zips LocalReportSender delivered, newest first, each with Share (spec §9). */
class SavedReportsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SampleTheme { SavedReportsScreen(load = { savedReports(this) }, onShare = ::share) } }
    }

    private fun share(zip: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.reports", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share report"))
    }
}

/**
 * A saved report zip with its size and modification time already read (R10: composition does no
 * file I/O; [savedReports] reads both once, off the main thread).
 */
internal data class SavedReport(val file: File, val sizeBytes: Long, val lastModified: Long)

/** Where LocalReportSender writes (spec §9: getExternalFilesDir("feedbackkit-reports")/<id>.zip). Disk I/O. */
internal fun savedReports(context: Context): List<SavedReport> =
    context.getExternalFilesDir("feedbackkit-reports")
        ?.listFiles { file -> file.extension == "zip" }
        .orEmpty()
        .map { SavedReport(file = it, sizeBytes = it.length(), lastModified = it.lastModified()) }
        .sortedByDescending { it.lastModified }

@Composable
internal fun SavedReportsScreen(load: () -> List<SavedReport>, onShare: (File) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val reports by produceState(initialValue = emptyList(), refresh) {
        value = withContext(Dispatchers.IO) { load() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Text(text = "Saved reports", style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = { refresh++ }) { Text("Refresh") }
        if (reports.isEmpty()) {
            Text("No reports yet. Send one from the main screen.")
        } else {
            LazyColumn {
                items(reports, key = { it.file.absolutePath }) { report ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = report.file.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${report.sizeBytes / 1024} KB · " +
                                    DateFormat.getDateTimeInstance().format(Date(report.lastModified)),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { onShare(report.file) }) { Text("Share") }
                    }
                }
            }
        }
    }
}
