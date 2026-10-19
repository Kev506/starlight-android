package com.starlight.games

import android.graphics.Bitmap

data class GameDataBaseDetails(
    var description: String? = null,
    var title: String? = null,
    var esrb: String? = null,
    var background: String? = null,
    var metaNumber: String? = null,
    var developers: String? = null,
    var released: String? = null,
    var coverArt: String? = null,
    var fallbackCoverArt: Bitmap? = null,
    var video: String? = null,
    var videos: String? = null,
    var slug: String? = null,
    var id: Int = 0
)