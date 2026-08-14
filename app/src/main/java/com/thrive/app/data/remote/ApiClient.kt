package com.thrive.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val code: Int, val body: String, val etag: String?)

/** Tiny synchronous HTTP client — good enough for the sync API. */
object ApiClient {

    suspend fun get(url: String, ifNoneMatch: String? = null): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                if (!ifNoneMatch.isNullOrBlank()) {
                    conn.setRequestProperty("If-None-Match", ifNoneMatch)
                }
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code == 304) {
                    HttpResult(code, "", conn.getHeaderField("ETag"))
                } else {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    HttpResult(code, body, conn.getHeaderField("ETag"))
                }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * PUTs a JSON string (used by the state backup). [ifMatch] carries the
     * backup revision the client last read ("*" creates); the server answers
     * 409 when the backup changed in the meantime, so concurrent devices never
     * silently overwrite each other.
     */
    suspend fun putJson(url: String, jsonBody: String, ifMatch: String? = null): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "PUT"
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (!ifMatch.isNullOrBlank()) {
                    conn.setRequestProperty("If-Match", ifMatch)
                }
                conn.instanceFollowRedirects = true
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                HttpResult(code, body, conn.getHeaderField("ETag"))
            } finally {
                conn.disconnect()
            }
        }
}
