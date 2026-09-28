package io.github.feedbacklib.android

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalView
import io.github.feedbacklib.android.internal.capture.PrivateViewRegistry
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Blacks out this composable in every screenshot the SDK takes (spec §5). Screen recordings are
 * not masked — use `FLAG_SECURE` for screens that must never be recorded.
 */
public fun Modifier.feedbackKitPrivate(isPrivate: Boolean = true): Modifier =
    if (isPrivate) this then PrivateElement else this

private data object PrivateElement : ModifierNodeElement<PrivateNode>() {
    override fun create(): PrivateNode = PrivateNode()

    override fun update(node: PrivateNode) = Unit
}

private class PrivateNode : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bounds = coordinates.boundsInWindow()
        // Round outward, never inward: a masked pixel that rounding trimmed off would defeat
        // the whole point of this modifier.
        PrivateViewRegistry.updateCompose(
            this,
            currentValueOf(LocalView),
            floor(bounds.left).toInt(),
            floor(bounds.top).toInt(),
            ceil(bounds.right).toInt(),
            ceil(bounds.bottom).toInt(),
        )
    }

    override fun onDetach() {
        PrivateViewRegistry.removeCompose(this)
    }
}
