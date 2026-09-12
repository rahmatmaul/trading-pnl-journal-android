# Changelog

All notable changes to this project will be documented in this file.

## [3.0.2] - 2026-09-12

### Changed

- Restored the original folded-journal V2.5 artwork selected by the maintainer.
- Kept the versioned launcher component so the restored icon is refreshed
  correctly when V3.0.2 is installed over an older build.

## [3.0.1] - 2026-09-12

### Fixed

- Replaced the launcher icon with a dedicated V3 adaptive icon resource.
- Added Android 13+ monochrome icon support for themed launchers.
- Moved the launcher intent to a versioned `LauncherV3Activity` component so launchers refresh
  cached icons after updating while the package name and journal database stay unchanged.

## [3.0.0] - 2026-09-12

### Added

- Quick Log and Full Story modes in one low-friction capture flow.
- Adaptive Playbook generated from setup performance, sample size, and average PnL.
- Trade Replay timeline combining thesis, execution, result, lesson, and chart evidence.
- Discipline Score based on review habits, context quality, execution scoring, and evidence.
- Weekly Review with a focused performance summary and next-best action.
- Profit factor, expectancy, maximum drawdown, streak, and mistake-cost analytics.
- Five current V3.0 product screenshots and a directly downloadable V3.0 APK.

### Changed

- Rebuilt the Journal dashboard as a trading command center while preserving fast capture.
- Expanded Insights around decision quality rather than outcome alone.
- Refined information hierarchy, depth, cards, and progressive-disclosure motion.
- Updated the in-app identity to Trading Performance OS V3.0.

### Data compatibility

- V3.0 keeps the existing Room schema and backup format, so V1, V2, and V2.5 data remain compatible.
- No destructive database migration or internet permission was introduced.

## [2.5.0] - 2026-09-12

### Added

- Original adaptive launcher icon built around a folded-journal symbol and
  restrained midnight-to-cobalt palette.
- Unified display typography across the complete application interface.
- Actual V2.5 screenshots and a directly downloadable APK in the repository.

### Changed

- Replaced heavy page effects with compositor-friendly directional slide motion.
- Refined button, bottom-sheet, navigation, and toast animation timing.
- Disabled decorative continuous animation that could stutter in Android WebView.
- Refreshed the GitHub presentation to make V2.5 the primary documented release.

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
