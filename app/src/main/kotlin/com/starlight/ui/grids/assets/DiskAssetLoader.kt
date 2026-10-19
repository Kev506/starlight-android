package com.starlight.ui.grids.assets

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageDecoder
import androidx.core.graphics.decodeBitmap
import com.starlight.ui.helpers.CacheHelper
import com.starlight.utils.LimeLog
import java.io.File
import java.io.IOException
import java.io.InputStream

class DiskAssetLoader(context: Context) {

    private val isLowRamDevice: Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val cacheDir: File = context.cacheDir

    // Helper method to check cache existence
    fun checkCacheExists(loaderTuple: CachedAppAssetLoader.LoaderTuple): Boolean {
        return CacheHelper.cacheFileExists(
            cacheDir,
            "boxart",
            loaderTuple.computer.uuid,
            loaderTuple.app.id.toString() + ".png"
        )
    }

    // Helper method to load bitmap from cache
    fun loadBitmapFromCache(loaderTuple: CachedAppAssetLoader.LoaderTuple): ScaledBitmap? {
        val file = loaderTuple.app.id?.let { getFile(loaderTuple.computer.uuid, it) }

        if (!file!!.exists()) {
            return null
        }

        if (file.length() > MAX_ASSET_SIZE) {
            LimeLog.warning("DiskAssetLoader: Removing cached tuple exceeding size threshold: $loaderTuple")
            file.delete()
            return null
        }

        val scaledBitmap = ScaledBitmap()
        try {
            ImageDecoder.createSource(file).also { source ->
                scaledBitmap.bitmap = ImageDecoder.decodeBitmap( source,  headerDecodedListener) . also {
                    source.decodeBitmap { info, _ ->
                        scaledBitmap.originalWidth = info.size.width
                        scaledBitmap.originalHeight = info.size.height
                    }
                }
                return scaledBitmap
            }
        } catch (e: Exception) {
            LimeLog.warning("DiskAssetLoader: Exception loading image: $e")
        }
        return null
    }

    // Helper method to clear cache
    private val headerDecodedListener = ImageDecoder.OnHeaderDecodedListener { imageDecoder, _, _ ->
        imageDecoder.setTargetSize(STANDARD_ASSET_WIDTH, STANDARD_ASSET_HEIGHT)
        if (isLowRamDevice) {
        imageDecoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
    }
    }

    // Helper method to get file
    fun getFile(computerUuid: String?, appId: Int): File {
        return CacheHelper.openPath(false, cacheDir, "boxart", computerUuid, "$appId.png")
    }

    // Helper method to delete assets for computer
    fun deleteAssetsForComputer(computerUuid: String?) {
        val dir = CacheHelper.openPath(false, cacheDir, "boxart", computerUuid)
        val files = dir.listFiles()
        if (files != null) {
            for (f in files) {
                f.delete()
            }
        }
    }

    // Helper method to populate cache with stream
    fun populateCacheWithStream(loaderTuple: CachedAppAssetLoader.LoaderTuple, input: InputStream?) {
        var success = false
        try {
            CacheHelper.openCacheFileForOutput(
                cacheDir,
                "boxart",
                loaderTuple.computer.uuid,
                loaderTuple.app.id.toString() + ".png"
            ).use { out ->
                // Handle potential null input stream
                input?.let {
                    CacheHelper.writeInputStreamToOutputStream(it, out, MAX_ASSET_SIZE)
                    success = true
                } ?: run {
                    LimeLog.warning("DiskAssetLoader: Input stream is null for tuple: $loaderTuple")
                }
            }
        } catch (e: IOException) {
            LimeLog.warning("DiskAssetLoader: Error populating cache: $e")
        } finally {
            if (!success) {
                LimeLog.warning("DiskAssetLoader: Unable to populate cache with tuple: $loaderTuple")
                CacheHelper.deleteCacheFile(
                    cacheDir,
                    "boxart",
                    loaderTuple.computer.uuid,
                    loaderTuple.app.id.toString() + ".png"
                )
            }
        }
    }

    companion object {
        // 5 MB
        private const val MAX_ASSET_SIZE = (5 * 1024 * 1024).toLong()

        // Standard box art is 300x400
        private const val STANDARD_ASSET_WIDTH = 300
        private const val STANDARD_ASSET_HEIGHT = 400

    }
}