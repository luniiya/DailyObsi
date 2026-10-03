# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

A native Android app + home-screen widgets that read/edit the user's Obsidian daily note directly, bypassing the (slow) Obsidian app. The app is a tabbed reader/editor for today's note; the widgets show one heading, the full note, or a shortcut into one heading.

The git-pulled file in shared storage is the single source of truth. App and widgets both read/write it directly; there is no intermediate state or sync of our own.

## Tasks moved to Nextcloud (workflow change, 2026-10-03)

An AI agent now manages the user's schedule, so **the task list (only the task list) moved out of Obsidian into Nextcloud** (https://nc.luniiya.me on the user's server `banana`). There, a user-built Nextcloud app, **Daily Todo**, builds one checklist per day from a template + calendar events + tasks. Everything else in the daily note stays in Obsidian. DailyObsi is getting a special tab that shows and edits the Nextcloud day list over HTTP. That's the one exception to "no backend" below: the Obsidian file is still the source of truth for everything else. Plugin model, API, auth (Nextcloud Single Sign-On through the Files app), the decisions taken and how the tab is built: **`docs/nextcloud-daily-todo.md`**. Code: `todo/` (pure model + `TodoCache` + `NextcloudTodoClient` + `NextcloudSignIn`), `ui/TodoViewModel.kt`, `ui/TodoTab.kt`; the tab is `TODO_TAB_ID`, appended by `tabIds`. The plugin source is read-only at `banana:/mnt/1/services/nextcloud/apps/daily_todo`; don't modify it from here.

## Constraints (decided, don't re-ask)

- **No Android Studio.** CLI only: `./gradlew`, `adb`, and the scripts below.
- **No Tasker, no Syncthing.** A Termux script `git pull`s the vault into Android *shared* storage (`~/storage/shared/...`), which is what makes SAF access viable.
- **No backend/server of our own.** The app reads/writes the note directly via SAF. The only network access is the Nextcloud Daily Todo tab (see above).
- **Use the `speak` MCP tool** (`mcp__speak__speak`) to get the user's attention (questions, decisions, blockers) and to report progress on long tasks (milestones, done).
- **On-device testing is the user's job, not Claude's.** After a change: `./test.sh`, `./run.sh` (build + install), `./logs.sh crash`, then stop. Never drive the emulator with `adb shell input`/screenshots — the user tests on it at the same time. Never run destructive diagnostics (`pm clear`, deleting vault files) without asking: a `pm clear` once wiped the user's picked folder and every widget's config mid-debugging.

## Scripts

- **`./run.sh`** — boots the `grid9test` AVD if nothing's attached, `assembleDebug`, `adb install -r`, relaunches the app.
- **`./test.sh [ClassNameFilter]`** — JVM unit tests, no emulator. Run before every install.
- **`./logs.sh`** (live, filtered) / **`dump`** / **`crash`** (one-shot crash scan — run after every install) / **`clear`**.

## Layout of the code

```
app/src/main/java/dev/ayaya/dailyobsi/
  MainActivity.kt          edge-to-edge setup, widget launch extras -> ViewModel
  DailyNote.kt             SAF file ops + pure note rewrites (toggle/indent/move/replace)
  MarkdownParsing.kt       shared regexes, Block/parseBlocks, headerBodyLineRange, colors
  MarkdownReading.kt       reading-mode renderer (MarkdownView, LazyColumn based)
  MarkdownEditing.kt       edit-mode editor (MarkdownTextField, TextFieldState based)
  ProgressBar.kt           ```progressbar``` parsing + value rewrite, shared by app AND widgets
  VaultPrefs.kt            picked folder/template URIs
  model/                   pure data + policy: NoteSections (H2 tabs), SectionIcon, DailyArchive
                           (file-name dates, "on this day" memories), TabInteractionPolicy
                           (read-only history, restoredSection), NoteModels
  storage/                 NoteRepository (IO-dispatched SAF), SaveCoordinator (debounced autosave),
                           AppPreferences (layout, per-section default mode, last open tab),
                           AttachmentResolver (embed lookup cache), NoteWriteLock (noteWriteMutex)
  ui/                      DailyObsiViewModel + EditorUiState, DailyObsiApp (scaffold, lifecycle),
                           NoteScreen (tabs/classic, shared NoteBody), DailyTopBar, SectionBottomBar,
                           SaveIndicator, ModeToggleFab, SettingsScreen, DailyCalendarDialog,
                           AppTheme (appColorScheme, DailyObsiTheme, applyComposeWorkarounds)
  widget/                  Glance widgets, GlanceMarkdown renderer, HeadingPickerActivity, WidgetKeys
app/src/test/java/dev/ayaya/dailyobsi/   unit tests, mirroring the folders above
```

