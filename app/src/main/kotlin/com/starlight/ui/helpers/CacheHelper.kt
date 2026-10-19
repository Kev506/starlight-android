package com.starlight.ui.helpers

import android.content.Context
import org.jetbrains.annotations.Contract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

object CacheHelper {

    // Helper function to open path
    fun openPath(createPath: Boolean, root: File, vararg pathComponents: String?): File {
        var currentFile = root
        for (i in pathComponents.indices) {
            val component = pathComponents[i] ?: continue // Skip null components
            currentFile = File(currentFile, component)
            if (i == pathComponents.size - 1 && createPath) {
                //Create parent directories only for the last component if createPath is true
                currentFile.parentFile?.mkdirs()
            }
        }
        return currentFile
    }

    // Helper function to get file from cache
    private fun getFileFromCache(root: File, vararg path: String?): File {
        return openPath(false, root, *path)
    }

    // Helper function to delete cache file
    fun deleteCacheFile(root: File, vararg path: String?) {
        getFileFromCache(root, *path).delete()
    }

    // Helper function to check if cache file exists
    fun cacheFileExists(root: File, vararg path: String?): Boolean {
        return getFileFromCache(root, *path).exists()
    }

    // Helper function to open cache file for input
    @Contract("_, _ -> new")
    fun openCacheFileForInput(root: File, vararg path: String): InputStream {
        val file = File(root, path.joinToString(File.separator))
        return BufferedInputStream(FileInputStream(file))
    }

    // Helper function to open cache file for output
    @Contract("_, _ -> new")
    @Throws(FileNotFoundException::class)
    fun openCacheFileForOutput(root: File, vararg path: String?): OutputStream {
        val cacheFile = File(root, path.joinToString(File.separator))
        cacheFile.parentFile?.mkdirs()
        return BufferedOutputStream(FileOutputStream(cacheFile))
    }

    // Helper function to write input stream to output stream
    @Throws(IOException::class)
    fun writeInputStreamToOutputStream(inputStream: InputStream, outputStream: OutputStream, maxLength: Long) {
        var remainingLength = maxLength
        val buffer = ByteArray(4096)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            remainingLength -= bytesRead
            if (remainingLength < 0) {
                throw IOException("Stream exceeded max size")
            }
            outputStream.write(buffer, 0, bytesRead)
        }
    }

    // Helper function to read input stream to string
    @Throws(IOException::class)
    fun readInputStreamToString(inputStream: InputStream): String {
        return inputStream.bufferedReader().use { it.readText() }
    }

    // Helper function to write string to output stream
    @Throws(IOException::class)
    fun writeStringToOutputStream(out: OutputStream, str: String) {
        out.writer(StandardCharsets.UTF_8).use { writer ->
            writer.write(str)
        }
    }

    // Helper function to format file size
    fun readableFileSize(size: Long): String {
        return when {
            size <= 0 -> "0"
            else -> {
                val units = arrayOf("B", "kB", "MB", "GB", "TB")
                val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
                DecimalFormat("#,##0.#").format(
                    size / 1024.0.pow(digitGroups.toDouble())
                ) + " " + units[digitGroups]
            }
        }
    }

    // Helper function to get directory size string
    fun getDirSizeString(context: Context): String {
        val cacheSize = context.cacheDir?.let { getDirSize(it) } ?: 0L
        val externalCacheSize = context.externalCacheDir?.let { getDirSize(it) } ?: 0L
        return readableFileSize(cacheSize + externalCacheSize)
    }

    // Helper function to get directory size
    fun getDirSize(dir: File): Long {
        var size: Long = 0
        val files = dir.listFiles() ?: return 0
        for (file in files) {
            size += if (file.isDirectory) {
                getDirSize(file)
            } else {
                file.length()
            }
        }
        return size
    }

    // Helper function to clear app cache
    fun clearAppCache(context: Context) {
        context.cacheDir?.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()
    }
}