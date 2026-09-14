package dev.ayaya.dailyobsi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.model.NoteSection
import dev.ayaya.dailyobsi.model.SectionIcon
import dev.ayaya.dailyobsi.model.SectionId
import dev.ayaya.dailyobsi.model.defaultSectionIcon
import dev.ayaya.dailyobsi.model.sectionNavLabel

@Composable
fun SectionBottomBar(
    sections: List<NoteSection>,
    selectedId: SectionId?,
    onSelect: (SectionId) -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        LazyRow(
            modifier = Modifier.navigationBarsPadding().height(72.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                items = sections,
                key = { "${it.id.normalizedTitle}:${it.id.occurrence}" },
            ) { section ->
                val selected = section.id == selectedId
                Column(
                    modifier = Modifier.width(78.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                        )
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(section.id) },
                            role = Role.Tab,
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = iconFor(defaultSectionIcon(section.title)),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = sectionNavLabel(section.title),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun iconFor(icon: SectionIcon): ImageVector = when (icon) {
    SectionIcon.TASKS -> Icons.Filled.CheckCircle
    SectionIcon.JOURNAL -> Icons.Filled.Create
    SectionIcon.GRATITUDE -> Icons.Filled.Favorite
    SectionIcon.HEALTH -> Icons.Filled.Star
    SectionIcon.WORK -> Icons.Filled.Build
    SectionIcon.IDEAS -> Icons.Filled.Info
    SectionIcon.PEOPLE -> Icons.Filled.Person
    SectionIcon.HOME -> Icons.Filled.Home
    SectionIcon.DEFAULT -> Icons.AutoMirrored.Filled.List
}
