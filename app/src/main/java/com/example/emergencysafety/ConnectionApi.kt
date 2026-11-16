
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

    private val refreshLock = Any()

    private data class HttpResult(
        val statusCode: Int,
        val json: JSONObject
    )

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
            message = response.optString(
                "message",
                "İstekler alındı."
            ),
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

        val appContext = context.applicationContext

        var token = try {
            SecureSessionStore.getAccessToken(appContext)
        } catch (_: Exception) {
            null
        }

        if (token.isNullOrBlank()) {
            token = refreshAccessToken(
                appContext,
                ""
            )
        }

        if (token.isNullOrBlank()) {
            return errorResponse(
                "Oturum yenilenemedi. Yeniden giriş yapman gerekebilir."
            )
        }

        val firstAttempt = executeAuthorizedRequest(
            path = path,
            method = method,
            body = body,
            accessToken = token
        )

        if (firstAttempt.statusCode != 401) {
            return firstAttempt.json
        }

        val newToken = refreshAccessToken(
            appContext,
            token
        )

        if (newToken.isNullOrBlank()) {
            return errorResponse(
                "Oturum süresi doldu. Oturum yenilenemedi."
            )
        }

        val secondAttempt = executeAuthorizedRequest(
            path = path,
            method = method,
            body = body,
            accessToken = newToken
        )

        return secondAttempt.json
    }

    private fun refreshAccessToken(
        context: Context,
        rejectedAccessToken: String
    ): String? {

        synchronized(refreshLock) {

            try {
                val currentAccessToken =
                    SecureSessionStore.getAccessToken(context)

                // Başka bir istek oturumu zaten yenilediyse
                // aynı refresh token ile ikinci kez işlem yapma.
                if (
                    !currentAccessToken.isNullOrBlank() &&
                    currentAccessToken != rejectedAccessToken
                ) {
                    return currentAccessToken
                }

                val refreshToken =
                    SecureSessionStore.getRefreshToken(context)

                if (refreshToken.isNullOrBlank()) {
                    return null
                }

                val body = JSONObject().put(
                    "refreshToken",
                    refreshToken
                )

                val result = executePublicRequest(
                    path = "/auth/session/refresh",
                    method = "POST",
                    body = body
                )

                if (
                    result.statusCode !in 200..299 ||
                    !result.json.optBoolean("success", false)
                ) {
                    return null
                }

                val newAccessToken =
                    result.json.optString("accessToken", "")

                val newRefreshToken =
                    result.json.optString("refreshToken", "")

                if (
                    newAccessToken.isBlank() ||
                    newRefreshToken.isBlank()
                ) {
                    return null
                }

                SecureSessionStore.updateTokens(
                    context,
                    newAccessToken,
                    newRefreshToken
                )

                return newAccessToken

            } catch (_: Exception) {
                return null
            }
        }
    }

    private fun executeAuthorizedRequest(
        path: String,
        method: String,
        body: JSONObject?,
        accessToken: String
    ): HttpResult {

        return executeHttpRequest(
            path = path,
            method = method,
            body = body,
            accessToken = accessToken
        )
    }

    private fun executePublicRequest(
        path: String,
        method: String,
        body: JSONObject?
    ): HttpResult {

        return executeHttpRequest(
            path = path,
            method = method,
            body = body,
            accessToken = null
        )
    }

    private fun executeHttpRequest(
        path: String,
        method: String,
        body: JSONObject?,
        accessToken: String?
    ): HttpResult {

        var connection: HttpURLConnection? = null

        return try {
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
                    "Cache-Control",
                    "no-store"
                )

                if (!accessToken.isNullOrBlank()) {
                    setRequestProperty(
                        "Authorization",
                        "Bearer $accessToken"
                    )
                }

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

            HttpResult(
                statusCode = status,
                json = json
            )

        } catch (error: Exception) {

            HttpResult(
                statusCode = -1,
                json = errorResponse(
                    error.localizedMessage
                        ?: "Sunucu bağlantısı kurulamadı."
                )
            )

        } finally {
            connection?.disconnect()
        }
    }

    private fun errorResponse(
        message: String
    ): JSONObject {

        return JSONObject()
            .put("success", false)
            .put("message", message)
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
