package com.starlight.games

import coil3.Bitmap

/**
 * Created by Kevin Andrews on 03/05/2026.
 */

data class GameDetailsState(
    var isLoading: Boolean = true,
    var title: String? = null,
    var releaseYear: String? = null,
    var ratingValue: String? = null,
    var ratingText: String? = null,
    var developer: String? = null,
    var description: String? = null,
    var coverUrl: String? = null,
    var fallbackCoverArt: Bitmap? = null,
    var backgroundUrl: String? = null,
    var isFavorite: Boolean = false,
    var gridText: String? = null,
    var isTVScreen: Boolean = false,
    var isVisible: Boolean = false,
    var video: String? = null,
    var slug: String? = null
)