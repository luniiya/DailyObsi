package dev.ayaya.dailyobsi.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Material You (wallpaper-derived) colors on API 31+, stock Material3 below.
 *  Shared by the app, the heading picker, and every widget's GlanceTheme. */
fun appColorScheme(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

/** Call from every Activity's onCreate, before setContent.
 *
 *  Compose Foundation 1.9's new text context menu (the copy/paste toolbar)
 *  crashes with "ToolbarRequester is not initialized" when a text field's
 *  selection toolbar tries to show after the field left composition -- e.g.
 *  flipping a tab from Write to Read, or swiping tabs, with text selected.
 *  Fixed upstream in a later Foundation release, but upgrading means moving
 *  AGP/Kotlin/compileSdk too; until then, use the older, stable toolbar. */
@OptIn(ExperimentalFoundationApi::class)
fun applyComposeWorkarounds() {
    ComposeFoundationFlags.isNewContextMenuEnabled = false
}

@Composable
fun DailyObsiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = appColorScheme(LocalContext.current, isSystemInDarkTheme()),
        content = content,
    )
}
