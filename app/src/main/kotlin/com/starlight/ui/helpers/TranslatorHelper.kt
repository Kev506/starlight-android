package com.starlight.ui.helpers

import android.content.Context
import android.content.res.Resources
import androidx.activity.ComponentActivity
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.starlight.R
import com.starlight.games.GameDataBaseManager
import com.starlight.games.GameDetailsState
import com.starlight.models.MainViewModel
import com.starlight.preferences.PreferenceKeys
import com.starlight.utils.LimeLog
import java.io.File

/**
 * Created by Kevin Andrews on 04/06/2026.
 */

class TranslatorHelper(
    private val resources: Resources,
    var viewModel: MainViewModel,
    var uiContext: ComponentActivity
) {

    var translator: Translator? = null

    // Helper function to get values from DataStore preferences
    fun getValues(
    ): Triple<String?, Boolean?, String?> {

        val prefConfig = PreferenceKeys.fromPreferences(uiContext)

        val isModelDownloaded: Boolean = prefConfig.mlDownloaded
        val language: String = prefConfig.language

        return Triple(null, isModelDownloaded, language)
    }

    // Check if database exists
    fun doesDatabaseExist(context: Context): Boolean {
        val dbFile: File = context.getDatabasePath(GameDataBaseManager.DB_NAME)
        return dbFile.exists()
    }

    // Display Translated Description
    fun displayTranslatedDescription(
        state: GameDetailsState,
        description: String?, language: String?, isModelDownloaded: Boolean
    ): String {

        if (isModelDownloaded) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(language!!)
                .build()
            translator = Translation.getClient(options)
        }

        if (language != "default" && language != "en" && isModelDownloaded && description != null) {
            translator?.translate(description)
                ?.addOnSuccessListener { translatedText ->
                    state.description = translatedText
                }
                ?.addOnFailureListener { exception ->
                    LimeLog.info("Translation failed: ${exception.message}")
                    state.description = description
                }
        } else {
            state.description =
                description
                    ?: resources.getString(
                        R.string.No_Details
                    )
        }
        return state.description!!
    }
}