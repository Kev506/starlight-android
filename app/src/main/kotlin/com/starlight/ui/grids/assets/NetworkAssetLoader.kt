package com.starlight.ui.grids.assets

import android.content.Context
import com.starlight.binding.PlatformBinding
import com.starlight.nvstream.http.NvHTTP
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import com.starlight.ui.helpers.ServerHelper
import java.io.IOException
import java.io.InputStream

class NetworkAssetLoader(private val context: Context, private val uniqueId: String) {

    fun getBitmapStream(tuple: CachedAppAssetLoader.LoaderTuple): InputStream? {
        return try {
            val http = NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                tuple.computer.httpsPort,
                uniqueId, tuple.computer.serverCert,
                PlatformBinding.getCryptoProvider(context)
            )
            val inputStream = http.getBoxArt(tuple.app)
            info("Network asset load complete: $tuple)")
            inputStream
        } catch (e: IOException) {
            warning("Error loading network asset: $tuple) $e")
            null
        }
    }
}