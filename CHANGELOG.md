# Changelog

All notable changes to Exchange (Android) are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

User-facing Play Store release notes live in
`fastlane/metadata/android/en-GB/changelogs/<versionCode>.txt`.

## [1.2] — 2026-06-05 (versionCode 12)

### Added
- **Rename recipients.** Tap a recipient to edit its display label and notes
  in a dialog. The underlying key identity and fingerprint are immutable and
  stay visible while editing.
- **Reorder recipients.** Long-press and drag recipients into a custom order.
  The manual order is persisted.

### Changed
- The recipient list now sorts by your manual order, falling back to
  newest-first for rows you haven't reordered.
- Renames and reorders are carried in passphrase-encrypted identity backups
  (latest edit wins on restore), keeping them consistent with iOS / macOS.
  The backup format gained two optional fields and remains compatible with
  older builds in both directions.

### Database
- Room schema bumped to v2 with a non-destructive `MIGRATION_1_2` that adds
  `order_index` and `updated_at` columns (existing rows are preserved;
  `updated_at` is seeded from `created_at`).

## [1.0] — (versionCode 3)

### Added
- Initial release: compose signed, encrypted messages as a single base64
  line to paste into any messenger; swap public keys by QR or paste;
  identity held in the Android Keystore with passphrase-encrypted backups.
- No accounts, no servers, no telemetry, no ads.
