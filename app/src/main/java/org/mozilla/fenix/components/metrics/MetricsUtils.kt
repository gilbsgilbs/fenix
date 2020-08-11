/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.metrics

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.search.SearchEngine
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.components.metrics.Event.PerformedSearch.SearchAccessPoint
import org.mozilla.fenix.components.searchengine.CustomSearchEngineStore
import org.mozilla.fenix.ext.searchEngineManager
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object MetricsUtils {
    fun createSearchEvent(
        engine: SearchEngine,
        context: Context,
        searchAccessPoint: SearchAccessPoint
    ): Event.PerformedSearch? {
        val isShortcut = engine != context.searchEngineManager.defaultSearchEngine
        val isCustom = CustomSearchEngineStore.isCustomSearchEngine(context, engine.identifier)

        val engineSource =
            if (isShortcut) Event.PerformedSearch.EngineSource.Shortcut(engine, isCustom)
            else Event.PerformedSearch.EngineSource.Default(engine, isCustom)

        return when (searchAccessPoint) {
            SearchAccessPoint.SUGGESTION -> Event.PerformedSearch(
                Event.PerformedSearch.EventSource.Suggestion(
                    engineSource
                )
            )
            SearchAccessPoint.ACTION -> Event.PerformedSearch(
                Event.PerformedSearch.EventSource.Action(
                    engineSource
                )
            )
            SearchAccessPoint.WIDGET -> Event.PerformedSearch(
                Event.PerformedSearch.EventSource.Widget(
                    engineSource
                )
            )
            SearchAccessPoint.SHORTCUT -> Event.PerformedSearch(
                Event.PerformedSearch.EventSource.Shortcut(
                    engineSource
                )
            )
            SearchAccessPoint.NONE -> Event.PerformedSearch(
                Event.PerformedSearch.EventSource.Other(
                    engineSource
                )
            )
        }
    }

    /**
     * Get the salt to use for hashing. This is a convenience
     * function to help with unit tests.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun getHashingSalt(): String = "org.mozilla.fenix-salt"

    /**
     * Produces a hashed version of the Google Advertising ID.
     * We want users using more than one of our products to report a different
     * ID in each of them. This function runs off the main thread and is CPU-bound.
     *
     * @return an hashed and salted Google Advertising ID or null if it was not possible
     *         to get the Google Advertising ID.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    suspend fun getHashedIdentifier(context: Context): String? = withContext(Dispatchers.Default) {
        AdvertisingIDImpl().query(context)?.let { unhashedID ->
            // Add some salt to the ID, before hashing. For this specific use-case, it's ok
            // to use the same salt value for all the hashes. We want hashes to be stable
            // within a single product, but we don't want hashes to be the same across different
            // products (e.g. Fennec vs Fenix).
            val salt = getHashingSalt()

            // Apply hashing.
            try {
                // Note that we intentionally want to use slow hashing functions here in order
                // to increase the cost of potentially repeatedly guess the original unhashed
                // identifier.
                val keySpec = PBEKeySpec(
                    unhashedID.toCharArray(),
                    salt.toByteArray(),
                    ActivationPing.PBKDF2_ITERATIONS,
                    ActivationPing.PBKDF2_KEY_LEN_BITS
                )

                val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                val hashedBytes = keyFactory.generateSecret(keySpec).encoded
                Base64.encodeToString(hashedBytes, Base64.NO_WRAP)
            } catch (e: java.lang.NullPointerException) {
                Logger.error("ActivationPing - missing or wrong salt parameter")
                null
            } catch (e: IllegalArgumentException) {
                Logger.error("ActivationPing - wrong parameter", e)
                null
            } catch (e: NoSuchAlgorithmException) {
                Logger.error("ActivationPing - algorithm not available")
                null
            } catch (e: InvalidKeySpecException) {
                Logger.error("ActivationPing - invalid key spec")
                null
            }
        }
    }
}
