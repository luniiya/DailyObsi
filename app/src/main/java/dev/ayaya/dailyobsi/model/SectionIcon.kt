package dev.ayaya.dailyobsi.model

enum class SectionIcon {
    TIME_UNTIL,
    MEDS,
    TASKS,
    JOURNAL,
    GRATITUDE,
    HEALTH,
    WORK,
    IDEAS,
    PEOPLE,
    HOME,
    /** The Nextcloud Daily Todo tab, never guessed from a heading. */
    NEXTCLOUD_TODO,
    DEFAULT,
}

fun defaultSectionIcon(title: String): SectionIcon {
    val normalized = normalizeHeading(title)
    return when {
        hasAny(normalized, "time until", "countdown", "deadline") -> SectionIcon.TIME_UNTIL
        hasAny(normalized, "med", "meds", "medicine", "medication", "pill") -> SectionIcon.MEDS
        hasAny(normalized, "todo", "task", "plan") -> SectionIcon.TASKS
        hasAny(normalized, "journal", "diary", "reflection", "report") ->
            SectionIcon.JOURNAL
        hasAny(normalized, "gratitude", "thank", "win") -> SectionIcon.GRATITUDE
        hasAny(normalized, "health", "exercise", "workout", "sleep") ->
            SectionIcon.HEALTH
        hasAny(normalized, "work", "project", "meeting") -> SectionIcon.WORK
        hasAny(normalized, "idea", "brainstorm", "learn") -> SectionIcon.IDEAS
        hasAny(normalized, "people", "friend", "family", "social") -> SectionIcon.PEOPLE
        hasAny(normalized, "home", "house") -> SectionIcon.HOME
        else -> SectionIcon.DEFAULT
    }
}

fun sectionNavLabel(title: String): String {
    val trimmed = title.trim()
    if (trimmed.length <= 12) return trimmed
    return trimmed.substringBefore(' ').take(12)
}

private fun hasAny(value: String, vararg candidates: String): Boolean =
    candidates.any(value::contains)
