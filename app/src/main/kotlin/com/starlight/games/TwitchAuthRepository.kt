package com.starlight.games

import com.starlight.games.api.RetrofitClient
import com.starlight.utils.LimeLog.warning

/**
 * Created by Kevin Andrews on 21/08/2026.
 */

object TwitchAuthRepository {

    // Fetch new token from Twitch
    suspend fun fetchNewToken(
        clientId: String,
        clientSecret: String
    ): TwitchTokenManager.TwitchTokenData? {

        return try {
            val response = RetrofitClient.twitchApi.fetchToken(clientId, clientSecret)
            TwitchTokenManager.TwitchTokenData(
                accessToken = response.accessToken,
                expiresIn = response.expiresIn,
                tokenType = response.tokenType
            )
        } catch (e: Exception) {
            warning("Retrofit: Failed to fetch Twitch token: ${e.message}")
            null
        }
    }
}
