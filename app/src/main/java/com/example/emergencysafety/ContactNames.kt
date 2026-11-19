
package com.example.emergencysafety

import android.content.Context
import java.util.Locale

/**
 * EmergencySafety kişi isimleri yöneticisi.
 *
 * Kullanıcının kişilere verdiği özel isimleri
 * yalnızca kendi cihazında saklar.
 *
 * E-posta adresleri hesap eşleştirmesi için
 * kullanılmaya devam eder.
 */
internal object ContactNames {

    private const val PREFS_NAME =
        "EmergencySafetyContactNames"

    private fun normalizeEmail(email: String): String {
        return email.trim().lowercase(Locale.ROOT)
    }

    /**
     * Bir kişiye özel isim kaydeder.
     */
    fun saveName(
        context: Context,
        email: String,
        name: String
    ) {
        val normalizedEmail = normalizeEmail(email)

        if (normalizedEmail.isBlank()) return

        val cleanName = name.trim()

        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        if (cleanName.isBlank()) {
            preferences.edit()
                .remove(normalizedEmail)
                .apply()
        } else {
            preferences.edit()
                .putString(normalizedEmail, cleanName)
                .apply()
        }
    }

    /**
     * Kişinin kayıtlı özel adını döndürür.
     * İsim bulunamazsa e-posta adresini gösterir.
     */
    fun getDisplayName(
        context: Context,
        email: String
    ): String {
        val normalizedEmail = normalizeEmail(email)

        if (normalizedEmail.isBlank()) {
            return ""
        }

        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val savedName = preferences.getString(
            normalizedEmail,
            null
        )?.trim().orEmpty()

        return savedName.ifBlank { email.trim() }
    }

    /**
     * Kişinin özel ismi olup olmadığını kontrol eder.
     */
    fun hasName(
        context: Context,
        email: String
    ): Boolean {
        val normalizedEmail = normalizeEmail(email)

        if (normalizedEmail.isBlank()) return false

        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        return !preferences.getString(
            normalizedEmail,
            null
        ).isNullOrBlank()
    }

    /**
     * Kişiye verilen özel ismi siler.
     * Bu işlem sunucudaki bağlantıyı silmez.
     */
    fun removeName(
        context: Context,
        email: String
    ) {
        val normalizedEmail = normalizeEmail(email)

        if (normalizedEmail.isBlank()) return

        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .remove(normalizedEmail)
            .apply()
    }
}
