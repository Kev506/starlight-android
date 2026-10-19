@file:Suppress("PropertyName")

package com.starlight.games

import android.graphics.Bitmap
import com.google.gson.annotations.SerializedName

data class GameDetails(

    @SerializedName("Released")
    var released: String? = null,

    @SerializedName("Rating")
    var esrb_rating: String? = null,

    @SerializedName("Metascore")
    var metacritic: String? = null,

    @SerializedName("Developers")
    var developers: String? = null,

    @SerializedName("Description")
    var description: String? = null,

    @SerializedName("Image")
    var background_image: String? = null,

    @SerializedName("Title")
    var title: String? = null,

    @SerializedName("Error")
    var error: Boolean = false,

    @SerializedName("ReleaseError")
    var releaseError: Boolean = false,

    @SerializedName("MetaError")
    var metaError: Boolean = false,

    @SerializedName("DeveloperError")
    var developerError: Boolean = false,

    @SerializedName("CoverArt")
    var coverArt: String? = null,

    @SerializedName("FallbackCoverArt")
    var fallbackCoverArt: Bitmap? = null,

    @SerializedName("Video")
    var video: String? = null,

    @SerializedName("Slug")
    var slug: String? = null
)