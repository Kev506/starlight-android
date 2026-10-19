package com.starlight.ui.content

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.starlight.ui.grids.assets.DiskAssetLoader
import com.starlight.utils.Constants
import java.io.FileNotFoundException

class PosterContentProvider : ContentProvider() {

    private lateinit var diskAssetLoader: DiskAssetLoader

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "This provider is only for read mode" }
        val match = sUriMatcher.match(uri)
        if (match != BOXART_URI_ID) {
            throw FileNotFoundException("Invalid URI: $uri")
        }
        return openBoxArtFile(uri)
    }

    @Throws(FileNotFoundException::class)
    private fun openBoxArtFile(uri: Uri): ParcelFileDescriptor {
        val segments = uri.pathSegments
        require(segments.size == 3) { "Invalid URI: $uri" }
        val appId = segments[APP_ID_PATH_INDEX].toIntOrNull()
            ?: throw FileNotFoundException("Invalid app ID")
        val uuid = segments[COMPUTER_UUID_PATH_INDEX]
        val file = diskAssetLoader.getFile(uuid, appId)
        if (file.exists()) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        throw FileNotFoundException("File not found for URI: $uri")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun getType(uri: Uri): String {
        return PNG_MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun onCreate(): Boolean {
        diskAssetLoader = DiskAssetLoader(context!!)
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        throw UnsupportedOperationException("This provider doesn't support query")
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        throw UnsupportedOperationException("This provider is support read only")
    }

    companion object {

        private const val AUTHORITY = "poster.${Constants.APPLICATION_ID}"

        const val PNG_MIME_TYPE = "image/png"
        private const val APP_ID_PATH_INDEX = 2
        private const val COMPUTER_UUID_PATH_INDEX = 1

        private val sUriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, BOXART_PATH, BOXART_URI_ID)
        }

        private const val BOXART_PATH = "boxart"
        private const val BOXART_URI_ID = 1

        fun resourceUri(resourceId: Int, context: Context): Uri {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.resources.getResourcePackageName(resourceId))
                .appendPath(context.resources.getResourceTypeName(resourceId))
                .appendPath(context.resources.getResourceEntryName(resourceId))
                .build()
        }

        fun createBoxArtUri(uuid: String, appId: String): Uri {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(BOXART_PATH)
                .appendPath(uuid)
                .appendPath(appId)
                .build()
        }
    }
}