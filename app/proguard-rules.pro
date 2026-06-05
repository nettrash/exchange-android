# ProGuard / R8 rules for Exchange release builds.
#
# Default Android rules from `proguard-android-optimize.txt` (applied
# via `proguardFiles(getDefaultProguardFile(…))` in build.gradle.kts)
# handle Compose, AndroidX, kotlinx.coroutines, etc. on their own
# through packaged `consumer-rules.pro` files inside each AAR.
#
# Keep crash-friendly stack traces — strip the original source file
# name but retain line numbers so Crashlytics / Play Console traces
# remain readable after obfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepattributes *Annotation*

# ---------------------------------------------------------------------------
# Exchange's own packages with wire-level invariants.
# ---------------------------------------------------------------------------
#
# `crypto` defines the byte layout of the EXC2 envelope, EXCBKP1
# backup blob, EXCQR1 identity transfer, and the public-bundle codec.
# Class / member names are part of the trust surface (debug logs,
# crash dumps, third-party verification) — keep them readable.
-keep class me.nettrash.exchange.crypto.** { *; }

# `data` holds Room entities (Recipient) plus the Keystore-wrapped
# identity store. Room synthesizes DAO impls referencing these via
# reflection; safer to keep them whole.
-keep class me.nettrash.exchange.data.** { *; }

# ---------------------------------------------------------------------------
# BouncyCastle
# ---------------------------------------------------------------------------
#
# We call BC primitives directly (X25519, Ed25519, ChaCha20-Poly1305,
# HKDF). We do NOT register a JCE provider, so R8 will trim the
# Service-Loader plumbing on its own. Suppress warnings about the
# unreached code paths.
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------------------
# ZXing — we only use the QR encoder + decoder.
# ---------------------------------------------------------------------------
-dontwarn com.google.zxing.**

# ---------------------------------------------------------------------------
# CameraX
# ---------------------------------------------------------------------------
#
# CameraX uses ServiceLoader-style configuration discovery. The AAR
# ships its own consumer rules — these are belt-and-braces for the
# bits R8 sometimes still warns about.
-dontwarn androidx.camera.**
