# PRD — Trading PnL Journal Android Offline

**Document:** Product Requirements Document  
**Product:** Trading PnL Journal  
**Target platform:** Android  
**Primary distribution:** Installable APK  
**Architecture:** Offline-only, local-first, no server  
**Priority:** Data persistence and reliability first; preserve current UI/behavior as much as possible  
**Source UI:** Existing single-file HTML/CSS/JavaScript journal supplied with this PRD

---

## 1. Product Summary

Build the existing Trading PnL Journal into a real Android application that can be installed as an APK and used completely offline.

The current journal is a single-file HTML app whose persistent data layer depends on browser `localStorage`. In environments where browser storage is unavailable, the app shows a "Storage notice" and trade data cannot be trusted to survive after the page is closed.

The Android version must remove that weakness.

The Android app must:

- be installable as a normal APK;
- work without internet;
- require no server, VPS, backend, account, or cloud database;
- store journal data permanently in a local SQLite database on the phone;
- keep saved trades after the app is closed, force-stopped, the phone is rebooted, or the APK is updated;
- provide local backup/export so the user can recover data after uninstalling or changing phones;
- preserve the current journal UI and behavior unless a change is specifically required by this PRD.

The product is for one person's personal trading journal. Multi-user features are not required.

---

## 2. Problem Statement

### Current problem

The existing web version uses browser `localStorage` for:

- trades;
- settings.

The current application tests browser storage at startup. If storage is unavailable, the application still runs, but persistent data is not safe.

This is unacceptable for a trading journal because the historical journal is the product's most important asset.

### Required solution

Use a real Android local database as the source of truth.

Target persistence model:

```text
Android APK
    |
    |-- Bundled journal UI
    |     HTML / CSS / JavaScript
    |
    |-- Native Android persistence layer
    |     Kotlin
    |       |
    |       +-- Room
    |             |
    |             +-- SQLite database
    |
    +-- Local backup/export
          JSON / CSV
          optional user-selected Documents folder
```

No remote component may be required for normal operation.

---

## 3. Product Goals

### G1 — Real persistent local storage

A saved trade must remain available after:

1. leaving the current screen;
2. closing the app;
3. force-stopping the app;
4. reopening the app;
5. rebooting Android;
6. installing a newer APK version over the existing app.

### G2 — Zero server dependency

The app must work with:

- Wi-Fi off;
- mobile data off;
- airplane mode on.

Core features must not make network requests.

### G3 — Preserve the existing journal

Do not redesign or rewrite the product unnecessarily.

The existing HTML/CSS/JS implementation is the visual and behavioral baseline. Keep the current:

- Dashboard;
- Calendar;
- daily detail;
- Trades list;
- Statistics;
- Settings;
- bottom navigation;
- floating Add Trade button;
- dark/light/system theme;
- dialogs, sheets, filters, cards, and charts.

Only change UI where required to support the Android persistence/backup behavior or to fix a genuine mobile issue.

### G4 — Recoverable data

The internal SQLite database protects normal use, but Android removes app-private data when an app is uninstalled.

Therefore the app must also support a portable backup that can be restored on the same phone or another phone.

### G5 — Minimal invasive migration

Do not rebuild the entire journal as Jetpack Compose in v1.

Preferred implementation is:

- Android native shell in Kotlin;
- existing local HTML/CSS/JavaScript loaded inside an Android WebView from packaged app assets;
- Room/SQLite as the durable data store;
- a small JS ↔ native bridge for persistence and Android file operations.

This preserves the mature existing UI while replacing the unsafe browser storage layer.

---

## 4. Non-Goals for v1

The following are explicitly out of scope unless needed for the requirements above:

- cloud synchronization;
- Firebase;
- Supabase;
- remote MySQL/PostgreSQL;
- web hosting;
- VPS;
- login/account system;
- social/community features;
- multi-user support;
- browser/PWA version;
- MT4/MT5 broker connection;
- automatic broker trade import;
- webhook;
- notifications;
- screenshot/media journal attachments;
- biometric/PIN lock;
- AI analysis;
- subscription/payment system.

