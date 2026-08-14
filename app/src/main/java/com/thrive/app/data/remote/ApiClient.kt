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
}
