/*
 * ExchangeViewModel.kt
 * Exchange (Android)
 *
 * Single shared ViewModel for the app. Holds:
 *
 *   - Identity loading state (loading / loaded / error)
 *   - The flow of recipients (Room → Compose)
 *   - A pendingDecryptEnvelope channel that MainActivity writes into
 *     when a share-sheet or deep-link intent arrives, and DecryptScreen
 *     reads from on first composition.
 *
 * One VM for everything is fine here: the app is small, the state is
 * naturally entangled (every screen needs the identity), and a single
 * VM lets us keep navigation arguments trivial.
 */

package me.nettrash.exchange.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.exchange.ExchangeApplication
import me.nettrash.exchange.crypto.CryptoEnvelope
import me.nettrash.exchange.crypto.EnvelopeUrl
import me.nettrash.exchange.crypto.FilePayload
import me.nettrash.exchange.crypto.Identity
import me.nettrash.exchange.crypto.IdentityBackup
import me.nettrash.exchange.crypto.IdentityTransferQR
import me.nettrash.exchange.data.AppLockTimeout
import me.nettrash.exchange.data.MessageShareFormat
import me.nettrash.exchange.data.Recipient

class ExchangeViewModel(app: Application) : AndroidViewModel(app) {

    private val application: ExchangeApplication = app as ExchangeApplication
    private val identityStore = application.identityStore
    private val recipientRepository = application.recipientRepository
    private val appPreferences = application.appPreferences

    sealed class IdentityState {
        data object Loading : IdentityState()
        data class Loaded(val identity: Identity) : IdentityState()
        data class Error(val message: String) : IdentityState()
    }

    private val _identityState = MutableStateFlow<IdentityState>(IdentityState.Loading)
    val identityState: StateFlow<IdentityState> = _identityState.asStateFlow()

    /** Latest list of recipients, newest first. */
    val recipientsByCreatedAt: StateFlow<List<Recipient>> = recipientRepository
        .observeRecipientsByCreatedAtDesc()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Recipients in the user's manual drag order. Used by both HomeScreen
     * and ComposeScreen's picker so the two stay in the same order.
     */
    val recipientsByManualOrder: StateFlow<List<Recipient>> = recipientRepository
        .observeRecipientsByManualOrder()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * `Recipient.id` of the recipient last composed to, so ComposeScreen
     * can pre-select them. Read once when the picker initialises.
     */
    val lastRecipientId: String?
        get() = appPreferences.lastRecipientId

    /** Remember the recipient just composed to (see [lastRecipientId]). */
    fun rememberLastRecipient(id: String) {
        appPreferences.lastRecipientId = id
    }

    /** Preferred Compose share representation (link vs raw EXC2). */
    val composeShareFormat: MessageShareFormat
        get() = appPreferences.composeShareFormat

    fun setComposeShareFormat(format: MessageShareFormat) {
        appPreferences.composeShareFormat = format
    }

    /**
     * Set by MainActivity when an external intent (share sheet,
     * deep link, custom scheme) hands us an envelope. DecryptScreen
     * reads + clears this on first composition.
     */
    private val _pendingDecryptEnvelope = MutableStateFlow<String?>(null)
    val pendingDecryptEnvelope: StateFlow<String?> = _pendingDecryptEnvelope.asStateFlow()

    // ---- App lock -------------------------------------------------------
    // A UI gate only; identity keys stay protected by the Keystore.
    // MainActivity owns the BiometricPrompt (unlock) and the lifecycle
    // hooks (re-lock); this VM just holds the locked flag + settings.

    /** Whether the lock is engaged now. Locked on cold launch if enabled. */
    private val _isLocked = MutableStateFlow(appPreferences.appLockEnabled)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /** When the app last went to the background, for re-lock timing. */
    private var lastBackgroundedAt: Long? = null

    val appLockEnabled: Boolean get() = appPreferences.appLockEnabled
    val appLockTimeout: AppLockTimeout get() = appPreferences.appLockTimeout
    val appLockCoversIncoming: Boolean get() = appPreferences.appLockCoversIncoming

    fun setLocked(locked: Boolean) { _isLocked.value = locked }

    fun setAppLockEnabled(enabled: Boolean) {
        appPreferences.appLockEnabled = enabled
        // Turning the lock off unlocks immediately; turning it on takes
        // effect on the next launch / re-lock, not retroactively.
        if (!enabled) _isLocked.value = false
    }

    fun setAppLockTimeout(timeout: AppLockTimeout) {
        appPreferences.appLockTimeout = timeout
    }

    fun setAppLockCoversIncoming(covers: Boolean) {
        appPreferences.appLockCoversIncoming = covers
    }

    /** Record that the app went to the background (for re-lock timing). */
    fun noteBackgrounded(nowMs: Long) {
        if (lastBackgroundedAt == null) lastBackgroundedAt = nowMs
    }

