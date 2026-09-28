package io.github.feedbacklib.android.internal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.ui.LocalTexts
import io.github.feedbacklib.android.internal.ui.ReportActions

/**
 * Proactive reporting's prompt (spec §8): a dialog card over the dimmed host screen. "Tell us" opens
 * the FRUSTRATING_EXPERIENCE form; "Not now", like a tap on the dimmed part, closes as a cancel.
 * Scrolls instead of clipping at a large font scale (spec §10).
 */
@Composable
internal fun ProactivePromptScreen(actions: ReportActions) {
    val texts = LocalTexts.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A sibling behind the card, never its ancestor: see PromptOptionsScreen.
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = texts[TextKey.PROACTIVE_NOT_NOW],
                    role = Role.Button,
                    onClick = actions::onProactiveDeclined,
                ),
        )
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .testTag(ReportTestTags.PROACTIVE_PROMPT),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    text = texts[TextKey.PROACTIVE_PROMPT_MESSAGE],
                    style = MaterialTheme.typography.titleMedium,
                    // Read out as it appears: nothing else on screen says why FeedbackKit opened.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                ) {
                    TextButton(
                        onClick = actions::onProactiveDeclined,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(ReportTestTags.PROACTIVE_DECLINE),
                    ) {
                        Text(texts[TextKey.PROACTIVE_NOT_NOW])
                    }
                    Button(
                        onClick = actions::onProactiveAccepted,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(ReportTestTags.PROACTIVE_ACCEPT),
                    ) {
                        Text(texts[TextKey.PROACTIVE_TELL_US])
                    }
                }
            }
        }
    }
}
