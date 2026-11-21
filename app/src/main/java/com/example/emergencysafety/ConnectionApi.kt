
package com.example.emergencysafety

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class ContactNameApiItem(
    val contactUserId: String,
    val email: String,
    val displayName: String?,
    val updatedAt: String?
)

internal data class ConnectionApiItem(
    val id: String,
    val email: String,
    val createdAt: String,
    val contactUserId: String = ""
)

internal data class MessageApiItem(
    val id: String,
    val senderUsername: String,
    val recipientUsername: String,
    val body: String,
    val messageType: String,
    val createdAt: String,
    val deliveredAt: String?,
    val readAt: String?,
    val connectionId: String = "",
    val isOutgoing: Boolean = false
)

internal data class UnreadConnectionCount(
    val connectionId: String,
    val count: Int
)

internal data class MessageSummaryApiItem(
    val unreadTotal: Int,
    val unreadByConnection: List<UnreadConnectionCount>,
    val pendingInvitations: Int
)

internal data class ConnectionApiResult<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
)

internal object ConnectionApi {

    private const val TAG = "ES_SESSION_DIAG"

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

        // Bildirimler yalnizca baglanti kimligi tasidiginda da
        // gonderenin kayitli adini bulabilmek icin yerel esleme tut.
        val cache = context.getSharedPreferences(
            "EmergencySafetyConnectionEmails",
            Context.MODE_PRIVATE
        )
        val editor = cache.edit().clear()
        items.forEach { connection ->
            if (connection.id.isNotBlank() && connection.email.isNotBlank()) {
                editor.putString(connection.id, connection.email)
            }
        }
        editor.apply()