**Keep things shared, not copied.** One implementation per concern: `NoteBody` renders any markdown chunk (a tab's section or the whole note) as editor or reader; `appColorScheme` is the only Material You / fallback color logic (app, picker, all widgets); `ProgressBar.kt` is the only progress-bar logic (app reader, widget renderer, widget +/- action); `SaveStatusLabel` is the only save indicator. When a second caller needs something, extract it rather than duplicating it.

## App behavior

- **Tabbed layout (default): one tab per `##` section**, via `parseH2Sections` (fence-aware; duplicate titles get distinct `SectionId(normalizedTitle, occurrence)`). A `HorizontalPager` holds the pages, and `SectionBottomBar` shows icon + label tabs (icons guessed from the title by `defaultSectionIcon`). Tabs share the bar's full width evenly (`bottomBarTabWidth`) and stop shrinking at 72dp, after which the row scrolls. The bar hides while the keyboard is up. **Classic layout** (Settings) shows the whole note as one page. A note with no `##` headers in tabbed mode shows a "choose Classic" message.
- **Each tab has its own Read/Write mode**, toggled by `ModeToggleFab`. Defaults come from Settings → "Default section modes" (persisted per normalized title; `report` defaults to Write). Swiping between tabs is only enabled in Write mode, so reading-mode horizontal swipes stay free for swipe-to-indent.
- **The open tab survives app switches and restarts.** `refreshAfterResume` (on `ON_START`) re-reads the file *silently* — no loading spinner, same tab, same modes — so widget writes made while backgrounded show up without resetting the UI. The last tab on today's note is persisted (`AppPreferences.lastSection`, keyed by date) and restored on a cold start. Creating today's note clears it, so a new day opens on the first tab. `restoredSection` holds the rule: open tab when reloading the same note → remembered tab → first tab.
- **Past notes are read-only** (`isHistorical`). The calendar (top bar) opens any indexed date, and "memory" chips in the top bar jump to the same day in earlier years. The back arrow returns to today.
- **Missing today's note** offers "Create today's note" (copies the picked template, else blank) and "Open yesterday" when it exists.
- **Saving:** every change goes through `SaveCoordinator` (750ms debounce, mutex-serialized, revision-tracked). Reader interactions (checkbox, indent, reorder, progress ±) save immediately; typing is debounced; everything flushes on `ON_STOP` and before switching notes/tabs/settings. The top bar shows "Saving…" while writing and "Saved" for 1s after, and nothing otherwise (a permanent "Saved", or an "Unsaved" flickering per keystroke, were both noise). Errors stay up with Retry. Widgets aren't refreshed on each in-app save (Glance composes on this process's main thread): saves mark them stale, and they refresh once on `ON_STOP`.
- **All SAF I/O runs on `Dispatchers.IO`.** SAF calls are Binder IPC to another process. A main-thread `findFile` caused a real ANR on a Pixel 8 Pro ("Input dispatching timed out"). `DailyNote.findFiles`/`indexFiles` read `DISPLAY_NAME` straight off one child-listing cursor, because `DocumentFile.findFile` does a separate query per child's `getName()` (up to 513 round-trips in the real 512-file folder; now ~177ms total).

## Reading mode (`MarkdownReading.kt`)

