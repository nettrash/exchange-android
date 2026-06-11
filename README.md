# Exchange

End-to-end encrypted messaging for any messenger. Android 16+ (API 36). Native Kotlin / Jetpack Compose / Android Keystore / Bouncy Castle. No accounts, no servers, no telemetry.

Type a plaintext message in Exchange, pick a recipient whose public key you exchanged out of band (in person via QR, or pasted through a trusted channel), and Exchange returns a single base64 line: the EXC2 envelope. Send that envelope through whatever transport you already use: SMS, Mail, Telegram, WhatsApp, Signal, Slack, anything that carries text. The recipient pastes it into Exchange and gets the original plaintext.

Exchange also supports the URL form `https://exchange.nettrash.me/msg?v=2&p=...` for richer sharing and app links. Internally this maps 1:1 to the same `EXC2:` envelope bytes.

## Status

Active release line. Current changelog entry is v1.3 (2026-06-10).

The repository layout is Android Studio / Gradle standard:

- `app/` contains the Android application module.
- `app/src/main/` contains Kotlin source, resources, manifest, and backup rules.
- `app/src/test/` contains crypto and protocol unit tests.
- `fastlane/metadata/android/` contains Play Store listing text and per-version release notes.

## Cryptographic protocol

Each outgoing message becomes a self-contained envelope framed as `EXC2:<base64>`.

The binary blob inside base64 is laid out as:

```
offset  size  field
0       1     version byte (0x02)
1       8     recipient encryption-key fingerprint (SHA-256 prefix)
9       32    sender Ed25519 signing public key
41      32    ephemeral X25519 public key
73      12    wrap nonce
85      32    wrapped K (ciphertext)
117     16    wrapped K (Poly1305 tag)
133     12    message nonce
145     N     message ciphertext
145+N   16    message Poly1305 tag
161+N   64    Ed25519 signature over bytes [0 .. 161+N-1]
```

Sealing a message:

1. Generate a fresh ephemeral X25519 keypair.
2. ECDH between ephemeral private key and recipient static encryption public key.
3. HKDF-SHA256(shared, salt = ephemeral pub, info = `exchange-v2-keywrap`) -> 32-byte wrapping key.
4. Generate fresh random 32-byte message key `K`.
5. Wrap `K` with ChaCha20-Poly1305 under the wrapping key.
6. Encrypt plaintext with ChaCha20-Poly1305 under `K`.
7. Concatenate all fields (everything except signature).
8. Sign the concatenation with sender Ed25519 private key; append 64-byte signature.
9. Base64-armour and prefix with `EXC2:`.

Opening proceeds in strict order:

1. Reject quickly if recipient fingerprint does not match local identity.
2. Verify Ed25519 signature against sender signing key embedded in envelope.
3. Derive wrapping key via ECDH + HKDF.
4. Unwrap `K`, then decrypt body.

Implementation is in `app/src/main/java/me/nettrash/exchange/crypto/CryptoEnvelope.kt`. The interoperability and tamper/forgery contract tests are in `app/src/test/java/me/nettrash/exchange/crypto/CryptoEnvelopeTest.kt`.

Sender authentication note: signature verification proves the envelope was produced by the embedded signing key. Whether that key belongs to a trusted saved contact is a UI-layer decision at decrypt time.

## File encryption

In addition to text messages, Exchange can encrypt files by framing filename + bytes inside the envelope plaintext:

```
offset  size  field
0       8     magic "EXCFILE1" (ASCII)
8       2     filename length (big-endian UInt16)
10      L     filename (UTF-8)
10+L    ...   file content bytes
```

This framing is internal to the plaintext. The outer EXC2 envelope format remains unchanged and cross-platform compatible.

Implementation is in `app/src/main/java/me/nettrash/exchange/crypto/FilePayload.kt`.

## Architecture

