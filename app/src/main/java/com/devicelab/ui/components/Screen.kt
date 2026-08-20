package com.devicelab.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devicelab.ui.theme.Spacing

/** The widest a column of report text is allowed to get. */
val MAX_CONTENT_WIDTH = 620.dp

/**
 * Constrains an item to the readable column width.
 *
 * On a tablet or an unfolded foldable a report is otherwise a single column of label/value
 * pairs stretched across 900dp or more, where the eye loses the line between a label on the
 * left and its value on the right. Capping the column and centring it is what makes one
 * layout readable on a phone and on a tablet, without a second set of layouts.
 */
fun Modifier.contentWidth(): Modifier = this.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth()

/**
 * The scroll container every tab uses.
 *
 * Three things every screen needs and none should re-derive: the status-bar inset (the
 * window draws edge-to-edge, so without it the first row sits under the clock),
 * [Spacing.navigationClearance] at the bottom (the pill floats over the content rather than
 * displacing it), and centred items so [contentWidth] has somewhere to centre against.
 *
 * A [LazyColumn] rather than a scrolling [Column], because a full lab report is on the order
 * of a thousand rows and a non-lazy column composes and measures all of them on first frame.
 */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Spacing.lg,
    topContent: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    Column(modifier = modifier.fillMaxSize()) {
        if (topContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBar.calculateTopPadding()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = Modifier.contentWidth()) { topContent() }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = if (topContent == null) {
                    statusBar.calculateTopPadding() + Spacing.lg
                } else {
                    Spacing.sm
                },
                bottom = Spacing.navigationClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * A screen's title block.
 *
 * The title carries `heading()` semantics so a screen-reader user can jump between screen
 * and section headings rather than swiping through every row to find the next one.
 */
@Composable
fun ScreenTitle(
    title: String,
    caption: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (trailing != null) {
                Spacer(Modifier.width(Spacing.md))
                trailing()
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The fold arrow.
 *
 * A rotating text glyph rather than a vector asset: it inherits the content colour, scales
 * with the user's font-size setting, and costs no drawable. Hidden from accessibility
 * services, because the row it sits in already announces its expanded state.
 */
@Composable
fun FoldIndicator(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.move(),
        label = "fold",
    )
    Box(
        modifier = modifier.size(24.dp).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⌄",
            modifier = Modifier.rotate(rotation),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A card whose contents can be folded away, used for a section of a lab report.
 *
 * Open by default. A report exists to be read, and a screen of collapsed headers makes the
 * reader tap through every one to find out whether it held anything -- which is worse than
 * scrolling. Collapsing is for getting a long section out of the way once you have read it.
 *
 * The body is not wrapped in `AnimatedVisibility`. A section can hold two hundred rows, and
 * animating the height of that means measuring all of them every frame of the transition;
 * the fold is instant and the arrow's rotation carries the change.
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRow(onClick = onToggle)
                .padding(
                    start = Spacing.lg,
                    end = Spacing.md,
                    top = Spacing.md,
                    bottom = Spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
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
                Spacer(Modifier.width(Spacing.xs))
            }
            FoldIndicator(expanded)
        }
        if (expanded) {
            HairlineDivider()
            Column(
                modifier = Modifier.padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.xs,
                    bottom = Spacing.md,
                )
            ) {
                content()
            }
        }
    }
}