    /**
     * On return to the foreground, re-lock if the configured grace period
     * has elapsed. `ONLY_ON_LAUNCH` (null interval) never re-locks here.
     */
    fun maybeRelock(nowMs: Long) {
        val last = lastBackgroundedAt
        lastBackgroundedAt = null
        if (!appPreferences.appLockEnabled) return
        val interval = appPreferences.appLockTimeout.millis ?: return
        if (last != null && nowMs - last >= interval) _isLocked.value = true
    }

    init {
        loadIdentity()
    }

    fun loadIdentity() {
        viewModelScope.launch {
            _identityState.value = IdentityState.Loading
            try {
                // Keystore key generation on first launch can take a
                // few hundred ms — push it off the main thread so the
                // splash actually animates.
                val identity = withContext(Dispatchers.IO) {
                    identityStore.loadOrCreate()
                }
                _identityState.value = IdentityState.Loaded(identity)
            } catch (e: Throwable) {
                _identityState.value = IdentityState.Error(
                    e.message ?: "Couldn't load identity."
                )
            }
        }
    }

    /** Re-read the identity from disk without re-creating it. */
    fun refreshIdentity() {
        viewModelScope.launch {
            try {
                val identity = withContext(Dispatchers.IO) { identityStore.load() }
                if (identity == null) {
                    loadIdentity()
                } else {
                    _identityState.value = IdentityState.Loaded(identity)
                }
            } catch (_: Throwable) {
                loadIdentity()
            }
        }
    }

    fun setPendingDecryptEnvelope(envelope: String?) {
        _pendingDecryptEnvelope.value = envelope
    }

