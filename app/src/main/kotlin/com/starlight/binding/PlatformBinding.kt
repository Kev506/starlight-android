package com.starlight.binding

import android.content.Context
import com.starlight.binding.crypto.AndroidCryptoProvider
import com.starlight.nvstream.http.StarlightCryptoProvider

enum class PlatformBinding {
    ;

    companion object {
        fun getCryptoProvider(c: Context?): StarlightCryptoProvider {
            return AndroidCryptoProvider(c!!)
        }
    }
}