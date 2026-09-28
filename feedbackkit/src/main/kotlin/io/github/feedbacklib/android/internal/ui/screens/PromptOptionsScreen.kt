package io.github.feedbacklib.android.internal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.ui.LocalTexts
import io.github.feedbacklib.android.internal.ui.ReportActions

/** The report type menu: a sheet over the dimmed host screen; a tap on the dimmed part cancels. */
@Composable
internal fun PromptOptionsScreen(types: List<ReportType>, actions: ReportActions) {
    val texts = LocalTexts.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The scrim is a sibling behind the sheet, never an ancestor of it: `clickable` merges every
        // non-actionable descendant's semantics into its own node, so an ancestor scrim would swallow
        // the sheet's heading and list items into one "Close" accessibility node.
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = stringResource(R.string.feedbackkit_action_close),
                    role = Role.Button,
                    onClick = actions::onCancelRequested,
                ),
        )
        val statusBarHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                // Never taller than the space below the status bar, so it can be scrolled instead of
                // growing into it (or off-screen) at a large font scale.
                .heightIn(max = maxHeight - statusBarHeight)
                .testTag(ReportTestTags.MENU),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 4.dp)) {
                    Text(
                        text = texts[TextKey.PROMPT_TITLE],
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    CloseButton(actions)
                }
                types.forEach { type ->
                    ListItem(
                        headlineContent = { Text(texts[type.titleKey()]) },
                        supportingContent = { Text(texts[type.descriptionKey()]) },
                        modifier = Modifier
                            .clickable { actions.onTypeSelected(type) }
                            .testTag(ReportTestTags.menuItem(type)),
                    )
                }
            }
        }
    }
}
