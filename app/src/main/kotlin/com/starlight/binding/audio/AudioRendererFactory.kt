package com.starlight.binding.audio

import android.content.Context
import com.starlight.nvstream.av.audio.AudioRenderer
import com.starlight.nvstream.jni.StarBridge
import com.starlight.nvstream.jni.StarBridge.AUDIO_CONFIGURATION_51_SURROUND
import com.starlight.preferences.DataStoreConfiguration


/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 28/09/2026.
 */

object AudioRendererFactory {
    fun getEffectiveAudioConfiguration(
        preferences: DataStoreConfiguration
    ): StarBridge.AudioConfiguration {
        return if (preferences.audioOutputMode.isEncoded())
            AUDIO_CONFIGURATION_51_SURROUND
        else
            preferences.audioConfiguration
    }

    fun create(context: Context, preferences: DataStoreConfiguration): AudioRenderer {
        if (!preferences.audioOutputMode.isEncoded()) {
            // Preserve the current PCM constructor behavior.
            return AudioTrackRenderer(context, preferences.enableAudioFx)
        }

        return Ac3AudioRenderer(
            context, preferences.audioOutputMode,
            preferences.playHostAudio, preferences.disableWarnings
        )
    }
}