Do not add these features just because they may be useful later.

---

## 5. Existing Functional Scope That Must Be Preserved

The supplied HTML implementation is the source of truth for existing behavior.

### 5.1 Main navigation

Keep five primary destinations:

1. Dashboard
2. Calendar
3. Trades
4. Stats
5. Settings

Keep the floating Add Trade action everywhere it currently makes sense.

---

## 6. Trade Data Model

The current v1 trade record contains:

```json
{
  "id": "string",
  "date": "YYYY-MM-DD",
  "timestamp": 0,
  "result": "win | loss",
  "pnl": 0.0,
  "symbol": "string",
  "notes": "string",
  "entryTime": "string"
}
```

### Required semantics

#### id

- Unique string identifier.
- Must remain stable when editing.
- Duplicating a trade must create a new ID.

#### date

- Required.
- Local calendar date.
- Format `YYYY-MM-DD`.
- Must be a valid date.

#### timestamp

- Used to preserve ordering.
- Must be stored durably.
- If importing legacy data with an invalid/missing timestamp, derive a reasonable timestamp from the date as the existing app does.

#### result

Required enum:

```text
win
loss
```

#### pnl

- Required numeric amount in USD.
- The result is the source of truth for the sign.
- `win` => positive absolute PnL.
- `loss` => negative absolute PnL.

#### symbol

- Optional.
- Maximum 40 characters.
- Existing symbols should continue to be offered as suggestions in the entry form.

#### notes

- Optional.
- Maximum 1000 characters.

#### entryTime

- Optional.
- Maximum 20 characters.
- Preserve the current flexible behavior: it may be `HH:MM` or a short text note.

---

## 7. Settings Data Model

Persist these existing settings in durable Android storage:

```json
{
  "theme": "system | light | dark",
  "startingBalance": null,
  "profitTarget": null,
  "maxDrawdown": null,
  "dailyDrawdown": null
}
```

Numeric account settings:

- are optional;
- must be `>= 0` when present.

All of these settings must survive app restarts.

---

## 8. Database Requirements

### 8.1 Source of truth

Room/SQLite is the only source of truth for durable trade and application data.

Do not use browser `localStorage` as the database.

`localStorage` may be omitted entirely. If retained for any reason, it may only be used for disposable/non-critical UI state and must never be required to reconstruct trades or persistent settings.

### 8.2 Suggested Room entities

Implementation may use equivalent naming, but the conceptual tables should be:

#### `trades`

Suggested columns:

```text
id TEXT PRIMARY KEY NOT NULL
date TEXT NOT NULL
timestamp INTEGER NOT NULL
result TEXT NOT NULL
pnl REAL NOT NULL
symbol TEXT NOT NULL DEFAULT ''
notes TEXT NOT NULL DEFAULT ''
entry_time TEXT NOT NULL DEFAULT ''
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
```

Indexes recommended:

```text
date
timestamp
symbol
result
```

#### `settings`

A simple key/value table or a one-row typed entity is acceptable.

The implementation should prioritize:

- simple migrations;
- type safety;
- reliable backups;
- easy future expansion.

### 8.3 Transactions

Use Room transactions where multiple database changes belong to one user action.

Never clear the existing journal before an imported replacement backup has been fully parsed and validated.

### 8.4 Schema versioning

The local database must have an explicit schema version.

Do not enable destructive fallback migrations that silently wipe journal data.

Production migration rule:

```text
database upgrade != delete old database
```

If a migration cannot safely complete, show a clear error and preserve the old database.

---

## 9. Android/WebView Architecture

### 9.1 Android shell

Use Kotlin.

The native Android layer owns:

- Room database initialization;
- DAO/repository operations;
- backup file creation;
- backup restore file selection;
- CSV/JSON file export;
- Storage Access Framework integration;
- WebView configuration;
- JS/native communication.

### 9.2 WebView

Load the existing journal from local packaged assets, for example:

```text
app/src/main/assets/index.html
```

Do not load the journal UI from:

- Z.ai;
- localhost server;
- remote URL;
- CDN.

