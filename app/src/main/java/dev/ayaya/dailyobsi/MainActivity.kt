package dev.ayaya.dailyobsi

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ayaya.dailyobsi.widget.TodoWidget
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets the system draw transparent, theme-matched status/nav bars
        // instead of the old opaque light-theme scrim -- without this the
        // bars stayed solid white/light regardless of app theme or dark mode.
        // enableEdgeToEdge()'s own default already makes the status bar fully
        // transparent, but NOT the navigation bar -- that defaults to a
        // translucent scrim (DefaultLightScrim/DefaultDarkScrim) so 3-button
        // nav stays legible over arbitrary content. This app wants the same
        // literal transparency there too, content visible straight through.
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val dark = isSystemInDarkTheme()
            // Material You: match the device's actual system theme/wallpaper
            // colors on Android 12+, fall back to stock Material3 below that.
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                // Top/horizontal safe-drawing inset only here, NOT bottom --
                // reading mode wants its content to actually draw behind the
                // (now-transparent) nav bar rather than stop short of it.
                // Edit mode/buttons/the section editor's field each add their
                // own navigationBarsPadding() locally instead, so only
                // reading mode gets the "extends under the nav bar" look.
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        )
                    ) {
                        DailyObsiApp()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyObsiApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var dailyUri by remember { mutableStateOf(VaultPrefs.getTreeUri(context)) }
    var templateUri by remember { mutableStateOf(VaultPrefs.getTemplateUri(context)) }
    // No folder picked yet -> land straight on settings, nothing else to show.
    var showSettings by remember { mutableStateOf(dailyUri == null) }
    // Reading mode (rendered checkboxes) is the default -- raw markdown
    // editing is an explicit opt-in via the top bar toggle.
    var editMode by remember { mutableStateOf(false) }

    var noteFile by remember { mutableStateOf<DocumentFile?>(null) }
    var text by remember { mutableStateOf("") }
    var viewingDate by remember { mutableStateOf(LocalDate.now()) }
    // Only meaningful right after landing on an empty today: is there a
    // yesterday note worth offering instead of jumping straight to "create"?
    var yesterdayFile by remember { mutableStateOf<DocumentFile?>(null) }

    // Section editor (pencil icon on a header, reading mode only): a separate
    // page that edits just that header's body, not the whole note. Non-null
    // line index means it's open; the draft is the extracted body text.
    var editingSectionLine by remember { mutableStateOf<Int?>(null) }
    var sectionDraft by remember { mutableStateOf("") }

    // SAF calls (findFile/readText/writeText/createTodayFile) go through
    // ContentResolver -> Binder IPC to the DocumentsProvider -- not
    // guaranteed fast, especially against a large/actively-synced daily
    // folder. Running any of that directly on the main thread risks an ANR
    // ("Input dispatching timed out") the instant the provider's slow to
    // answer; a real one hit exactly this path via onOpenYesterday (a plain
    // findFile call) with a 5s+ stall. So every SAF call here is pushed onto
    // Dispatchers.IO inside a coroutine -- loadNoteSuspend/persist's caller
    // always resumes on Main to touch Compose state, but the SAF work itself
    // never runs there.
    suspend fun loadNoteSuspend(date: LocalDate) {
        val uri = dailyUri ?: return
        val startMs = System.currentTimeMillis()
        val (file, content, yFile) = withContext(Dispatchers.IO) {
            // Today and the yesterday-fallback are looked up in the SAME
            // listing query via findFiles (not two separate DocumentFile.findFile
            // calls) -- see its doc comment; this was the actual slow part
            // the user was timing at startup, not readText.
            val todayName = DailyNote.fileNameFor(date)
            val yesterdayName = if (date == LocalDate.now()) DailyNote.fileNameFor(date.minusDays(1)) else null
            val found = DailyNote.findFiles(context, uri, setOfNotNull(todayName, yesterdayName))
            val f = found[todayName]
            val c = f?.let { DailyNote.readText(context, it.uri) } ?: ""
            val y = if (f == null) yesterdayName?.let { found[it] } else null
            Triple(f, c, y)
        }
        android.util.Log.d("DailyObsiPerf", "loadNoteSuspend($date) took ${System.currentTimeMillis() - startMs}ms, found=${file != null}")
        noteFile = file
        text = content
        viewingDate = date
        editMode = false
        yesterdayFile = yFile
    }

    fun loadNote(date: LocalDate) {
        scope.launch { loadNoteSuspend(date) }
    }

    fun persist(newText: String) {
        val file = noteFile ?: return
        text = newText
        scope.launch {
            withContext(Dispatchers.IO) { DailyNote.writeText(context, file.uri, newText) }
            TodoWidget().updateAll(context)
        }
    }

    // No Save button -- edit mode autosaves instead: periodically while
    // typing, and immediately whenever edit mode is left (toggle or back).
    fun exitEditMode() {
        if (editMode) persist(text)
        editMode = false
    }

    fun openSectionEditor(headerLineIndex: Int) {
        val range = headerBodyLineRange(text, headerLineIndex)
        sectionDraft = if (range.first > range.last) "" else text.lines().subList(range.first, range.last + 1).joinToString("\n")
        editingSectionLine = headerLineIndex
    }

    fun cancelSectionEditor() { editingSectionLine = null }

    fun saveSectionEditor() {
        val headerLineIndex = editingSectionLine ?: return
        // Nothing else can change `text` while this page is open, so the
        // range computed at open time is still valid here.
        val range = headerBodyLineRange(text, headerLineIndex)
        persist(DailyNote.replaceLines(text, range, sectionDraft))
        editingSectionLine = null
    }

    LaunchedEffect(dailyUri) { if (dailyUri != null) loadNoteSuspend(LocalDate.now()) }

    LaunchedEffect(editMode) {
        if (editMode) {
            while (true) {
                delay(10_000)
                persist(text)
            }
        }
    }

    // Also autosave when the app is backgrounded/killed mid-edit, so a swipe-
    // away or a phone call doesn't lose whatever hasn't hit the 10s tick yet.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && editMode) persist(text)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // System back while editing exits to reading mode (autosaving), same as
    // tapping the Edit/Read toggle -- instead of leaving the screen/app.
    BackHandler(enabled = editMode) { exitEditMode() }

    // System back while the section editor is open acts like Cancel.
    BackHandler(enabled = editingSectionLine != null) { cancelSectionEditor() }

    Scaffold(
        // Scaffold reserves system-bar insets in its content padding by
        // default -- but MainActivity's outer Box(Modifier.safeDrawingPadding())
        // already does that for the whole app, so without this the bottom
        // inset gets applied twice: once there, once here. Same background
        // color both times so there's no visible seam (unlike the earlier
        // Surface/safeDrawingPadding bug), just the reading/editing area's
        // bottom sitting well above the screen's actual bottom edge.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // The file name is more useful screen real-estate than a
                // static app label once a note's actually open.
                title = {
                    Text(
                        when {
                            editingSectionLine != null -> "Edit section"
                            !showSettings && noteFile != null -> noteFile?.name ?: "DailyObsi"
                            else -> "DailyObsi"
                        },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (editingSectionLine != null) {
                        TextButton(onClick = ::cancelSectionEditor) { Text("Cancel") }
                        TextButton(onClick = ::saveSectionEditor) { Text("Save") }
                    } else if (showSettings) {
                        if (dailyUri != null) {
                            TextButton(onClick = { showSettings = false }) { Text("Done") }
                        }
                    } else {
                        if (noteFile != null) {
                            if (viewingDate != LocalDate.now()) {
                                TextButton(onClick = { loadNote(LocalDate.now()) }) { Text("Back to today") }
                            }
                            TextButton(onClick = { if (editMode) exitEditMode() else editMode = true }) {
                                Text(if (editMode) "Read" else "Edit")
                            }
                        }
                        IconButton(onClick = { showSettings = true }) { Text("⚙") }
                    }
                }
            )
        }
    ) { padding ->
        if (editingSectionLine != null) {
            SectionEditorScreen(
                modifier = Modifier.padding(padding),
                draft = sectionDraft,
                onDraftChanged = { sectionDraft = it }
            )
        } else if (showSettings) {
            SettingsScreen(
                modifier = Modifier.padding(padding),
                dailyUri = dailyUri,
                templateUri = templateUri,
                onDailyUriChanged = { dailyUri = it },
                onTemplateUriChanged = { templateUri = it }
            )
        } else {
            EditorScreen(
                modifier = Modifier.padding(padding),
                editMode = editMode,
                noteFile = noteFile,
                text = text,
                dailyUri = dailyUri!!,
                viewingDate = viewingDate,
                yesterdayFile = yesterdayFile,
                onToggleCheckbox = { lineIndex -> persist(DailyNote.toggleCheckbox(text, lineIndex)) },
                onShiftIndent = { lineIndex, delta -> persist(DailyNote.shiftIndent(text, lineIndex, delta)) },
                onMoveLine = { lineIndex, delta -> persist(DailyNote.moveLine(text, lineIndex, delta)) },
                onSetLine = { lineIndex, newLine -> persist(DailyNote.replaceLine(text, lineIndex, newLine)) },
                onEditSection = ::openSectionEditor,
                onTextChanged = { text = it },
                onOpenYesterday = { loadNote(LocalDate.now().minusDays(1)) },
                onCreateToday = {
                    scope.launch {
                        val created = withContext(Dispatchers.IO) {
                            DailyNote.createTodayFile(context, dailyUri!!, templateUri)
                        }
                        if (created != null) loadNoteSuspend(LocalDate.now())
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionEditorScreen(
    modifier: Modifier = Modifier,
    draft: String,
    onDraftChanged: (String) -> Unit,
) {
    // Same borderless/fullscreen raw-text editing as the main edit mode, just
    // scoped to one header's body. Save/Cancel live in the top bar; there's
    // no autosave here since leaving this page always resolves it either way.
    MarkdownTextField(
        value = draft,
        onValueChange = onDraftChanged,
        modifier = modifier.fillMaxSize().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        // Line indices from MarkdownTextField's overlay here are local to
        // `draft` (not the full file), so these operate on `draft` itself via
        // the same onDraftChanged the text editing already uses -- not on the
        // file directly, which would go stale against `draft` until Save.
        onShiftIndent = { lineIndex, delta -> onDraftChanged(DailyNote.shiftIndent(draft, lineIndex, delta)) },
        onMoveLine = { lineIndex, delta -> onDraftChanged(DailyNote.moveLine(draft, lineIndex, delta)) }
    )
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    dailyUri: Uri?,
    templateUri: Uri?,
    onDailyUriChanged: (Uri) -> Unit,
    onTemplateUriChanged: (Uri) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val pickDailyFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            VaultPrefs.setTreeUri(context, uri)
            onDailyUriChanged(uri)
        }
    }

    val pickTemplate = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            VaultPrefs.setTemplateUri(context, uri)
            onTemplateUriChanged(uri)
        }
    }

    Column(modifier = modifier.fillMaxSize().navigationBarsPadding().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // Both pickers are direct and independent -- no vault root, no
        // guessing subfolders. Point each at exactly the thing it names.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dailyUri?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: "No daily folder picked",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { pickDailyFolder.launch(null) }) {
                Text(if (dailyUri == null) "Choose daily folder" else "Change")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                templateUri?.let { DocumentFile.fromSingleUri(context, it)?.name } ?: "No daily template picked",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { pickTemplate.launch(arrayOf("text/*", "*/*")) }) {
                Text(if (templateUri == null) "Choose daily template" else "Change")
            }
        }
    }
}

