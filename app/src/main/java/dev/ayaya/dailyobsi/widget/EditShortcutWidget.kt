package dev.ayaya.dailyobsi.widget

import android.content.Context
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import dev.ayaya.dailyobsi.ui.appColorScheme
import dev.ayaya.dailyobsi.MainActivity

// actionStartActivity's `parameters` become intent extras on the launched
// activity keyed by the parameter's key NAME string -- this must exactly
// match MainActivity.EXTRA_OPEN_SECTION_HEADING for
// intent.getStringExtra(...) to actually see it.
private val OPEN_SECTION_HEADING_KEY = ActionParameters.Key<String>(MainActivity.EXTRA_OPEN_SECTION_HEADING)

/**
 * Widget 1 of 3 (see CLAUDE.md's "Planned: three-widget system"): a heading
 * shortcut, not a generic "open edit mode" button -- each placed instance is
 * configured (via HeadingPickerActivity, see edit_shortcut_widget_info.xml's
 * android:configure) to point at one heading from today's note. The face
 * shows just that heading's title, no body content (that's what HeadingWidget
 * is for); tapping it launches straight into the section editor scoped to
 * that heading via MainActivity.EXTRA_OPEN_SECTION_HEADING, skipping both
 * the reading-mode default and a generic full-note edit mode.
 */
class EditShortcutWidget : GlanceAppWidget() {
    // Freely resizable, no min/max beyond the platform floor (explicit
    // requirement) -- Exact reads the actual current size live via
    // LocalSize.current rather than snapping between preset buckets.
    override val sizeMode = SizeMode.Exact

    // REQUIRED for HeadingPickerActivity's post-config update to actually
    // show up -- see the long comment on the state read inside provideContent
    // below for the real reason (a real bug, found via logcat, not
    // theoretical: configuring a widget saved correctly but the face kept
    // showing "Not configured" indefinitely).
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // ColorProviders(light, dark) -- not GlanceTheme's own no-arg
        // default, which resolves to the library-internal
        // (@RestrictTo(LIBRARY_GROUP), unusable from app code)
        // DynamicThemeColorProviders -- bakes in day/night switching itself
        // from the two resolved ColorSchemes, matching MainActivity's own
        // dynamicLightColorScheme/dynamicDarkColorScheme + API<31 fallback,
        // just via the RemoteViews-compatible path.
        val light = appColorScheme(context, dark = false)
        val dark = appColorScheme(context, dark = true)

        provideContent {
            // Reading the heading here (currentState<Preferences>(), inside
            // provideContent's composable) rather than once via
            // getAppWidgetState() before provideContent, as this used to do,
            // is not a style choice -- it's the actual fix for a real bug.
            // Confirmed by reading Glance's own source
            // (AppWidgetSession.kt): when HeadingPickerActivity calls
            // EditShortcutWidget().update(context, glanceId) on an ALREADY-
            // RUNNING session (true the instant this widget has rendered
            // once, which happens immediately on placement, before the user
            // even finishes configuring), update() does NOT re-invoke
            // provideGlance() from scratch -- it calls session.updateGlance(),
            // which re-reads state ONLY via this widget's `stateDefinition`
            // and pushes it through LocalState/currentState() reactively.
            // provideGlance's suspend body (where the old getAppWidgetState()
            // call lived) truly only runs ONCE per session lifetime --
            // anything computed there before calling provideContent is
            // permanently baked into every later recomposition, no matter
            // how many times .update()/.updateAll() gets called afterward.
            val prefs = currentState<Preferences>()
            val rawHeading = prefs[SELECTED_HEADING_KEY]
            val heading = rawHeading?.trimStart('#', ' ').orEmpty()
            val emoji = prefs[SELECTED_EMOJI_KEY]?.ifBlank { null } ?: DEFAULT_WIDGET_EMOJI
            android.util.Log.d("DailyObsiWidget", "EditShortcutWidget content recompose: id=$id rawHeading=\"$rawHeading\" emoji=\"$emoji\"")

            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        // Opaque surface color, not a faint alpha-tinted wash
                        // -- the earlier Color(0x1F6750A4) read as basically
                        // transparent on a real home screen, which was
                        // explicitly reported as wrong.
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(16.dp)
                        .padding(12.dp)
                        .clickable(
                            actionStartActivity<MainActivity>(
                                parameters = if (rawHeading != null)
                                    actionParametersOf(OPEN_SECTION_HEADING_KEY to rawHeading)
                                else actionParametersOf()
                            )
                        ),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                ) {
                    Text(emoji, style = TextStyle(fontSize = 24.sp))
                    Text(
                        heading.ifEmpty { "Not configured" },
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 3
                    )
                }
            }
        }
    }
}

class EditShortcutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EditShortcutWidget()
}