    /**
     * Normalize raw text from a share / deep link into a canonical
     * `EXC2:` envelope, and stash it. Accepts either the raw EXC2 form
     * or our exchange.nettrash.me/msg URL form.
     */
    fun acceptIncomingText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val canonical = EnvelopeUrl.envelopeIfPresent(text) ?: return false
        _pendingDecryptEnvelope.value = canonical
        // If the lock isn't scoped to cover incoming messages, let this
        // shared / opened envelope through without forcing an unlock.
        if (!appPreferences.appLockCoversIncoming) _isLocked.value = false
        return true
    }

    // ---- Actions used by the screens ------------------------------------

    suspend fun saveRecipient(
        displayName: String,
        bundleBase64: String,
        notes: String,
    ): SaveRecipientResult {
        val bundle = try {
            Identity.decode(bundleBase64.trim())
        } catch (_: Throwable) {
            return SaveRecipientResult.MalformedBundle
        }
        val fingerprint = Identity.fingerprint(bundle)
        if (recipientRepository.hasFingerprint(fingerprint)) {
            return SaveRecipientResult.Duplicate
        }
        // New recipients sit at the top of the manual order (one below the
        // current minimum), preserving the historical "newest first"
        // default until the user drags things around.
        val topOrderIndex = (recipientRepository.minOrderIndex() ?: 0) - 1
        val recipient = Recipient.create(
            displayName = displayName.trim(),
            publicBundle = bundle,
            notes = notes.trim(),
            orderIndex = topOrderIndex,
        )
        recipientRepository.insert(recipient)
        return SaveRecipientResult.Saved
    }

    suspend fun deleteRecipient(recipient: Recipient) {
        recipientRepository.delete(recipient)
    }

    /** Rename a recipient (edit its local display label + notes). */
    suspend fun renameRecipient(recipient: Recipient, newDisplayName: String, newNotes: String) {
        val trimmedName = newDisplayName.trim()
        val label = if (trimmedName.isEmpty()) recipient.fingerprintDisplay else trimmedName
        recipientRepository.rename(recipient.id, label, newNotes.trim())
    }

    /**
     * Persist a drag-reorder. [orderedIds] is the new top-to-bottom order
     * of recipient ids; each row's order_index is rewritten to its
     * position so the change survives restarts and propagates via backup.
     */
    suspend fun reorderRecipients(orderedIds: List<String>) {
        recipientRepository.applyManualOrder(orderedIds)
    }

    suspend fun encrypt(
        plaintext: String,
        recipient: Recipient,
        identity: Identity,
    ): EncryptResult = try {
        val envelope = CryptoEnvelope.seal(
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            recipientEncryptionPublicKey = recipient.encryptionPublicKey,
            sender = identity,
        )
        EncryptResult.Sealed(envelope)
    } catch (e: Throwable) {
        EncryptResult.Failed(e.message ?: "Couldn't encrypt.")
    }

    suspend fun decrypt(
        envelopeText: String,
        identity: Identity,
    ): DecryptResult {
        val canonical = EnvelopeUrl.envelopeIfPresent(envelopeText) ?: envelopeText
        val opened = try {
            CryptoEnvelope.open(canonical, identity)
        } catch (e: CryptoEnvelope.Error) {
            return DecryptResult.Failed(describe(e))
        } catch (e: Throwable) {
            return DecryptResult.Failed("Couldn't decrypt: ${e.message ?: e.javaClass.simpleName}.")
        }
        val plaintext = try {
            String(opened.plaintext, Charsets.UTF_8).takeIf { it.isValidUtf8For(opened.plaintext) }
        } catch (_: Throwable) {
            null
        }
        // Match sender by signing-key bytes against the saved recipient list.
        val sender = recipientRepository.listAll().firstOrNull { r ->
            r.signingPublicKey.contentEquals(opened.senderSigningPublicKey)
        }
        return DecryptResult.Opened(
            plaintextText = plaintext,
            binaryByteCount = if (plaintext == null) opened.plaintext.size else null,
            senderDisplayName = sender?.displayName,
            senderSigningKeyFingerprint =
                if (sender == null)
                    me.nettrash.exchange.crypto.Identity.encryptionFingerprint(opened.senderSigningPublicKey)
                else null,
        )
    }

    /** True if encoding then decoding round-trips byte-for-byte. */
    private fun String.isValidUtf8For(raw: ByteArray): Boolean =
        this.toByteArray(Charsets.UTF_8).contentEquals(raw)

    // ---- File encryption / decryption -----------------------------------

    /**
     * Encrypt a file (its name + bytes, framed by [FilePayload]) to a
     * recipient. Returns the `EXC2:` envelope to write to an `.exc2` file.
     */
    suspend fun encryptFile(
        content: ByteArray,
        filename: String,
        recipient: Recipient,
        identity: Identity,
    ): EncryptResult = try {
        val payload = FilePayload.encode(filename, content)
        val envelope = CryptoEnvelope.seal(
            plaintext = payload,
            recipientEncryptionPublicKey = recipient.encryptionPublicKey,
            sender = identity,
        )
        EncryptResult.Sealed(envelope)
    } catch (e: Throwable) {
        EncryptResult.Failed(e.message ?: "Couldn't encrypt the file.")
    }

    /**
     * Decrypt an `.exc2` envelope. If it carries a file (FilePayload magic)
     * the original name + bytes come back; otherwise it falls back to the
     * text/binary message it actually was.
     */
    suspend fun decryptFile(envelopeText: String, identity: Identity): FileDecryptResult {
        val canonical = EnvelopeUrl.envelopeIfPresent(envelopeText) ?: envelopeText
        val opened = try {
            CryptoEnvelope.open(canonical, identity)
        } catch (e: CryptoEnvelope.Error) {
            return FileDecryptResult.Failed(describe(e))
        } catch (e: Throwable) {
            return FileDecryptResult.Failed("Couldn't decrypt: ${e.message ?: e.javaClass.simpleName}.")
        }

        val sender = recipientRepository.listAll().firstOrNull { r ->
            r.signingPublicKey.contentEquals(opened.senderSigningPublicKey)
        }
        val senderName = sender?.displayName
        val senderFp = if (sender == null) Identity.encryptionFingerprint(opened.senderSigningPublicKey) else null

        val file = FilePayload.decode(opened.plaintext)
        return if (file != null) {
            FileDecryptResult.File(file.filename, file.content, senderName, senderFp)
        } else {
            val text = try {
                String(opened.plaintext, Charsets.UTF_8).takeIf { it.isValidUtf8For(opened.plaintext) }
            } catch (_: Throwable) {
                null
            }
            FileDecryptResult.Message(
                text = text,
                binaryByteCount = if (text == null) opened.plaintext.size else null,
                senderDisplayName = senderName,
                senderSigningKeyFingerprint = senderFp,
            )
        }
    }

    sealed class FileDecryptResult {
        data class File(
            val filename: String,
            val content: ByteArray,
            val senderDisplayName: String?,
            val senderSigningKeyFingerprint: ByteArray?,
        ) : FileDecryptResult()
        data class Message(
            val text: String?,
            val binaryByteCount: Int?,
            val senderDisplayName: String?,
            val senderSigningKeyFingerprint: ByteArray?,
        ) : FileDecryptResult()
        data class Failed(val message: String) : FileDecryptResult()
    }

    suspend fun resetIdentity() {
        recipientRepository.replaceAll(emptyList())
        withContext(Dispatchers.IO) { identityStore.reset() }
        loadIdentity()
    }

    /**
     * Encrypt the local identity + recipient list as an EXCBKP1 string.
     * Runs the PBKDF2 derivation off the main thread — 600k iterations
     * is ~1.5 s on a mid-range phone.
     */
    suspend fun exportIdentity(passphrase: String): ExportResult {
        val identity = (identityState.value as? IdentityState.Loaded)?.identity
            ?: return ExportResult.Failed("Identity not loaded.")
        return try {
            val recipients = recipientRepository.snapshotForBackup()
            val blob = withContext(Dispatchers.Default) {
                IdentityBackup.encode(
                    identity = identity,
                    recipients = recipients,
                    passphrase = passphrase,
                )
            }
            ExportResult.Done(blob)
        } catch (e: Throwable) {
            ExportResult.Failed(e.message ?: "Couldn't encrypt the backup.")
        }
    }

    suspend fun decryptBackupPreview(blob: String, passphrase: String): ImportPreviewResult =
        try {
            // Same 600k-iteration PBKDF2 hit as encode — run on Default.
            val decoded = withContext(Dispatchers.Default) {
                IdentityBackup.decode(blob, passphrase)
            }
            ImportPreviewResult.Decoded(decoded)
        } catch (e: IdentityBackup.Error) {
            val message = when (e) {
                is IdentityBackup.Error.Malformed ->
                    "This doesn't look like an Exchange backup blob."
                is IdentityBackup.Error.UnsupportedVersion ->
                    "Unsupported backup version (${e.version})."
                is IdentityBackup.Error.WrongPassphraseOrTampered ->
                    "The passphrase didn't decrypt the backup. It may be wrong, or the blob may have been corrupted."
            }
            ImportPreviewResult.Failed(message)
        } catch (e: Throwable) {
            ImportPreviewResult.Failed(e.message ?: "Couldn't decrypt the backup.")
        }

    suspend fun applyBackup(backup: IdentityBackup.DecodedBackup) {
        withContext(Dispatchers.IO) { identityStore.replace(backup.identity) }
        recipientRepository.replaceAll(backup.recipients)
        refreshIdentity()
    }

    suspend fun decodeIdentityTransferQR(payload: String): TransferDecodeResult =
        try {
            val decoded = IdentityTransferQR.decode(payload)
            TransferDecodeResult.Decoded(decoded)
        } catch (e: IdentityTransferQR.Error) {
            val message = when (e) {
                is IdentityTransferQR.Error.Malformed ->
                    "That QR doesn't look like an Exchange transfer code."
                is IdentityTransferQR.Error.Tampered ->
                    "The transfer code couldn't be decrypted."
            }
            TransferDecodeResult.Failed(message)
        } catch (e: Throwable) {
            TransferDecodeResult.Failed(e.message ?: "Couldn't decode the transfer.")
        }

    suspend fun applyTransfer(transfer: IdentityTransferQR.DecodedTransfer) {
        withContext(Dispatchers.IO) { identityStore.replace(transfer.toIdentity()) }
        refreshIdentity()
    }

    sealed class SaveRecipientResult {
        data object Saved : SaveRecipientResult()
        data object Duplicate : SaveRecipientResult()
        data object MalformedBundle : SaveRecipientResult()
    }

    sealed class EncryptResult {
        data class Sealed(val envelope: String) : EncryptResult()
        data class Failed(val message: String) : EncryptResult()
    }

    sealed class DecryptResult {
        data class Opened(
            val plaintextText: String?,
            val binaryByteCount: Int?,
            val senderDisplayName: String?,
            val senderSigningKeyFingerprint: ByteArray?,
        ) : DecryptResult()
        data class Failed(val message: String) : DecryptResult()
    }

    sealed class ExportResult {
        data class Done(val blob: String) : ExportResult()
        data class Failed(val message: String) : ExportResult()
    }

    sealed class ImportPreviewResult {
        data class Decoded(val backup: IdentityBackup.DecodedBackup) : ImportPreviewResult()
        data class Failed(val message: String) : ImportPreviewResult()
    }

    sealed class TransferDecodeResult {
        data class Decoded(val transfer: IdentityTransferQR.DecodedTransfer) : TransferDecodeResult()
        data class Failed(val message: String) : TransferDecodeResult()
    }

    private fun describe(error: CryptoEnvelope.Error): String = when (error) {
        is CryptoEnvelope.Error.MalformedEnvelope ->
            "This doesn't look like an Exchange envelope (expected EXC2: prefix and a base64 body)."
        is CryptoEnvelope.Error.UnsupportedVersion ->
            "Unsupported envelope version: 0x${"%02x".format(error.version)}. You may need a newer build of Exchange."
        is CryptoEnvelope.Error.FingerprintMismatch ->
            "This message wasn't sent to your identity. Either it's for someone else, or your identity has changed since the sender encrypted it."
        is CryptoEnvelope.Error.SignatureVerificationFailed ->
            "The sender's signature didn't verify. The envelope was tampered with after it was signed."
        is CryptoEnvelope.Error.DecryptionFailed ->
            "The message is corrupt or has been tampered with after it was sent."
    }
}
