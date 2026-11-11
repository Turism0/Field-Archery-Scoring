package uk.co.fnurble.fieldarchery.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A filter chip that opens a menu: "Bow: Longbow". [selected] null shows [anyLabel] and the chip unselected.
 * Choosing [anyLabel] in the menu clears it.
 */
@Composable
fun <T> FilterMenuChip(
    title: String,
    anyLabel: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    /** Extra entries at the end of the menu (e.g. "Dates…"), shown as given. */
    extra: List<Pair<String, () -> Unit>> = emptyList(),
    /** Overrides the chip text when something is selected (e.g. a date range). */
    selectedText: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(if (selected == null) "$title: $anyLabel" else selectedText ?: optionLabel(selected)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(anyLabel) }, onClick = { open = false; onSelect(null) })
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) })
            }
            extra.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

/**
 * A filter chip that opens a menu of tick boxes (3.2): several can be chosen, never none. The menu stays open while
 * choosing; [onToggle] is called for each one tapped.
 */
@Composable
fun <T> MultiChoiceChip(
    text: String,
    options: List<T>,
    selected: Set<T>,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    onToggle: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = true,
            onClick = { open = true },
            label = { Text(text) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                val on = o in selected
                DropdownMenuItem(
                    text = { Text(optionLabel(o)) },
                    leadingIcon = { Checkbox(checked = on, onCheckedChange = null, enabled = !on || selected.size > 1) },
                    enabled = !on || selected.size > 1,
                    onClick = { onToggle(o) },
                )
            }
        }
    }
}
