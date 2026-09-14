package dev.ayaya.dailyobsi.model

fun effectiveSectionMode(isHistorical: Boolean, configured: SectionMode): SectionMode =
    if (isHistorical) SectionMode.READ else configured

fun canSwipeBetweenTabs(isHistorical: Boolean, mode: SectionMode): Boolean =
    !isHistorical && mode == SectionMode.WRITE
