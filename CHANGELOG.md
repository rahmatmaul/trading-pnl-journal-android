# Changelog

All notable changes to this project will be documented in this file.

## [2.5.0] - 2026-09-12

### Added

- Original adaptive launcher icon built around a folded-journal symbol and
  restrained midnight-to-cobalt palette.
- Locally bundled SF Pro Display typography for private builds.

### Changed

- Replaced heavy page effects with compositor-friendly directional slide motion.
- Refined button, bottom-sheet, navigation, and toast animation timing.
- Disabled decorative continuous animation that could stutter in Android WebView.

## [2.0.0] - 2026-09-11

### Added

- Apple-inspired glass interface with a faster Quick Log flow.
- Trade Story fields: setup, session, emotion, R multiples, execution score,
  mistake tags, lessons, and review status.
- Before/after chart screenshot attachments stored privately on-device.
- Setup/session edge analysis, average R, consistency, and review progress.
- Portable `.tpjbackup` packages containing JSON data and verified images.
- Package Merge/Replace import, attachment duplication, and cascade cleanup.
- Explicit non-destructive Room migration from schema 1 to schema 2.
- Tests for migration, V2 metadata, attachment cascade, and package checksums.

### Changed

- Automatic recovery backups now include chart images in `.tpjbackup` packages.
- CSV exports include the complete V2 review metadata.
- Navigation now prioritizes Journal, Calendar, Quick Log, Insights, and Settings.

### Compatibility

- Existing V1 trades and settings are preserved during in-place APK update.
- Legacy V1 JSON backups remain importable.

## [1.0.0] - 2026-09-11

### Added

- Bundled offline HTML/CSS/JavaScript journal interface.
- Kotlin Android shell with a restricted local-only WebView.
- Room/SQLite persistence for trades and settings.
- Trade CRUD, duplicate action, filters, calendar, statistics, and charts.
- JSON backup/export with legacy version-1 compatibility.
- CSV export and validated Merge/Replace import.
- Storage Access Framework recovery folder and automatic local snapshots.
- Robolectric persistence and backup test suite.
