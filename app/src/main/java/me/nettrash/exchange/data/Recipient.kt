/*
 * Recipient.kt
 * Exchange (Android)
 *
 * A contact we can send signed, encrypted messages to.
 *
 * Stores both of the contact's public keys (X25519 encryption + Ed25519
 * signing), the combined identity fingerprint, plus a display name,
 * free-form notes, and creation timestamp.
 *
 * Mirrors iOS `Exchange/Models/Recipient.swift` (which uses SwiftData).
 */

package me.nettrash.exchange.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import me.nettrash.exchange.crypto.Identity
import me.nettrash.exchange.crypto.toGroupedHex

@Entity(tableName = "recipients")
data class Recipient(
    @PrimaryKey
    val id: String,

    /** Human-friendly name shown in the UI. */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** Raw 32-byte X25519 public key (used for ECDH). */
    @ColumnInfo(name = "encryption_public_key")
    val encryptionPublicKey: ByteArray,

    /** Raw 32-byte Ed25519 public key (used to verify the sender's signature). */
    @ColumnInfo(name = "signing_public_key")
    val signingPublicKey: ByteArray,

    /** First 8 bytes of SHA-256 over the concatenation of both public keys. */
    @ColumnInfo(name = "fingerprint")
    val fingerprint: ByteArray,

    /** Free-form notes (where you got this key, when you verified it, etc.) */
    @ColumnInfo(name = "notes")
    val notes: String,

    /** Epoch milliseconds since 1970-01-01 UTC. */
    @ColumnInfo(name = "created_at")
    val createdAtUnixMs: Long,

    /**
     * User-defined position in the recipient list (manual drag-to-reorder).
     * Lower sorts first; ties break by created_at DESC so the pre-reorder
     * default stays "newest first". Added in DB v2 with a default of 0.
     */
    @ColumnInfo(name = "order_index", defaultValue = "0")
    val orderIndex: Int = 0,

    /**
     * Last time the user edited this recipient's display label or its
     * position, epoch ms. Used by the cross-device backup merge to resolve
     * rename/reorder conflicts (latest write wins). The v1→v2 migration
     * seeds it from created_at for existing rows.
     */
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAtUnixMs: Long = createdAtUnixMs,
) {
    /** Reconstruct the typed public bundle from stored bytes. */
    val publicBundle: Identity.PublicBundle
        get() = Identity.PublicBundle(
            encryptionPublicKey = encryptionPublicKey,
            signingPublicKey = signingPublicKey,
        )

    /** Display-friendly identity fingerprint, e.g. "a1b2-c3d4-e5f6-0708". */
    val fingerprintDisplay: String get() = fingerprint.toGroupedHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Recipient) return false
        return id == other.id &&
            displayName == other.displayName &&
            encryptionPublicKey.contentEquals(other.encryptionPublicKey) &&
            signingPublicKey.contentEquals(other.signingPublicKey) &&
            fingerprint.contentEquals(other.fingerprint) &&
            notes == other.notes &&
            createdAtUnixMs == other.createdAtUnixMs &&
            orderIndex == other.orderIndex &&
            updatedAtUnixMs == other.updatedAtUnixMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + encryptionPublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + fingerprint.contentHashCode()
        result = 31 * result + notes.hashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        result = 31 * result + orderIndex
        result = 31 * result + updatedAtUnixMs.hashCode()
        return result
    }

    companion object {
        /**
         * Build a recipient row from a freshly-imported public bundle.
         * Computes the fingerprint and assigns a UUID.
         */
        fun create(
            displayName: String,
            publicBundle: Identity.PublicBundle,
            notes: String = "",
            createdAtUnixMs: Long = System.currentTimeMillis(),
            orderIndex: Int = 0,
            updatedAtUnixMs: Long = createdAtUnixMs,
        ): Recipient = Recipient(
            id = java.util.UUID.randomUUID().toString(),
            displayName = displayName,
            encryptionPublicKey = publicBundle.encryptionPublicKey,
            signingPublicKey = publicBundle.signingPublicKey,
            fingerprint = Identity.fingerprint(publicBundle),
            notes = notes,
            createdAtUnixMs = createdAtUnixMs,
            orderIndex = orderIndex,
            updatedAtUnixMs = updatedAtUnixMs,
        )
    }
}
