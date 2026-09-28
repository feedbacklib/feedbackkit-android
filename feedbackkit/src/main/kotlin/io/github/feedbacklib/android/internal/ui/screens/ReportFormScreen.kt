package io.github.feedbacklib.android.internal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.ui.EmailMode
import io.github.feedbacklib.android.internal.ui.LocalTexts
import io.github.feedbacklib.android.internal.ui.PrimaryAction
import io.github.feedbacklib.android.internal.ui.ReportActions
import io.github.feedbacklib.android.internal.ui.ReportUiState

@Composable
internal fun ReportFormScreen(state: ReportUiState, actions: ReportActions) {
    val type = state.type ?: return
    val rules = state.rules ?: return
    val texts = LocalTexts.current
    Scaffold(
        topBar = { ReportTopBar(texts[type.titleKey()], state.canGoBack, actions) },
        bottomBar = {
            PrimaryButtonBar(
                label = texts[if (state.primaryAction == PrimaryAction.NEXT) TextKey.BUTTON_NEXT else TextKey.BUTTON_SEND],
                // Task 6's single rule for the primary button; never recomputed here.
                enabled = state.primaryEnabled,
                error = texts[TextKey.SUBMIT_FAILED].takeIf { state.submitFailed },
                onClick = actions::onPrimaryAction,
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rules.emailMode != EmailMode.HIDDEN) {
                OutlinedTextField(
                    value = state.fields.email,
                    onValueChange = actions::onEmailChanged,
                    label = { Text(texts[if (rules.emailMode == EmailMode.OPTIONAL) TextKey.EMAIL_LABEL_OPTIONAL else TextKey.EMAIL_LABEL]) },
                    singleLine = true,
                    isError = state.check.showEmailError,
                    supportingText = when {
                        state.check.showEmailError -> { { Text(texts[TextKey.EMAIL_INVALID]) } }
                        state.check.emailMissing -> { { Text(texts[TextKey.REQUIRED_FIELD]) } }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ReportTestTags.EMAIL_FIELD),
                )
            }
            OutlinedTextField(
                value = state.fields.comment,
                onValueChange = actions::onCommentChanged,
                label = { Text(texts[type.commentHintKey()]) },
                minLines = 4,
                isError = state.check.commentMissing,
                supportingText = if (state.check.commentMissing || rules.commentMinLength > 0) {
                    {
                        Row(Modifier.fillMaxWidth()) {
                            if (state.check.commentMissing) {
                                Text(texts[TextKey.REQUIRED_FIELD], Modifier.weight(1f))
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (rules.commentMinLength > 0) {
                                val counterDescription = stringResource(
                                    R.string.feedbackkit_comment_counter_description,
                                    state.check.commentLength,
                                    rules.commentMinLength,
                                )
                                Text(
                                    "${state.check.commentLength}/${rules.commentMinLength}",
                                    Modifier
                                        .testTag(ReportTestTags.COMMENT_COUNTER)
                                        .semantics { contentDescription = counterDescription },
                                )
                            }
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ReportTestTags.COMMENT_FIELD),
            )
            AttachmentStrip(state, actions)
        }
    }
}