The APK must contain everything required for the UI.

### 9.3 No network dependency

The journal must render and operate from APK assets only.

Do not add third-party web fonts, remote scripts, analytics, or external CSS dependencies.

Prefer no `INTERNET` permission in the Android manifest.

If the project can build and function without that permission, it must be absent.

### 9.4 JavaScript/native bridge

Create a clean persistence adapter so UI code does not directly depend on Android implementation details.

Conceptual API:

```text
JournalStorage.initialize()
JournalStorage.getTrades()
JournalStorage.getSettings()

JournalStorage.addTrade(trade)
JournalStorage.updateTrade(trade)
JournalStorage.deleteTrade(id)
JournalStorage.duplicateTrade(id)

JournalStorage.saveSettings(settings)

JournalStorage.importBackup(...)
JournalStorage.exportBackup(...)
JournalStorage.exportCsv(...)

JournalStorage.selectBackupFolder()
JournalStorage.backupNow()
```

The exact bridge implementation may use:

- WebView `JavascriptInterface`;
- WebMessage/WebViewCompat messaging;
- another safe Android-local bridge.

Prefer an asynchronous JS-facing API where practical.

The key architectural requirement is:

```text
UI -> storage adapter -> Android native -> Room/SQLite
```

and NOT:

```text
UI -> localStorage
```

---

## 10. Application Startup

Expected startup sequence:

```text
Launch APK
   |
   +-- initialize Room database
   |
   +-- load settings
   |
   +-- load trades
   |
   +-- provide initial state to JavaScript UI
   |
   +-- apply theme
   |
   +-- render current view
```

### Startup must NOT show

The existing browser message:

> Storage notice — Browser storage is unavailable...

That notice is specific to the old browser/localStorage architecture and must be removed from the Android build.

### Real database failure

If Room/database initialization genuinely fails:

- do not pretend saving is available;
- do not silently start a fresh database if that could hide existing data;
- show a clear recovery-oriented error;
- preserve any existing database/backup.

---

## 11. Add/Edit/Delete/Duplicate Trade

### Add Trade

Keep the current bottom-sheet style form and current fields:

- Date
- Result: Win / Loss
- PnL amount (USD)
- Symbol (optional)
- Entry time (optional)
- Notes (optional)

On Save:

1. validate;
2. write to Room;
3. wait for confirmed successful write;
4. update UI;
5. show success feedback if appropriate.

Do not update only the in-memory JS array and assume it was saved.

### Edit Trade

Edit the existing row by ID and persist it in Room.

### Delete Trade

Require confirmation.

After confirmation:

- delete in Room;
- update UI only after successful deletion.

### Duplicate Trade

Create a complete copy except:

- generate a new ID;
- generate an appropriate new creation timestamp.

Persist the duplicated record immediately.

---

## 12. Dashboard Requirements

Preserve existing Dashboard behavior.

### Period selectors

- All Time
- This Week
- This Month
- Custom

Custom range must validate:

```text
from <= to
```

### Primary statistics

Preserve:

- Total PnL
- Winrate
- Wins / losses
- Avg PnL / Trade
- Best Trade
- Worst Trade
- Cumulative PnL chart
- Recent trades

### Account card

When `startingBalance` is configured:

```text
current balance = starting balance + all-time total PnL
```

Preserve:

- Starting balance
- Current balance
- Profit target
- Target progress

### Risk warnings

Preserve the current limit warnings:

- daily loss limit;
- maximum drawdown floor.

These are journal warnings only. They do not connect to a broker and do not block trading.

---

## 13. Calendar Requirements

Calendar remains a primary journal workflow.

### Monthly calendar

Each day should continue to show:

- day number;
- trade count or `No trades`;
- aggregate daily PnL when trades exist;
- positive/negative visual state;
- today's visual highlight.

Support:

- previous month;
- next month;
- today.

### Day detail

Tapping a date opens the daily detail.

Preserve:

- date/day heading;
- previous day / next day navigation;
- number of trades;
- winrate;
- daily PnL;
- average PnL/trade;
- daily loss warning if configured;
- Add Trade for the selected date;
- list of trades on that date.

