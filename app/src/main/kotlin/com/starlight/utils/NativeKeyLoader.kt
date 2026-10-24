package com.starlight.utils

/**
 * Created by Kevin Andrews on 21/10/2026.
 */

class NativeKeyLoader {
    external fun getChars(operationId: Int, source: String): CharArray

    inline fun decryptPayload(encryptedPayload: String, block: (CharArray) -> Unit) {
        val chars = getChars(2, encryptedPayload)
        try {
            block(chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    // For printing out the native encrypted keys
    @Suppress("unused")
    fun encryptPayload(plainTextConfig: String): String {
        val chars = getChars(1, plainTextConfig)
        val result = String(chars)
        chars.fill('\u0000')
        return result
    }

}