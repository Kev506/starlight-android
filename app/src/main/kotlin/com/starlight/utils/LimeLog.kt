package com.starlight.utils

import android.content.Context
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.starlight.preferences.PreferenceKeys.fromPreferences
import com.starlight.utils.Constants.DIRECTORY_PREF
import com.starlight.utils.Constants.HEADER_PREF
import com.starlight.utils.Constants.PASSWORD_PREF
import com.starlight.utils.Constants.SERVER_PREF
import com.starlight.utils.Constants.USERNAME_PREF
import com.starlight.utils.Starlight.Companion.aead
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.dataStore
import com.starlight.utils.Starlight.Companion.instance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.util.zip.GZIPOutputStream


// Helper object for logging
object LimeLog {

    private val LOGGER = Logger.getLogger(LimeLog::class.java.name)
    private var fileWriter: BufferedWriter? = null
    private var logFile: File? = null

    private const val MAX_SIZE_IN_BYTES = 500 * 1024L
    private val MAX_TIME_IN_HOURS = TimeUnit.HOURS.toMillis(24)

    private var LOG_FILE_TIME_FORMAT: SimpleDateFormat? = null
    private var ftpConfig: FtpConfig? = null

    // Helper function to initialize the log file
    fun initialize(context: Context) {

        setFtpConfiguration(context)

        LOG_FILE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        logFile = File(context.getExternalFilesDir(null), "starlight_logs.txt")
        fileWriter = BufferedWriter(FileWriter(logFile, true))

        if (logFile!!.exists() && !isFileEmpty(logFile!!)) {
            // Check log file size
            val fileSize = logFile!!.length()
            if (fileSize > MAX_SIZE_IN_BYTES) {
                applicationScope.launch {
                    rotateLogs(logFile!!)
                }

                applicationScope.launch {
                    context.dataStore.edit { preferences ->
                        preferences[booleanPreferencesKey(HEADER_PREF)] = false
                    }
                }
            }
        } else {
            try {
                applicationScope.launch {
                    context.dataStore.edit { preferences ->
                        preferences[booleanPreferencesKey(HEADER_PREF)] = false
                    }
                }
                logFile!!.createNewFile()
                info("Created new log file : ${logFile!!.name}")
            } catch (e: IOException) {
                severe("Error creating new log file : ${e.message}")
            }
        }
    }

