
package com.example.emergencysafety

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

data class MatrixConnectionResult(
    val isConnected: Boolean,
    val httpStatus: Int? = null,
    val response: String? = null,
    val error: String? = null
)

object MatrixConnection {

    private const val HOMESERVER_URL =
        "https://emergency-safety.com"

    suspend fun checkServer(): MatrixConnectionResult {

        val client = HttpClient(CIO)

        return try {
            val response = client.get(
                "$HOMESERVER_URL/_matrix/client/versions"
            )

            val responseText = response.bodyAsText()

            MatrixConnectionResult(
                isConnected = response.status.value in 200..299,
                httpStatus = response.status.value,
                response = responseText
            )

        } catch (e: Exception) {

            MatrixConnectionResult(
                isConnected = false,
                error = e.message ?: "Matrix sunucusuna bağlanılamadı."
            )

        } finally {
            client.close()
        }
    }
}
