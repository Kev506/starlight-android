package com.starlight.computers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.ComputerDetails.AddressTuple
import com.starlight.nvstream.http.NvHTTP
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class ComputerDatabaseManager(c: Context, factory: SQLiteDatabase.CursorFactory?) :
    SQLiteOpenHelper(c, COMPUTER_DB_NAME, factory, DB_VERSION + 1) {

    private enum class AddressField {
        LOCAL, REMOTE, MANUAL, IPv6, ADDRESS, PORT
    }

    // Create the database
    override fun onCreate(db: SQLiteDatabase) {
        val query = """
        CREATE TABLE $COMPUTER_TABLE_NAME (
                $COMPUTER_UUID_COLUMN_NAME TEXT PRIMARY KEY,
                $COMPUTER_NAME_COLUMN_NAME TEXT,
                $ADDRESSES_COLUMN_NAME TEXT,
                $MAC_ADDRESS_COLUMN_NAME TEXT,
                $SERVER_CERT_COLUMN_NAME TEXT
                )
                """.trimIndent()
        db.execSQL(query)
    }

    // Upgrade the database
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $COMPUTER_TABLE_NAME")
        onCreate(db)
    }

    // Delete a computer from the database
    fun deleteComputer(details: ComputerDetails) {
        val computerDb = this.readableDatabase
        computerDb!!.delete(
            COMPUTER_TABLE_NAME,
            "$COMPUTER_UUID_COLUMN_NAME=?",
            arrayOf(details.uuid)
        )
    }

    // Update a computer in the database
    fun updateComputer(details: ComputerDetails): Boolean {
        val computerDb = this.readableDatabase
        val values = ContentValues()
        values.put(COMPUTER_UUID_COLUMN_NAME, details.uuid)
        values.put(COMPUTER_NAME_COLUMN_NAME, details.name)
        try {
            val addresses = JSONObject()
            addresses.put(AddressField.LOCAL.name, tupleToJson(details.localAddress))
            addresses.put(AddressField.REMOTE.name, tupleToJson(details.remoteAddress))
            addresses.put(AddressField.MANUAL.name, tupleToJson(details.manualAddress))
            addresses.put(AddressField.IPv6.name, tupleToJson(details.ipv6Address))
            values.put(ADDRESSES_COLUMN_NAME, addresses.toString())
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
        values.put(MAC_ADDRESS_COLUMN_NAME, details.macAddress)
        try {
            if (details.serverCert != null) {
                values.put(SERVER_CERT_COLUMN_NAME, details.serverCert!!.encoded)
            } else {
                values.put(SERVER_CERT_COLUMN_NAME, null as ByteArray?)
            }
        } catch (e: CertificateEncodingException) {
            values.put(SERVER_CERT_COLUMN_NAME, null as ByteArray?)
            e.printStackTrace()
        }
        return -1L != computerDb!!.insertWithOnConflict(
            COMPUTER_TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // Get a computer from the database
    private fun getComputerFromCursor(c: Cursor): ComputerDetails {
        val details = ComputerDetails()
        details.uuid = c.getString(0)
        details.name = c.getString(1)
        try {
            val addresses = JSONObject(c.getString(2))
            details.localAddress = tupleFromJson(addresses, AddressField.LOCAL.name)
            details.remoteAddress = tupleFromJson(addresses, AddressField.REMOTE.name)
            details.manualAddress = tupleFromJson(addresses, AddressField.MANUAL.name)
            details.ipv6Address = tupleFromJson(addresses, AddressField.IPv6.name)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        // External port is persisted in the remote address field
        if (details.remoteAddress != null) {
            details.externalPort = details.remoteAddress!!.port
        } else {
            details.externalPort = NvHTTP.DEFAULT_HTTP_PORT
        }
        details.macAddress = c.getString(3)
        try {
            val derCertData = c.getBlob(4)
            if (derCertData != null) {
                details.serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            }
        } catch (e: CertificateException) {
            e.printStackTrace()
        }

        // This signifies we don't have dynamic state (like pair state)
        details.state = ComputerDetails.State.UNKNOWN
        return details
    }

    // Get all computers from the database
    val allComputers: List<ComputerDetails>
        get() {
            val computerDb = this.readableDatabase
            computerDb!!.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
                val computerList = ArrayDeque<ComputerDetails>()
                while (c.moveToNext()) {
                    computerList.add(getComputerFromCursor(c))
                }
                return computerList
            }
        }

    // Get a computer by UUID from the database
    fun getComputerByUUID(uuid: String): ComputerDetails? {
        val computerDb = this.readableDatabase
        computerDb!!.query(
            COMPUTER_TABLE_NAME, null, "$COMPUTER_UUID_COLUMN_NAME=?",
            arrayOf(uuid), null, null, null
        ).use { c ->
            return if (!c.moveToFirst()) {
                // No matching computer
                null
            } else getComputerFromCursor(c)
        }
    }

    companion object {
        private const val DB_VERSION = 1
        private const val COMPUTER_DB_NAME = "computers4.db"
        private const val COMPUTER_TABLE_NAME = "Computers"
        private const val COMPUTER_UUID_COLUMN_NAME = "UUID"
        private const val COMPUTER_NAME_COLUMN_NAME = "ComputerName"
        private const val ADDRESSES_COLUMN_NAME = "Addresses"
        private const val MAC_ADDRESS_COLUMN_NAME = "MacAddress"
        private const val SERVER_CERT_COLUMN_NAME = "ServerCert"

        private var mInstance: ComputerDatabaseManager? = null

        @Synchronized
        fun getInstance(context: Context): ComputerDatabaseManager? {
            if (mInstance == null) {
                mInstance = ComputerDatabaseManager(context.applicationContext, null)
            }
            return mInstance
        }

        // Convert a tuple to a JSON object
        @Throws(JSONException::class)
        fun tupleToJson(tuple: AddressTuple?): JSONObject? {
            if (tuple == null) {
                return null
            }
            val json = JSONObject()
            json.put(AddressField.ADDRESS.name, tuple.address)
            json.put(AddressField.PORT.name, tuple.port)
            return json
        }

        // Convert a JSON object to a tuple
        @Throws(JSONException::class)
        fun tupleFromJson(json: JSONObject, name: String?): AddressTuple? {
            if (!json.has(name)) {
                return null
            }
            val address = json.getJSONObject(name!!)
            return AddressTuple(
                address.getString(AddressField.ADDRESS.name), address.getInt(AddressField.PORT.name)
            )
        }
    }
}