        return ConnectionApiResult(
            success = true,
            message = response.optString(
                "message",
                "Bağlantılar alındı."
            ),
            data = items
        )
    }

    fun getContactNames(
        context: Context
    ): ConnectionApiResult<List<ContactNameApiItem>> {

        val response = request(
            context = context,
            path = "/contact-names",
            method = "GET"
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        val array = response.optJSONArray("contacts")
            ?: JSONArray()

        val items = mutableListOf<ContactNameApiItem>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val contactUserId = item.optString("contactUserId", "")
            val email = item.optString("email", "")
            if (contactUserId.isBlank() || email.isBlank()) continue

            items.add(
                ContactNameApiItem(
                    contactUserId = contactUserId,
                    email = email,
                    displayName = if (item.isNull("displayName")) null
                        else item.optString("displayName", ""),
                    updatedAt = if (item.isNull("updatedAt")) null
                        else item.optString("updatedAt", "")
                )
            )
        }

        return ConnectionApiResult(
            success = true,
            message = "Kişi isimleri alındı.",
            data = items
        )
    }

    /**
     * Sunucuda kayitli kisi adini olusturur veya gunceller.
     * Yalnizca onayli baglantilar icin gecerlidir.
     */
    fun updateContactName(
        context: Context,
        contactUserId: String,
        displayName: String
    ): ConnectionApiResult<Boolean> {
        val id = contactUserId.trim()
        if (!isValidContactUserId(id)) {
            return ConnectionApiResult(false, "Gecersiz kisi kimligi.")
        }

        val name = displayName.trim()
        if (
            name.isBlank() ||
            name.codePointCount(0, name.length) > 100 ||
            name.any { it.code < 32 || it.code == 127 }
        ) {
            return ConnectionApiResult(
                false,
                "Isim 1-100 karakter arasinda olmali."
            )
        }

        val response = request(
            context = context,
            path = "/contact-names/$id",
            method = "PUT",
            body = JSONObject().put("displayName", name)
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        return ConnectionApiResult(true, "Kisi adi guncellendi.", true)
    }

    /**
     * Sunucudaki kisiye ozel adi siler.
     * Baglantinin kendisini silmez.
     */
    fun deleteContactName(
        context: Context,
        contactUserId: String
    ): ConnectionApiResult<Boolean> {
        val id = contactUserId.trim()
        if (!isValidContactUserId(id)) {
            return ConnectionApiResult(false, "Gecersiz kisi kimligi.")
        }

        val response = request(
            context = context,
            path = "/contact-names/$id",
            method = "DELETE"
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        return ConnectionApiResult(true, "Kisi adi silindi.", true)
    }

    private fun isValidContactUserId(id: String): Boolean =
        Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        ).matches(id)

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

    fun sendMessage(
        context: Context,
        connectionId: String,
        message: String,
        messageType: String = "text"
    ): ConnectionApiResult<MessageApiItem> {

        if (connectionId.isBlank()) {
            return ConnectionApiResult(
                false,
                "Bağlantı bilgisi bulunamadı."
            )
        }

        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty() || cleanMessage.length > 4000) {
            return ConnectionApiResult(
                false,
                "Mesaj 1-4000 karakter arasında olmalı."
            )
        }

        if (messageType !in listOf("text", "emergency", "location")) {
            return ConnectionApiResult(
                false,
                "Geçersiz mesaj türü."
            )
        }

        val body = JSONObject()
            .put("connectionId", connectionId)
            .put("body", cleanMessage)
            .put("messageType", messageType)

        val response = request(
            context = context,
            path = "/messages",
            method = "POST",
            body = body
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        val messageObject = response.optJSONObject("message")
            ?: return ConnectionApiResult(
                false,
                "Sunucu mesaj bilgilerini döndürmedi."
            )

        val item = parseMessage(
            item = messageObject,
            recipientUsername = messageObject.optString(
                "recipientUsername",
                ""
            ),
            connectionId = connectionId,
            isOutgoing = true
        )

        return ConnectionApiResult(
            success = true,
            message = "Mesaj gönderildi.",
            data = item
        )
    }

    fun getInbox(
        context: Context,
        limit: Int = 50
    ): ConnectionApiResult<List<MessageApiItem>> {

        val safeLimit = limit.coerceIn(1, 50)

        val response = request(
            context = context,
            path = "/messages/inbox?limit=$safeLimit",
            method = "GET"
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        val array = response.optJSONArray("messages")
            ?: JSONArray()

        val items = mutableListOf<MessageApiItem>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue

            items.add(
                parseMessage(
                    item = item,
                    recipientUsername = item.optString(
                        "recipientUsername",
                        ""
                    ),
                    connectionId = item.optString(
                        "connectionId",
                        ""
                    ),
                    isOutgoing = item.optBoolean(
                        "isOutgoing",
                        false
                    )
                )
            )
        }

        return ConnectionApiResult(
            success = true,
            message = response.optString(
                "message",
                "Mesajlar alındı."
            ),
            data = items
        )
    }

    fun getMessageSummary(
        context: Context
    ): ConnectionApiResult<MessageSummaryApiItem> {

        val response = request(
            context = context,
            path = "/messages/summary",
            method = "GET"
        )

        if (!response.optBoolean("success", false)) {
            return failure(response)
        }

        val unreadArray = response.optJSONArray("unreadByConnection")
            ?: JSONArray()

        val unreadByConnection = mutableListOf<UnreadConnectionCount>()

        for (index in 0 until unreadArray.length()) {
            val item = unreadArray.optJSONObject(index) ?: continue

            unreadByConnection.add(
                UnreadConnectionCount(
                    connectionId = item.optString("connectionId", ""),
                    count = item.optInt("count", 0).coerceAtLeast(0)
                )
            )
        }

        val summary = MessageSummaryApiItem(
            unreadTotal = response.optInt(
                "unreadTotal",
                0
            ).coerceAtLeast(0),
            unreadByConnection = unreadByConnection,
            pendingInvitations = response.optInt(
                "pendingInvitations",
                0
            ).coerceAtLeast(0)
        )

        return ConnectionApiResult(
            success = true,
            message = "Mesaj ve davet sayıları alındı.",
            data = summary
        )
    }

    fun markMessageRead(
        context: Context,
        messageId: String
    ): ConnectionApiResult<Boolean> {

        if (messageId.isBlank()) {
            return ConnectionApiResult(
                false,
                "Mesaj kimliği bulunamadı."
            )
        }

        val response = request(
            context = context,
            path = "/messages/$messageId/read",
            method = "PATCH"
        )

        return ConnectionApiResult(
            success = response.optBoolean("success", false),
            message = response.optString(
                "message",
                "Okundu bilgisi güncellenemedi."
            ),
            data = response.optBoolean("success", false)
        )
    }

    // FIREBASE BILDIRIM TOKENINI SUNUCUYA KAYDET
    fun registerFcmToken(
        context: Context,
        token: String
    ): ConnectionApiResult<Boolean> {

        val cleanToken = token.trim()

        if (cleanToken.isEmpty() || cleanToken.length > 4096) {
            return ConnectionApiResult(
                false,
                "Geçersiz bildirim tokenı."
            )
        }

        val body = JSONObject().put(
            "token",
            cleanToken
        )

        val response = request(
            context = context,
            path = "/notifications/register-token",
            method = "POST",
            body = body
        )

        return ConnectionApiResult(
            success = response.optBoolean("success", false),
            message = response.optString(
                "message",
                "Bildirim kaydedilemedi."
            ),
            data = response.optBoolean("success", false)
        )
    }

    private fun parseMessage(
        item: JSONObject,
        recipientUsername: String,
        connectionId: String = "",
        isOutgoing: Boolean = false
    ): MessageApiItem {

        return MessageApiItem(
            id = item.optString("id", ""),
            senderUsername = item.optString("senderUsername", ""),
            recipientUsername = recipientUsername,
            body = item.optString("body", ""),
            messageType = item.optString("messageType", "text"),
            createdAt = item.optString("createdAt", ""),
            deliveredAt = nullableString(item, "deliveredAt"),
            readAt = nullableString(item, "readAt"),
            connectionId = connectionId,
            isOutgoing = isOutgoing
        )
    }

    private fun nullableString(
        item: JSONObject,
        key: String
    ): String? {
        return if (item.isNull(key)) {
            null
        } else {
            item.optString(key, "").takeIf { it.isNotBlank() }
        }
    }

    private fun parseItem(
        item: JSONObject,
        emailField: String
    ): ConnectionApiItem {

        return ConnectionApiItem(
            id = item.optString("id", ""),
            email = item.optString(emailField, ""),
            createdAt = item.optString("createdAt", ""),
            contactUserId = item.optString("contactUserId", "")
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
            Log.w(TAG, "İstek öncesi access token boş; yenileme deneniyor.")
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

        Log.w(TAG, "Korumalı istek HTTP 401 döndürdü; token yenileme deneniyor.")

        val newToken = refreshAccessToken(
            appContext,
            token
        )

        if (newToken.isNullOrBlank()) {
            Log.e(TAG, "Token yenileme başarısız; korumalı istek tekrarlanmadı.")
            return errorResponse(
                "Oturum süresi doldu. Oturum yenilenemedi."
            )
        }

        Log.i(TAG, "Token yenilendi; korumalı istek bir kez tekrarlanıyor.")

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

                if (
                    !currentAccessToken.isNullOrBlank() &&
                    currentAccessToken != rejectedAccessToken
                ) {
                    return currentAccessToken
                }

                val refreshToken =
                    SecureSessionStore.getRefreshToken(context)

                if (refreshToken.isNullOrBlank()) {
                    Log.e(TAG, "Refresh token cihazda bulunamadı veya çözülemedi.")
                    return null
                }

                Log.i(TAG, "Refresh token mevcut; /auth/session/refresh çağrılıyor.")

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
                    Log.e(
                        TAG,
                        "Refresh yanıtı başarısız. HTTP=" + result.statusCode +
                            ", success=" + result.json.optBoolean("success", false) +
                            ", message=" + result.json.optString("message", "yok")
                    )
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
                    Log.e(
                        TAG,
                        "Refresh yanıtında token alanlarından biri eksik. access=" +
                            newAccessToken.isNotBlank() +
                            ", refresh=" + newRefreshToken.isNotBlank()
                    )
                    return null
                }

                SecureSessionStore.updateTokens(
                    context,
                    newAccessToken,
                    newRefreshToken
                )

                Log.i(TAG, "Yeni tokenlar güvenli alana kaydedildi.")

                return newAccessToken

            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Refresh sırasında Android tarafında hata: " +
                        error.javaClass.simpleName + ": " +
                        (error.localizedMessage ?: "mesaj yok")
                )
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
