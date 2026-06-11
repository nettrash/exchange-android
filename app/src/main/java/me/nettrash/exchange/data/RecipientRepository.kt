/*
 * RecipientRepository.kt
 * Exchange (Android)
 *
 * Thin coordinator between the Recipient DAO and higher layers.
 * Keeps the ViewModels free of Room types and centralises the
 * business rules ("don't accept two recipients with the same
 * fingerprint", "rebuild the table on import").
 */

package me.nettrash.exchange.data

import kotlinx.coroutines.flow.Flow
import me.nettrash.exchange.crypto.IdentityBackup

class RecipientRepository(private val dao: RecipientDao) {

    fun observeRecipientsByCreatedAtDesc(): Flow<List<Recipient>> =
        dao.observeAllByCreatedAtDesc()

    /**
     * Recipients in the user's manual drag order — drives both the home
     * list and the Compose picker.
     */
    fun observeRecipientsByManualOrder(): Flow<List<Recipient>> =
        dao.observeAllByManualOrder()

    suspend fun listAll(): List<Recipient> = dao.listAll()

    suspend fun hasFingerprint(fingerprint: ByteArray): Boolean =
        dao.countByFingerprint(fingerprint) > 0

    suspend fun insert(recipient: Recipient) = dao.upsert(recipient)

    suspend fun delete(recipient: Recipient) = dao.delete(recipient)

    /** Smallest order_index in use (null when empty) — new rows go above it. */
    suspend fun minOrderIndex(): Int? = dao.minOrderIndex()

    /** Edit a recipient's display label + notes, stamping the edit time. */
    suspend fun rename(
        id: String,
        displayName: String,
        notes: String,
        updatedAtUnixMs: Long = System.currentTimeMillis(),
    ) = dao.rename(id, displayName, notes, updatedAtUnixMs)

    /**
     * Persist a freshly dragged order. Rewrites every row's order_index
     * to its position in [orderedIds] and stamps the same edit time, so
     * the cross-device backup merge adopts the new order.
     */
    suspend fun applyManualOrder(
        orderedIds: List<String>,
        updatedAtUnixMs: Long = System.currentTimeMillis(),
    ) {
        orderedIds.forEachIndexed { position, id ->
            dao.setOrder(id, position, updatedAtUnixMs)
        }
    }

    /** Snapshot every row for inclusion in an EXCBKP1 export. */
    suspend fun snapshotForBackup(): List<IdentityBackup.RecipientSnapshot> =
        dao.listAll().map { r ->
            IdentityBackup.RecipientSnapshot(
                displayName = r.displayName,
                encryptionPublicKey = r.encryptionPublicKey,
                signingPublicKey = r.signingPublicKey,
                notes = r.notes,
                createdAtUnixMs = r.createdAtUnixMs,
                orderIndex = r.orderIndex,
                updatedAtUnixMs = r.updatedAtUnixMs,
            )
        }

    /**
     * Replace the entire recipient table with the supplied snapshots —
     * used by the import flow. Snapshots that fail to round-trip as
     * valid public keys are skipped (defence in depth against a
     * subtly-corrupted backup).
     */
    suspend fun replaceAll(snapshots: List<IdentityBackup.RecipientSnapshot>) {
        dao.deleteAll()
        if (snapshots.isEmpty()) return
        val rows = snapshots.mapNotNull { snap ->
            try {
                val bundle = me.nettrash.exchange.crypto.Identity.PublicBundle(
                    encryptionPublicKey = snap.encryptionPublicKey,
                    signingPublicKey = snap.signingPublicKey,
                )
                Recipient.create(
                    displayName = snap.displayName,
                    publicBundle = bundle,
                    notes = snap.notes,
                    createdAtUnixMs = snap.createdAtUnixMs,
                    orderIndex = snap.orderIndex,
                    updatedAtUnixMs = snap.updatedAtUnixMs,
                )
            } catch (_: Exception) {
                null
            }
        }
        dao.upsertAll(rows)
    }
}
