
package com.example.emergencysafety

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class ConnectionApiItem(
    val id: String,
    val email: String,
    val createdAt: String
)

internal data class ConnectionApiResult<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
)

internal object ConnectionApi {

    private const val API_BASE_URL =
        "https://emergency-safety.com/api"

    fun sendRequest(
        context: Context,
        email: String
    ): ConnectionApiResult<ConnectionApiItem> {

        val body = JSONObject().put(
            "email",
            email.trim().lowercase()
        )

        val response = request(
            context = context,
            path = "/connections/request",
            method = "POST",
            body = body
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        val connection = response.optJSONObject("connection")
            ?: return ConnectionApiResult(
                false,
                "Sunucu bağlantı bilgilerini döndürmedi."
            )

        val item = ConnectionApiItem(
            id = connection.optString("id", ""),
            email = response.optString("recipientEmail", ""),
            createdAt = connection.optString("created_at", "")
        )

        return ConnectionApiResult(
            success = true,
            message = response.optString(
                "message",
                "Bağlantı isteği gönderildi."
            ),
            data = item
        )
    }

    fun getRequests(
        context: Context
    ): ConnectionApiResult<List<ConnectionApiItem>> {

        val response = request(
            context = context,
            path = "/connections/requests",
            method = "GET"
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        val array = response.optJSONArray("requests")
            ?: JSONArray()

        val items = mutableListOf<ConnectionApiItem>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            items.add(parseItem(item, "email"))
        }

        return ConnectionApiResult(
            success = true,
            message = response.optString("message", "İstekler alındı."),
            data = items
        )
    }

    fun getConnections(
        context: Context
    ): ConnectionApiResult<List<ConnectionApiItem>> {

        val response = request(
            context = context,
            path = "/connections",
            method = "GET"
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        val array = response.optJSONArray("connections")
            ?: JSONArray()

        val items = mutableListOf<ConnectionApiItem>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            items.add(parseItem(item, "email"))
        }

        return ConnectionApiResult(
            success = true,
            message = response.optString(
                "message",
                "Bağlantılar alındı."
            ),
            data = items
        )
    }

    fun respond(
        context: Context,
        connectionId: String,
        action: String
    ): ConnectionApiResult<Boolean> {

        if (action !in listOf("accept", "reject", "block")) {
            return ConnectionApiResult(
                false,
                "Geçersiz işlem."
            )
        }

        val body = JSONObject().put("action", action)

        val response = request(
            context = context,
            path = "/connections/$connectionId/respond",
            method = "POST",
            body = body
        )

        return ConnectionApiResult(
            success = response.optBoolean("success", false),
            message = response.optString(
                "message",
                "İşlem tamamlanamadı."
            ),
            data = response.optBoolean("success", false)
        )
    }

    fun removeConnection(
        context: Context,
        connectionId: String
    ): ConnectionApiResult<Boolean> {

        val response = request(
            context = context,
            path = "/connections/$connectionId",
            method = "DELETE"
        )

        return ConnectionApiResult(
            success = response.optBoolean("success", false),
            message = response.optString(
                "message",
                "Bağlantı kaldırılamadı."
            ),
            data = response.optBoolean("success", false)
        )
    }

    private fun parseItem(
        item: JSONObject,
        emailField: String
    ): ConnectionApiItem {

        return ConnectionApiItem(
            id = item.optString("id", ""),
            email = item.optString(emailField, ""),
            createdAt = item.optString("createdAt", "")
        )
    }

    private fun request(
        context: Context,
        path: String,
        method: String,
        body: JSONObject? = null
    ): JSONObject {

        var connection: HttpURLConnection? = null

        return try {
            val token = SecureSessionStore.getAccessToken(
                context.applicationContext
            )

            if (token.isNullOrBlank()) {
                return JSONObject()
                    .put("success", false)
                    .put(
                        "message",
                        "Oturum bulunamadı. Yeniden giriş yapın."
                    )
            }

            connection = (
                URL(API_BASE_URL + path).openConnection()
                    as HttpURLConnection
                ).apply {

                requestMethod = method
                connectTimeout = 15000
                readTimeout = 15000

                setRequestProperty(
                    "Accept",
                    "application/json"
                )

                setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )

                setRequestProperty(
                    "Cache-Control",
                    "no-store"
                )

                if (body != null) {
                    doOutput = true

                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                    )
                }
            }

            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(
                        body.toString().toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )
                }
            }

            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.use { input ->
                BufferedReader(
                    InputStreamReader(
                        input,
                        StandardCharsets.UTF_8
                    )
                ).readText()
            }.orEmpty()

            val json = if (responseText.isNotBlank()) {
                JSONObject(responseText)
            } else {
                JSONObject()
            }

            if (status !in 200..299) {
                json.put("success", false)

                if (json.optString("message").isBlank()) {
                    json.put(
                        "message",
                        "Sunucu isteği reddetti. HTTP $status"
                    )
                }
            }

            json

        } catch (error: Exception) {
            JSONObject()
                .put("success", false)
                .put(
                    "message",
                    error.localizedMessage
                        ?: "Sunucu bağlantısı kurulamadı."
                )

        } finally {
            connection?.disconnect()
        }
    }

    private fun <T> failure(
        response: JSONObject
    ): ConnectionApiResult<T> {

        return ConnectionApiResult(
            success = false,
            message = response.optString(
                "message",
                "İşlem tamamlanamadı."
            )
        )
    }
}