@Composable
private fun EditorScreen(
    modifier: Modifier = Modifier,
    editMode: Boolean,
    noteFile: DocumentFile?,
    text: String,
    dailyUri: Uri,
    viewingDate: LocalDate,
    yesterdayFile: DocumentFile?,
    onToggleCheckbox: (lineIndex: Int) -> Unit,
    onShiftIndent: (lineIndex: Int, delta: Int) -> Unit,
    onMoveLine: (lineIndex: Int, delta: Int) -> Unit,
    onSetLine: (lineIndex: Int, newLine: String) -> Unit,
    onEditSection: (headerLineIndex: Int) -> Unit,
    onTextChanged: (String) -> Unit,
    onOpenYesterday: () -> Unit,
    onCreateToday: () -> Unit,
) {
    // Edit mode goes edge-to-edge (no padding, no boxed frame) to give the
    // raw text as much room as possible. Reading mode keeps left/right/top
    // margins for readability, but NOT bottom -- MainActivity's outer Box
    // deliberately no longer reserves the nav-bar inset, specifically so
    // reading mode's list can draw all the way behind the (transparent) nav
    // bar. Edit mode and the empty state (nothing scrollable to show
    // through the nav bar, just buttons that need to stay tappable) opt
    // back into that inset locally via navigationBarsPadding() instead.
    val emptyState = noteFile == null
    Column(
        modifier = modifier.fillMaxSize()
            .padding(
                start = if (editMode) 0.dp else 16.dp,
                end = if (editMode) 0.dp else 16.dp,
                top = if (editMode) 0.dp else 16.dp,
                bottom = 0.dp
            )
            .then(if (editMode || emptyState) Modifier.navigationBarsPadding() else Modifier)
    ) {
        if (noteFile == null) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "No note for ${DailyNote.fileNameFor(viewingDate)} yet.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (yesterdayFile != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(onClick = onOpenYesterday, modifier = Modifier.weight(1f)) { Text("Open yesterday's note") }
                    OutlinedButton(onClick = onCreateToday, modifier = Modifier.weight(1f)) { Text("Create today's note") }
                }
            } else {
                Button(onClick = onCreateToday, modifier = Modifier.fillMaxWidth()) { Text("Create today's note") }
            }
            return@Column
        }

        if (editMode) {
            // Borderless/frameless -- no OutlinedTextField box, no Save button.
            // Autosave (10s tick + on exit/backgrounding) is wired in DailyObsiApp.
            MarkdownTextField(
                value = text,
                onValueChange = onTextChanged,
                // No bottom padding here either -- same reasoning as reading
                // mode's Column: it'd stack on top of the safe-area inset
                // already reserved once, shrinking how far the field can
                // actually scroll before its last line clears the gesture-nav area.
                modifier = Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // Reading mode's body text is bodyLarge (16sp) -- match that
                // scale here too (was stuck at bodyMedium/14sp, way too small),
                // just a hair smaller since edit mode also carries raw syntax.
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Checkbox lines get the same swipe-to-indent + up/down
                // reorder as reading mode -- line indices here are absolute
                // into the file, matching what these callbacks expect.
                onShiftIndent = onShiftIndent,
                onMoveLine = onMoveLine
            )
        } else {
            MarkdownView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                text = text,
                dailyUri = dailyUri,
                viewingDate = viewingDate,
                onToggleCheckbox = onToggleCheckbox,
                onShiftIndent = onShiftIndent,
                onMoveLine = onMoveLine,
                onSetLine = onSetLine,
                onEditSection = onEditSection
            )
        }
    }
}
