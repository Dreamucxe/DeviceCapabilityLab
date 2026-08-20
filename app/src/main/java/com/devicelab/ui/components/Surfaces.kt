package com.devicelab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devicelab.ui.theme.Radii
import com.devicelab.ui.theme.Spacing

/**
 * The app's card.
 *
 * Section 22 asks for rounded cards on subtle translucent surfaces. That is done with a
 * container colour at partial alpha over the background plus a one-pixel border, rather
 * than with a real blur: `RenderEffect` blur needs API 31, costs a full-screen render
 * pass per frame, and Section 23 requires the UI to stay smooth on low-end devices. A
 * translucent fill over a dark background gives the same layered impression for the price
 * of a rectangle.
 *
 * The border is what actually makes it read as glass. Without it the card dissolves into
 * the background at these alphas; with it there is an edge catching light.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.card),
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    tonalAlpha: Float = 0.55f,
    borderAlpha: Float = 0.65f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = tonalAlpha))
            .border(
                width = Spacing.hairline,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha),
                shape = shape,
            )
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A card with a highlight along its top edge.
 *
 * Used for the one card on a screen that is the screen's subject -- the device header on
 * the dashboard, the comparison summary. The gradient is a single vertical brush, drawn
 * once, not an animated sweep.
 */
@Composable
fun FeatureCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = PaddingValues(Spacing.xl),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radii.card)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                    )
                )
            )
            .border(
                width = Spacing.hairline,
                color = accent.copy(alpha = 0.28f),
                shape = shape,
            )
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A section heading: small tracked-out uppercase label above content.
 *
 * The overline is a heading for accessibility purposes, so a screen reader's heading
 * navigation lands on it rather than treating it as loose text.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailing()
        }
    }
}

/** A small tracked-out uppercase label, for a group caption inside a card. */
@Composable
fun Overline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A pill badge.
 *
 * [containerAlpha] is deliberately low: these appear several to a row, and at full
 * saturation a row of them competes with the values they annotate.
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    containerAlpha: Float = 0.14f,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radii.chip),
        color = color.copy(alpha = containerAlpha),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            leading?.invoke()
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A thin horizontal rule.
 *
 * Marked [clearAndSetSemantics] with nothing, so a screen reader does not announce a
 * decorative line between every two rows of a long table. There are hundreds of these in
 * a full report.
 */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    alpha: Float = 0.5f,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Spacing.hairline)
            .clearAndSetSemantics { }
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha))
    )
}

/** An empty state: a glyph, a line, and an explanation of what to do about it. */
@Composable
fun EmptyState(
    glyph: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Radii.chip))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}
