package com.starlight.nvstream.http

import java.io.IOException
import java.io.Serial

class HostHttpResponseException(private val errorCode: Int, private val errorMessage: String) :
    IOException() {

    fun getErrorCode(): Int = errorCode

    fun getErrorMessage(): String = errorMessage

    override val message: String
        get() = "Host PC returned error: $errorMessage (Error code: $errorCode)"

    companion object {
        @Serial
        private val serialVersionUID = 1543508830807804222L
    }
}