
package com.example.emergencysafety

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class EsEmergencySendResult(
    val contactName: String,
    val connectionId: String,
    val accepted: Boolean,
    val error: String? = null
)

internal data class EsEmergencySendSummary(
    val results: List<EsEmergencySendResult>,
    val error: String? = null
) {
    val acceptedCount: Int
        get() = results.count { it.accepted }

    val failedCount: Int
        get() = results.count { !it.accepted }
}

internal object EsEmergencySender {

    private const val PREFS_NAME =
        "EmergencyContactSettings"

    private const val MESSAGE_KEY =
        "emergency_message"

    private const val MAX_CONTACTS = 3

    private const val EMERGENCY_HEADER =
        "🚨 ACİL DURUM 🚨"

    private data class Recipient(
        val connectionId: String,
        val name: String
    )

    suspend fun send(
        context: Context
    ): EsEmergencySendSummary = withContext(Dispatchers.IO) {

        val appContext = context.applicationContext

        val prefs = appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val savedMessage = prefs.getString(
            MESSAGE_KEY,
            ""
        )?.trim().orEmpty()

        if (savedMessage.isBlank()) {
            return@withContext EsEmergencySendSummary(
                results = emptyList(),
                error = "Acil durum mesajı kaydedilmemiş."
            )
        }

        val recipients = mutableListOf<Recipient>()
        val usedConnectionIds = mutableSetOf<String>()

        for (slot in 1..MAX_CONTACTS) {

            val connectionId = prefs.getString(
                "es_contact${slot}_id",
                ""
            )?.trim().orEmpty()

            val savedName = prefs.getString(
                "es_contact${slot}_name",
                ""
            )?.trim().orEmpty()

            if (connectionId.isBlank()) {
                continue
            }

            if (!usedConnectionIds.add(connectionId)) {
                continue
            }

            val name = savedName.takeIf {
                it.isNotBlank() && !it.contains("@")
            } ?: "ES kişisi $slot"

            recipients.add(
                Recipient(
                    connectionId = connectionId,
                    name = name
                )
            )
        }

        if (recipients.isEmpty()) {
            return@withContext EsEmergencySendSummary(
                results = emptyList(),
                error = "En az bir ES acil durum kişisi seçilmeli."
            )
        }

        val preparedMessage = if (
            savedMessage.startsWith(EMERGENCY_HEADER)
        ) {
            savedMessage
        } else {
            "$EMERGENCY_HEADER\n\n$savedMessage"
        }

        if (preparedMessage.length > 4000) {
            return@withContext EsEmergencySendSummary(
                results = emptyList(),
                error = "Acil durum mesajı 4000 karakteri aşıyor."
            )
        }

        val results = mutableListOf<EsEmergencySendResult>()

        for (recipient in recipients) {

            val result = try {
                val response = ConnectionApi.sendMessage(
                    context = appContext,
                    connectionId = recipient.connectionId,
                    message = preparedMessage,
                    messageType = "emergency"
                )

                if (response.success) {
                    EsEmergencySendResult(
                        contactName = recipient.name,
                        connectionId = recipient.connectionId,
                        accepted = true
                    )
                } else {
                    EsEmergencySendResult(
                        contactName = recipient.name,
                        connectionId = recipient.connectionId,
                        accepted = false,
                        error = response.message
                    )
                }

            } catch (e: Exception) {

                EsEmergencySendResult(
                    contactName = recipient.name,
                    connectionId = recipient.connectionId,
                    accepted = false,
                    error = e.message
                        ?: "Gönderim sırasında bağlantı hatası."
                )
            }

            results.add(result)
        }

        EsEmergencySendSummary(
            results = results
        )
    }
}
