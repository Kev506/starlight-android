package com.starlight.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IgdbGame(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
    val summary: String? = null,
    @SerialName("first_release_date")
    val firstReleaseDate: Long? = null,
    @SerialName("total_rating")
    val totalRating: Double? = null,
    @SerialName("total_rating_count")
    val totalRatingCount: Int? = null,
    val cover: IgdbImage? = null,
    val video: IgdbVideo? = null,
    val screenshots: List<IgdbImage>? = null,
    val artworks: List<IgdbImage>? = null,
    val videos: List<IgdbVideo>? = null,
    @SerialName("involved_companies")
    val involvedCompanies: List<InvolvedCompany>? = null,
    @SerialName("age_ratings")
    val ageRatings: List<AgeRating>? = null
)

@Serializable
data class IgdbVideo(
    val id: Long,
    @SerialName("video_id")
    val videoId: String = "",
    @SerialName("name")
    val videoName: String = ""
)

@Serializable
data class IgdbImage(
    val id: Long,
    @SerialName("image_id")
    val imageId: String = ""
)

@Serializable
data class InvolvedCompany(
    val id: Long,
    val company: Company? = null,
    val publisher: Boolean = false
)

@Serializable
data class Company(
    val id: Long,
    val name: String = ""
)

@Serializable
data class AgeRating(
    val id: Long = 0,
    val organization: Int = -1,
    @SerialName("rating_category")
    val ratingCategory: Int = -1,
    val rating: Int? = null
)

@Serializable
data class TwitchTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("token_type")
    val tokenType: String
)

@Serializable
data class DbVideoItem(
    val id: Long,
    val videoId: String,
    val videoName: String
)