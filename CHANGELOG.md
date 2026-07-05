# Changelog

All notable changes to Exchange (Android) are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

User-facing Play Store release notes live in
`fastlane/metadata/android/en-GB/changelogs/<versionCode>.txt`.

## [1.4] — 2026-07-04 (versionCode 24)

### Added
- **Share any file to Exchange to encrypt or decrypt it.** Exchange now shows
  up in the Android share sheet for any file (share `*/*`), and also opens
  `.exc2` files from a file manager. Share something to Exchange and it works
  out what to do: if it's one of ours — a `.exc2` file, an `EXC2:` envelope, or
  an `exchange.nettrash.me/msg` link — it opens **Decrypt** and restores the
  original; anything else opens **Encrypt**, where you pick a recipient and get
  back a sealed `.exc2` to save or share. A shared identity backup / transfer
  is recognised and pointed at Settings → Import instead of being encrypted.
  The decrypted/encrypted result now also has a **Share** button (via a
  FileProvider), not just Save. Same envelope format, so files interoperate
  across Android, iOS and macOS.

## [1.3] — 2026-06-10 (versionCode 22)

### Added
- **Compose remembers your last recipient.** Picking someone in Compose is
  remembered, so the next time you compose they're pre-selected instead of
  the list resetting to the top.
- **Decrypt a shared message link.** Decrypt now accepts a
  `https://exchange.nettrash.me/msg…` link, not just the raw `EXC2:` envelope:
  paste either form (or pull it from the clipboard) and Exchange extracts the
  envelope before decrypting. Tapping such a link, or sharing it to Exchange,
  already opened the app and decrypted it.
- **Choose how you share a sealed message.** The Compose result screen has a
  Link / EXC2 toggle: share the rich `exchange.nettrash.me/msg` link
  (default) or the raw `EXC2:` envelope for plain-text channels. Your choice
  is remembered.
- **App lock.** An optional biometric lock — turn on “Require biometric
  unlock” in Settings and Exchange asks for your fingerprint / face (or your
  device PIN / pattern / password as a fallback) before opening. Two
  settings make it configurable:
  - **Re-lock** — how long the app can be in the background before it locks
    again: Immediately, after 1 / 5 / 15 minutes, or only on launch.
  - **Also lock message links** — extends the lock to opening a shared
    message link, not just the main app.
  It's a convenience lock; your keys stay protected by the Android Keystore
  either way, and if the device has no biometric or screen lock enrolled the
  lock fails open so you can't be shut out of your own identity.
- **Encrypt & decrypt files.** New "Encrypt file" / "Decrypt file" actions
  (the ⋮ menu on the home screen) seal any file — its name and bytes — to a
  recipient as a shareable `.exc2` file, signed by you. The recipient opens
  Decrypt file, picks it, and gets the original back. Same envelope format as
  messages, so it interoperates across Android, iOS and macOS.

### Changed
- The Compose recipient picker now lists recipients in the **same order as
  the home screen** — your manual drag-order first, then newest-first for the
  rest — instead of alphabetically.

### Fixed
- The Compose **Encrypt & sign** button is no longer hidden behind the
  soft keyboard while typing a message.

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
