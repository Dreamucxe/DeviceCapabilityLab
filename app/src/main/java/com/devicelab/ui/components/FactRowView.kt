package com.devicelab.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Support
import com.devicelab.ui.theme.LocalMonospaceValues
import com.devicelab.ui.theme.LocalShowProvenance
import com.devicelab.ui.theme.MonoValue
import com.devicelab.ui.theme.ProportionalValue
import com.devicelab.ui.theme.Radii
import com.devicelab.ui.theme.Spacing
import com.devicelab.ui.theme.colorFor

/**
 * The status glyph.
 *
 * Colour *and* glyph, never colour alone. Section 27's contrast requirement is really a
 * requirement that the information survive without colour at all, and roughly one in
 * twelve men cannot reliably separate the green and the red used here. ✓ ◐ ✕ — ? carry
 * the whole meaning on their own.
 *
 * The glyph is hidden from accessibility services and the surrounding row supplies the
 * status in words instead, because "✓" read aloud is either silence or a character name.
 */
@Composable
fun StatusGlyph(
    support: Support,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.titleMedium.fontSize,
) {
    Text(
        text = support.glyph,
        modifier = modifier.clearAndSetSemantics { },
        color = colorFor(support),
        fontSize = size,
        style = MaterialTheme.typography.titleMedium,
    )
}

/**
 * One label/value row, expandable when it has more to say.
 *
 * This is the single most repeated composable in the app -- a full scan produces well over
 * a thousand of these -- so it is deliberately shallow: a Row, two Columns, and no
 * intermediate Surface or Card per row. Cards are drawn once around groups of rows
 * instead, which keeps a long lab screen at a handful of layout nodes per item rather
 * than a dozen.
 *
 * The whole row is one accessibility node. Read as separate nodes, a table of a hundred
 * facts becomes three hundred swipes; merged, each swipe reads "Refresh rate, 120 Hz,
 * queried via Display.getMode" and moves on.
 */
@Composable
fun FactRowView(
    fact: Fact,
    modifier: Modifier = Modifier,
) {
    val hasDetail = fact.detail != null
    var expanded by rememberSaveable(fact.label, fact.value) { mutableStateOf(false) }
    val showProvenance = LocalShowProvenance.current

    val description = remember(fact, showProvenance, expanded) {
        buildString {
            append(fact.label)
            append(": ")
            append(fact.value)
            if (fact.support != Support.INFORMATIONAL) {
                append(". Status: ")
                append(fact.support.label)
            }
            append(". ")
            append(fact.provenance.explanation.replace(" · ", ", "))
            if (hasDetail && !expanded) append(". Double tap for technical detail")
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hasDetail) {
                    Modifier.clickableRow(
                        onClick = { expanded = !expanded },
                        minHeight = Spacing.minTouchTarget,
                    )
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StatusGlyph(
                support = fact.support,
                modifier = Modifier
                    .width(20.dp)
                    .padding(top = 1.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fact.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = fact.value,
                    style = if (LocalMonospaceValues.current) MonoValue else ProportionalValue,
                    color = valueColor(fact),
                )
                if (showProvenance) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = fact.provenance.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasDetail) {
                Spacer(Modifier.width(Spacing.sm))
                FoldIndicator(expanded)
            }
        }

        if (hasDetail) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = Motion.resize()) +
                    fadeIn(animationSpec = Motion.fade()),
                exit = shrinkVertically(animationSpec = Motion.resize()) +
                    fadeOut(animationSpec = Motion.fade()),
            ) {
                DetailBlock(fact.detail!!)
            }
        }
    }
}

/** The expanded technical note under a fact. */
@Composable
private fun DetailBlock(detail: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = Spacing.sm, end = Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .heightIn(min = 16.dp)
                .clip(RoundedCornerShape(Radii.chip))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The colour of a value.
 *
 * A measurement takes the ordinary text colour: a resolution is not a verdict and
 * colouring it would imply one. A verdict takes its status colour, except that "not
 * exposed" and "unknown" stay muted -- see [com.devicelab.ui.theme.StatusColors].
 */
@Composable
private fun valueColor(fact: Fact): Color = when (fact.support) {
    Support.INFORMATIONAL -> MaterialTheme.colorScheme.onSurface
    Support.NOT_EXPOSED, Support.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> colorFor(fact.support)
}

/** A row of facts inside one card, with hairlines between them. */
@Composable
fun FactList(
    facts: List<Fact>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Top) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) HairlineDivider(alpha = 0.35f)
            FactRowView(fact)
        }
    }
}

