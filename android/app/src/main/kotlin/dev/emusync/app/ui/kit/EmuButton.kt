package dev.emusync.app.ui.kit

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu
import kotlinx.coroutines.launch

/**
 * A 40 dp button inside a 48 dp touch target. Secondary is a hairline outline; [primary] is the inverted fill, the
 * same inversion as the cursor row. [glyph] shows the controller button that does the same thing on this screen.
 */
@Composable
fun EmuButton(
    text: String,
    primary: Boolean = false,
    glyph: Action? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = LocalEmu.current
    val source = remember { MutableInteractionSource() }
    val fill = primary && enabled
    val ink = when {
        !enabled -> c.ink3
        primary -> c.bg
        else -> c.ink
    }
    Box(
        modifier
            .height(48.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(40.dp)
                .then(if (fill) Modifier.background(c.ink, EmuRadius) else Modifier)
                .border(1.5.dp, if (fill) c.ink else c.rule2, EmuRadius)
                .indication(source, PressIndication)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (glyph != null && enabled) Glyph(glyph, inverted = fill)
            BasicText(text, style = EmuType.body.copy(fontWeight = FontWeight.SemiBold, color = ink), maxLines = 1)
        }
    }
}

/**
 * The kit's press feedback: a faint neutral wash in the 3 dp shape while pressed. No ripple, no tint, no motion.
 */
internal object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

private class PressNode(private val source: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableSetOf<PressInteraction.Press>()
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> presses += i
                    is PressInteraction.Release -> presses -= i.press
                    is PressInteraction.Cancel -> presses -= i.press
                }
                if (presses.isNotEmpty() != pressed) {
                    pressed = presses.isNotEmpty()
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) {
            val c = currentValueOf(LocalEmu)
            drawRoundRect(c.ink.copy(alpha = 0.08f), cornerRadius = CornerRadius(3.dp.toPx()))
        }
    }
}
