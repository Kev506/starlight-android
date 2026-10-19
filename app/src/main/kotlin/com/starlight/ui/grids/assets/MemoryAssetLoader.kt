package com.starlight.ui.grids.assets

import android.util.LruCache
import com.starlight.utils.LimeLog
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap

class MemoryAssetLoader {

    // Helper function to load bitmap from cache
    fun loadBitmapFromCache(loaderTuple: CachedAppAssetLoader.LoaderTuple): ScaledBitmap? {
        val key = constructKey(loaderTuple)
        var bitmap = memoryCache[key]
        if (bitmap != null) {
            LimeLog.info("LRU cache hit for tuple: $loaderTuple")
            return bitmap
        }
        val bitmapRef = evictionCache[key]
        if (bitmapRef != null) {
            bitmap = bitmapRef.get()
            if (bitmap != null) {
                LimeLog.info("Eviction cache hit for tuple: $loaderTuple")

                // Put this entry back into the LRU cache
                evictionCache.remove(key)
                memoryCache.put(key, bitmap)
                return bitmap
            } else {
                // The data is gone, so remove the dangling SoftReference now
                evictionCache.remove(key)
            }
        }
        return null
    }

    // Helper function to populate cache
    fun populateCache(loaderTuple: CachedAppAssetLoader.LoaderTuple, bitmap: ScaledBitmap) {
        memoryCache.put(constructKey(loaderTuple), bitmap)
    }

    // Helper function to clear cache
    fun clearCache() {
        // We must evict first because that will push all items into the eviction cache
        memoryCache.evictAll()
        evictionCache.clear()
    }

    companion object {

        private val MAX_MEMORY = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private const val CACHE_SIZE_RATIO = 16

        // Helper function to set max memory for the cache
        private val memoryCache: LruCache<String, ScaledBitmap> =
            object : LruCache<String, ScaledBitmap>(
                MAX_MEMORY / CACHE_SIZE_RATIO
            ) {
                override fun sizeOf(key: String, bitmap: ScaledBitmap): Int {
                    // Sizeof returns kilobytes
                    return bitmap.bitmap.byteCount / 1024
                }

                override fun entryRemoved(
                    evicted: Boolean, key: String,
                    oldValue: ScaledBitmap,
                    newValue: ScaledBitmap?
                ) {
                    super.entryRemoved(evicted, key, oldValue, newValue)
                    if (evicted) {
                        // Keep a soft reference around to the bitmap as long as we can
                        evictionCache[key] =
                            SoftReference(oldValue)
                    }
                }
            }

        // Helper function to set eviction cache
        private val evictionCache = ConcurrentHashMap<String, SoftReference<ScaledBitmap>>()

        // Helper function to construct key
        private fun constructKey(loaderTuple: CachedAppAssetLoader.LoaderTuple): String {
            return loaderTuple.computer.uuid + "-" + loaderTuple.app.id
        }
    }
}