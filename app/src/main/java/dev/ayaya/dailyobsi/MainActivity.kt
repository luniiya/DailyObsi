package dev.ayaya.dailyobsi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ayaya.dailyobsi.ui.DailyObsiApp
import dev.ayaya.dailyobsi.ui.DailyObsiTheme
import dev.ayaya.dailyobsi.ui.DailyObsiViewModel
import dev.ayaya.dailyobsi.ui.TodoViewModel
import dev.ayaya.dailyobsi.todo.NextcloudSignIn
import dev.ayaya.dailyobsi.ui.applyComposeWorkarounds

class MainActivity : ComponentActivity() {
    private val todo: TodoViewModel by viewModels()

    companion object {
        const val EXTRA_OPEN_EDIT_MODE = "open_edit_mode"
        const val EXTRA_OPEN_SECTION_HEADING = "open_section_heading"
        /** From the Todo widget: open on the Nextcloud Todo tab. */
        const val EXTRA_OPEN_TODO = "open_todo_tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyComposeWorkarounds()
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val startInEditMode = intent.getBooleanExtra(EXTRA_OPEN_EDIT_MODE, false)
        val openSectionHeading = intent.getStringExtra(EXTRA_OPEN_SECTION_HEADING)
        val openTodoTab = intent.getBooleanExtra(EXTRA_OPEN_TODO, false)
        setContent {
            DailyObsiTheme {
                val model: DailyObsiViewModel = viewModel(
                    factory = DailyObsiViewModel.Factory(
                        application,
                        startInEditMode,
                        openSectionHeading,
                        openTodoTab,
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
                        DailyObsiApp(model, todo)
                    }
                }
            }
        }
    }

    // Nextcloud SSO still uses startActivityForResult (library 1.3.2).
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (val result = NextcloudSignIn.handleResult(this, requestCode, resultCode, data)) {
            is NextcloudSignIn.Result.Connected -> todo.onConnected(result.account)
            is NextcloudSignIn.Result.Failed -> todo.showMessage(result.message)
            NextcloudSignIn.Result.Cancelled, NextcloudSignIn.Result.Ignored -> Unit
        }
    }
}

