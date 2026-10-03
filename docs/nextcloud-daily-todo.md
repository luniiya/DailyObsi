# Nextcloud Daily Todo integration

Notes for adding the "Daily Todo" tab. Written 2026-10-03, from the user's
description of the new workflow and a read-only look at the plugin on `banana`.

## The workflow change (from the user)

- An AI agent now manages the user's schedule. It can't work through Obsidian,
  so **the task list moved out of Obsidian into Nextcloud**.
- **Only the task list moved.** Everything else in the daily note (journal,
  `report`, `meds`, progress bars, ...) stays in Obsidian, git-pulled by Termux
  as before. The Obsidian file is still the source of truth for everything
  except tasks.
- Nextcloud runs on the user's server `banana` (`ssh banana`, key in
  `~/.ssh/banana`) at **https://nc.luniiya.me**. The agent reads and writes
  tasks there, through Calendar/Tasks (CalDAV) and the custom app below.
- The user built a Nextcloud app, **Daily Todo** (`daily_todo`), with an agent.
  It turns a template, calendar events and tasks into one checklist per day.
- DailyObsi needs a **special tab that shows and edits that day list**, next to
  the normal `##`-section tabs.

## Where the plugin lives

`banana:/mnt/1/services/nextcloud/` (Docker Compose stack, Nextcloud 34 + Postgres
+ Redis, behind Nginx Proxy Manager). **Read-only for us**: we don't edit the
plugin or the stack from this repo.

- `apps/daily_todo/`: separate git repo, bind-mounted into the container.
  `appinfo/routes.php` lists the routes, `lib/Controller/ApiController.php`
  holds the controllers, `lib/Service/BoardService.php` the logic, and
  `src/api.ts` the reference web client.
- `AGENTS.md` / `CLAUDE.md` next to it describe the item model and its rules.
- `vision-taily-todo-plugin.md`: the conversation the design came out of.

## The plugin's model (summary of its AGENTS.md)

A **board** (one per user) has **days**. A day is a flat, ordered list of
**items**, nested through `parent_id`:

| `source_type` | Origin | Rename | Delete | Tick |
|---|---|---|---|---|
| `routine` | generated daily from the board's Template | yes (that day only) | no | yes |
| `event` | Calendar event that day (read-only mirror) | no | no | yes |
| `task` | Tasks VTODO, placed by start date (else due date) | yes (rewrites SUMMARY in Tasks) | no | yes (written back to CalDAV) |
| `quick` | added by hand | yes | yes | yes |

- Items titled `---`/`___` are **divider lines** and `<br>` items are **blank
  gaps**: layout only, not tickable or renamable (`separatorKind` in `api.ts`).
- **Day lifecycle:** a day only exists once it is "today". Future days, and past
  days that were never created, come back empty and read-only. Never try to
  create or preview them.
- **Logical today** comes from the board's timezone + `rolloverHour`, so it can
  differ from the phone's date after midnight. Use the `logicalToday` the server
  returns rather than `LocalDate.now()`.
- **Editable window:** today and the 2 days before it (`editable` in the
  response). Older days are read-only history.
- Rollover of unfinished items is done by the server; the client does nothing.

## HTTP API

Base: `https://nc.luniiya.me/index.php/apps/daily_todo`. Errors come back as
HTTP 400 `{ "error": "message" }`: show that message.

| Call | Route | Body |
|---|---|---|
| day | `GET /api/day/{YYYY-MM-DD}` | |
| month summary | `GET /api/summary/{YYYY-MM}` | |
| tick | `PUT /api/items/{id}/complete` | `{ "completed": true }` |
| reorder day | `PUT /api/day/{date}/order` | `{ "ids": [..all ids in order..] }` |
| add | `POST /api/day/{date}/quick` | `{ "title", "parentId"?, "afterId"? }` → `{ "id" }` |
| rename | `PUT /api/items/{id}` | `{ "title" }` |
| delete (quick only) | `DELETE /api/items/{id}` | |
| shared lists | `GET /api/shared` | (day calls then take `?owner=`) |

