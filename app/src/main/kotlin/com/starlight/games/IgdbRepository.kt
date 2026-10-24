package com.starlight.games

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import com.google.gson.Gson
import com.starlight.R
import com.starlight.games.GameDataBaseManager.Companion.GAME_VIDEOS_COLUMN_NAME
import com.starlight.games.api.RetrofitClient
import com.starlight.models.IgdbGame
import com.starlight.models.IgdbVideo
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.ui.grids.assets.CachedAppAssetLoader
import com.starlight.ui.grids.assets.DiskAssetLoader
import com.starlight.ui.grids.assets.MemoryAssetLoader
import com.starlight.ui.grids.assets.NetworkAssetLoader
import com.starlight.utils.Constants.BASE_COVER_URL
import com.starlight.utils.Constants.BASE_IMAGE_URL
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.NativeKeyLoader
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale.getDefault
import kotlin.math.ceil

/**
 * Created by Kevin Andrews on 21/08/2026.
 */

class IgdbRepository(
    private val activity: ComponentActivity,
    computer: ComputerDetails,
    uuidString: String
) {

    private var loader: CachedAppAssetLoader? = null

    init {
        dbHandler = GameDataBaseManager.getInstance(activity.applicationContext)

        if (loader != null) {
            cancelQueuedOperations()
        }

        loader = CachedAppAssetLoader(
            computer,
            NetworkAssetLoader(activity, uuidString),
            MemoryAssetLoader(),
            DiskAssetLoader(activity),
            BitmapFactory.decodeResource(
                activity.resources,
                R.drawable.no_app_image
            )
        )
    }

    // Check for valid token or renew
    fun syncGameInfo(app: NvApp, igdbConfig: IgdbConfig) {
        applicationScope.launch(Dispatchers.Main) {
            val tokenManager = TwitchTokenManager(activity.applicationContext)
            var validToken = tokenManager.getValidToken()

            if (validToken == null) {

                if (!igdbConfig.clientId.isNullOrEmpty() && !igdbConfig.clientSecret.isNullOrEmpty()) {
                    val newTokenObj = TwitchAuthRepository.fetchNewToken(igdbConfig.clientId, igdbConfig.clientSecret)
                    if (newTokenObj != null) {
                        tokenManager.saveToken(newTokenObj.accessToken, newTokenObj.expiresIn)
                        validToken = newTokenObj.accessToken
                    }
                }
            }

            if (validToken != null && !igdbConfig.clientId.isNullOrEmpty()) {
                fetchGameFromIgdb(app, igdbConfig.clientId, validToken)
            } else {
                saveDefaultGameInfo(app)
            }
        }
    }

    private fun saveDefaultGameInfo(app: NvApp) {
        val values = ContentValues().apply {
            put(GameDataBaseManager.GAME_TABLE_ID, app.id)
            app.appName?.let {
                put(GameDataBaseManager.GAME_NAME_COLUMN_NAME, capitalizeSentences(sanitizeAppNames(it)))
            }
            put(GameDataBaseManager.GAME_DETAILS_COLUMN_NAME, activity.getString(R.string.No_Details))
        }
        insertOrUpdateGame(values)
    }

    // Fetch game data
    private suspend fun fetchGameFromIgdb(
        app: NvApp,
        clientId: String,
        accessToken: String
    ) {
        val searchNameQuery = app.appName ?: ""
        val values = ContentValues().apply {
            put(GameDataBaseManager.GAME_TABLE_ID, app.id)
            app.appName?.let {
                put(GameDataBaseManager.GAME_NAME_COLUMN_NAME, capitalizeSentences(sanitizeAppNames(it)))
            }
        }

        val escapedSearchQuery = searchNameQuery.replace("\"", "\\\"")
        val apicalypseBody = """
            where (name ~ "$escapedSearchQuery" | alternative_names.name ~ "$escapedSearchQuery") & version_parent = null;
            fields name, slug, summary, first_release_date, total_rating, total_rating_count, 
            cover.image_id, screenshots.image_id, artworks.image_id, 
            involved_companies.company.name, involved_companies.publisher, videos.video_id, videos.name,
            age_ratings.organization, age_ratings.rating_category, age_ratings.rating;
            sort popularity desc;
            limit 1;
        """.trimIndent()

        try {
            val body = apicalypseBody.toRequestBody("text/plain".toMediaType())
            val response = RetrofitClient.igdbApi.searchGames(clientId, "Bearer $accessToken", body)
            if (response.isNotEmpty()) {
                val igdbGame = response[0]
                mapIgdbGameToValues(igdbGame, values)
            } else {
                warning("IGDB: No titles matched search textual query parameters: $searchNameQuery")
                values.put(GameDataBaseManager.GAME_DETAILS_COLUMN_NAME, activity.getString(R.string.No_Details))
            }
        } catch (e: Exception) {
            warning("IGDB: Request failed ${e.message}")
            values.put(GameDataBaseManager.GAME_DETAILS_COLUMN_NAME, activity.getString(R.string.No_Details))
        } finally {
            insertOrUpdateGame(values)
            fetchAndStoreCoverArt(app, values)
        }
    }

    private fun mapIgdbGameToValues(game: IgdbGame, values: ContentValues) {
        values.put(GameDataBaseManager.GAME_DETAILS_COLUMN_NAME, game.summary ?: "")

        game.totalRating?.let { rawRating ->
            if (rawRating > 0.0) {
                values.put(GameDataBaseManager.GAME_META_NUMBER_COLUMN_NAME, ceil(rawRating).toInt().toString())
            } else {
                values.put(GameDataBaseManager.GAME_META_NUMBER_COLUMN_NAME, "")
            }
        }

        game.firstReleaseDate?.let { epoch ->
            if (epoch > 0) {
                val date = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
                val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                values.put(GameDataBaseManager.GAME_YEAR_COLUMN_NAME, date.format(formatter))
            }
        }

        if (game.slug.isNotEmpty()) {
            values.put(GameDataBaseManager.GAME_SLUG_COLUMN_NAME, game.slug)
        } else {
            values.put(GameDataBaseManager.GAME_SLUG_COLUMN_NAME, "")
        }

        val gson = Gson()
        val jsonString: String = gson.toJson(cleanVideoIds(game))
        values.put(GAME_VIDEOS_COLUMN_NAME, jsonString)

        val videoUrl = determineVideoUrl(game)
        values.put(GameDataBaseManager.GAME_VIDEO_COLUMN_NAME, videoUrl)

        val esrbRatingString = getEsrbRatingFromGame(game)
        values.put(GameDataBaseManager.GAME_ESRB_COLUMN_NAME, mapEsrbToRawgFormat(esrbRatingString))

        val imageUrl = determineImageUrl(game)
        values.put(GameDataBaseManager.GAME_IMAGE_COLUMN_NAME, imageUrl)

        val coverUrl = determineCoverUrl(game)
        values.put(GameDataBaseManager.GAME_COVER_ART_COLUMN_NAME, coverUrl)

        val publisher = game.involvedCompanies
            ?.firstOrNull { it.publisher }
            ?.company?.name
        
        publisher?.let {
            values.put(GameDataBaseManager.GAME_DEVELOPERS_COLUMN_NAME, "Publisher: ${capitalizeSentences(it)}")
        }
    }

    private fun getEsrbRatingFromGame(game: IgdbGame): String {
        var esrbRatingString = "Not Rated"

        game.ageRatings?.forEach { rating ->
            val category = rating.organization
            val ratingValue = rating.ratingCategory

            when (category) {
                1 -> {
                    esrbRatingString = getEsrbRatingName(ratingValue)
                    info("IGDB: Success! Match Found. Category: $category | Value ID: $ratingValue -> Name: $esrbRatingString")
                }
                2 -> {
                    esrbRatingString = getEsrbRatingName(ratingValue)
                    info("IGDB: Success! Match Found. Category: $category | Value ID: $ratingValue -> Name: $esrbRatingString")
                    return@getEsrbRatingFromGame esrbRatingString
                }
                else -> {
                    info("IGDB: Skipping non-PEGI-ESRB node. Category type: $category")
                }
            }
        }
        return esrbRatingString
    }

    private fun determineImageUrl(game: IgdbGame): String {
        val baseImageUrl = BASE_IMAGE_URL
        val imageId = game.artworks?.firstOrNull()?.imageId
            ?: game.screenshots?.firstOrNull()?.imageId
            ?: game.cover?.imageId
        
        return if (imageId != null) "$baseImageUrl$imageId.jpg" else ""
    }

    private fun determineCoverUrl(game: IgdbGame): String {
        val baseCoverUrl = BASE_COVER_URL
        val imageId = game.cover?.imageId

        return if (imageId != null) "$baseCoverUrl$imageId.jpg" else ""
    }

    fun cleanVideoIds(game: IgdbGame): List<IgdbVideo>? {
        if (game.videos.isNullOrEmpty()) return null

        val promotionalKeywords = listOf(
            "developer diary", "dev diary", "making of", "interview", "behind the scenes",
            "promo", "commercial", "tv spot", "teaser", "music video", "soundtrack",
            "cinematic trailer", "accolades", "review trailer", "delta company"
        )

        val cleanVideos = game.videos.filter { video ->
            promotionalKeywords.none { promoKeyword ->
                video.videoName.contains(promoKeyword, ignoreCase = true)
            }
        }

        val candidates = cleanVideos.ifEmpty { game.videos }
        val enrichedCandidates = candidates.map { video ->
            val trimmedOriginalName = video.videoName.trim()

            val descriptiveName = when {
                trimmedOriginalName.equals("Trailer", ignoreCase = true) || trimmedOriginalName.isBlank() -> {
                    "${game.name} - Trailer"
                }
                trimmedOriginalName.startsWith(game.name, ignoreCase = true) -> {
                    trimmedOriginalName
                }
                else -> {
                    "${game.name} - $trimmedOriginalName"
                }
            }

            IgdbVideo(
                id = video.id,
                videoId = video.videoId,
                videoName = descriptiveName
            )
        }

        val resultList = mutableListOf<IgdbVideo>()

        val highestPriority = enrichedCandidates.find {
            it.videoName.contains("gameplay", ignoreCase = true)
        }
        if (highestPriority != null) resultList.add(highestPriority)

        val mediumPriority = enrichedCandidates.find {
            (it.videoName.contains("launch", ignoreCase = true) ||
                    it.videoName.contains("official", ignoreCase = true) ||
                    it.videoName.contains("reveal", ignoreCase = true)) &&
                    it.videoId != highestPriority?.videoId
        }
        if (mediumPriority != null) resultList.add(mediumPriority)

        enrichedCandidates.forEach { video ->
            if (resultList.none { it.videoId == video.videoId }) {
                resultList.add(video)
            }
        }

        return resultList
    }

    fun determineVideoUrl(game: IgdbGame): String? {
        if (game.videos.isNullOrEmpty()) return null

        val promotionalKeywords = listOf(
            "developer diary", "dev diary", "making of", "interview", "behind the scenes",
            "promo", "commercial", "tv spot", "teaser", "music video", "soundtrack",
            "cinematic trailer", "accolades", "review trailer", "delta company"
        )

        val cleanVideos = game.videos.filter { video ->
            promotionalKeywords.none { promoKeyword ->
                video.videoName.contains(promoKeyword, ignoreCase = true)
            }
        }

        val candidates = cleanVideos.ifEmpty { game.videos }
        val highestPriority = candidates.find {
            it.videoName.contains("gameplay trailer", ignoreCase = true) ||
                    it.videoName.contains("gameplay reveal", ignoreCase = true)
        }

        if (highestPriority != null) return highestPriority.videoId

        val mediumPriority = candidates.find {
            it.videoName.contains("launch trailer", ignoreCase = true) ||
                    it.videoName.contains("official trailer", ignoreCase = true) ||
                    it.videoName.contains("reveal trailer", ignoreCase = true)
        }
        if (mediumPriority != null) return mediumPriority.videoId

        return candidates.firstOrNull()?.videoId
    }

    // Map UI to IGDB results
    fun mapEsrbToRawgFormat(igdbRating: String): String {
        return when (igdbRating) {
            "Everyone (E)", "Everyone" -> "Everyone"
            "Everyone 10+ (E10)" -> "Everyone 10+"
            "Teen (T)", "Teen" -> "Teen"
            "Twelve Years Plus (Twelve)" -> "Teen"
            "Sixteen Years Plus (Sixteen)" -> "Teen"
            "Eighteen Years Plus (Eighteen)" -> "Mature"
            "Mature 17+ (M)", "Mature" -> "Mature"
            "Adults Only 18+ (AO)" -> "Adults Only"
            "Rating Pending (RP)" -> "Rating Pending"
            "Not Rated" -> "Rating Pending"
            else -> "Rating Pending"
        }
    }

    // Get ratings from IGDB
    fun getEsrbRatingName(ratingEnum: Int): String {
        return when (ratingEnum) {
            1 -> "Three Years Plus (Three)"
            2 -> "Seven Years Plus (Seven)"
            3 -> "Twelve Years Plus (Twelve)"
            4 -> "Sixteen Years Plus (Sixteen)"
            5 -> "Eighteen Years Plus (Eighteen)"
            7 -> "Early Childhood (EC)"
            8 -> "Everyone (E)"
            9 -> "Everyone 10+ (E10)"
            10 -> "Teen (T)"
            11 -> "Mature 17+ (M)"
            12 -> "Adults Only 18+ (AO)"
            6 -> "Rating Pending (RP)"
            else -> "Not Rated"
        }
    }

    // Convert Bitmap to ByteArray (for saving)
    private fun getBitmapAsByteArray(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }

    // Insert or update cover art
    private fun fetchAndStoreCoverArt(app: NvApp, values: ContentValues) {

        val isImageStored = dbHandler?.queryImageExistsForId(app.id.toString())

        if (isImageStored == false) {
            applicationScope.launch {
                try {
                    val imageBitmap = loader!!.getGameImage(app)
                    val imageBytes = getBitmapAsByteArray(imageBitmap)
                    values.put(GameDataBaseManager.GAME_FALLBACK_COVER_ART_COLUMN_NAME, imageBytes)
                } catch (e: Exception) {
                    warning(TAG + " Failed to load cover art for ${app.appName}: ${e.message}")
                } finally {
                    insertOrUpdateGame(values)
                }
            }
        }
    }

    // Cancel queued operations
    private fun cancelQueuedOperations() {
        loader?.freeCacheMemory()
        loader?.destroy()
    }

    // Sanitize app name for database
    private fun sanitizeAppNames(appName: String): String {
        return appName
            .replace("\\s+\\(.*\\)".toRegex(), "")
            .replace("[^\\x00-\\x7F]".toRegex(), "")
            .lowercase(getDefault())
    }

    // Insert or update game in the database
    private fun insertOrUpdateGame(values: ContentValues) {
        dbHandler?.writableDatabase?.insertWithOnConflict(
            GameDataBaseManager.GAME_TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // Capitalize sentences
    private fun capitalizeSentences(tagName: String?): String {
        if (tagName.isNullOrEmpty()) return ""

        val acronyms = setOf("GTA", "FPS", "RPG", "MMO", "DLC", "GOTY", "COD", "WWE", "UFC", "NCAA")
        val romanNumeralRegex =
            Regex("^(w{0,2})(i{1,3}|iv|v|vi{1,3}|ix|x{1,3})$", RegexOption.IGNORE_CASE)

        val splits = tagName.split(" ")
        val sb = StringBuilder()

        for (i in splits.indices) {
            val eachWord = splits[i]
            if (eachWord.isEmpty()) continue

            if (i > 0) {
                sb.append(" ")
            }

            val cleanWord = eachWord.replace(Regex("[^a-zA-Z0-9]"), "")
            val upperCleanWord = cleanWord.uppercase(getDefault())

            val transformedWord = when {
                acronyms.contains(upperCleanWord) -> {
                    eachWord.uppercase(getDefault())
                }

                romanNumeralRegex.matches(cleanWord) -> {
                    eachWord.uppercase(getDefault())
                }

                cleanWord.isNotEmpty() && cleanWord.all { it.isUpperCase() } -> {
                    eachWord
                }

                else -> {
                    eachWord.take(1).uppercase(getDefault()) + eachWord.drop(1)
                        .lowercase(getDefault())
                }
            }

            sb.append(transformedWord)
        }

        return sb.toString()
    }

    // Fetch encrypted payload from server
    suspend fun fetchAndConnectIgdb(app: NvApp): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val okHttpClient = OkHttpClient()
                info("Fetching IGDB config from server...")

                val request = Request.Builder()
                    .url("https://your-server.com/igdb-data.config") // !!YOUR SERVER MUST GO HERE!!
                    .build()

                val encryptedPayload = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        severe("Failed to download config from server. HTTP ${response.code}")
                        return@use ""
                    }
                    response.body.string()
                }

                if (encryptedPayload.isEmpty()) {
                    warning("Downloaded IGDB config payload is empty.")
                    return@withContext false
                }

                info(
                    "IGDB config downloaded (${encryptedPayload.length} bytes). Content preview: '${
                        encryptedPayload.take(
                            40
                        ).replace("\n", " ")
                    }...'. Decrypting with JNI..."
                )

                NativeKeyLoader().decryptPayload(encryptedPayload) { decryptedChars ->
                    val configString = String(decryptedChars)
                    if (configString.isEmpty()) {
                        severe("JNI Decryption failed or returned empty string. Ensure raw payload matches JNI uint32 format.")
                        return@decryptPayload
                    }

                    val igdbConfig = parseIgdbConfig(configString)
                    info("JNI Decryption succeeded IGDB config")

                    // Connect to IGDB...
                    withContext(Dispatchers.IO) {
                        syncGameInfo(app, igdbConfig)
                    }
                }

                true
            } catch (e: Exception) {
                severe("Error in fetchAndConnectIgdb: ${e.javaClass.simpleName} - ${e.message}")
                false
            }
        }
    }

    fun parseIgdbConfig(configString: String): IgdbConfig {
        var clientId = ""
        var clientSecret = ""

        configString.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .forEach { line ->
                val parts = line.split("=", limit = 2)
                val key = parts[0].trim()
                val rawValue = parts[1].trim()

                when (key) {
                    "IGDB_CLIENT_ID" -> clientId = rawValue
                    "IGDB_CLIENT_SECRET" -> clientSecret = rawValue
                }
            }

        return IgdbConfig(clientId, clientSecret)
    }

    data class IgdbConfig(
        val clientId: String?,
        val clientSecret: String?
    )

    companion object {
        var dbHandler: GameDataBaseManager? = null
        const val TAG = "Games"
    }
}