```
app/src/main/java/me/nettrash/exchange/
├── ExchangeApplication.kt        app singleton; wires stores/repository/preferences
├── MainActivity.kt               single Activity, incoming intents, app lock prompt
├── AppConstants.kt               privacy/support URLs
├── data/
│   ├── IdentityStore.kt          Keystore-wrapped identity persistence (AES-GCM)
│   ├── Recipient.kt              Room entity + key/fingerprint helpers
│   ├── RecipientDao.kt           Room DAO
│   ├── RecipientRepository.kt    data access + ordering/rename helpers
│   ├── ExchangeDatabase.kt       Room database setup + migrations
│   └── AppPreferences.kt         last recipient, lock settings, share-format preference
├── crypto/
│   ├── Identity.kt               X25519 + Ed25519 identity model, bundle encoding
│   ├── CryptoEnvelope.kt         EXC2 seal/open protocol
│   ├── EnvelopeUrl.kt            EXC2 <-> https://exchange.nettrash.me/msg codec
│   ├── IdentityBackup.kt         EXCBKP1 passphrase backup (PBKDF2 + ChaCha20-Poly1305)
│   ├── IdentityTransferQR.kt     EXCQR1 one-shot identity transfer payload
│   ├── FilePayload.kt            framed file plaintext for EXC2
│   ├── Base64Ext.kt / HexExt.kt  codec/fingerprint helpers
│   └── ...
└── ui/
    ├── ExchangeViewModel.kt      shared app state + actions
    ├── ExchangeNav.kt            NavHost routes
    ├── components/               QR scanner/view, camera permission, platform actions
    ├── screens/
    │   ├── HomeScreen.kt
    │   ├── ComposeScreen.kt / DecryptScreen.kt
    │   ├── EncryptFileScreen.kt / DecryptFileScreen.kt
    │   ├── AddRecipientScreen.kt / MyIdentityQrScreen.kt
    │   ├── ExportIdentityScreen.kt / ImportIdentityScreen.kt
    │   ├── ShowIdentityQrTransferScreen.kt / ScanIdentityQrTransferScreen.kt
    │   ├── SettingsScreen.kt / LockScreen.kt / SplashScreen.kt
    │   └── ...
    └── theme/

app/src/main/
├── AndroidManifest.xml           launch/share/deep-link intent filters
└── res/xml/
    ├── backup_rules.xml          excludes all app data from backup
    └── data_extraction_rules.xml disables cloud/device transfer extraction

app/src/test/java/me/nettrash/exchange/crypto/
├── CryptoEnvelopeTest.kt
├── EnvelopeUrlTest.kt
├── IdentityBackupTest.kt
└── IdentityTransferQRTest.kt
```

## Building

Requirements:

- Android Studio (recent stable with AGP 9 support) or Gradle CLI
- JDK 17
- Android SDK 36 (compileSdk/targetSdk/minSdk are all 36)

### Debug build

```bash
./gradlew :app:assembleDebug
```

Install from Android Studio or with `adb install` from `app/build/outputs/apk/debug/`.

### Release build

Release signing can come from either:

1. `keystore.properties` at repo root (local development), or
2. Environment variables in CI:
   - `EXCHANGE_KEYSTORE_PATH`
   - `EXCHANGE_KEYSTORE_PASSWORD`
   - `EXCHANGE_KEY_ALIAS`
   - `EXCHANGE_KEY_PASSWORD`

Without signing material configured, release builds still compile with debug signing for local smoke testing, but are not Play-uploadable.

```bash
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

### Versioning

- `versionCode` is read from `version.properties`.
- After successful `assemble*` or `bundle*`, `versionCode` auto-increments for the next build.
- Override at build time with `-PversionCode=N` and `-PversionName=X.Y.Z`.
- Skip auto-bump for one build with `-PnoBump`.

## Test

Run JVM unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Run instrumentation tests (device/emulator):

```bash
./gradlew :app:connectedDebugAndroidTest
```

Crypto/protocol tests cover round-trip, tampering, malformed input handling, URL codec behavior, passphrase backup integrity, and identity QR transfer decoding.

## Privacy

Exchange is designed to operate fully on-device.

- No accounts, no telemetry, no ads, no tracking SDKs.
- App does not request `INTERNET` permission.
- Identity private keys are stored encrypted under an Android Keystore AES-256/GCM key.
- Recipient data is local Room storage.
- Android Auto Backup and device-to-device extraction are explicitly disabled (`android:allowBackup="false"` plus exclusion rules in `res/xml/`).
- Data transfer between devices is explicit and user-driven:
  - Passphrase backup (`EXCBKP1`, PBKDF2-HMAC-SHA256 600k iterations + ChaCha20-Poly1305)
  - In-person QR identity transfer (`EXCQR1`)

Privacy policy: <https://nettrash.me/play/exchange/privacy.html>

## Cross-platform compatibility

Envelope and key formats are byte-compatible with sibling Exchange clients (including iOS):

- `EXC2` encrypted message envelopes
- `EXCBKP1` passphrase identity backup blobs
- `EXCQR1` in-person identity transfer payloads
- `exchange.nettrash.me/msg` URL form

## Changelog

See `CHANGELOG.md` for repository history and `fastlane/metadata/android/en-GB/changelogs/` for Play release notes by `versionCode`.

## License

MIT — see `LICENSE`.

## Author

Ivan Alekseev — `nettrash@nettrash.me` — <https://nettrash.me>
