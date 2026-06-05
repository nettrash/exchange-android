/*
 * ExchangeDatabase.kt
 * Exchange (Android)
 *
 * Room database holding the recipient list. Identity itself is NOT
 * in this database — it lives Keystore-wrapped in SharedPreferences
 * (see IdentityStore.kt).
 *
 * Single-instance singleton, lazily created by the Application class.
 */

package me.nettrash.exchange.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Recipient::class],
    version = 2,
    exportSchema = false,
)
abstract class ExchangeDatabase : RoomDatabase() {
    abstract fun recipientDao(): RecipientDao

    companion object {
        @Volatile
        private var instance: ExchangeDatabase? = null

        /**
         * v1 → v2: adds manual-ordering (`order_index`) and an edit
         * timestamp (`updated_at`) for the rename/reorder feature. Both
         * are non-destructive ALTERs; existing rows get order_index = 0
         * (all tied → previous "newest first" order is preserved) and
         * updated_at seeded from created_at so the cross-device backup
         * merge has a sensible baseline.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recipients ADD COLUMN order_index INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE recipients ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE recipients SET updated_at = created_at")
            }
        }

        fun get(context: Context): ExchangeDatabase {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    ExchangeDatabase::class.java,
                    "exchange.db",
                )
                    // Explicit migrations only — never destructive
                    // fallback, which would strand an in-progress identity
                    // with no recipients.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                instance = db
                return db
            }
        }
    }
}
