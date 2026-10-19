package com.starlight.ui.helpers

import android.content.Context
import android.graphics.BitmapFactory
import com.starlight.R
import com.starlight.games.GameDataBaseManager
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.ui.grids.assets.CachedAppAssetLoader
import com.starlight.ui.grids.assets.DiskAssetLoader
import com.starlight.ui.grids.assets.MemoryAssetLoader
import com.starlight.ui.grids.assets.NetworkAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Created by Kevin Andrews on 20/06/2026.
 */

class DetailsLoaderHelper(
    computer: ComputerDetails, uniqueId: String,
    context: Context
) {

    private var computer: ComputerDetails? = null
    private var uniqueId: String? = null

    // Cancel queued operations
    fun cancelQueuedOperations() {
        loader!!.freeCacheMemory()
        loader?.destroy()
    }

    // Set the loader
    private fun setLoader(context: Context) {

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations()
        }

        loader = CachedAppAssetLoader(
            computer!!,
            NetworkAssetLoader(context, uniqueId!!),
            MemoryAssetLoader(),
            DiskAssetLoader(context),
            BitmapFactory.decodeResource(context.resources, R.drawable.no_app_image)
        )
    }

    // Get the cover art from the database
    suspend fun getGameImage(app: NvApp, context: Context): Any? = withContext(Dispatchers.IO) {
        val dbHandler = GameDataBaseManager.getInstance(context)
        val appData = dbHandler?.getAppData(app.id.toString())

        val image = if (appData?.coverArt != null) {
            appData.coverArt
        } else if (appData?.fallbackCoverArt != null) {
            appData.fallbackCoverArt
        } else {
            return@withContext null
        }

        return@withContext image!!
    }

    // Get the video from the database
    fun getGameTrailer(app: NvApp?, context: Context): String  {
        val dbHandler = GameDataBaseManager.getInstance(context)
        val appData = dbHandler?.getAppData(app?.id.toString())

        val videoUrl = if (appData?.video != null) {
            appData.video
        } else {
            return ""
        }

        return videoUrl!!
    }

    // Get the slug from the database
    fun getGameSlug(app: NvApp?, context: Context): String  {
        val dbHandler = GameDataBaseManager.getInstance(context)
        val appData = dbHandler?.getAppData(app?.id.toString())

        val slug = if (appData?.slug != null) {
            appData.slug
        } else {
            return ""
        }

        return slug!!
    }

    init {

        this.computer = computer
        this.uniqueId = uniqueId

        setLoader(context)
    }

    companion object {
        var loader: CachedAppAssetLoader? = null
    }
}