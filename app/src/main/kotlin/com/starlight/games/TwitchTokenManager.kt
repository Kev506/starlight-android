package com.starlight.games

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.utils.Constants.ACCESS_TOKEN_KEY
import com.starlight.utils.Constants.EXPIRATION_TIME_KEY
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.flow.first

/**
 * Created by Kevin Andrews on 21/08/2026.
 */

class TwitchTokenManager(private val context: Context) {

    data class TwitchTokenData(
        val accessToken: String,
        val expiresIn: Long,
        val tokenType: String
    )

    // Save token from Twitch for future use
    suspend fun saveToken(token: String, expiresInSeconds: Long) {
        val currentTimeMs = System.currentTimeMillis()
        val expirationTimestampMs = currentTimeMs + (expiresInSeconds * 1000)

        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey(ACCESS_TOKEN_KEY)] = token
            preferences[longPreferencesKey(EXPIRATION_TIME_KEY)] = expirationTimestampMs
        }
    }

    // Retrieve a valid token. Returns null if missing or expired.
    suspend fun getValidToken(): String? {
        val preferences = context.dataStore.data.first()
        val token = preferences[stringPreferencesKey(ACCESS_TOKEN_KEY)]
        val expirationTimestamp = preferences[longPreferencesKey(EXPIRATION_TIME_KEY)] ?: 0L

        val bufferTimeMs = 5 * 60 * 1000
        val isExpired = System.currentTimeMillis() + bufferTimeMs >= expirationTimestamp

        return if (!token.isNullOrEmpty() && !isExpired) token else null
    }
}