Pragmatic, not full CommonMark — scoped to what the vault's `00 - todo/daily template.md` uses: headers (collapsible by tapping; a `#tag` line without a space isn't a header), bold/italic/strike/code, `==highlight==` and Highlightr `<mark class="hltr-*">` (color guessed from the class), `[[wikilink|alias]]` and `[text](url)` (styled, not navigable — the rest of the vault is outside the SAF grant), `![[embeds]]` (resolved via `AttachmentResolver`, a process-wide cache so scrolling doesn't re-run the SAF search; usually "not found", since attachments live outside the daily folder), lists with indentation, blockquotes, `---` rules, and ```` ```progressbar ```` blocks (`manual` + `button: true` get working −/+; `day-year`/`day-custom` compute from dates).

- **Checkbox rows:** tap to toggle, ▲/▼ to reorder (`DailyNote.moveLine`), horizontal swipe past ~56dp to indent/outdent. The swipe is hand-rolled (`awaitEachGesture`) to coexist with the list's vertical scroll: it only claims the gesture when horizontal movement clearly dominates (1.5×).
- **Headers are colored by level** (`headerColorFor`: h2 pink, h3 flamingo, h4 rosewater, h6 blue — Catppuccin *Latte* values from the vault's leftover AnuPpuccin config; Mocha is too washed out on light backgrounds). **Links use the Material You accent** (`colorScheme.primary`, threaded in as `linkColor` since the builders aren't composable), never a hardcoded blue.
- Embedded images get 12dp rounded corners.

## Edit mode (`MarkdownEditing.kt` → `MarkdownTextField`)

One continuous borderless, monospace (`FontFamily.Monospace`, 15sp) text field over the raw markdown, built on `TextFieldState`-based `BasicTextField` (needs Compose BOM ≥ 2025.09, hence the bump). Per-line fields were considered and rejected to keep normal typing and multi-line selection. `value`/`onValueChange` stay a plain `String` API. Its own edits round-trip via `snapshotFlow`, and a `LaunchedEffect(value)` resync handles external changes.

- **Styling:** `highlightMarkdownForEdit` is length-preserving (it swaps `[ ]` for a same-length ☐ glyph but never adds or removes characters), so its spans replay onto the raw text by offset through an `OutputTransformation`. There's a test for that invariant.
- **Real overlaid widgets on top of hidden syntax:** each checkbox line's `- [ ]` is made transparent (it still takes up space), and a real `Checkbox` is overlaid there. Each `---` line is hidden the same way and a `HorizontalDivider` drawn over it. Positions come from `TextLayoutResult`, shifted by a `ScrollState` shared between the field and the overlay. Two rules learned the hard way:
  - Compute overlay specs from **`layoutResult.layoutInput.text`**, not `state.text`. The layout lags typing by a frame, and mixing new offsets with the old layout made every checkbox below the cursor jump sideways for a frame on each keystroke.
  - **Key each overlay by its line's content** (`checkboxOverlayKeys`: indent + todo text, numbered if duplicated), not its position. Otherwise pressing Enter shifts every checkbox below into its neighbor's slot and they briefly animate checked↔unchecked.
  - The checkbox hit area is exactly the hidden `- [ ]` span at full line height — never wider, so taps on the todo text still place the cursor.
- **Tools bubble:** one small floating ⇤⇥▲▼ bubble acts on the line the cursor is on. Tried and ripped out, in order: an "insert checkbox" FAB (a thumb resting near it kept firing it mid-typing), per-row buttons, and a row swipe zone. Anything on top of the row stole cursor-placement taps.
- **Cursor after bubble actions:** `PendingCursorFollow` (`MovedLine` / `ReindentedLine`) tells the resync how to carry the cursor. Just clamping the raw offset left it on the *other* line after a swap, and the original `setTextAndPlaceCursorAtEnd` jumped to the end of the document.
- **Enter continues lists** (`listEnterFor`): `1.` → `2.`, and any checkbox → a fresh unchecked `- [ ] `. Enter on an empty item removes the marker instead, ending the list.
- **Compose 1.9 text-toolbar crash:** Foundation 1.9's new text context menu crashes with `ToolbarRequester is not initialized` when a selection toolbar shows after the field left composition (e.g. flipping a tab to Read with text selected). `applyComposeWorkarounds()` (called first thing in every Activity's `onCreate`) sets `ComposeFoundationFlags.isNewContextMenuEnabled = false`. Fixed upstream, but upgrading Compose past 1.9 means moving AGP/Kotlin/compileSdk too — drop the workaround when that happens.

## Window / theme

