package com.starlight.utils

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Manages Tink AEAD primitive for encryption and decryption.
 */
class TinkAead(context: Context) {

    private val keysetName = "master_keyset"
    private val preferenceFile = "master_key_preference"
    private val masterKeyUri = "${AndroidKeystoreKmsClient.PREFIX}master_key"
    private var aead: Aead

    init {
        try {
            AeadConfig.register()
            aead = getOrCreateAead(context)
        } catch (e: GeneralSecurityException) {
            throw e
        } catch (e: IOException) {
            throw e
        }
    }

    fun get(): Aead {
        return aead
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun getOrCreateAead(context: Context): Aead {
        // Initialize an AndroidKeysetManager which facilitates keyset management. [1]
        // It can generate, store, and retrieve keysets. [1]
        val keysetManager = AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, preferenceFile)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(masterKeyUri)
            .build()

        // To encrypt the keyset, we use a master key stored in the Android Keystore. [1]
        // The get() method retrieves the keyset handle, creating a new one if none exists.
        val keysetHandle: KeysetHandle = keysetManager.keysetHandle

        // Retrieve the Aead primitive from the keyset handle. [1]
        return keysetHandle.getPrimitive(RegistryConfiguration.get(),Aead::class.java)
    }
}
