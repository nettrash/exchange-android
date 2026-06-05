/*
 * ExchangeApplication.kt
 * Exchange (Android)
 *
 * Application singleton. Lazily constructs the things we want to
 * outlive any one Activity:
 *
 *   - IdentityStore        — Keystore-wrapped 64-byte identity blob.
 *   - ExchangeDatabase     — Room database holding recipients.
 *   - RecipientRepository  — thin coordinator over the DAO.
 *
 * These are exposed via lazy properties (not Hilt) because the surface
 * area is small enough that pulling in DI machinery would cost more
 * than it saves. ViewModels that need them grab them off the
 * Application in their factory.
 */

package me.nettrash.exchange

import android.app.Application
import me.nettrash.exchange.data.ExchangeDatabase
import me.nettrash.exchange.data.IdentityStore
import me.nettrash.exchange.data.RecipientRepository

class ExchangeApplication : Application() {

    val identityStore: IdentityStore by lazy { IdentityStore(this) }

    val database: ExchangeDatabase by lazy { ExchangeDatabase.get(this) }

    val recipientRepository: RecipientRepository by lazy {
        RecipientRepository(database.recipientDao())
    }
}
