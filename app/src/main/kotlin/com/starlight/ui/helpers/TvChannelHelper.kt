package com.starlight.ui.helpers

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.BaseColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.starlight.R
import com.starlight.games.GameDataBaseManager
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.preferences.PreferenceKeys
import com.starlight.ui.content.PosterContentProvider
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class TvChannelHelper(private val activity: ComponentActivity) {

    // Helper function to request channel on home screen
    @Suppress("DEPRECATION")
    fun requestChannelOnHomeScreen(computer: ComputerDetails) {
        if (!isAndroidTV or isGoogleTV or isGoogleTVStreamer) return

        val channelId = getChannelId(computer.uuid!!) ?: return
        val intent = Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
        intent.putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId)
        try {
            activity.startActivityForResult(intent, 0)
        } catch (ignored: Exception) {
            LimeLog.warning("Unable to request channel on home screen: $ignored")
        }
    }

    // Helper function to create a TV channel
    fun createTvChannel(computer: ComputerDetails) {
        if (!isAndroidTV or isGoogleTV or isGoogleTVStreamer) return

        // Build the channel
        val builder = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(computer.name)
            .setInternalProviderId(computer.uuid)
            .setAppLinkIntent(ServerHelper.createPcShortcutIntent(activity, computer))
        val channelId = getChannelId(computer.uuid!!)
        if (channelId != null) {
            activity.contentResolver.update(
                TvContractCompat.buildChannelUri(channelId),
                builder.build().toContentValues(), null, null
            )
            return
        }
        val channelUri: Uri? = try {
            activity.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI, builder.build().toContentValues()
            )
        } catch (e: IllegalArgumentException) {
            LimeLog.warning("Unable to create channel: $e")
            return
        }
        if (channelUri != null) {
            val id = ContentUris.parseId(channelUri)
            updateChannelIcon(id)
        }
    }

    // Helper function to update the channel icon
    private fun updateChannelIcon(channelId: Long) {
        // Use ContextCompat to simplify resource access
        val drawable = ContextCompat.getDrawable(activity, R.mipmap.ic_channel)

        // Handle potential null drawable
        drawable?.let {
            val logo = drawableToBitmap(it)
            try {
                val localUri = TvContractCompat.buildChannelLogoUri(channelId)
                activity.contentResolver.openOutputStream(localUri)?.use { outputStream ->
                    logo.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.flush()
                }
            } catch (_: SQLiteException) {
                LimeLog.warning("Failed to store logo due to SQLiteException")
            } catch (_: IOException) {
                LimeLog.warning("Failed to store logo due to IOException")
            } finally {
                logo.recycle()
            }
        }
            ?: LimeLog.warning("Channel icon drawable not found.")
    }

    // Helper function to convert drawable to bitmap
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val size = activity.resources.getDimensionPixelSize(R.dimen.tv_channel_logo_width)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    // Helper function to add a game to a channel
    @SuppressLint("RestrictedApi")
    suspend fun addGameToChannel(computer: ComputerDetails, app: NvApp) {
        if (!isAndroidTV or isGoogleTV or isGoogleTVStreamer) return

        val channelId = getChannelId(computer.uuid!!) ?: return
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewProgramColumns.TYPE_GAME)
            .setTitle(app.appName)
            .setDescription(getGameDescription(app))
            .setPosterArtUri(getPosterArtUri(computer, app))
            .setPosterArtAspectRatio(getAspectRatio())
            .setIntent(ServerHelper.createAppShortcutIntent(activity, computer, app))
            .setInternalProviderId("" + app.id.toString())
            .setWeight(((System.currentTimeMillis() - 1500000000000L) / 1000).toInt())
        val programId = getProgramId(channelId, "" + app.id)
        if (programId != null) {
            activity.contentResolver.update(
                TvContractCompat.buildPreviewProgramUri(programId),
                builder.build().toContentValues(), null, null
            )
            return
        }
        try {
            activity.contentResolver.insert(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                builder.build().toContentValues()
            )
            // Show toast notification (only for non-Google TV devices)
            if (!isGoogleTV or !isGoogleTVStreamer) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        app.appName + activity.getString(R.string.added_to_favorites),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: IllegalArgumentException) {
            LimeLog.warning("Unable to add program to channel: $e")
            return
        }
        TvContractCompat.requestChannelBrowsable(activity, channelId)
    }

    // Helper function to add a game to watch next
    @SuppressLint("RestrictedApi")
    fun addGameToWatchNext(computer: ComputerDetails, app: NvApp) {
        if (!isAndroidTV or isGoogleTV or isGoogleTVStreamer) return

        val builder = WatchNextProgram.Builder()
        builder.setType(TvContractCompat.WatchNextPrograms.TYPE_GAME)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setTitle(app.appName)
            .setDescription(getGameDescription(app))
            .setPosterArtUri(getPosterArtUri(computer, app))
            .setPosterArtAspectRatio(getAspectRatio())
            .setIntent(ServerHelper.createAppShortcutIntent(activity, computer, app))
            .setInternalProviderId("" + app.id.toString())

        val programId = getWatchNextPrograms(activity, "" + app.id)
        if (programId != null) {
            activity.contentResolver.update(
                TvContractCompat.buildWatchNextProgramUri(programId.id),
                builder.build().toContentValues(), null, null
            )
            return
        }
        try {
            activity.contentResolver
                .insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    builder.build().toContentValues()
                )
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            return
        }
    }

    // Helper function to get the aspect ratio
    @SuppressLint("RestrictedApi")
    private fun getAspectRatio(): Int {
        return when {
            hasPoster -> TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9
            else -> TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_MOVIE_POSTER
        }
    }

    // Helper function to get the poster art URI
    private fun getPosterArtUri(computer: ComputerDetails, app: NvApp): Uri? {

        val db = GameDataBaseManager.getInstance(activity)

        // Check if database exists and is not empty
        if (doesDatabaseExist(activity) && !db!!.isDatabaseEmpty()) {
            val backgroundUrl = db.getAppData(app.id.toString()).background
            if (backgroundUrl != null) {
                hasPoster = true
                return backgroundUrl.toUri()
            } else {
                hasPoster = true
                return PosterContentProvider.resourceUri(R.drawable.igdb_cover_art, activity)
            }
        }

        // Fallback to default or generated poster
        return computer.uuid?.let {
            PosterContentProvider.createBoxArtUri(it, "" + app.id.toString())
        }
    }

    // Helper function to get the game description
    private fun getGameDescription(app: NvApp): String {

        val prefs = PreferenceKeys.fromPreferences(activity)

        val db = GameDataBaseManager.getInstance(activity)

        // Check if the database exists and is not empty
        if (!doesDatabaseExist(activity) || db!!.isDatabaseEmpty()) {
            return activity.resources.getString(R.string.No_Details)
        }

        val appData = db.getAppData(app.id.toString())

        // Return "No Details" if no description is found
        val fullDescription = appData.description
            ?: return activity.resources.getString(R.string.No_Details)

        var shortDescription = fullDescription.take(150) + " ....."

        // Translate if necessary and model is available
        if (prefs.language != "default" && prefs.language != "en" && prefs.mlDownloaded) {
            val options = prefs.language.let {
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(it)
                    .build()
            }
            val translator = options.let { Translation.getClient(it) }
            translator.translate(shortDescription)
                .addOnSuccessListener { translatedText ->
                    shortDescription = translatedText
                }
                .addOnFailureListener { exception ->
                    LimeLog.info("Translation failed: " + exception.message)
                    shortDescription = fullDescription
                }
        } else {
            shortDescription = fullDescription
        }
        return shortDescription
    }

    // Helper function to check if the database exists
    private fun doesDatabaseExist(context: Context): Boolean {
        val dbFile: File = context.getDatabasePath(GameDataBaseManager.DB_NAME)
        return dbFile.exists()
    }

    // Helper function to delete a channel
    fun deleteChannel(computer: ComputerDetails) {
        if (isAndroidTV or !isGoogleTV or !isGoogleTVStreamer) return

        val channelId = getChannelId(computer.uuid!!) ?: return
        activity.contentResolver.delete(TvContractCompat.buildChannelUri(channelId), null, null)
    }

    // Helper function to delete a program
    fun deleteProgram(computer: ComputerDetails, app: NvApp) {
        if (!isAndroidTV or isGoogleTV or isGoogleTVStreamer) return

        val channelId = getChannelId(computer.uuid!!) ?: return
        val programId = getProgramId(channelId, "" + app.id.toString())
            ?: return

        // Delete the program
        activity.contentResolver.delete(
            TvContractCompat.buildPreviewProgramUri(programId),
            null,
            null
        )

        // Show toast notification (only for non-Google TV devices)
        if (!isGoogleTV or !isGoogleTVStreamer) {
            Starlight.applicationScope.launch(Dispatchers.Main) {
                val message = app.appName + activity.getString(R.string.removed_from_favorites)
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Helper function to delete a watch next program
    @SuppressLint("RestrictedApi")
    fun deleteWatchNext(app: NvApp) {
        if (!isAndroidTV or isGoogleTV or isGoogleTVStreamer) return

        val programId = getWatchNextPrograms(activity, "" + app.id.toString()) ?: return
        activity.contentResolver.delete(
            TvContractCompat.buildWatchNextProgramUri(programId.id), null,
            null
        )
    }

    // Helper function to delete a watch next program
    @SuppressLint("RestrictedApi")
    fun deleteWatchNextById(appId: Int) {
        if (!isAndroidTV or isGoogleTV or isGoogleTVStreamer) return

        val programId = getWatchNextPrograms(activity, "" + appId.toString()) ?: return
        activity.contentResolver.delete(
            TvContractCompat.buildWatchNextProgramUri(programId.id), null,
            null
        )
    }

    // Helper function to get the channel ID
    private fun getChannelId(computerUuid: String): Long? {
        activity.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(
                TvContractCompat.Channels._ID,
                TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
            ),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor == null || cursor.count == 0) {
                return null
            }
            while (cursor.moveToNext()) {
                val internalProviderId = cursor.getString(INTERNAL_PROVIDER_ID_INDEX)
                if (computerUuid == internalProviderId) {
                    return cursor.getLong(ID_INDEX)
                }
            }
            return null
        }
    }

    // Helper function to get the watch next program
    @SuppressLint("RestrictedApi")
    fun getWatchNextPrograms(context: Context, appId: String): WatchNextProgram? {
        context.contentResolver
            .query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                WATCH_NEXT_MAP_PROJECTION,
                null, null, null, null
            )
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        val watchNextInternalId =
                            cursor.getString(COLUMN_WATCH_NEXT_INTERNAL_PROVIDER_ID_INDEX)
                        if (appId == watchNextInternalId) {
                            val watchNextProgram = WatchNextProgram.fromCursor(cursor)
                            val browsable = cursor.getInt(PROGRAM_BROWSABLE_INDEX)
                            if (browsable != 0) {
                                return watchNextProgram
                            } else {
                                val countDeleted = context.contentResolver.delete(
                                    TvContractCompat.buildWatchNextProgramUri(watchNextProgram.id),
                                    null,
                                    null
                                )
                                if (countDeleted > 0) {
                                    LimeLog.info("Watch next program has been deleted")
                                } else {
                                    LimeLog.warning("Watch next program has not been deleted")
                                }
                            }
                        }
                    } while (cursor.moveToNext())
                }
            }
        return null
    }

    // Helper function to get the program ID
    @SuppressLint("RestrictedApi")
    private fun getProgramId(channelId: Long, appId: String): Long? {
        activity.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            arrayOf(
                TvContractCompat.PreviewPrograms._ID,
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContractCompat.PreviewPrograms.COLUMN_BROWSABLE
            ),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor == null || cursor.count == 0) {
                return null
            }
            while (cursor.moveToNext()) {
                val internalProviderId = cursor.getString(INTERNAL_PROVIDER_ID_INDEX)
                if (appId == internalProviderId) {
                    val id = cursor.getLong(ID_INDEX)
                    val browsable = cursor.getInt(PROGRAM_BROWSABLE_INDEX)
                    if (browsable != 0) {
                        return id
                    } else {
                        val countDeleted = activity.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(id),
                            null,
                            null
                        )
                        if (countDeleted > 0) {
                            LimeLog.info("Preview program has been deleted")
                        } else {
                            LimeLog.warning("Preview program has not been deleted")
                        }
                    }
                }
            }
        }

        return null
    }

    // Helper function to check if the device is a Google TV
    private val isGoogleTV: Boolean
        get() = Starlight.getDeviceName() == "Google Chromecast"

    // Helper function to check if the device is a Google TV Streamer
    private val isGoogleTVStreamer: Boolean
        get() = Starlight.getDeviceName() == "Google TV Streamer"

    // Helper function to check if the device is an Android TV
    private val isAndroidTV: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    companion object {

        private const val COLUMN_WATCH_NEXT_INTERNAL_PROVIDER_ID_INDEX = 1
        private const val INTERNAL_PROVIDER_ID_INDEX = 1
        private const val PROGRAM_BROWSABLE_INDEX = 2
        private const val ID_INDEX = 0

        // Helper function to set the watch next projection
        @SuppressLint("RestrictedApi")
        private val WATCH_NEXT_MAP_PROJECTION =
            arrayOf(
                BaseColumns._ID,
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE
            )

        private var hasPoster: Boolean = false

    }
}