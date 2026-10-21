package com.example.emergencysafety

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WhatsAppWorkerClient {

    private const val WORKER_URL =
        "https://frosty-disk-6780.kingpeace41.workers.dev/send"

    suspend fun sendMessage(
        phoneNumber: String,
        message: String,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {

        var connection: HttpURLConnection? = null

        try {
            val payload = JSONObject().apply {
                put("to", phoneNumber)
                put("message", message)
            }

            connection = (URL(WORKER_URL).openConnection()
                    as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty(
                    "Authorization",
                    "Bearer $apiKey"
                )
                setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
            }

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val responseStream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val responseText = responseStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (status in 200..299) {
                Result.success(responseText)
            } else {
                Result.failure(
                    Exception("Worker HTTP $status: $responseText")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
