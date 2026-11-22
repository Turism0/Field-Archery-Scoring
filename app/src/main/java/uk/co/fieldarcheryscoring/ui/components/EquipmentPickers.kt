package uk.co.fieldarcheryscoring.ui.components

import androidx.compose.runtime.Composable
import uk.co.fieldarcheryscoring.domain.ArrowSet
import uk.co.fieldarcheryscoring.domain.Bow
import uk.co.fieldarcheryscoring.domain.Equipment
import uk.co.fieldarcheryscoring.domain.EquipmentChoice

/**
 * The bow and arrows for a round (3.2.24, Pete): under Bow style on New round, Join and Round details. Each choice only
 * shows with two or more to pick from (the only one is used without asking); it starts on the last ones used with the
 * bow style.
 */
@Composable
fun EquipmentPickers(
    choice: EquipmentChoice?,
    onBow: (String) -> Unit,
    onArrows: (String) -> Unit,
    /** Round details: shown with even one to pick from, so an old round can be given it. */
    always: Boolean = false,
) {
    val c = choice ?: return
    if (c.showBows || (always && c.bows.isNotEmpty())) {
        OptionDropdown(
            label = "Bow",
            options = c.bows,
            selected = c.bows.firstOrNull { it.id == c.bowId },
            optionLabel = { b: Bow -> Equipment.label(b) },
            onSelect = { b -> onBow(b?.id ?: Equipment.NONE) },
            noneLabel = "None",
            supportingText = c.bows.firstOrNull { it.id == c.bowId }?.let { Equipment.detail(it) } ?: "Your bows for this bow style (Equipment on Home)",
        )
    }
    if (c.showArrows || (always && c.arrows.isNotEmpty())) {
        OptionDropdown(
            label = "Arrows",
            options = c.arrows,
            selected = c.arrows.firstOrNull { it.id == c.arrowId },
            optionLabel = { a: ArrowSet -> Equipment.label(a) },
            onSelect = { a -> onArrows(a?.id ?: Equipment.NONE) },
            noneLabel = "None",
            supportingText = c.arrows.firstOrNull { it.id == c.arrowId }?.let { Equipment.detail(it).ifEmpty { null } } ?: "Your arrows for this bow",
        )
    }
}
