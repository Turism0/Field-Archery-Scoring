package uk.co.fieldarcheryscoring.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import uk.co.fieldarcheryscoring.domain.PersonalHandicap
import java.time.temporal.ChronoUnit

/**
 * Your handicap over time with one bow style (3.2.24, Pete): what it was after each round, spaced by date. Drawn here,
 * no chart library. A lower handicap is better.
 */
@Composable
fun HandicapChart(points: List<PersonalHandicap.HistoryPoint>) {
    if (points.size < 2) {
        Text(
            "Your handicap over time shows here after your next round with this bow style.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val values = points.map { it.handicap }
    var lo = values.min() - 5
    var hi = values.max() + 5
    if (hi - lo < 20) { val mid = (hi + lo) / 2; lo = mid - 10; hi = mid + 10 }
    val first = points.first().entry.shootDate
    val last = points.last().entry.shootDate
    val days = ChronoUnit.DAYS.between(first, last).coerceAtLeast(1)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Row(Modifier.fillMaxWidth().height(140.dp)) {
            Column(Modifier.width(36.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("$hi", style = MaterialTheme.typography.labelSmall)
                Text("${(hi + lo) / 2}", style = MaterialTheme.typography.labelSmall)
                Text("$lo", style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                fun x(i: Int) = ChronoUnit.DAYS.between(first, points[i].entry.shootDate).toFloat() / days * size.width
                fun y(v: Int) = size.height * (1 - (v - lo).toFloat() / (hi - lo))
                listOf(0f, size.height / 2, size.height).forEach { gy ->
                    drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                // A step line: the handicap holds until the next round changes it.
                val path = Path().apply {
                    points.forEachIndexed { i, p ->
                        if (i == 0) moveTo(x(i), y(p.handicap)) else { lineTo(x(i), y(points[i - 1].handicap)); lineTo(x(i), y(p.handicap)) }
                    }
                }
                drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
                points.forEachIndexed { i, p -> drawCircle(line, radius = 3.dp.toPx(), center = Offset(x(i), y(p.handicap))) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(first.format(UkDate), style = MaterialTheme.typography.labelSmall)
            Text(last.format(UkDate), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "After each round · lower is better",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
