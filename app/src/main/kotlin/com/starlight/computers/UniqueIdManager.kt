package com.starlight.computers

import android.content.Context
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

class UniqueIdManager(context: Context) {

    var uniqueId: String?

    init {
        uniqueId = loadUniqueId(context)

        if (uniqueId == null) {
            uniqueId = generateNewUniqueId(context)
        }
        info("UID is now: $uniqueId")
    }

    companion object {
        private const val UNIQUE_ID_FILE_NAME = "uniqueid"
        private const val UID_SIZE_IN_BYTES = 8
        private const val TAG = "UniqueIdManager"
    }

    // Load the unique ID from disk, if it exists
    private fun loadUniqueId(context: Context): String? {
        val uid = CharArray(UID_SIZE_IN_BYTES * 2)
        info("$TAG: Reading UID from disk")

        return try {
            context.openFileInput(UNIQUE_ID_FILE_NAME).use { stream ->
                InputStreamReader(stream).use { reader ->
                    if (reader.read(uid) != UID_SIZE_IN_BYTES * 2) {
                        severe("$TAG: UID file data is truncated")
                        return null
                    }
                    info("$TAG: UID read from disk: ${uid.contentToString()}")
                    String(uid)
                }
            }
        } catch (_: FileNotFoundException) {
            info("$TAG: No UID file found")
            null
        } catch (e: IOException) {
            severe("$TAG: Error while reading UID file")
            e.printStackTrace()
            null
        }
    }

    // Generate a new unique ID and write it to disk
    private fun generateNewUniqueId(context: Context): String {
        val newUid = UUID.randomUUID().toString()
        info("$TAG: Generated new UID: $newUid")

        try {
            context.openFileOutput(UNIQUE_ID_FILE_NAME, Context.MODE_PRIVATE).use { outputStream ->
                outputStream.writer().use { writer ->
                    writer.write(newUid)
                }
                info("$TAG: UID written to disk")
            }
        } catch (e: IOException) {
            severe(TAG + ": Error while writing UID file: ${e.message}")
        }

        return newUid
    }
}