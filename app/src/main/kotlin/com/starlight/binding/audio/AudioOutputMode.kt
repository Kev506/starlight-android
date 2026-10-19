package com.starlight.binding.audio

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 28/09/2026.
 */

enum class AudioOutputMode(private val preferenceValue: String) {
    PCM("pcm"),
    AC3_AUTO("ac3_auto"),
    AC3_FORCE("ac3_force"),
    EAC3_AUTO("eac3_auto"),
    EAC3_FORCE("eac3_force");

    fun getPreferenceValue(): String {
        return preferenceValue
    }

    fun isEncoded(): Boolean {
        return this != PCM
    }

    fun isEac3(): Boolean {
        return this == EAC3_AUTO || this == EAC3_FORCE
    }

    fun isAutomatic(): Boolean {
        return this == AC3_AUTO || this == EAC3_AUTO
    }

    companion object {
        fun fromPreference(value: String?): AudioOutputMode {
            if (value != null) {
                for (mode in entries) {
                    if (mode.preferenceValue == value) {
                        return mode
                    }
                }
            }
            return PCM
        }
    }
}