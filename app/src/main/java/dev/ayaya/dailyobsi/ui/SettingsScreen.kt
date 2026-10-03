package dev.ayaya.dailyobsi.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.SectionMode
import dev.ayaya.dailyobsi.todo.NextcloudSignIn

@Composable
fun SettingsScreen(
    state: EditorUiState,
    model: DailyObsiViewModel,
    todoState: TodoUiState,
    todo: TodoViewModel,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { persistTreePermission(context, it); model.setDailyUri(it) } }
    val pickTemplate = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { persistReadPermission(context, it); model.setTemplateUri(it) } }

    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PreferencePicker(
            label = state.dailyUri?.let { DocumentFile.fromTreeUri(context, it)?.name }
                ?: "No daily folder picked",
            button = if (state.dailyUri == null) "Choose folder" else "Change",
            onClick = { pickFolder.launch(null) },
        )
        PreferencePicker(
            label = state.templateUri?.let { DocumentFile.fromSingleUri(context, it)?.name }
                ?: "No daily template picked",
            button = if (state.templateUri == null) "Choose template" else "Change",
            onClick = { pickTemplate.launch(arrayOf("text/*", "*/*")) },
        )

        Text("Nextcloud Daily Todo", style = MaterialTheme.typography.titleMedium)
        PreferencePicker(
            label = todoState.accountName?.let { "Todo tab uses $it" }
                ?: "Not connected. Uses the account logged in to the Nextcloud app.",
            button = if (todoState.connected) "Disconnect" else "Connect",
            onClick = {
                if (todoState.connected) {
                    todo.disconnect()
                } else {
                    context.findActivity()?.let(NextcloudSignIn::start)?.let(todo::showMessage)
                }
            },
        )
        todoState.message?.takeIf { !todoState.connected }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Text("Note layout", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LayoutMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.layoutMode == mode,
                    onClick = { model.setLayoutMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, LayoutMode.entries.size),
                ) {
                    Text(if (mode == LayoutMode.TABBED) "Tabbed sections" else "Classic")
                }
            }
        }

        if (state.sections.isNotEmpty()) {
            Text("Default section modes", style = MaterialTheme.typography.titleMedium)
            state.sections.distinctBy { it.id.normalizedTitle }.forEach { section ->
                val mode = model.configuredMode(section.title)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(section.title, style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SectionMode.entries.forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = mode == choice,
                                onClick = { model.setConfiguredMode(section.title, choice) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index,
                                    SectionMode.entries.size,
                                ),
                            ) { Text(if (choice == SectionMode.READ) "Read" else "Write") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PreferencePicker(label: String, button: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClick) { Text(button) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun persistTreePermission(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}

private fun persistReadPermission(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
}
