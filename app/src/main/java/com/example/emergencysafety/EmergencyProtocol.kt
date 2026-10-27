
package com.example.emergencysafety

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale

data class EmergencyProtocolContact(
    val name: String,
    val phone: String
)

data class EmergencyProtocolSendResult(
    val contactName: String,
    val phone: String,
    val accepted: Boolean,
    val error: String? = null,
    val apiKeyRejected: Boolean = false
)

object EmergencyProtocol {

    private const val TAG = "EmergencySafety"

    /**
     * Kayıtlı mesajı hazırlar ve mümkünse konum bağlantısını ekler.
     * Konum alınamaması mesajın gönderilmesini engellemez.
     */
    suspend fun prepareMessage(
        context: Context,
        originalMessage: String
    ): String {

        val message = originalMessage.trim()

        if (message.isBlank()) {
            return message
        }

        val emergencyLocation = try {
            EmergencyLocationProvider.getBestAvailableLocation(
                context.applicationContext
            )
        } catch (e: Exception) {
            Log.e(TAG, "Konum alınırken hata oluştu.", e)
            null
        }

        val locationText = if (emergencyLocation == null) {
            "Konum şu anda alınamadı. Telefonun konum ayarlarını kontrol edin."
        } else {
            val location = emergencyLocation.location

            val coordinates = String.format(
                Locale.US,
                "%.6f,%.6f",
                location.latitude,
                location.longitude
            )

            val mapUrl =
                "https://www.google.com/maps/search/?api=1&query=$coordinates"

            if (emergencyLocation.isLastKnown) {
                val ageMillis = (
                    System.currentTimeMillis() - location.time
                ).coerceAtLeast(0L)

                val ageMinutes = ageMillis / 60000L

                "Son bilinen konum (yaklaşık $ageMinutes dakika önce):\n$mapUrl"
            } else {
                "Güncel konum:\n$mapUrl"
            }
        }

        val placeholder = Regex(
            "\\[Otomatik konum bağlantısı\\]",
            RegexOption.IGNORE_CASE
        )

        return if (placeholder.containsMatchIn(message)) {
            placeholder.replace(message) {
                locationText
            }
        } else {
            "$message\n\n$locationText"
        }
    }

    /**
     * Gönderimleri mevcut WhatsAppWorkerClient üzerinden yapar.
     * Worker adresi, HTTP isteği ve API anahtarı biçimi değiştirilmez.
     */
    suspend fun sendToContacts(
        apiKey: String,
        message: String,
        contacts: List<EmergencyProtocolContact>
    ): List<EmergencyProtocolSendResult> {

        val results = mutableListOf<EmergencyProtocolSendResult>()

        for (contact in contacts) {
            val result = try {
                WhatsAppWorkerClient.sendMessage(
                    phoneNumber = contact.phone,
                    message = message,
                    apiKey = apiKey
                )
            } catch (e: Exception) {
                Result.failure<String>(e)
            }

            val sendResult = result.fold(
                onSuccess = { response ->
                    val accepted = try {
                        JSONObject(response)
                            .optBoolean("success", false)
                    } catch (_: Exception) {
                        false
                    }

                    if (accepted) {
                        EmergencyProtocolSendResult(
                            contactName = contact.name,
                            phone = contact.phone,
                            accepted = true
                        )
                    } else {
                        EmergencyProtocolSendResult(
                            contactName = contact.name,
                            phone = contact.phone,
                            accepted = false,
                            error = "Sunucu gönderimi başarılı olarak doğrulamadı."
                        )
                    }
                },
                onFailure = { error ->
                    val errorText = error.message
                        ?: "Gönderim başarısız."

                    EmergencyProtocolSendResult(
                        contactName = contact.name,
                        phone = contact.phone,
                        accepted = false,
                        error = errorText,
                        apiKeyRejected = errorText.contains(
                            "HTTP 401",
                            ignoreCase = true
                        )
                    )
                }
            )

            results.add(sendResult)

            // Anahtar reddedildiyse diğer kişilere aynı anahtarla
            // istek göndermeye devam etme.
            if (sendResult.apiKeyRejected) {
                break
            }
        }

        return results
    }
}
