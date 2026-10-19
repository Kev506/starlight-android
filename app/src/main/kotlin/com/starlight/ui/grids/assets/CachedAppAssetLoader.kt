package com.starlight.ui.grids.assets

import android.graphics.Bitmap
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.utils.LimeLog
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CachedAppAssetLoader(
    private val computer: ComputerDetails,
    private val networkLoader: NetworkAssetLoader, private val memoryLoader: MemoryAssetLoader,
    private val diskLoader: DiskAssetLoader, private val noAppImageBitmap: Bitmap
) {

    @OptIn(DelicateCoroutinesApi::class)
    private val cacheDispatcher = newSingleThreadContext("Cache-Pre-loader")

    // Helper method to destroy the loader
    fun destroy() {
        cacheDispatcher.close()
        LimeLog.info("Cache-Pre-loader destroyed")
    }

    // Helper method to free the memory cache
    fun freeCacheMemory() {
        memoryLoader.clearCache()
    }

    // Helper method to load the bitmap from the network
    private suspend fun doNetworkAssetLoad(tuple: LoaderTuple): ScaledBitmap? {
        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            // Since this is a suspend function, we can check `isActive` directly.
            if (!applicationScope.isActive) {
                // The scope has been canceled, so stop trying.
                return null
            }

            val inputStream = networkLoader.getBitmapStream(tuple)
            inputStream?.use {
                diskLoader.populateCacheWithStream(tuple, it)

                // Now that it's on disk, load it. This will return the ScaledBitmap.
                return diskLoader.loadBitmapFromCache(tuple)
            }

            // If we got here, the stream was null. Let's wait before retrying.
            if (attempt < maxRetries) {
                val delayMillis = (1000 + Math.random() * 500).toLong()
                try {
                    delay(delayMillis.milliseconds)
                } catch (e: Exception) {
                    // This will be a CancellationException if the scope is canceled during delay
                    LimeLog.warning("NetworkAssetLoader: Interrupted while waiting to retry: $e")
                    return null // Exit if canceled
                }
            }
        }

        // If all retries fail, return null.
        return null
    }

    // Helper method to queue a cache load
    fun queueCacheLoad(app: NvApp) {
        val loaderTuple = LoaderTuple(computer, app)

        if (memoryLoader.loadBitmapFromCache(loaderTuple) != null) {
            // Already in memory, no need to load
            return
        }

        applicationScope.launch (cacheDispatcher) {
            if (diskLoader.checkCacheExists(loaderTuple)) {
                return@launch
            }

            doNetworkAssetLoad(loaderTuple)
        }
    }

    // Get the cover art from cache
    suspend fun getGameImage(app: NvApp): Bitmap = withContext(Dispatchers.IO) {
        val tupleToLoad = LoaderTuple(computer, app)

        val memBmp = memoryLoader.loadBitmapFromCache(tupleToLoad)
        if (memBmp != null) {
            return@withContext memBmp.bitmap
        }

        val diskBmp = diskLoader.loadBitmapFromCache(tupleToLoad)
        if (diskBmp != null) {
            // Populate memory cache for next time
            memoryLoader.populateCache(tupleToLoad, diskBmp)
            return@withContext diskBmp.bitmap
        }

        val netBmp = doNetworkAssetLoad(tupleToLoad)
        if (netBmp != null) {
            memoryLoader.populateCache(tupleToLoad, netBmp)
            return@withContext netBmp.bitmap
        }

        LimeLog.info("Not found, returning placeholder")
        return@withContext noAppImageBitmap
    }


    data class LoaderTuple(val computer: ComputerDetails, val app: NvApp)
}