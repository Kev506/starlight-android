package com.starlight.games.api

import com.starlight.utils.Constants.IGDB_API_BASEURL
import com.starlight.utils.Constants.TWITCH_AUTH_BASEURL
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object RetrofitClient {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    val twitchApi: TwitchApi by lazy {
        Retrofit.Builder()
            .baseUrl(TWITCH_AUTH_BASEURL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TwitchApi::class.java)
    }

    val igdbApi: IgdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(IGDB_API_BASEURL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(IgdbApi::class.java)
    }
}
