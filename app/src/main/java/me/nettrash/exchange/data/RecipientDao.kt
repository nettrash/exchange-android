/*
 * RecipientDao.kt
 * Exchange (Android)
 *
 * Room DAO for the recipients table. Read paths expose Flow so the
 * Compose UI can recompose on changes; write paths are suspend so they
 * run off the main thread.
 */

package me.nettrash.exchange.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipientDao {

    /** All recipients, newest first — matches the home-screen ordering. */
    @Query("SELECT * FROM recipients ORDER BY created_at DESC")
    fun observeAllByCreatedAtDesc(): Flow<List<Recipient>>

    /**
     * All recipients in the user's manual order: order_index ascending,
     * then newest first as the tie-break so rows that have never been
     * reordered keep their previous ordering. Drives the home screen.
     */
    @Query("SELECT * FROM recipients ORDER BY order_index ASC, created_at DESC")
    fun observeAllByManualOrder(): Flow<List<Recipient>>

    /** Smallest order_index currently in use (null if the table is empty). */
    @Query("SELECT MIN(order_index) FROM recipients")
    suspend fun minOrderIndex(): Int?

    /** Rename: update the display label + notes and stamp the edit time. */
    @Query(
        "UPDATE recipients SET display_name = :displayName, notes = :notes, " +
            "updated_at = :updatedAtUnixMs WHERE id = :id"
    )
    suspend fun rename(id: String, displayName: String, notes: String, updatedAtUnixMs: Long)

    /** Persist a row's manual position + edit time (used by reorder). */
    @Query(
        "UPDATE recipients SET order_index = :orderIndex, " +
            "updated_at = :updatedAtUnixMs WHERE id = :id"
    )
    suspend fun setOrder(id: String, orderIndex: Int, updatedAtUnixMs: Long)

    @Update
    suspend fun update(recipient: Recipient)

    /** One-shot read of every recipient — used by the export flow. */
    @Query("SELECT * FROM recipients ORDER BY created_at DESC")
    suspend fun listAll(): List<Recipient>

    @Query("SELECT COUNT(*) FROM recipients WHERE fingerprint = :fingerprint")
    suspend fun countByFingerprint(fingerprint: ByteArray): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipient: Recipient)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(recipients: List<Recipient>)

    @Delete
    suspend fun delete(recipient: Recipient)

    @Query("DELETE FROM recipients")
    suspend fun deleteAll()
}