Day response:

```json
{ "day": "2026-10-03", "logicalToday": "2026-10-03", "editable": true,
  "future": false, "owner": "...", "isOwner": true,
  "items": [ { "id": 1, "source_type": "routine", "title": "...",
    "starts_at": null, "due_at": null, "all_day": false, "completed": false,
    "href": null, "error": null, "parent_id": null, "position": 0, ... } ] }
```

`items` is in display order (by `position`, parents before children).

## Auth: how a native client calls these routes

These are plain AppFramework routes, not OCS. Checked against Nextcloud 34's
`lib/private/AppFramework/Http/Request.php`:

- Authenticate with **HTTP Basic, `user:appPassword`** (an app password, never
  the real password).
- **Send `OCS-APIRequest: true` on every request.** Without a CSRF
  `requesttoken`, `passesCSRFCheck()` only passes when that header is present,
  so PUT/POST/DELETE would fail with "CSRF check failed".
- **Send no cookies.** The strict-cookie check is skipped when there's no
  session cookie. `HttpURLConnection` without a `CookieHandler`, or OkHttp with
  its default no-op cookie jar, is fine.
- Getting the app password: Nextcloud **Login Flow v2**
  (`POST /index.php/login/v2` → open `login` URL in the browser → poll
  `poll.endpoint` with `token` → get `server`, `loginName`, `appPassword`).
  This is the standard way native apps log in. The fallback is pasting an app
  password made in Nextcloud → Settings → Security.

The app currently has no network access at all. This needs the `INTERNET`
permission. All calls go on `Dispatchers.IO`, same rule as SAF.

## Decisions (user, 2026-10-03)

- **Placement: an extra tab, always last**, after all the `##` section tabs. The
  note's own `## tasks` section stays an ordinary markdown tab.
- **Login: Nextcloud Android Single Sign-On** (reuse the account already logged
  in to the official Nextcloud Files app, picked through Android's account
  picker; requests are proxied through the Files app). Login Flow v2 is the
  fallback only if SSO turns out impossible.
- **Scope: full parity with the web Today view.** Tick, add (incl. subtasks),
  rename, delete quick items, move up/down.
- **Past days:** the plugin already serves past days. When a past note is open,
  the tab shows that date's list, editable or read-only as the server says
  (`editable`).
- **Offline fallback is required, "so nothing breaks"**: no network or an
  unreachable server must never crash the tab or leave it blank. It shows the
  last list fetched for that date, clearly marked offline.

## How it's built (2026-10-03)

- **SSO library:** `com.github.nextcloud:Android-SingleSignOn:1.3.2` from
  JitPack. Pinned: 1.3.3+ is built with Kotlin 2.2 and needs a newer
  toolchain than ours (Kotlin 2.0.21 / AGP 8.7.3). It requires **core library
  desugaring** (`isCoreLibraryDesugaringEnabled` + `desugar_jdk_libs`), or
  `checkDebugAarMetadata` fails.
- **The Nextcloud Files app must be installed and logged in** on the device
  (`com.nextcloud.client`). Requests go through it over AIDL. It adds the auth
  and the `OCS-APIRequest` header itself, and **throws if we set
  `OCS-APIRequest`**, so don't. String bodies are sent as `application/json`.
  On non-2xx it passes the response body as the exception cause's message
  (`NextcloudHttpRequestFailedException.cause.message`), which is how the
  plugin's `{ "error" }` reaches the UI.
- **Sign-in** (`todo/NextcloudSignIn.kt`): `AccountImporter.pickNewAccount` →
  `onActivityResult` in `MainActivity` → `requestAuthToken` → second
  `onActivityResult` → `extractSingleSignOnAccountFromResponse`. We don't call
  `AccountImporter.onActivityResult`: its error paths open AppCompat dialogs,
  which crash under our `Theme.DeviceDefault` theme. Only the account name is
  stored (`dailyobsi_nextcloud` prefs). The library keeps the token.
