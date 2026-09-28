package io.github.feedbacklib.android.sample

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.ColorTheme
import io.github.feedbacklib.android.ExtendedBugReport
import io.github.feedbacklib.android.FeatureState
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.Option
import io.github.feedbacklib.android.RecordingButtonPosition
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.TextKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/** Keeps a checked set of an enum type across rotation and process death, as enum names (like InvocationEventsSaver). */
private fun <T : Enum<T>> enumSetSaver(valueOf: (String) -> T): Saver<Set<T>, ArrayList<String>> = Saver(
    save = { values -> ArrayList(values.map { it.name }) },
    restore = { names -> names.mapTo(mutableSetOf()) { valueOf(it) } },
)

/** Keeps a single enum value across rotation and process death, by name (like InvocationEventsSaver). */
private fun <T : Enum<T>> enumSaver(valueOf: (String) -> T): Saver<T, String> = Saver(
    save = { it.name },
    restore = valueOf,
)

/** Keeps an optional enum value across rotation and process death, by name, empty string for "none selected". */
private fun <T : Enum<T>> nullableEnumSaver(valueOf: (String) -> T): Saver<T?, String> = Saver(
    save = { it?.name.orEmpty() },
    restore = { name -> name.takeIf { it.isNotEmpty() }?.let(valueOf) },
)

private enum class CommentMinimum(val label: String, val count: Int) {
    NONE("None", 0),
    TEN("10", 10),
    TWENTY("20", 20),
}

private enum class SampleColor(val label: String, val argb: Int) {
    BLUE("Blue", 0xFF1565C0.toInt()),
    GREEN("Green", 0xFF2E7D32.toInt()),
    ORANGE("Orange", 0xFFEF6C00.toInt()),
}

/**
 * Every report UI switch the sample exposes, so each stage-4 feature can be exercised by hand.
 * Each toggle's state survives rotation and process death via `rememberSaveable`, and is applied to
 * the SDK from a `LaunchedEffect` that also runs on first composition, so a restored screen and the
 * running SDK agree after process death.
 */
