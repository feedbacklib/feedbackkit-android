package io.github.feedbacklib.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.feedbacklib.android.ColorTheme
import io.github.feedbacklib.android.internal.annotate.AnnotationTool
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.annotate.PenWidth
import io.github.feedbacklib.android.internal.ui.AnnotationActions
import io.github.feedbacklib.android.internal.ui.AnnotationUiState
import io.github.feedbacklib.android.internal.ui.LocalTexts
import io.github.feedbacklib.android.internal.ui.rememberTexts
import io.github.feedbacklib.android.internal.ui.screens.AnnotationScreen
import io.github.feedbacklib.android.internal.ui.screens.ReportTestTags
import io.github.feedbacklib.android.internal.ui.theme.FeedbackKitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The editor screen drawn from fixed states (English device locale). */
@RunWith(AndroidJUnit4::class)
class AnnotationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()

    private fun state(
        tool: AnnotationTool = AnnotationTool.PEN,
        canUndo: Boolean = false,
        loadFailed: Boolean = false,
        saveFailed: Boolean = false,
        confirmingDiscard: Boolean = false,
    ) = AnnotationUiState(
        fileName = "screenshot.png",
        imageWidth = 100,
        imageHeight = 200,
        preview = if (loadFailed) null else ImageBitmap(100, 200),
        tool = tool,
        canUndo = canUndo,
        loadFailed = loadFailed,
        saveFailed = saveFailed,
        confirmingDiscard = confirmingDiscard,
    )

    private fun show(state: AnnotationUiState) = compose.setContent {
        FeedbackKitTheme(ColorTheme.LIGHT, null) {
            CompositionLocalProvider(LocalTexts provides rememberTexts(emptyMap())) { AnnotationScreen(state, actions) }
        }
    }

    @Test
    fun toolsSwitchAndThePenOffersColoursAndWidths() {
        show(state())
        compose.onNodeWithContentDescription("Blur").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Blue").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Thick line").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("tool:BLUR", "color:BLUE", "width:THICK"), actions.calls)
    }

    @Test
    fun theChosenToolColourAndWidthReadAsSelected() {
        show(state())
        compose.onNodeWithContentDescription("Pen").assertIsSelected()
        compose.onNodeWithContentDescription("Magnifier").assertIsNotSelected().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Red").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Yellow").assertIsNotSelected()
        compose.onNodeWithContentDescription("Thin line").assertIsSelected().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Thick line").assertIsNotSelected()
    }

    @Test
    fun penOptionsAreHiddenForOtherTools() {
        show(state(tool = AnnotationTool.MAGNIFIER))
        compose.onNodeWithContentDescription("Red").assertDoesNotExist()
    }

    @Test
    fun undoIsDisabledWithoutSteps() {
        show(state(canUndo = false))
        compose.onNodeWithContentDescription("Undo").assertIsNotEnabled()
    }

    @Test
    fun undoDoneAndCloseReachTheEditor() {
        show(state(canUndo = true))
        compose.onNodeWithContentDescription("Undo").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag(ReportTestTags.ANNOTATION_DONE).assertTextEquals("Done").performClick()
        compose.onNodeWithContentDescription("Close").assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(listOf("undo", "done", "cancel"), actions.calls)
    }

    @Test
    fun aSwipeWithThePenBecomesOneStroke() {
        show(state())
        compose.onNodeWithTag(ReportTestTags.ANNOTATION_CANVAS).performTouchInput {
            swipe(Offset(centerX - width / 8f, centerY), Offset(centerX + width / 8f, centerY), 300)
        }
        val stroke = actions.calls.single()
        assertTrue(stroke, stroke.startsWith("stroke:"))
        assertTrue(stroke, stroke.removePrefix("stroke:").toInt() >= 4)
    }

    @Test
    fun aTapWithTheMagnifierPlacesOneWhereTapped() {
        show(state(tool = AnnotationTool.MAGNIFIER))
        compose.onNodeWithTag(ReportTestTags.ANNOTATION_CANVAS).performTouchInput { click(center) }
        val (x, y) = actions.calls.single().removePrefix("magnifier:").split(',').map { it.toFloat() }
        assertTrue("x=$x", abs(x - 50f) < 3f)
        assertTrue("y=$y", abs(y - 100f) < 3f)
    }

    @Test
    fun aBlurDragBecomesARectangle() {
        show(state(tool = AnnotationTool.BLUR))
        compose.onNodeWithTag(ReportTestTags.ANNOTATION_CANVAS).performTouchInput {
            swipe(Offset(centerX - width / 6f, centerY - height / 6f), Offset(centerX + width / 6f, centerY + height / 6f), 300)
        }
        assertTrue(actions.calls.toString(), actions.calls.single().startsWith("blur:"))
    }

    @Test
    fun failuresAreShown() {
        show(state(loadFailed = true))
        compose.onNodeWithText("Could not open the image").assertIsDisplayed()
    }

    @Test
    fun aFailedSaveIsShown() {
        show(state(saveFailed = true))
        compose.onNodeWithTag(ReportTestTags.ANNOTATION_ERROR).assertTextEquals("Could not save the image. Please try again.")
    }

    @Test
    fun discardAsksWithTheReportTexts() {
        show(state(confirmingDiscard = true))
        compose.onNodeWithText("Discard your changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(listOf("discardDismissed", "discardConfirmed"), actions.calls)
    }

    private class RecordingActions : AnnotationActions {
        val calls = mutableListOf<String>()
        override fun onToolSelected(tool: AnnotationTool) { calls += "tool:$tool" }
        override fun onPenColorSelected(color: PenColor) { calls += "color:$color" }
        override fun onPenWidthSelected(width: PenWidth) { calls += "width:$width" }
        override fun onStrokeDrawn(points: List<Float>, width: Float) { calls += "stroke:${points.size}" }
        override fun onBlurDrawn(left: Float, top: Float, right: Float, bottom: Float) { calls += "blur:$left,$top,$right,$bottom" }
        override fun onMagnifierPlaced(centerX: Float, centerY: Float) { calls += "magnifier:$centerX,$centerY" }
        override fun onMagnifierChanged(index: Int, centerX: Float, centerY: Float, radius: Float) { calls += "moved:$index" }
        override fun onUndo() { calls += "undo" }
        override fun onAnnotationDone() { calls += "done" }
        override fun onAnnotationCancel() { calls += "cancel" }
        override fun onAnnotationDiscardConfirmed() { calls += "discardConfirmed" }
        override fun onAnnotationDiscardDismissed() { calls += "discardDismissed" }
    }
}
