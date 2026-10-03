package dev.ayaya.dailyobsi.model

fun effectiveSectionMode(isHistorical: Boolean, configured: SectionMode): SectionMode =
    if (isHistorical) SectionMode.READ else configured

/** The Nextcloud Daily Todo tab. Not a note section: headings' occurrences
 *  count from 0, so no heading can produce this id. */
val TODO_TAB_ID = SectionId("nextcloud daily todo", -1)

/** The tab row: the note's `##` sections, then the todo tab last when Nextcloud is connected. */
fun tabIds(sections: List<NoteSection>, todoConnected: Boolean): List<SectionId> =
    sections.map { it.id } + listOfNotNull(TODO_TAB_ID.takeIf { todoConnected && sections.isNotEmpty() })

/** Which tab to show after (re)loading a note: the tab already open when
 *  reloading the same note ([current]), else the one remembered for that
 *  day ([remembered]), else the first -- skipping any that no longer exist. */
fun restoredSection(
    tabs: List<SectionId>,
    current: SectionId?,
    remembered: SectionId?,
): SectionId? = when {
    current != null && current in tabs -> current
    remembered != null && remembered in tabs -> remembered
    else -> tabs.firstOrNull()
}

fun canSwipeBetweenTabs(isHistorical: Boolean, mode: SectionMode): Boolean =
    !isHistorical && mode == SectionMode.WRITE
