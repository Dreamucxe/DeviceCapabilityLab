package com.devicelab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.devicelab.R
import com.devicelab.core.model.Support
import com.devicelab.ui.theme.Radii
import com.devicelab.ui.theme.Spacing

/**
 * The global search field.
 *
 * A [BasicTextField] rather than Material's `TextField`, because `TextField` carries a
 * 56dp-plus container with its own decoration box and label animation, and what is wanted
 * here is a single translucent pill matching the rest of the surfaces.
 *
 * Search is instant: there is no submit action and no debounce. Section 21 asks for an
 * instant filter, and filtering an already-materialised list of facts in memory is cheap
 * enough that a delay would only be felt as lag. The IME action closes the keyboard rather
 * than searching, since results are already on screen behind it.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.capabilities_search_hint),
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(Radii.chip)
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
            .border(
                width = Spacing.hairline,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = shape,
            )
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "⌕",
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.md))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .semantics { contentDescription = hint },
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(Spacing.sm))
            val clearLabel = stringResource(R.string.action_clear_search)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Radii.chip))
                    .clickable(role = Role.Button) { onQueryChange("") }
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The legend explaining what the glyphs mean.
 *
 * Shown once on the dashboard and once at the top of the capability matrix. Every glyph in
 * the app is defined here, so a user who wonders what "—" means as opposed to "✕" has
 * somewhere to find out without a help screen. The distinction matters: "not exposed" is a
 * statement about Android, "unsupported" a statement about the device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportLegend(
    modifier: Modifier = Modifier,
    entries: List<Support> = LEGEND_ENTRIES,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        entries.forEach { support ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                StatusGlyph(
                    support = support,
                    size = MaterialTheme.typography.labelMedium.fontSize,
                )
                Text(
                    text = support.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val LEGEND_ENTRIES = listOf(
    Support.SUPPORTED,
    Support.PARTIAL,
    Support.NOT_EXPOSED,
    Support.UNSUPPORTED,
)