@Composable
fun ReportUiControls(onOpenSavedReports: () -> Unit, onOpenSecureScreen: () -> Unit) {
    SectionTitle("Report UI")
    FlowRow {
        OutlinedButton(onClick = { BugReporting.show(ReportType.BUG) }) { Text("Report a bug") }
        OutlinedButton(onClick = { BugReporting.show(ReportType.FEEDBACK) }) { Text("Suggest") }
        OutlinedButton(onClick = { BugReporting.show(ReportType.QUESTION) }) { Text("Ask") }
    }

    SectionTitle("Report types")
    var selectedReportTypes by rememberSaveable(stateSaver = enumSetSaver(ReportType::valueOf)) {
        mutableStateOf(setOf(ReportType.BUG, ReportType.FEEDBACK, ReportType.QUESTION))
    }
    LaunchedEffect(selectedReportTypes) {
        BugReporting.setReportTypes(*selectedReportTypes.toTypedArray())
    }
    val onlyOneReportTypeLeft = selectedReportTypes.size == 1
    LabeledCheckbox(
        label = "Bug",
        checked = ReportType.BUG in selectedReportTypes,
        enabled = !(onlyOneReportTypeLeft && ReportType.BUG in selectedReportTypes),
        onCheckedChange = { checked ->
            selectedReportTypes = if (checked) selectedReportTypes + ReportType.BUG else selectedReportTypes - ReportType.BUG
        },
    )
    LabeledCheckbox(
        label = "Feedback",
        checked = ReportType.FEEDBACK in selectedReportTypes,
        enabled = !(onlyOneReportTypeLeft && ReportType.FEEDBACK in selectedReportTypes),
        onCheckedChange = { checked ->
            selectedReportTypes = if (checked) selectedReportTypes + ReportType.FEEDBACK else selectedReportTypes - ReportType.FEEDBACK
        },
    )
    LabeledCheckbox(
        label = "Question",
        checked = ReportType.QUESTION in selectedReportTypes,
        enabled = !(onlyOneReportTypeLeft && ReportType.QUESTION in selectedReportTypes),
        onCheckedChange = { checked ->
            selectedReportTypes = if (checked) selectedReportTypes + ReportType.QUESTION else selectedReportTypes - ReportType.QUESTION
        },
    )

    SectionTitle("Options")
    var selectedOptions by rememberSaveable(stateSaver = enumSetSaver(Option::valueOf)) {
        mutableStateOf(emptySet<Option>())
    }
    LaunchedEffect(selectedOptions) {
        BugReporting.setOptions(*selectedOptions.toTypedArray())
    }
    LabeledCheckbox(
        label = "Email optional",
        checked = Option.EMAIL_FIELD_OPTIONAL in selectedOptions,
        onCheckedChange = { checked ->
            selectedOptions = if (checked) selectedOptions + Option.EMAIL_FIELD_OPTIONAL else selectedOptions - Option.EMAIL_FIELD_OPTIONAL
        },
    )
    LabeledCheckbox(
        label = "Email hidden",
        checked = Option.EMAIL_FIELD_HIDDEN in selectedOptions,
        onCheckedChange = { checked ->
            selectedOptions = if (checked) selectedOptions + Option.EMAIL_FIELD_HIDDEN else selectedOptions - Option.EMAIL_FIELD_HIDDEN
        },
    )
    LabeledCheckbox(
        label = "Comment required",
        checked = Option.COMMENT_FIELD_REQUIRED in selectedOptions,
        onCheckedChange = { checked ->
            selectedOptions = if (checked) selectedOptions + Option.COMMENT_FIELD_REQUIRED else selectedOptions - Option.COMMENT_FIELD_REQUIRED
        },
    )

    SectionTitle("Comment minimum")
    var commentMinimum by rememberSaveable(stateSaver = enumSaver(CommentMinimum::valueOf)) {
        mutableStateOf(CommentMinimum.NONE)
    }
    LaunchedEffect(commentMinimum) {
        BugReporting.setCommentMinimumCharacterCount(commentMinimum.count)
    }
    Row {
        CommentMinimum.entries.forEach { option ->
            FilterChip(
                selected = commentMinimum == option,
                onClick = { commentMinimum = option },
                label = { Text(option.label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }

    SectionTitle("Extended bug report")
    var extendedState by rememberSaveable(stateSaver = enumSaver(ExtendedBugReport.State::valueOf)) {
        mutableStateOf(ExtendedBugReport.State.DISABLED)
    }
    LaunchedEffect(extendedState) {
        BugReporting.setExtendedBugReportState(extendedState)
    }
    RadioOption(
        label = "Disabled",
        selected = extendedState == ExtendedBugReport.State.DISABLED,
        onClick = { extendedState = ExtendedBugReport.State.DISABLED },
    )
    RadioOption(
        label = "Required fields",
        selected = extendedState == ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS,
        onClick = { extendedState = ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS },
    )
    RadioOption(
        label = "Optional fields",
        selected = extendedState == ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS,
        onClick = { extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS },
    )
    var customHints by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(customHints) {
        if (customHints) {
            BugReporting.setExtendedBugReportHints(
                "Open Wi-Fi settings, tap Scan",
                "The list stays empty",
                "My networks are listed",
            )
        } else {
            BugReporting.setExtendedBugReportHints(null, null, null)
        }
    }
    LabeledCheckbox(label = "Custom hints", checked = customHints, onCheckedChange = { customHints = it })

    SectionTitle("Attachments")
    var invocationScreenshot by rememberSaveable { mutableStateOf(true) }
    var extraScreenshot by rememberSaveable { mutableStateOf(true) }
    var gallery by rememberSaveable { mutableStateOf(true) }
    var screenRecording by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(invocationScreenshot, extraScreenshot, gallery, screenRecording) {
        BugReporting.setAttachmentTypesEnabled(invocationScreenshot, extraScreenshot, gallery, screenRecording)
    }
    LabeledCheckbox(label = "Invocation screenshot", checked = invocationScreenshot, onCheckedChange = { invocationScreenshot = it })
    LabeledCheckbox(label = "Extra screenshot", checked = extraScreenshot, onCheckedChange = { extraScreenshot = it })
    LabeledCheckbox(label = "Gallery", checked = gallery, onCheckedChange = { gallery = it })
    LabeledCheckbox(label = "Screen recording", checked = screenRecording, onCheckedChange = { screenRecording = it })
    Text(text = "At most 4 per report, the invocation screenshot included", style = MaterialTheme.typography.labelSmall)

    SectionTitle("Host files")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    FlowRow {
        OutlinedButton(onClick = {
            FeedbackKit.addFileAttachment("Sample log written at ${Date()}".toByteArray(), "sample-log.txt")
        }) { Text("Attach a log") }
        OutlinedButton(onClick = {
            FeedbackKit.addFileAttachment("{\"sample\":true}".toByteArray(), "settings.json")
        }) { Text("Attach settings") }
        OutlinedButton(onClick = {
            scope.launch {
                val uri = withContext(Dispatchers.IO) { writeHostFile(context) }
                FeedbackKit.addFileAttachment(uri, HOST_FILE_NAME)
            }
        }) { Text("Attach by content Uri") }
        TextButton(onClick = { FeedbackKit.clearFileAttachments() }) { Text("Clear host files") }
    }
    Text(text = "Up to 3 files go with every report and never show in the form", style = MaterialTheme.typography.labelSmall)

    SectionTitle("Screens to capture")
    FlowRow {
        OutlinedButton(onClick = onOpenSecureScreen) { Text("Open a secure screen") }
        OutlinedButton(onClick = onOpenSavedReports) { Text("Open saved reports") }
    }
    Text(text = "In capture mode, walk to any screen and tap Capture; the secure one gives the placeholder", style = MaterialTheme.typography.labelSmall)

    SectionTitle("Screen recording")
    var recordingPosition by rememberSaveable(stateSaver = enumSaver(RecordingButtonPosition::valueOf)) {
        mutableStateOf(RecordingButtonPosition.BOTTOM_RIGHT)
    }
    LaunchedEffect(recordingPosition) {
        BugReporting.setVideoRecordingButtonPosition(recordingPosition)
    }
    RecordingButtonPosition.entries.forEach { position ->
        RadioOption(
            label = "Stop control: ${position.name.lowercase().replace('_', ' ')}",
            selected = recordingPosition == position,
            onClick = { recordingPosition = position },
        )
    }
    var autoRecording by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoRecording) {
        BugReporting.setAutoScreenRecordingEnabled(autoRecording)
    }
    LabeledSwitch("Auto screen recording [beta, internal testing only]", autoRecording) { autoRecording = it }
    Text(
        text = "Record screen in the form asks for consent every time and stops at 60 s. Auto recording asks once per process and attaches the last 30 s to each report.",
        style = MaterialTheme.typography.labelSmall,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // The SDK never asks for it: showing the recording notification is the host's call.
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        OutlinedButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("Allow the recording notification")
        }
    }

    SectionTitle("Theme")
    var colorTheme by rememberSaveable(stateSaver = enumSaver(ColorTheme::valueOf)) {
        mutableStateOf(ColorTheme.SYSTEM)
    }
    LaunchedEffect(colorTheme) {
        FeedbackKit.setColorTheme(colorTheme)
    }
    RadioOption(label = "System", selected = colorTheme == ColorTheme.SYSTEM, onClick = { colorTheme = ColorTheme.SYSTEM })
    RadioOption(label = "Light", selected = colorTheme == ColorTheme.LIGHT, onClick = { colorTheme = ColorTheme.LIGHT })
    RadioOption(label = "Dark", selected = colorTheme == ColorTheme.DARK, onClick = { colorTheme = ColorTheme.DARK })

    SectionTitle("Primary colour")
    var primaryColor by rememberSaveable(stateSaver = nullableEnumSaver(SampleColor::valueOf)) {
        mutableStateOf<SampleColor?>(null)
    }
    // No LaunchedEffect(Unit) fallback here: there is no API to reset to the SDK's default colour,
    // so an unselected chip (the initial state) must never call setPrimaryColor.
    LaunchedEffect(primaryColor) {
        primaryColor?.let { FeedbackKit.setPrimaryColor(it.argb) }
    }
    Row {
        SampleColor.entries.forEach { color ->
            FilterChip(
                selected = primaryColor == color,
                onClick = { primaryColor = color },
                label = { Text(color.label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    Text(text = "SDK default until you pick one", style = MaterialTheme.typography.labelSmall)

    SectionTitle("Custom texts")
    var customTexts by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(customTexts) {
        FeedbackKit.setCustomTexts(
            if (customTexts) {
                mapOf(TextKey.PROMPT_TITLE to "What happened?", TextKey.SUCCESS_TITLE to "Got it, thanks!")
            } else {
                emptyMap()
            },
        )
    }
    LabeledCheckbox(label = "Custom texts", checked = customTexts, onCheckedChange = { customTexts = it })

    SectionTitle("Bug reporting")
    var bugReportingEnabled by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(bugReportingEnabled) {
        BugReporting.setState(if (bugReportingEnabled) FeatureState.ENABLED else FeatureState.DISABLED)
    }
    LabeledSwitch("Bug reporting enabled", bugReportingEnabled) { bugReportingEnabled = it }

    SectionTitle("Proactive reporting")
    var proactive by remember { mutableStateOf(SampleSettings.proactiveEnabled(context)) }
    var noGap by remember { mutableStateOf(SampleSettings.proactiveNoGap(context)) }
    LaunchedEffect(proactive, noGap) {
        SampleSettings.setProactive(context, proactive, noGap)
        // Applies to a prompt of this run not shown yet; the next run reads it before build().
        BugReporting.setProactiveReportingConfigurations(SampleSettings.proactiveConfigs(context))
    }
    LabeledSwitch("Ask after a crash, an ANR or a force restart", proactive) { proactive = it }
    LabeledSwitch("No gap between prompts (testing; the SDK default is 24 h)", noGap) { noGap = it }
    FlowRow {
        OutlinedButton(onClick = { throw IllegalStateException("Sample crash for proactive reporting") }) { Text("Crash now") }
        OutlinedButton(onClick = { Thread { throw IllegalStateException("Sample crash on a background thread") }.start() }) { Text("Crash in background") }
        // Blocks the main thread: the system offers to close the app (an ANR exit on Android 11+).
        OutlinedButton(onClick = { Thread.sleep(20_000) }) { Text("Freeze (ANR)") }
    }
    Text(
        text = "Open the app again after a crash: the prompt comes 2 s after the first screen. Force restart: swipe the app away in Recents while it is on screen and open it within 5 s.",
        style = MaterialTheme.typography.labelSmall,
    )

    SectionTitle("Email for the form")
    var email by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { FeedbackKit.identifyUser(email.ifBlank { null }, null) }) {
            Text("Apply")
        }
    }

    SectionTitle("Last dismiss")
    var lastDismiss by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        BugReporting.setOnDismissCallback { type, reportType -> lastDismiss = "$type · $reportType" }
        onDispose { BugReporting.setOnDismissCallback(null) }
    }
    Text(text = lastDismiss?.let { "Last dismiss: $it" } ?: "Last dismiss: none")

    Button(onClick = onOpenSavedReports, modifier = Modifier.padding(top = 24.dp)) {
        Text("Saved reports")
    }
}

private const val HOST_FILE_NAME = "app-state.txt"

/**
 * Writes a small file into `filesDir/host-files/` and returns its content Uri from the sample's
 * FileProvider (`res/xml/report_paths.xml`), as a host would hand over a file it owns. The SDK reads
 * it when a report is queued, so it always carries the latest write. Disk I/O.
 */
private fun writeHostFile(context: Context): Uri {
    val file = File(context.filesDir, "host-files/$HOST_FILE_NAME")
    file.parentFile!!.mkdirs()
    file.writeText("Sample app state written at ${Date()}\n")
    return FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp),
    )
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(text = label)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label)
    }
}
