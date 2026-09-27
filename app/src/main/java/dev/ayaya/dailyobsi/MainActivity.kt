package dev.ayaya.dailyobsi

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ayaya.dailyobsi.ui.DailyObsiApp
import dev.ayaya.dailyobsi.ui.DailyObsiTheme
import dev.ayaya.dailyobsi.ui.DailyObsiViewModel

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_EDIT_MODE = "open_edit_mode"
        const val EXTRA_OPEN_SECTION_HEADING = "open_section_heading"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val startInEditMode = intent.getBooleanExtra(EXTRA_OPEN_EDIT_MODE, false)
        val openSectionHeading = intent.getStringExtra(EXTRA_OPEN_SECTION_HEADING)
        setContent {
            DailyObsiTheme {
                val model: DailyObsiViewModel = viewModel(
                    factory = DailyObsiViewModel.Factory(
                        application,
                        startInEditMode,
                        openSectionHeading,
                    ),
                )
                Surface(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        ),
                    ) {
                        DailyObsiApp(model)
                    }
                }
            }
        }
    }
}