- **Tab:** `TODO_TAB_ID` (`SectionId("nextcloud daily todo", -1)`; headings
  never have occurrence −1), appended last by `tabIds()` when connected and
  the note has sections. Tabbed layout only. No Read/Write FAB on it; swiping
  away from it is allowed. Its last-open state is remembered like any tab.
- **Data flow** (`ui/TodoViewModel.kt`): follows the note's date (`show()`),
  refreshes on `ON_START` and on pull-to-refresh. Writes are optimistic, go
  out one at a time (mutex), and the list is re-read once no writes are
  pending, so the server's order wins. A load that started before a write is
  dropped.
- **Offline:** every successful fetch is cached as raw JSON
  (`filesDir/todo-cache/<date>.json`, last 31 dates). If the server can't be
  reached, the cached list shows with an "Offline · list from HH:MM" banner
  and is read-only until a refresh succeeds. Errors the server returned
  (HTTP 400 with a message) are shown and keep the live list.
- **Not done yet:** widgets for the todo list, hide-completed toggle, shared
  lists (`?owner=`), classic layout.

## Live sync between phone and web (2026-10-03)

The user wants changes to show up on both ends without a manual refresh.

- **Phone (done):** while the todo tab is the visible page and the app is
  started, `TodoViewModel.poll()` re-reads the list every 10s
  (`POLL_INTERVAL_MS`). It skips a beat while writes are pending or a load is
  running, and it doubles as the automatic retry when offline. Unchanged data
  doesn't recompose (equal `TodoDay`).
- **Web (plugin side, not done here, plugin is read-only from this repo):**
  hand this to the plugin's agent:

  > In `src/components/DayView.vue`, keep the open day in sync with edits made
  > elsewhere (the DailyObsi Android app, the scheduling agent):
  > - Every 10 s while `document.visibilityState === 'visible'`, call
  >   `load(true)` (silent), but skip that tick while a rename or draft line
  >   is open (`renamingId !== null || draft !== null`), so typing is never
  >   clobbered.
  > - Also call `load(true)` on `visibilitychange` → visible and on window
  >   `focus`, so switching back to the tab is instant.
  > - Clear the interval and listeners in `onBeforeUnmount`.
  > - Also refresh the sidebar calendar's month summary on the same tick.
  >
  > Real push (Nextcloud `notify_push`) would avoid polling, but needs a
  > separate push server; polling one small JSON route is fine for one user.

## Subtasks are independent by default (2026-10-03)

The user asked for ticking a parent to leave its subtasks alone. The plugin
now has a board setting, **`completeSubtasks`** (Settings in the web UI, off by
default). When it's on, ticking a parent ticks its subtasks; unticking never
cascades. `GET /api/day` returns it, and it's part of the day's `version`, so
flipping it reaches the phone by itself. The app's optimistic tick
(`withCompleted(..., completeSubtasks)`) follows it.

## Moving rows: long-press drag (2026-10-03)

The web UI got a "Move" bubble (▲ Done ▼) in the plugin's ac7cbd2. On Android
the natural gesture is **long-press a row, drag, release** (`TodoTab`):

- Same rules as Move up/down: a parent carries its subtasks, a subtask only
  moves among its siblings. While dragging, the block swaps with a sibling
  block once dragged over half of it (`todoNeighbor`, `todoBlock`,
  `reorderedIds`), the list auto-scrolls near the edges, and polling pauses
  (`TodoViewModel.setDragging`).
- Nothing is saved until release; then **one** `PUT /api/day/{date}/order`
  with the final order (`TodoViewModel.reorder`). No server change was needed.
  Re-parenting (dragging a task into/out of another as a subtask) would need
  one: the order route only sets positions, never `parent_id`.
- Move up / Move down stay in the ⋯ menu as the fallback.
- Separator rows (`---`, `<br>`) span the full width; a quick one's ⋯ sits on
  top at the end instead of reserving a column.
- The "Add a task" field is a soft rounded pill (no outline) that gets an accent
  border when focused, plus a round "add" button once there's text.
