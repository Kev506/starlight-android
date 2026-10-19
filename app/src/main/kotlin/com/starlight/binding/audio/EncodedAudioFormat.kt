package com.starlight.binding.audio

import android.media.AudioFormat

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 28/09/2026.
 */

internal enum class EncodedAudioFormat(
    val displayName: String, val androidEncoding: Int, val encoderVariant: Int,
    val bitrate: Int, val frameBytes: Int
) {
    AC3("AC-3", AudioFormat.ENCODING_AC3, 0, 448000, 1792),
    EAC3("E-AC-3", AudioFormat.ENCODING_E_AC3, 1, 640000, 2560);

    companion object {
        fun fromMode(mode: AudioOutputMode?): EncodedAudioFormat {
            return if (mode != null && mode.isEac3()) EAC3 else AC3
        }
    }
}