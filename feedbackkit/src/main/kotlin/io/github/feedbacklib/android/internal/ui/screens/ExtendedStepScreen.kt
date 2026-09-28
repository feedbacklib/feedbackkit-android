package io.github.feedbacklib.android.internal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.ui.LocalTexts
import io.github.feedbacklib.android.internal.ui.ReportActions
import io.github.feedbacklib.android.internal.ui.ReportUiState

@Composable
internal fun ExtendedStepScreen(state: ReportUiState, actions: ReportActions) {
    val rules = state.rules ?: return
    val texts = LocalTexts.current
    Scaffold(
        topBar = { ReportTopBar(texts[TextKey.EXTENDED_TITLE], state.canGoBack, actions) },
        bottomBar = {
            PrimaryButtonBar(
                texts[TextKey.BUTTON_SEND],
                // Task 6's single rule for the primary button; never recomputed here.
                state.primaryEnabled,
                texts[TextKey.SUBMIT_FAILED].takeIf { state.submitFailed },
                actions::onPrimaryAction,
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
            ExtendedField(
                value = state.fields.steps,
                onValueChange = actions::onStepsChanged,
                label = texts[TextKey.EXTENDED_STEPS],
                hint = state.hints.steps ?: stringResource(R.string.feedbackkit_extended_steps_hint),
                showRequired = rules.extendedRequired && state.fields.steps.isBlank(),
                tag = ReportTestTags.STEPS_FIELD,
            )
            ExtendedField(
                value = state.fields.actual,
                onValueChange = actions::onActualChanged,
                label = texts[TextKey.EXTENDED_ACTUAL],
                hint = state.hints.actual ?: stringResource(R.string.feedbackkit_extended_actual_hint),
                showRequired = rules.extendedRequired && state.fields.actual.isBlank(),
                tag = ReportTestTags.ACTUAL_FIELD,
            )
            ExtendedField(
                value = state.fields.expected,
                onValueChange = actions::onExpectedChanged,
                label = texts[TextKey.EXTENDED_EXPECTED],
                hint = state.hints.expected ?: stringResource(R.string.feedbackkit_extended_expected_hint),
                showRequired = rules.extendedRequired && state.fields.expected.isBlank(),
                tag = ReportTestTags.EXPECTED_FIELD,
            )
        }
    }
}

@Composable
private fun ExtendedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    showRequired: Boolean,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        minLines = 3,
        isError = showRequired,
        supportingText = if (showRequired) {
            { Text(LocalTexts.current[TextKey.REQUIRED_FIELD]) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}