- `enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))` — the default nav bar style is a translucent scrim that visibly tinted the content behind it.
- `Surface(fillMaxSize)` → inner `Box` with `safeDrawing.only(Top + Horizontal)` padding. The padding goes on the inner Box so the Surface background reaches behind the system bars (padding the Surface left a colored seam). The bottom inset is left out on purpose, so reading mode draws behind the transparent nav bar; the list's `contentPadding` adds the nav-bar height at the end so the last item can still scroll clear of it. Edit mode, the empty state and Settings add `navigationBarsPadding()` themselves. No extra bottom margins: each one stacks on the inset (a real "big gap at the bottom" bug).
- Material You dynamic color on API 31+, stock Material3 below (`appColorScheme`). Dynamic color follows the system's actual color source (often wallpaper-derived), not necessarily the swatch shown in system Settings. Day/night window themes split via `values`/`values-night` (`Theme.DeviceDefault(.Light).NoActionBar`).
- Launcher icon has a `<monochrome>` layer for Android 13+ themed icons.

## Widgets (`widget/`, Jetpack Glance 1.1.1)

Three widgets, all freely resizable (`SizeMode.Exact`, 40dp min, no max), opaque `GlanceTheme.colors.surface` backgrounds from `ColorProviders(light = appColorScheme(..., false), dark = appColorScheme(..., true))`:

- **Edit Heading** (`EditShortcutWidget`) — shows one picked heading's title + a picked emoji; tap opens the app on that tab in Write mode (`MainActivity.EXTRA_OPEN_SECTION_HEADING`).
- **One Heading** (`HeadingWidget`) — renders one heading's body (`headerBodyLineRange`) with working checkboxes and progress ±.
- **Full Note** (`ReadingViewWidget`) — the whole note, same renderer; a header row opens the app.

The old "Checklist" (`TodoWidget`) prototype was removed entirely. Picker labels have no "DailyObsi:" prefix.

