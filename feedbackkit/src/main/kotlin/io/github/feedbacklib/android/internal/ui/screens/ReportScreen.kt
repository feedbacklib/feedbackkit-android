package io.github.feedbacklib.android.internal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.ui.LocalTexts
import io.github.feedbacklib.android.internal.ui.ReportActions
import io.github.feedbacklib.android.internal.ui.ReportStep
import io.github.feedbacklib.android.internal.ui.ReportUiState
import io.github.feedbacklib.android.internal.ui.rememberTexts
import io.github.feedbacklib.android.internal.ui.theme.FeedbackKitTheme

/** The whole report screen (spec §6): theme, texts, the current step and the cancel dialog. */
@Composable
internal fun ReportRoot(state: ReportUiState, actions: ReportActions) {
    FeedbackKitTheme(state.colorTheme, state.primaryColor) {
        CompositionLocalProvider(LocalTexts provides rememberTexts(state.customTexts)) {
            ReportScreen(state, actions)
        }
    }
}

@Composable
internal fun ReportScreen(state: ReportUiState, actions: ReportActions) {
    // Back is always ours: it walks back a step, asks to cancel, or is ignored while sending.
    BackHandler { actions.onBack() }
    when (state.step) {
        ReportStep.MENU -> PromptOptionsScreen(state.menuTypes, actions)
        ReportStep.FORM -> ReportFormScreen(state, actions)
        ReportStep.EXTENDED -> ExtendedStepScreen(state, actions)
        ReportStep.SENDING -> SendingScreen()
        ReportStep.SUCCESS -> SuccessScreen()
        ReportStep.ANNOTATE -> state.annotation?.let { AnnotationScreen(it, actions) } ?: LoadingScreen()
        ReportStep.PROACTIVE_PROMPT -> ProactivePromptScreen(actions)
    }
    if (state.confirmingCancel) CancelReportDialog(actions)
}

/**
 * A small custom bar, not M3's `TopAppBar`: that one has a fixed height, so a title wrapped to more
 * lines at a large font scale gets clipped (spec §10). This one only has a minimum height and grows
 * with the title instead.
 */
@Composable
internal fun ReportTopBar(title: String, canGoBack: Boolean, actions: ReportActions) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                .heightIn(min = 64.dp)
                .testTag(ReportTestTags.TOP_BAR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                IconButton(onClick = actions::onBack, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.feedbackkit_ic_back), contentDescription = stringResource(R.string.feedbackkit_action_back))
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    // The standard 16 dp inset from the edge when no back button stands before it.
                    .padding(start = if (canGoBack) 4.dp else 16.dp, end = 4.dp),
            )
            CloseButton(actions)
        }
    }
}

@Composable
internal fun CloseButton(actions: ReportActions) {
    IconButton(onClick = actions::onCancelRequested, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.feedbackkit_ic_close), contentDescription = stringResource(R.string.feedbackkit_action_close))
    }
}

/** The step's main button, above the navigation bar and the keyboard, with the submit error over it. */
@Composable
internal fun PrimaryButtonBar(label: String, enabled: Boolean, error: String?, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(ReportTestTags.SUBMIT_ERROR),
                )
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(ReportTestTags.PRIMARY_BUTTON),
            ) {
                Text(label)
            }
        }
    }
}

/** While a restored editor waits for the draft's listing. */
@Composable
private fun LoadingScreen() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun CancelReportDialog(actions: ReportActions) {
    val texts = LocalTexts.current
    AlertDialog(
        onDismissRequest = actions::onCancelDismissed,
        title = { Text(texts[TextKey.CANCEL_CONFIRM_TITLE]) },
        text = { Text(texts[TextKey.CANCEL_CONFIRM_MESSAGE]) },
        confirmButton = { TextButton(onClick = actions::onCancelConfirmed) { Text(texts[TextKey.CANCEL_CONFIRM_DISCARD]) } },
        dismissButton = { TextButton(onClick = actions::onCancelDismissed) { Text(texts[TextKey.CANCEL_CONFIRM_KEEP]) } },
    )
}

internal fun ReportType.titleKey(): TextKey = when (this) {
    ReportType.BUG -> TextKey.REPORT_BUG
    ReportType.FEEDBACK -> TextKey.REPORT_FEEDBACK
    ReportType.QUESTION -> TextKey.REPORT_QUESTION
    ReportType.FRUSTRATING_EXPERIENCE -> TextKey.REPORT_FRUSTRATING_EXPERIENCE
}

internal fun ReportType.descriptionKey(): TextKey = when (this) {
    ReportType.BUG -> TextKey.REPORT_BUG_DESCRIPTION
    ReportType.FEEDBACK -> TextKey.REPORT_FEEDBACK_DESCRIPTION
    ReportType.QUESTION -> TextKey.REPORT_QUESTION_DESCRIPTION
    ReportType.FRUSTRATING_EXPERIENCE -> TextKey.REPORT_FEEDBACK_DESCRIPTION // never in the menu (spec §4)
}

internal fun ReportType.commentHintKey(): TextKey = when (this) {
    ReportType.BUG -> TextKey.COMMENT_HINT_BUG
    ReportType.FEEDBACK -> TextKey.COMMENT_HINT_FEEDBACK
    ReportType.QUESTION -> TextKey.COMMENT_HINT_QUESTION
    ReportType.FRUSTRATING_EXPERIENCE -> TextKey.COMMENT_HINT_FRUSTRATING_EXPERIENCE
}
