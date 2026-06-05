/*
 * IdentityStore.kt
 * Exchange (Android)
 *
 * Persistence boundary for `Identity`. Stores the 64-byte private-key
 * blob (32 X25519 scalar + 32 Ed25519 seed) on disk encrypted under
 * an AES-256/GCM key held inside Android Keystore.
 *
 * Why Keystore-wrapped instead of plain file or `EncryptedSharedPreferences`:
 *   - Keystore keys live in TEE / StrongBox on supported devices, so an
 *     attacker who lifts the raw disk can't decrypt without arbitrary
 *     code execution on the device itself.
 *   - `EncryptedSharedPreferences` (the easier API) was deprecated in
 *     androidx.security 1.1.0, and bringing it in for a single secret
 *     isn't worth the maintenance debt.
 *   - We need exactly one secret, accessed maybe once a minute. A
 *     hand-rolled wrap-with-keystore is small and obvious.
 *
 * iOS counterpart: `Exchange/Crypto/Identity.swift` + `Storage/KeychainStore.swift`.
 * iCloud-Keychain sync has no Android equivalent, so this store is
 * always device-local — multi-device flow goes through the passphrase
 * export (`IdentityBackup`) or in-person QR (`IdentityTransferQR`).
 */

package me.nettrash.exchange.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import me.nettrash.exchange.crypto.Identity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IdentityStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Return the existing identity, generating + persisting a new one
     * if absent.
     */
    @Synchronized
    fun loadOrCreate(): Identity {
        load()?.let { return it }
        val fresh = Identity.generate()
        persist(fresh)
        return fresh
    }

    /** Return the existing identity, or null if none has been generated yet. */
    @Synchronized
    fun load(): Identity? {
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        val ctB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
        val ct = android.util.Base64.decode(ctB64, android.util.Base64.NO_WRAP)
        return try {
            val plain = decryptWithKeystore(iv, ct)
            if (plain.size != 64) return null
            Identity(
                encryptionPrivateKey = plain.copyOfRange(0, 32),
                signingPrivateKey = plain.copyOfRange(32, 64),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replace the stored identity with the supplied one. Used by the
     * import / restore / QR-receive flows.
     */
    @Synchronized
    fun replace(newIdentity: Identity): Identity {
        persist(newIdentity)
        return newIdentity
    }

    /**
     * Wipe the identity from disk and remove the Keystore-held wrapping
     * key. Irreversible — every envelope ever addressed to the old key
     * becomes unreadable.
     */
    @Synchronized
    fun reset() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Best-effort cleanup; never let a Keystore hiccup block reset.
        }
    }

    /** True if an identity has already been generated and persisted. */
    fun hasIdentity(): Boolean =
        prefs.contains(KEY_IV) && prefs.contains(KEY_CIPHERTEXT)

    // -- Internals --------------------------------------------------------

    private fun persist(identity: Identity) {
        val plain = identity.encryptionPrivateKey + identity.signingPrivateKey
        val (iv, ct) = encryptWithKeystore(plain)
        prefs.edit()
            .putString(KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
            .apply()
    }

    private fun encryptWithKeystore(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val ct = cipher.doFinal(plain)
        return cipher.iv to ct
    }

    private fun decryptWithKeystore(iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateMasterKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(ct)
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Allow access whenever the device has been unlocked at
            // least once (so background notifications, share-target
            // intents, etc. can still read the identity). Matches the
            // iOS `kSecAttrAccessibleAfterFirstUnlock` posture.
            .setRandomizedEncryptionRequired(true)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "exchange.identity.v1"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ct"
        private const val KEY_ALIAS = "exchange-identity-master"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_BITS = 128
    }
}