    // Helper function to set the FTP configuration
    private fun setFtpConfiguration(context: Context) {

        if (aead == null) {
            severe("Cannot set FTP configuration because encryption is not available.")
            return
        }

        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        // Attempt to load credentials from the encrypted file first
        val server = readSecureData(SERVER_PREF, context)
        val username = readSecureData(USERNAME_PREF, context)
        val password = readSecureData(PASSWORD_PREF, context)
        val directory = readSecureData(DIRECTORY_PREF, context)

        if (server != null && username != null && password != null && directory != null) {
            ftpConfig = FtpConfig(server, username, password, directory)
            info("Keys are present, get from encrypted data")
        } else {
            // Keys are not present or incomplete, fetch from Remote Config
            remoteConfig.fetchAndActivate()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val config = FtpConfig(
                            remoteConfig.getString(SERVER_PREF),
                            remoteConfig.getString(USERNAME_PREF),
                            remoteConfig.getString(PASSWORD_PREF),
                            remoteConfig.getString(DIRECTORY_PREF)
                        )
                        ftpConfig = config
                        info("Keys are not present, fetching from remote config")
                        storeDataSecurely(config.toMap(), context)
                        info("Fetched from remote config and stored securely")
                    } else {
                        warning("Remote config fetch failed: ${task.exception}")
                    }
                }
        }
    }

    // Helper function to rotate the log files
    private suspend fun rotateLogs(file: File) {
        if (compress(file)) {
            try {
                withContext(Dispatchers.IO) {
                    PrintWriter(file).use { it.close() }
                }

                file.parentFile?.listFiles { _, name ->
                    name.endsWith(".gz", ignoreCase = true) &&
                            System.currentTimeMillis() - File(
                        file.parentFile,
                        name
                    ).lastModified() > MAX_TIME_IN_HOURS
                }?.forEach {
                    it.delete()
                    info("Log rotated and compressed file uploaded : ${it.name}")
                    info("Deleted log file : ${it.name}")
                }
            } catch (e: Exception) {
                severe("Error rotating logs : ${e::class.simpleName} - ${e.message}")
            }
        } else {
            severe("Failed to compress or upload log file : ${file.name}")
        }
    }

    // Helper function to compress a file
    private suspend fun compress(file: File): Boolean {
        return try {

            val timestamp = LOG_FILE_TIME_FORMAT?.format(Date())
            val compressed =
                File(file.parentFile, "${file.nameWithoutExtension}_$timestamp.gz")

            withContext(Dispatchers.IO) {
                FileInputStream(file).use { input ->
                    FileOutputStream(compressed).use { output ->
                        GZIPOutputStream(output).use { zipped ->
                            input.copyTo(zipped)
                            zipped.finish()
                            info("Compressed and uploaded log file : ${compressed.name}")
                        }
                    }
                }
            }
            uploadLogs()
        } catch (e: IOException) {
            severe("Error compressing log : ${e.message}")
            false
        }
    }

    // Helper function to check if a file is empty
    private fun isFileEmpty(file: File): Boolean {
        try {
            FileReader(file).use { fileReader ->
                BufferedReader(fileReader).use { bufferedReader ->
                    return bufferedReader.readLine() == null
                }
            }
        } catch (e: IOException) {
            warning("Error checking file : ${e.message}")
            return false
        }
    }

    // Helper function to get user action
    fun getUserAction(): Boolean {
        val config = fromPreferences(instance)
        return config.statsEnabled
    }

    // Helper functions for logging
    fun info(msg: String?) {
        if (msg == null) return
        applicationScope.launch {
            if (getUserAction()) {
                Firebase.crashlytics.log("INFO: $msg")
            } else {
                LOGGER.info(msg)
                writeLog("INFO : $msg")
            }
        }
    }

    fun warning(msg: String?) {
        if (msg == null) return
        applicationScope.launch {
            if (getUserAction()) {
                Firebase.crashlytics.log("WARNING: $msg")
            } else {
                LOGGER.warning(msg)
                writeLog("WARNING : $msg")
            }
        }
    }

    fun severe(msg: String?) {
        if (msg == null) return
        applicationScope.launch {
            if (getUserAction()) {
                Firebase.crashlytics.log("SEVERE: $msg")
                Firebase.crashlytics.recordException(Exception("Severe Error: $msg"))
            } else {
                LOGGER.severe(msg)
                writeLog("SEVERE : $msg")
            }
        }
    }

    // Helper function to write a log message with context
    suspend fun writeLog(message: String) {
        val fallbackContext = instance
        writeLog(message, fallbackContext)
    }

    // Helper function to write a log message to a file
    private suspend fun writeLog(message: String, context: Context) = withContext(Dispatchers.IO) {
        try {
            val config = fromPreferences(context)
            val headerWritten = config.header

            if (!headerWritten) {
                writeHeader()
                context.dataStore.edit { preferences ->
                    preferences[booleanPreferencesKey(HEADER_PREF)] = true
                }
            }

            fileWriter?.write(message + "\n")
            fileWriter?.flush()
        } catch (e: IOException) {
            Log.e("LogWriter", "Failed to write local log file", e)
        }
    }

    // Helper function to write a header to the log file
    private suspend fun writeHeader() {

        val timestamp =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
        val formattedDate: String = timestamp.format(Date())

        var device = "Device-infos:"
        device += """
        Date: $formattedDate"""
        device += """
        Locale: ${Locale.getDefault().language}"""
        device += """
        OS Version: ${System.getProperty("os.version")}(${Build.VERSION.INCREMENTAL})"""
        device += """
        OS API Level: ${Build.VERSION.SDK_INT}"""
        device += """
        Device: ${Build.DEVICE}"""
        device += """
        Model (and Product): ${Build.MODEL} (${Build.PRODUCT})"""

        try {
            withContext(Dispatchers.IO) {
                fileWriter?.write(device + "\n" + "\n")
            }
        } catch (e: IOException) {
            severe("Error writing header : ${e.message}")
        }
    }

    // Helper function to close the log file
    fun close() {
        try {
            fileWriter?.close()
        } catch (e: IOException) {
            severe("Error closing log : ${e.message}")
        }
    }

    // Helper function to get the file for upload
    private fun getFileForUpload(): File? {
        return logFile?.parentFile?.let { parentDir ->
            parentDir.listFiles { file ->
                file.name.startsWith("starlight_logs_") && file.name.endsWith(".gz")
            }?.firstOrNull()
        }
    }

    // Helper function to upload logs
    private suspend fun uploadLogs(): Boolean {
        val file = getFileForUpload() ?: return false

        if (ftpConfig == null) {
            warning("FTP configuration is missing. Cannot upload logs.")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                FTPClient().use { ftpClient ->
                    ftpClient.connect(ftpConfig!!.server, 21)
                    ftpClient.login(ftpConfig!!.username, ftpConfig!!.password)
                    ftpClient.enterLocalPassiveMode()
                    ftpClient.setFileType(FTP.ASCII_FILE_TYPE)

                    if (!FTPReply.isPositiveCompletion(ftpClient.replyCode)) {
                        severe("Failed to connect to server: ${ftpClient.replyString}")
                        ftpClient.disconnect() // Ensure disconnection on failure
                        return@withContext false
                    }
                    info("Connected to ${ftpConfig!!.server}. Reply: ${ftpClient.replyString}")

                    if (!ftpClient.changeWorkingDirectory(ftpConfig!!.directory)) {
                        severe("Failed to change FTP directory to '${ftpConfig!!.directory}'.")
                        ftpClient.logout()
                        ftpClient.disconnect()
                        return@withContext false
                    }

                    val success = FileInputStream(file).use { fis ->
                        ftpClient.storeFile(file.name, fis)
                    }

                    if (!success) {
                        severe("Failed to store file on FTP server. Reply: ${ftpClient.replyString}")
                    } else {
                        info("File uploaded successfully. Reply: ${ftpClient.replyString}")
                        if (file.delete()) {
                            info("File deleted successfully: ${ file.name }")
                        }
                    }
                    return@withContext success
                }
            } catch (e: IOException) {
                severe("Error during log upload: ${e.message}")
                false
            }
        }
    }

    // Helper function for using FTPClient in a try-with-resources block
    inline fun <T> FTPClient.use(block: (FTPClient) -> T): T {
        try {
            return block(this)
        } finally {
            if (isConnected) {
                try {
                    logout()
                    disconnect()
                } catch (e: IOException) {
                    info("Error during FTPClient cleanup: ${e.message}")
                }
            }
        }
    }

    // Data class for FTP configuration
    data class FtpConfig(
        val server: String?,
        val username: String?,
        val password: String?,
        val directory: String?
    )

    // Extension function to convert FtpConfig to Map
    private fun FtpConfig.toMap(): Map<String, String?> {
        return mapOf(
            SERVER_PREF to server,
            USERNAME_PREF to username,
            PASSWORD_PREF to password,
            DIRECTORY_PREF to directory
        )
    }

    // Helper function for storing data securely using Tink
    private fun storeDataSecurely(data: Map<String, String?>, context: Context) {
        if (aead == null) {
            severe("AEAD not initialized. Cannot store data securely.")
            return
        }
        data.forEach { (key, value) ->
            try {
                val file = File(context.filesDir, key)
                val valueBytes = value?.toByteArray(StandardCharsets.UTF_8)
                val encryptedValue = aead!!.encrypt(valueBytes, null)
                FileOutputStream(file).use { it.write(encryptedValue) }
            } catch (e: GeneralSecurityException) {
                severe("Failed to encrypt and store data for key '$key': ${e.message}")
            } catch (e: IOException) {
                severe("Failed to write encrypted file for key '$key': ${e.message}")
            }
        }
    }

    // Helper function for reading data securely using Tink
    fun readSecureData(key: String, context: Context): String? {
        if (aead == null) {
            severe("AEAD not initialized. Cannot read secure data.")
            return null
        }
        try {
            val file = File(context.filesDir, key)
            if (!file.exists()) {
                return null
            }

            // Use the .use block to automatically close the FileInputStream
            val encryptedValue = FileInputStream(file).use { it.readBytes() }
            val decryptedValue = aead!!.decrypt(encryptedValue, null)

            return String(decryptedValue, StandardCharsets.UTF_8)

        } catch (e: GeneralSecurityException) {
            severe("Failed to decrypt data for key '$key': ${e.message}")
            return null
        } catch (e: IOException) {
            severe("Failed to read encrypted file for key '$key': ${e.message}")
            return null
        }
    }
}