- **Configuration:** `HeadingPickerActivity` is the shared `ACTION_APPWIDGET_CONFIGURE` screen for Edit Heading and One Heading. Pick a heading, then an emoji (suggestions or the keyboard's emoji key). It stores the **raw heading line text** (`"## meds"`, not an index, which would go stale the next day or after edits) plus the emoji in the instance's `PreferencesGlanceStateDefinition` state (`SELECTED_HEADING_KEY`/`SELECTED_EMOJI_KEY`; `DEFAULT_WIDGET_EMOJI` fallback). With a configure activity the system doesn't push the first update itself, so the picker updates that `glanceId` directly (enumeration can miss a mid-configuration instance), refreshes the rest, and sets `RESULT_OK`.
- **Glance session semantics (read from Glance's `AppWidgetSession.kt` source):** `update()`/`updateAll()` do **not** re-run `provideGlance` on a live session. They only push new state into `LocalState` and force a recomposition, and anything computed before `provideContent {}` is fixed for the whole session. So every widget declares `override val stateDefinition = PreferencesGlanceStateDefinition` and reads `currentState<Preferences>()` *inside* `provideContent`, even Full Note, which has no config (reading it is what makes recomposition happen). The note itself loads via `produceState` keyed on `widgetDataVersion`, which `refreshAllWidgets` bumps, so loading runs on IO and not in the composable body (Glance composes on the main thread).
- **Refreshing:** `refreshAllWidgets()` is the one place that updates every widget class. Writers call `requestWidgetRefresh()` instead. It's debounced (200ms, generation counter), so a burst of taps collapses into one trailing refresh that reads the final file. Refreshing on every tap let older in-flight recomposes land after newer ones and show stale ticks. Holding a delay inside the write lock made rapid ± taps feel dead. Rapid-tap lag that remains afterwards is RemoteViews update coalescing on the platform side, not a data bug.
- **`noteWriteMutex`** serializes every read-modify-write of the note (widget actions *and* the app's repository), so two quick taps can't lose a write.
- **Checkbox taps:** exactly one handler, the Row's `.clickable`; the `CheckBox` itself gets `onCheckedChange = null`. Giving both an action made one tap fire twice (toggle, then toggle back).
- **RemoteViews limits:** a flat `Column`/`Row` crashes above 10 children, so lists use Glance `LazyColumn` and inline runs are capped (first 9 styled, the rest merged). The bitmap budget is ~15MB per update, so embeds are decoded with a two-pass `inSampleSize` downsample to ~600px. A native-resolution photo decoded to ~48MB and killed updates after a few resizes.
- **Embeds:** `.fillMaxWidth().wrapContentHeight()` + `ContentScale.Fit`. Glance only turns on `adjustViewBounds` when a dimension is Wrap (from its `ImageTranslator.kt`). Fixed heights letterboxed or cropped. No rounded corners (looked bad on real widgets).
- **Why a second renderer (`GlanceMarkdown.kt`):** widgets render as `RemoteViews` in the launcher's process, so no regular Compose UI works there — a platform limit, not a Glance one. Real tappable checkboxes/± were chosen over a pixel-perfect bitmap. Inline styles become a Row of Texts (`parseInlineSegmentsForGlance`). Frosted glass / blur isn't possible on any current API, and see-through backgrounds were dropped as unwanted.
- **Debugging lesson:** the in-app view once looked like ground truth while being stale (loaded once, never re-read on resume). That's why the app now reloads on `ON_START`. When the widget and app disagree, check the file on disk first.

## Local dev environment (this machine)

The Android SDK is split across two roots:

- `/opt/android-sdk` — `$ANDROID_HOME` for building and `adb` (cmdline-tools, platform-tools). No emulator image.
- `/home/ayaya/Android/Sdk-avd` — the `emulator` binary + `system-images/android-35/google_apis/x86_64`. To launch by hand:
  ```
  ANDROID_SDK_ROOT=/home/ayaya/Android/Sdk-avd \
  ANDROID_HOME=/home/ayaya/Android/Sdk-avd \
  ANDROID_AVD_HOME=/home/ayaya/.config/.android/avd \
  /home/ayaya/Android/Sdk-avd/emulator/emulator -avd <name>
  ```
  (`ANDROID_AVD_HOME` is required — AVDs live in `~/.config/.android/avd`.) AVDs: `grid9test`, `recoral_test`.

Gradle needs **JDK 21** (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`; the system default JDK 26 fails the build) — the scripts set it. `buildToolsVersion = "37.0.0"` is pinned because that's what's installed (AGP's default 34.0.0 isn't, and there's no `licenses/` dir to auto-accept it). Toolchain: AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, Compose BOM 2025.09.00, Glance 1.1.1.

## Signing & release

`release-keystore.jks` (PKCS12, alias `dailyobsi`) + `keystore.properties` are gitignored — never commit them. `app/build.gradle.kts` wires the release `signingConfig` only if `keystore.properties` exists. PKCS12 reuses the store password for the key, so `storePassword`/`keyPassword` are intentionally identical.

`lint { checkReleaseBuilds = false; abortOnError = false }` plus a few disabled detectors are required. Several AndroidX lint detectors are binary-incompatible with AGP 8.7.3's Kotlin analysis API and crash lint itself, which would fail `assembleRelease`.

`.github/workflows/release.yml` builds a signed APK on a `v*` tag push (or manual run) and attaches it to a GitHub Release, rebuilding the keystore from secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`. Without them it still produces an unsigned APK.

## Testing

**`./test.sh`** runs the JVM unit tests in `app/src/test/java/dev/ayaya/dailyobsi/` (mirroring the main source folders). Feature logic lives in pure functions precisely so it's testable there: note rewrites (`DailyNote.toggleCheckbox`/`shiftIndent`/`moveLine`/`replaceLine(s)`), `parseBlocks`/`headerBodyLineRange`, `parseH2Sections`/`replaceSectionBody`, `ProgressBar.kt`, edit mode's `listEnterFor`/`checkboxOverlaySpecs`/`checkboxOverlayKeys`/`horizontalRuleRanges`/`resolveCursorFollow` (`internal` for tests), `restoredSection`, `bottomBarTabWidth`, `SaveCoordinator`, and the widget's `parseInlineSegmentsForGlance`. **When adding a feature, put its logic in a function like these and add a test.** Not covered (needs Android classes): `AppPreferences`, the ViewModel's load/save flow, SAF I/O, and composables.

Emulator vault: a snapshot copy of `~/obsidian/main` was pushed to `/sdcard/obsidian` on `grid9test` (it goes stale; re-push to refresh). Pick `obsidian/02 - daily` as the folder and `obsidian/00 - todo/daily template.md` as the template. The emulator's template was cut down to 3 sections (`time until`, `meds`, `tasks`) for testing; the original is next to it as `daily template.backup.md`.

## Vault layout (external to this repo)

`~/obsidian/main/` on the dev machine, mirrored to the phone by Termux: `00 - todo/` (daily-note template), `02 - daily/` (`YYYY-MM-DD.md` daily notes).
