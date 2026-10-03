package dev.ayaya.dailyobsi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.model.NoteSection
import dev.ayaya.dailyobsi.model.SectionIcon
import dev.ayaya.dailyobsi.model.SectionId
import dev.ayaya.dailyobsi.model.TODO_TAB_ID
import dev.ayaya.dailyobsi.model.defaultSectionIcon
import dev.ayaya.dailyobsi.model.sectionNavLabel

/** One entry in the bottom bar: a note section or the todo tab. */
data class TabSpec(val id: SectionId, val label: String, val icon: SectionIcon)

fun NoteSection.tabSpec() = TabSpec(id, sectionNavLabel(title), defaultSectionIcon(title))

val TODO_TAB_SPEC = TabSpec(TODO_TAB_ID, "Todo", SectionIcon.NEXTCLOUD_TODO)

@Composable
fun SectionBottomBar(
    tabs: List<TabSpec>,
    selectedId: SectionId?,
    onSelect: (SectionId) -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        // Tabs share the bar's full width evenly; once they'd get narrower than
        // MIN_TAB_WIDTH, they stop shrinking and the row scrolls instead.
        BoxWithConstraints(Modifier.navigationBarsPadding()) {
        val tabWidth = bottomBarTabWidth(maxWidth, tabs.size)
        LazyRow(
            modifier = Modifier.height(72.dp),
            contentPadding = PaddingValues(horizontal = TAB_SIDE_PADDING, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(TAB_SPACING),
        ) {
            items(
                items = tabs,
                key = { "${it.id.normalizedTitle}:${it.id.occurrence}" },
            ) { tab ->
                val selected = tab.id == selectedId
                Column(
                    modifier = Modifier.width(tabWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                        )
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(tab.id) },
                            role = Role.Tab,
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = iconFor(tab.icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        }
    }
}

private val MIN_TAB_WIDTH = 72.dp
private val TAB_SIDE_PADDING = 6.dp
private val TAB_SPACING = 2.dp

/** Tabs split [barWidth] evenly, but never narrower than [MIN_TAB_WIDTH]
 *  (past that the row scrolls instead). */
internal fun bottomBarTabWidth(barWidth: Dp, tabCount: Int): Dp {
    val count = tabCount.coerceAtLeast(1)
    val available = barWidth - TAB_SIDE_PADDING * 2 - TAB_SPACING * (count - 1)
    return (available / count).coerceAtLeast(MIN_TAB_WIDTH)
}

private fun iconFor(icon: SectionIcon): ImageVector = when (icon) {
    SectionIcon.TIME_UNTIL -> Icons.Filled.HourglassEmpty
    SectionIcon.MEDS -> Icons.Filled.Medication
    SectionIcon.TASKS -> Icons.Filled.CheckCircle
    SectionIcon.JOURNAL -> Icons.Filled.Create
    SectionIcon.GRATITUDE -> Icons.Filled.Favorite
    SectionIcon.HEALTH -> Icons.Filled.Star
    SectionIcon.WORK -> Icons.Filled.Build
    SectionIcon.IDEAS -> Icons.Filled.Info
    SectionIcon.PEOPLE -> Icons.Filled.Person
    SectionIcon.HOME -> Icons.Filled.Home
    SectionIcon.NEXTCLOUD_TODO -> Icons.Filled.Checklist
    SectionIcon.DEFAULT -> Icons.AutoMirrored.Filled.List
}
