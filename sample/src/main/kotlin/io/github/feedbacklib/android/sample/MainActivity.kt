package io.github.feedbacklib.android.sample

import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.FeedbackKitInfo
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.feedbackKitPrivate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SampleTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleScreen(
                        onOpenSavedReports = { startActivity(Intent(this, SavedReportsActivity::class.java)) },
                        onOpenSecureScreen = { startActivity(Intent(this, SecureScreenActivity::class.java)) },
                    )
                }
            }
        }
    }
}

/** Keeps the checked invocation events across rotation and process death, as enum names. */
private val InvocationEventsSaver = Saver<Set<InvocationEvent>, ArrayList<String>>(
    save = { events -> ArrayList(events.map { it.name }) },
    restore = { names -> names.mapTo(mutableSetOf()) { InvocationEvent.valueOf(it) } },
)

@Composable
private fun SampleScreen(onOpenSavedReports: () -> Unit, onOpenSecureScreen: () -> Unit) {
    var selectedEvents by rememberSaveable(stateSaver = InvocationEventsSaver) {
        mutableStateOf(setOf(InvocationEvent.SHAKE, InvocationEvent.FLOATING_BUTTON))
    }
    var wifiPassword by remember { mutableStateOf("") }

    // Also runs on the first composition, so checkboxes restored after process death (when the SDK
    // was rebuilt with SampleApplication's defaults) are what the SDK uses.
    LaunchedEffect(selectedEvents) {
        val events = selectedEvents.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf(InvocationEvent.NONE)
        BugReporting.setInvocationEvents(*events)
    }

    fun onInvocationEventToggled(event: InvocationEvent, checked: Boolean) {
        selectedEvents = if (checked) selectedEvents + event else selectedEvents - event
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = "FeedbackKit ${FeedbackKitInfo.VERSION} · enabled: ${FeedbackKit.isEnabled}",
            style = MaterialTheme.typography.headlineSmall,
        )

        Button(
            onClick = { FeedbackKit.show() },
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text("Show FeedbackKit")
        }

        Text(
            text = "Invocation events",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )

        InvocationEventCheckbox(
            label = "Shake",
            checked = InvocationEvent.SHAKE in selectedEvents,
            onCheckedChange = { onInvocationEventToggled(InvocationEvent.SHAKE, it) },
        )
        InvocationEventCheckbox(
            label = "Screenshot",
            checked = InvocationEvent.SCREENSHOT in selectedEvents,
            onCheckedChange = { onInvocationEventToggled(InvocationEvent.SCREENSHOT, it) },
        )
        // Below Android 14 the SDK finds screenshots in the media store, which needs a read
        // permission the host app must hold.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            Text(
                text = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
                    "needs READ_MEDIA_IMAGES permission; this sample does not request it"
                } else {
                    "needs READ_EXTERNAL_STORAGE permission; this sample does not request it"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        InvocationEventCheckbox(
            label = "Floating button",
            checked = InvocationEvent.FLOATING_BUTTON in selectedEvents,
            onCheckedChange = { onInvocationEventToggled(InvocationEvent.FLOATING_BUTTON, it) },
        )
        InvocationEventCheckbox(
            label = "Two-finger swipe",
            checked = InvocationEvent.TWO_FINGER_SWIPE_LEFT in selectedEvents,
            onCheckedChange = { onInvocationEventToggled(InvocationEvent.TWO_FINGER_SWIPE_LEFT, it) },
        )

        ReportUiControls(onOpenSavedReports = onOpenSavedReports, onOpenSecureScreen = onOpenSecureScreen)

        OutlinedTextField(
            value = wifiPassword,
            onValueChange = { wifiPassword = it },
            label = { Text("Wi-Fi password") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .feedbackKitPrivate(),
        )
        Text(
            text = "masked in screenshots",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun InvocationEventCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label)
    }
}