---

## 14. Trades List Requirements

Preserve filters:

### Period

- All
- This Week
- This Month
- Custom

### Result

- All
- Wins
- Losses

### PnL

- All
- Positive
- Negative

### Symbol

Filter using symbols present in the journal.

### Sort

- Newest
- Oldest

### Summary

For the currently filtered trades preserve:

- number of trades;
- winrate;
- total PnL.

### Large journals

The current UI progressively reveals more rows rather than rendering everything at once.

Preserve equivalent behavior so a large journal remains responsive.

Target at least:

```text
10,000 trades without data loss
```

The implementation should remain usable at that scale.

---

## 15. Statistics Requirements

Preserve the existing cumulative PnL statistics view.

Required:

- all-time cumulative PnL;
- all-time total trades;
- all-time winrate;
- cumulative PnL chart;
- period aggregation.

Period granularity:

- Daily
- Weekly
- Monthly

Preserve the intent of current scopes such as:

- recent active days;
- recent weeks;
- recent months;
- expandable history where currently supported.

Statistics must be derived from persisted trades, not from manually maintained totals.

---

## 16. Theme

Support:

```text
Light
Dark
System
```

Theme selection must persist across app restarts.

`System` follows Android's light/dark preference.

The app should apply the stored theme as early as practical to avoid a bright/dark flash during startup.

---

## 17. Backup & Restore

This is a critical v1 requirement.

There are two different forms of persistence:

### Layer A — Internal Room database

Purpose:

- normal daily use;
- fast reliable reads/writes;
- survives app close/restart/device reboot/app update.

### Layer B — Portable local backup

Purpose:

- uninstall protection;
- phone replacement;
- manual archival;
- disaster recovery.

### 17.1 Full JSON backup

Keep compatibility with the existing conceptual backup structure:

```json
{
  "app": "trading-pnl-journal",
  "version": 1,
  "exportedAt": "ISO timestamp",
  "settings": {},
  "trades": []
}
```

For the Android version, it is acceptable and recommended to add explicit metadata such as:

```json
{
  "schemaVersion": 1,
  "appVersion": "..."
}
```

Do not break import of the current version-1 backup format.

### 17.2 CSV export

Preserve CSV export for spreadsheet use.

At minimum export:

```text
id
date
entryTime
result
pnl
symbol
notes
```

### 17.3 JSON import

Preserve two import modes:

```text
Merge
Replace
```

#### Merge

- Keep existing records.
- Add imported records not already present.
- Skip duplicate IDs.
- Report added/skipped counts.

#### Replace

- Validate the entire backup before destructive action.
- Ask for explicit confirmation.
- Replace trades only after validation succeeds.
- Restore imported settings when present.

### 17.4 Legacy compatibility

The user must be able to export a JSON backup from the current HTML journal and import that backup into the Android APK.

This is the migration route from the old journal.

Do not require direct access to the Z.ai browser storage.

### 17.5 User-selected backup folder

Add an optional feature in Settings:

```text
Backup folder
[ Choose folder ]
```

Use Android Storage Access Framework.

Recommended destination example:

```text
Documents/TradingJournal/
```

Do not hardcode a filesystem path that requires broad storage permission.

After the user grants folder access, the app should be able to write backup files there.

### 17.6 Automatic local backup

Once a backup folder has been selected, automatically create/update a recovery backup after persistent journal changes.

Do not write excessively on every keypress.

Trigger automatic backup after completed persistent actions such as:

- add trade;
- edit trade;
- delete trade;
- duplicate trade;
- settings change;
- successful import.

Recommended strategy:

```text
successful DB commit
     ->
debounced/queued backup
     ->
atomic JSON file write
```

Maintain at least:

```text
latest backup
```

Optionally maintain a small rotating history, e.g. the latest 5 dated backups, if implementation remains simple.

### 17.7 Uninstall behavior

The app must clearly communicate:

- internal database data is removed by Android if the app is uninstalled;
- portable backup files saved to the user-selected Documents folder remain available;
- after reinstall, the user can select/import that backup.

