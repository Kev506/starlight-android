package com.starlight.games

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.starlight.models.DbVideoItem


class GameDataBaseManager(context: Context?, factory: SQLiteDatabase.CursorFactory?) :
    SQLiteOpenHelper(context, DB_NAME, factory, DB_VERSION + 1) {

    override fun onCreate(db: SQLiteDatabase) {
        val query = """
        CREATE TABLE $GAME_TABLE_NAME (
            $GAME_TABLE_ID TEXT PRIMARY KEY,
            $GAME_NAME_COLUMN_NAME TEXT,
            $GAME_ESRB_COLUMN_NAME TEXT,
            $GAME_IMAGE_COLUMN_NAME TEXT,
            $GAME_DETAILS_COLUMN_NAME TEXT,
            $GAME_META_NUMBER_COLUMN_NAME TEXT,
            $GAME_DEVELOPERS_COLUMN_NAME TEXT,
            $GAME_YEAR_COLUMN_NAME TEXT,
            $GAME_COVER_ART_COLUMN_NAME TEXT,
            $GAME_FALLBACK_COVER_ART_COLUMN_NAME BLOB,
            $GAME_VIDEO_COLUMN_NAME TEXT,
            $GAME_SLUG_COLUMN_NAME TEXT,
            $GAME_VIDEOS_COLUMN_NAME TEXT
        )
    """.trimIndent()
        db.execSQL(query)
    }

    // Helper function to check if a game with the given ID exists
    fun queryDataExists(id: String): Boolean {
        val db = this.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $GAME_TABLE_NAME WHERE $GAME_TABLE_ID = ?", arrayOf(id))
            .use { cursor ->
                cursor.moveToFirst()
                return cursor.getInt(0) > 0
            }
    }

    fun getVideoListFromCursor(cursor: Cursor, columnName: String): MutableList<DbVideoItem> {
        val columnIndex = cursor.getColumnIndex(columnName)
        if (columnIndex < 0 || cursor.isNull(columnIndex)) {
            return mutableListOf()
        }

        val jsonString = cursor.getString(columnIndex)
        if (jsonString.isNullOrEmpty() || jsonString.trim() == "null") {
            return mutableListOf()
        }

        return try {
            val gson = Gson()
            val listType = object : TypeToken<MutableList<DbVideoItem>>() {}.type
            val result: MutableList<DbVideoItem>? = gson.fromJson(jsonString, listType)
            result ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun fetchVideosById(rowId: String): MutableList<DbVideoItem> {
        val db = this.readableDatabase
        val query = "SELECT $GAME_VIDEOS_COLUMN_NAME FROM $GAME_TABLE_NAME WHERE id = ?"

        db.rawQuery(query, arrayOf(rowId)).use { cursor ->
            if (cursor.moveToFirst()) {
                if (cursor.isNull(0)) {
                    return mutableListOf()
                }
                return getVideoListFromCursor(cursor, GAME_VIDEOS_COLUMN_NAME)
            }
        }

        return mutableListOf()
    }

    fun queryImageExistsForId(rowId: String): Boolean {
        val db = this.readableDatabase
        val query = "SELECT $GAME_COVER_ART_COLUMN_NAME FROM $GAME_TABLE_NAME WHERE id = ?"

        db.rawQuery(query, arrayOf(rowId)).use { cursor ->
            if (cursor.moveToFirst()) {
                if (cursor.isNull(0)) {
                    return false
                }
                val imageValue = cursor.getString(0)
                return imageValue.trim().isNotEmpty()
            }
            return false
        }
    }

    // Helper function to get game data by its ID
    @SuppressLint("Range")
    fun getAppData(id: String): GameDataBaseDetails {
        val db = this.readableDatabase
        val app = GameDataBaseDetails()
        db.query(
            GAME_TABLE_NAME,
            null,
            "$GAME_TABLE_ID=?", arrayOf(id), null, null, null
        ).use { cursor ->
            val idIndex = cursor.getColumnIndex(GAME_TABLE_ID)
            val titleIndex = cursor.getColumnIndex(GAME_NAME_COLUMN_NAME)
            val esrbIndex = cursor.getColumnIndex(GAME_ESRB_COLUMN_NAME)
            val imageIndex = cursor.getColumnIndex(GAME_IMAGE_COLUMN_NAME)
            val cursorIndex = cursor.getColumnIndex(GAME_DETAILS_COLUMN_NAME)
            val metaIndex = cursor.getColumnIndex(GAME_META_NUMBER_COLUMN_NAME)
            val developersIndex = cursor.getColumnIndex(GAME_DEVELOPERS_COLUMN_NAME)
            val cursorYearIndex = cursor.getColumnIndex(GAME_YEAR_COLUMN_NAME)
            val coverArtIndex = cursor.getColumnIndex(GAME_COVER_ART_COLUMN_NAME)
            val fallBackCoverArtIndex = cursor.getColumnIndex(GAME_FALLBACK_COVER_ART_COLUMN_NAME)
            val videoIndex = cursor.getColumnIndex(GAME_VIDEO_COLUMN_NAME)
            val slugIndex = cursor.getColumnIndex(GAME_SLUG_COLUMN_NAME)
            val videosIndex = cursor.getColumnIndex(GAME_VIDEOS_COLUMN_NAME)

            if (cursor.moveToFirst()) {
                do {
                    app.id = cursor.getInt(idIndex)
                    app.title = cursor.getString(titleIndex)
                    app.esrb = cursor.getString(esrbIndex)
                    app.background = cursor.getString(imageIndex)
                    app.description = cursor.getString(cursorIndex)
                    app.metaNumber = cursor.getString(metaIndex)
                    app.developers = cursor.getString(developersIndex)
                    app.released = cursor.getString(cursorYearIndex)
                    app.coverArt = cursor.getString(coverArtIndex)
                    app.video = cursor.getString(videoIndex)
                    app.videos = cursor.getString(videosIndex)
                    app.slug = cursor.getString(slugIndex)
                    val imgByte = cursor.getBlob(fallBackCoverArtIndex)
                    if (imgByte != null) {
                        app.fallbackCoverArt = getByteArrayAsBitmap(imgByte)
                    }
                } while (cursor.moveToNext())
            }
        }
        return app
    }

    // Helper function to get the number of games in the database
    fun getGamesCount(): Long {
        val db = this.readableDatabase
        return DatabaseUtils.queryNumEntries(db, GAME_TABLE_NAME)
    }

    // Helper function to delete the database
    fun deleteGameData(context: Context, db: SQLiteDatabase) {
        if (db.isOpen) {
            db.close()
        }
        context.deleteDatabase(DB_NAME)
    }

    // Helper function to delete a game by its name
    fun deleteGameEntryByName(appName: String) {
        val db = this.writableDatabase
        db.delete(GAME_TABLE_NAME, "$GAME_NAME_COLUMN_NAME = ?", arrayOf(appName))
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $GAME_TABLE_NAME")
        onCreate(db)
    }

    // Convert ByteArray to Bitmap (for loading)
    private fun getByteArrayAsBitmap(bytes: ByteArray): Bitmap? {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    companion object {
        private const val DB_VERSION = 1
        const val DB_NAME = "game.db"
        const val GAME_TABLE_NAME = "Games"
        const val GAME_TABLE_ID = "id"
        const val GAME_NAME_COLUMN_NAME = "Title"
        const val GAME_ESRB_COLUMN_NAME = "Esrb"
        const val GAME_IMAGE_COLUMN_NAME = "Background"
        const val GAME_DETAILS_COLUMN_NAME = "Description"
        const val GAME_META_NUMBER_COLUMN_NAME = "Meta"
        const val GAME_DEVELOPERS_COLUMN_NAME = "Developers"
        const val GAME_YEAR_COLUMN_NAME = "Released"
        const val GAME_COVER_ART_COLUMN_NAME = "CoverArt"
        const val GAME_FALLBACK_COVER_ART_COLUMN_NAME = "FallbackCoverArt"
        const val GAME_VIDEO_COLUMN_NAME = "Video"
        const val GAME_VIDEOS_COLUMN_NAME = "Videos"
        const val GAME_SLUG_COLUMN_NAME = "Slug"

        @Synchronized
        fun getInstance(context: Context): GameDataBaseManager? {
            if (mInstance == null) {
                mInstance = GameDataBaseManager(context.applicationContext, null)
            }
            return mInstance
        }

        private var mInstance: GameDataBaseManager? = null
    }

    // Helper function to check if the database is empty
    fun isDatabaseEmpty(): Boolean {
        val db = this.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $GAME_TABLE_NAME", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0) == 0
        }
    }

    // Helper function to check if a game with the given name already exists
    fun queryAllGames(): List<GameDataBaseDetails> {
        val db = this.readableDatabase
        val games = mutableListOf<GameDataBaseDetails>()

        val cursor = db.query(
            GAME_TABLE_NAME,
            arrayOf(GAME_NAME_COLUMN_NAME), // Fetch only app names for this query
            null,
            null,
            null,
            null,
            null
        )

        with(cursor) {
            while (moveToNext()) {
                val game = GameDataBaseDetails(
                    title = getString(getColumnIndexOrThrow(GAME_NAME_COLUMN_NAME)),
                )
                games.add(game)
            }
            close()
        }

        return games
    }
}
