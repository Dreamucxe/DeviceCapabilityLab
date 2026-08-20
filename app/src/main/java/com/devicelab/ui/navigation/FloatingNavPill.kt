package com.devicelab.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devicelab.R
import com.devicelab.ui.components.Motion
import com.devicelab.ui.components.clickableRow
import com.devicelab.ui.theme.Radii
import com.devicelab.ui.theme.Spacing

/**
 * The floating pill navigation.
 *
 * Section 22 asks for a floating pill-style bottom navigation, so this is not Material's
 * `NavigationBar`: that is a full-width bar pinned to the bottom edge with its own
 * elevation and no way to inset it into a pill without fighting its internals. This is a
 * rounded [Surface] with its own inset, floating above the content.
 *
 * Because it floats *over* the content rather than displacing it, every scrollable screen
 * reserves [Spacing.navigationClearance] at the bottom. Without that the last row of a lab
 * report sits permanently under the pill.
 *
 * The pill is inset from the navigation-bar inset rather than ignoring it, so on a device
 * with gesture navigation it sits above the gesture area and on one with three-button
 * navigation it sits above the buttons -- in both cases clear of the system's own targets.
 *
 * There is no sliding indicator behind the selected tab. A pill that narrow leaves each tab
 * around 60dp wide, and an indicator travelling that distance in 180ms reads as a twitch;
 * the selected tab instead lifts in colour and gains a dot, which is legible instantly and
 * costs one recomposition of two tabs rather than an animation frame budget.
 */
@Composable
fun FloatingNavPill(
    selected: Tab?,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemBars = WindowInsets.navigationBars.asPaddingValues()
    Box(
        modifier = modifier
            .padding(bottom = systemBars.calculateBottomPadding())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(Radii.pill),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.border(
                width = Spacing.hairline,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(Radii.pill),
            ),
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { tab ->
                    NavPillItem(
                        tab = tab,
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavPillItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(tab.titleRes)
    val accent = MaterialTheme.colorScheme.primary
    val content = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant

    // A very small lift on the selected glyph. Two dp, so it registers as emphasis without
    // reading as a jump; a spring so a fast double tap between tabs does not stutter.
    val lift by animateFloatAsState(
        targetValue = if (selected) -2f else 0f,
        animationSpec = Motion.move(),
        label = "tabLift",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.pill))
            .then(
                if (selected) {
                    Modifier.background(accent.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
            .clickableRow(onClick = onClick, role = Role.Tab)
            .semantics {
                this.selected = selected
                contentDescription = title
            }
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = tab.glyph,
            modifier = Modifier
                .graphicsLayer { translationY = lift }
                .clearAndSetSemantics { },
            style = MaterialTheme.typography.titleLarge,
            color = content,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelSmall,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * A top bar for a detail screen: a back affordance, a title, and an optional action.
 *
 * Detail screens have no pill -- they are pushed over a tab, and a nested navigation
 * control would be ambiguous about what "Hardware" would return to. They get this instead.
 */
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backLabel: String = stringResource(R.string.action_back),
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.minTouchTarget)
                .clip(RoundedCornerShape(Radii.chip))
                .clickableRow(
                    onClick = onBack,
                    minHeight = Spacing.minTouchTarget,
                    role = Role.Button,
                )
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailing()
        }
    }
}