Never claim internal app data survives uninstall.

---

## 18. Settings Screen Changes

Preserve current sections:

### Theme

- Light
- Dark
- System

### Account

- Starting balance
- Profit target
- Max drawdown limit
- Daily loss limit

### Data

Replace browser-specific wording with Android-specific wording.

Show something like:

```text
Local database
123 trades stored on this device
```

Actions:

```text
Export full backup (JSON)
Export trades (CSV)
Import backup (JSON)
Choose backup folder
Backup now
```

If a backup folder is selected, show a friendly folder status/name.

### Danger zone

Preserve:

```text
Clear all data
```

Require a deliberate confirmation.

Clearing app data must not silently delete external backup files.

### About

Use truthful copy such as:

```text
Trading PnL Journal
Offline Android app.
No account, no tracking, no required network connection.
Journal data is stored locally on this device.
```

Do not say "saved in this browser."

---

## 19. Removal of Browser-Only Storage Logic

The Android build must remove or bypass all code paths that assume browser `localStorage` is the persistent database.

Examples to replace:

```text
LS_TRADES
LS_SETTINGS
Store.read(...)
Store.write(...)
localStorage.setItem(...)
localStorage.getItem(...)
browser storage probe
browser storage unavailable notice
```

Do not fix the problem by only hiding the popup.

The persistence implementation itself must change.

---

## 20. Data Integrity Rules

### Validation

On user entry and import:

- required date must be valid;
- result must be `win` or `loss`;
- PnL must be finite;
- sign must be normalized from result;
- malformed imported entries must never crash the app.

### Save guarantees

A successful "Saved" state means the Room transaction succeeded.

If persistence fails:

- keep the form open where reasonable;
- show an error;
- do not falsely show a successful save.

### Import safety

Never replace good current data with an invalid backup.

### Clear safety

Clear All must require explicit confirmation.

---

## 21. Privacy & Security Requirements

This is a private local journal.

Requirements:

- no analytics SDK;
- no telemetry;
- no advertisements;
- no trackers;
- no account;
- no cloud;
- no automatic network transmission;
- no remote crash-reporting service in v1;
- no unnecessary Android permissions.

The project should be auditable: a reviewer should be able to inspect the manifest and see that normal journal use does not require internet access.

---

## 22. Performance Requirements

Target normal device behavior:

- startup should feel immediate for a typical journal;
- adding/editing a trade should persist promptly;
- calendar navigation should remain smooth;
- chart rendering should not freeze normal UI;
- 10,000 saved trades must not cause data loss or an unusable application.

Do not perform Room database work on the Android main/UI thread.

Use coroutines/background I/O as appropriate.

---

## 23. Compatibility

Target:

- Android phones first;
- portrait usage first;
- responsive enough for common phone widths;
- current desktop-oriented responsive HTML may remain functional in larger WebView sizes.

Recommended:

- choose a reasonable `minSdk` supported by the available build environment;
- prefer modern Android APIs;
- avoid obsolete external-storage permission patterns.

Do not require Google Play Services for core functionality.

---

## 24. Build & Deliverables

Codex must produce a normal Android project.

Expected output structure may resemble:

```text
/
|-- PRD.md
|-- app/
|   |-- src/main/
|   |   |-- java/.../
|   |   |-- assets/index.html
|   |   |-- AndroidManifest.xml
|   |-- build.gradle(.kts)
|-- build.gradle(.kts)
|-- settings.gradle(.kts)
|-- README.md
```

Required deliverables:

1. Complete Android source code.
2. Existing journal UI bundled locally.
3. Room entities/DAO/database/repository.
4. JS/native storage adapter.
5. JSON backup import/export.
6. CSV export.
7. Storage Access Framework folder support.
8. Tests for critical persistence and import validation.
9. Build instructions.
10. A successfully built debug APK if the environment supports Android builds.

Do not leave the project as pseudocode.

---

## 25. Testing Requirements

At minimum include automated tests where practical for:

### Database

- insert trade;
- update trade;
- delete trade;
- duplicate trade;
- load trades after database reopen;
- settings persistence.

