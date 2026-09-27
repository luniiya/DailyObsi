package dev.ayaya.dailyobsi.model

fun effectiveSectionMode(isHistorical: Boolean, configured: SectionMode): SectionMode =
    if (isHistorical) SectionMode.READ else configured

/** Which tab to show after (re)loading a note: the tab already open when
 *  reloading the same note ([current]), else the one remembered for that
 *  day ([remembered]), else the first -- skipping any that no longer exist. */
fun restoredSection(
    sections: List<NoteSection>,
    current: SectionId?,
    remembered: SectionId?,
): SectionId? {
    fun exists(id: SectionId?) = id != null && sections.any { it.id == id }
    return when {
        exists(current) -> current
        exists(remembered) -> remembered
        else -> sections.firstOrNull()?.id
    }
}

fun canSwipeBetweenTabs(isHistorical: Boolean, mode: SectionMode): Boolean =
    !isHistorical && mode == SectionMode.WRITE
