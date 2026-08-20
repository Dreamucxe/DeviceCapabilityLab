package com.devicelab.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devicelab.ui.theme.Radii
import com.devicelab.ui.theme.Spacing

/**
 * A clickable row that is always at least a full touch target tall.
 *
 * Section 27 asks for adequate touch targets. A fact row's natural height is driven by its
 * text, and a short one -- "HDR: No" -- lands around 40dp, under the 48dp minimum. Rather
 * than pad every row to 48dp and open gaps in a dense table, the constraint is a *minimum*
 * height applied only where the row is actually tappable.
 *
 * Not a `@Composable`; [clickable] resolves its own indication internally, so this composes
 * like any other modifier.
 */
fun Modifier.clickableRow(
    onClick: () -> Unit,
    minHeight: Dp = Spacing.minTouchTarget,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
): Modifier = this
    .heightIn(min = minHeight)
    .clickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )

/**
 * The app's primary button.
 *
 * Hand-built rather than Material's `Button` so it can be a translucent tinted surface
 * matching the cards, and so the same shape serves for both filled and outline variants
 * without two different components.
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    leading: String? = null,
) {
    val shape = RoundedCornerShape(Radii.chip)
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
        filled -> accent.copy(alpha = 0.9f)
        else -> accent.copy(alpha = 0.12f)
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        filled -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> accent
    }
    Box(
        modifier = modifier
            .heightIn(min = Spacing.minTouchTarget)
            .clip(shape)
            .background(container)
            .then(
                if (filled || !enabled) {
                    Modifier
                } else {
                    Modifier.border(Spacing.hairline, accent.copy(alpha = 0.35f), shape)
                }
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (leading != null) {
                Text(
                    text = leading,
                    modifier = Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}

/**
 * A small selectable chip, for filters.
 *
 * `Role.RadioButton` with `selected` in the semantics rather than a plain button, so a
 * screen reader announces which of a row of filters is active instead of reading five
 * identically-shaped buttons.
 */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    count: Int? = null,
) {
    val shape = RoundedCornerShape(Radii.chip)
    val container = if (selected) {
        accent.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
    }
    val content = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(container)
            .border(
                width = Spacing.hairline,
                color = if (selected) {
                    accent.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = shape,
            )
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = content)
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * A settings switch row.
 *
 * The whole row toggles, not just the switch: a 32dp-wide switch is a poor target, and the
 * row is one merged accessibility node so the label, the explanation and the state are read
 * together.
 */
@Composable
fun ToggleRow(
    title: String,
    note: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickableRow(
                onClick = { onCheckedChange(!checked) },
                enabled = enabled,
                role = Role.Switch,
            )
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (note != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(Spacing.lg))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** A radio row, for a single choice among a few named options. */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickableRow(onClick = onClick, role = Role.RadioButton)
            .semantics { this.selected = selected }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A determinate progress bar.
 *
 * Material's `LinearProgressIndicator` animates indeterminately by default and, in the
 * determinate case, still runs its own animation; this one is a plain box whose width is
 * driven by [Motion.progress], which collapses to a snap under reduced motion. It also
 * exposes [ProgressBarRangeInfo] so a screen reader can report "40 percent".
 */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 6.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = Motion.progress(),
        label = "progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Radii.chip))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                if (label != null) contentDescription = label
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radii.chip))
                .background(color)
        )
    }
}
