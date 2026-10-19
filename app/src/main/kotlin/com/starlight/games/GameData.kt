package com.starlight.games

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.activity.ComponentActivity
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.starlight.R
import com.starlight.preferences.PreferenceKeys
import com.starlight.utils.AppComputerObject
import com.starlight.utils.LimeLog.info
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class GameData(private val context: Context, activity: ComponentActivity) {

    private var translator: Translator? = null
    private var componentActivity: ComponentActivity? = null
    private var game = GameDetails()

    init {
        componentActivity = activity

        applicationScope.launch(Dispatchers.Main) {
           setUpTranslator()
        }
    }

    fun setUpTranslator() {

        val prefs = PreferenceKeys.fromPreferences(context)

        // Initialize the translator
        if (prefs.mlDownloaded) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(prefs.language)
                .build()
            translator = Translation.getClient(options)
        }
    }

    fun getGameDetailsFromSource(app: AppComputerObject): GameDetails {

        db = GameDataBaseManager.getInstance(context)

        if (doesDatabaseExist(context)) {
            val dbInstance = db
            if (dbInstance != null && !dbInstance.isDatabaseEmpty()) {
                if (dbInstance.queryDataExists(app.app?.id.toString())) {
                    try {
                        // Load data from database and update UI
                        val appData = dbInstance.getAppData(app.app?.id.toString())

                        game.title = appData.title
                        game.background_image = appData.background
                        game.description = appData.description
                        game.released = appData.released
                        game.esrb_rating = appData.esrb
                        game.metacritic = appData.metaNumber
                        game.developers = appData.developers
                        game.coverArt = appData.coverArt
                        game.fallbackCoverArt = appData.fallbackCoverArt
                        game.video = appData.video
                        game.slug = appData.slug

                    } catch (e: SQLiteException) {
                        info("$TAG Data Load Failed: ${e.message}")
                        setErrorDefaults(app)
                    }
                } else {
                    info("$TAG No Data Found: ${app.app?.appName}")
                    setErrorDefaults(app)
                }
            } else {
                info("$TAG No Data Found: ${app.app?.appName}")
                setErrorDefaults(app)
            }
        } else {
            info("$TAG No Database Found: ")
            setErrorDefaults(app)
        }
        return game
    }

    // Set defaults on error
    fun setErrorDefaults(app: AppComputerObject): GameDetails {

        val prefs = PreferenceKeys.fromPreferences(context)

        val imageUri = context.resourceUri(R.drawable.portrait_background)
        val description = context.resources.getString(R.string.No_Details)
        val name = app.app?.appName?.let { sanitizeAppNames(it) }

        game.title = name?.let { getCapsSentences(it) }

        game.background_image = imageUri.toString()

        if (prefs.language != "default" && prefs.language != "en" && prefs.mlDownloaded) {
            translator?.translate(description)
                ?.addOnSuccessListener { translatedText ->
                    game.description = translatedText
                }
                ?.addOnFailureListener { exception ->
                    info("Translation failed: ${exception.message}")
                    game.description = description
                }
        } else {
            game.description = description
        }

        game.released = null
        game.esrb_rating = null
        game.metacritic = null
        game.developers = null
        game.error = true

        return game
    }

    //*************** Utility Functions *******************//

    fun Context.resourceUri(resourceId: Int): Uri = with(resources) {
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(getResourcePackageName(resourceId))
            .appendPath(getResourceTypeName(resourceId))
            .appendPath(getResourceEntryName(resourceId))
            .build()
    }

    private fun getCapsSentences(tagName: String): String {
        val splits = tagName.lowercase(Locale.getDefault()).split(" ".toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()
        val sb = StringBuilder()
        for (i in splits.indices) {
            val eachWord = splits[i]
            if (i > 0 && eachWord.isNotEmpty()) {
                sb.append(" ")
            }
            val cap = (eachWord.take(1).uppercase(Locale.getDefault())
                    + eachWord.substring(1))
            sb.append(cap)
        }
        return sb.toString()
    }

    // Sanitize app name for database
    private fun sanitizeAppNames(appName: String): String {
        return appName
            .replace("\\s+\\(.*\\)".toRegex(), "")
            .replace("[^\\x00-\\x7F]".toRegex(), "")
            .lowercase(Locale.getDefault())
    }

    //Check if database exists
    private fun doesDatabaseExist(context: Context): Boolean {
        val dbFile: File = context.getDatabasePath(GameDataBaseManager.DB_NAME)
        return dbFile.exists()
    }


    companion object {
        var db: GameDataBaseManager? = null
        const val TAG = "Games"
    }
}
