package dev.ayaya.dailyobsi.ui

import android.content.Context
import android.os.Build
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

@Composable
fun DailyObsiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = appColorScheme(LocalContext.current, isSystemInDarkTheme()),
        content = content,
    )
}