### Validation

- valid trade import;
- invalid date;
- invalid result;
- invalid PnL;
- sign normalization;
- duplicate IDs during merge.

### Backup

- export JSON;
- import exported JSON;
- merge;
- replace;
- settings restore;
- corrupted/malformed JSON must fail safely.

---

## 26. Manual Acceptance Test Checklist

The implementation is not complete until these tests pass.

### Persistence

- [ ] Install APK.
- [ ] Add at least three trades.
- [ ] Close app from recent apps.
- [ ] Reopen app.
- [ ] All trades remain.
- [ ] Force-stop app from Android settings.
- [ ] Reopen app.
- [ ] All trades remain.
- [ ] Reboot phone/emulator.
- [ ] Reopen app.
- [ ] All trades remain.
- [ ] Install a newer APK over the same app.
- [ ] All trades and settings remain.

### Offline

- [ ] Turn on airplane mode.
- [ ] Open app.
- [ ] Add/edit/delete trades.
- [ ] Open Dashboard/Calendar/Trades/Stats/Settings.
- [ ] All features work.

### Storage notice

- [ ] The browser `Storage notice` popup never appears in the Android version.

### Backup

- [ ] Choose a backup folder.
- [ ] Create trades.
- [ ] Backup exists in that folder.
- [ ] Export full JSON manually.
- [ ] Clear app data / use a fresh install.
- [ ] Import backup.
- [ ] Trades and settings are restored correctly.

### Existing behavior

- [ ] Dashboard statistics match the same trade set in the old HTML logic.
- [ ] Calendar daily counts and PnL are correct.
- [ ] Filters work.
- [ ] Win/loss signs are correct.
- [ ] Theme persists.
- [ ] Duplicate creates a new independent record.
- [ ] Clear All requires confirmation.

---

## 27. Definition of Done

The product is done when:

1. It is a real installable Android app.
2. The UI is packaged in the APK and does not depend on the original web link.
3. Room/SQLite, not browser `localStorage`, is the source of truth.
4. Saved data survives app close, force-stop, reboot, and APK update.
5. JSON backup can recover data after reinstall or transfer to another phone.
6. The existing Dashboard, Calendar, Trades, Stats, Settings, Add/Edit/Delete/Duplicate flow, account calculations, filters, themes, charts, and import/export continue to work.
7. No browser-storage warning popup remains.
8. Normal operation works fully in airplane mode.
9. No server or recurring hosting cost is required.
10. There is no silent destructive database migration.
11. Build/test instructions are documented.
12. The project builds successfully.

---

## 28. Implementation Priorities

When trade-offs are required, use this priority order:

```text
1. Never lose journal data
2. Correct persistence
3. Backup/restore reliability
4. Preserve current functionality
5. Preserve current UI/UX
6. Performance
7. Code elegance
8. Optional polish
```

Do not sacrifice data reliability for architectural cleverness.

---

## 29. Source of Truth and Change Discipline

Codex should treat the supplied existing HTML file as the behavior reference.

Before editing:

1. inspect the full existing HTML;
2. identify the storage layer;
3. identify every `localStorage` dependency;
4. identify every place that calls save/load/import/export;
5. identify all current views and event handlers.

During implementation:

- make the smallest practical behavioral changes;
- do not remove existing features without documenting why;
- do not rewrite working statistics/UI merely for style preferences;
- do not invent unrelated features;
- keep imported legacy JSON compatibility.

If a conflict exists, priority is:

```text
This PRD
   >
existing user-visible behavior
   >
internal implementation details of the old HTML
```

---

## 30. Final Technical Intent

The expected end state is:

```text
User taps Trading PnL Journal icon
        |
        v
Installed Android APK
        |
        +-- local HTML/CSS/JS UI
        |
        +-- Kotlin native bridge
                |
                v
             Room
                |
                v
             SQLite
                |
                +---- survives close / reboot / APK update
                |
                +---- JSON/CSV backup -> user-selected local folder
```

There must be no server in this path.

The journal should feel like the current journal, but behave like a trustworthy native offline application.
