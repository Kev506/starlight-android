package com.starlight.ui.adapters

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateListOf
import com.starlight.R
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.ui.grids.assets.CachedAppAssetLoader
import com.starlight.ui.grids.assets.DiskAssetLoader
import com.starlight.ui.grids.assets.MemoryAssetLoader
import com.starlight.ui.grids.assets.NetworkAssetLoader
import com.starlight.utils.AppComputerObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by Kevin Andrews on 17/04/2026.
 */
class AppAdapter(
    computer: ComputerDetails, uniqueId: String,
    showHiddenApps: Boolean, context: Context
) {

    private var computer: ComputerDetails? = null
    private var uniqueId: String? = null
    private var uniqueIdl: String? = null
    private var showHiddenApps: Boolean = false

    // Update the hidden app IDs and notify the adapter
    fun updateHiddenApps(newHiddenAppIds: Set<Int>, hideImmediately: Boolean) {
        hiddenAppIds = newHiddenAppIds.toMutableSet()

        if (hideImmediately) {
            // Use a more functional approach with filter
            _items.filter { !hiddenAppIds.contains(it.app?.id) || showHiddenApps }.toMutableList()
        } else {
            // Update isHidden state directly
            _items.forEach { it.isHidden = hiddenAppIds.contains(it.app?.id) }
        }

        updateItems(_items, hiddenAppIds)
    }

    // Update the items list
    fun updateItems(newList: MutableList<AppComputerObject>?, newHiddenAppIds: Set<Int>) {
        hiddenAppIds = newHiddenAppIds.toMutableSet()

        _items.clear()

        newList?.forEach { app ->
            app.isHidden = hiddenAppIds.contains(app.app?.id)

            if (showHiddenApps || !app.isHidden!!) {
                app.app?.let { loader?.queueCacheLoad(it) }

                _items.add(app)
                sortList()
            }
        }
    }

    // Sort the list of computers
    private fun sortList() {
        _items.sortWith(Comparator.comparing { lhs: AppComputerObject ->
            lhs.app?.appName!!.lowercase(
                Locale.ROOT
            )
        })
    }

    // Get an app by index
    fun get(index: Int): AppComputerObject {
        return _items[index]
    }

    // Get adapter size
    fun size(): Int {
        return _items.size
    }

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

    // Remove an app by ID
    fun removeAppById(itemId: Int?) {
        allApps!!.removeAll { it.app?.id == itemId }
        updateItems(allApps, hiddenAppIds)
    }

    // Remove an app
    fun removeApp(app: AppComputerObject) {
        allApps?.remove(app)
        updateItems(allApps, hiddenAppIds)
    }

    // Add an app
    fun addApp(app: AppComputerObject) {
        allApps?.add(app)
        updateItems(allApps, hiddenAppIds)
    }

    init {

        allApps = ArrayList()

        this.computer = computer
        this.uniqueId = uniqueId
        this.uniqueIdl = uniqueId
        this.showHiddenApps = showHiddenApps

        setLoader(context)
    }

    companion object {
        var hiddenAppIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        var loader: CachedAppAssetLoader? = null

        private val _items = mutableStateListOf<AppComputerObject>()
        val itemList: List<AppComputerObject> = _items

        var allApps: MutableList<AppComputerObject>? = null
        val sunFavAppIds: MutableList<NvApp> = mutableListOf()
        val favAppIds: MutableList<NvApp> = mutableListOf()

    }
}