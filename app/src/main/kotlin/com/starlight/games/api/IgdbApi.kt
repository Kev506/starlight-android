package com.starlight.games.api

import com.starlight.models.IgdbGame
import com.starlight.models.TwitchTokenResponse
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface IgdbApi {
    @POST("games")
    suspend fun searchGames(
        @Header("Client-ID") clientId: String,
        @Header("Authorization") accessToken: String,
        @Body body: RequestBody
    ): List<IgdbGame>
}

interface TwitchApi {
    @POST("oauth2/token")
    suspend fun fetchToken(
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String,
        @Query("grant_type") grantType: String = "client_credentials"
    ): TwitchTokenResponse
}
