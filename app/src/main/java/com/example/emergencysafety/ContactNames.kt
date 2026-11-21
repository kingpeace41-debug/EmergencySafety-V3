
package com.example.emergencysafety

import android.content.Context
import java.util.Locale

/**
 * EmergencySafety kişi isimleri yöneticisi.
 *
 * Kullanıcının kişilere verdiği özel isimleri
 * cihazda saklar.
 *
 * Kalıcı sunucu eşitlemesi için kayıtlı isimlerin
 * topluca okunmasını da destekler.
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
     *
     * Acil durum ekranındaki e-posta yerine
     * isim gösterme davranışı sonraki aşamada
     * ortak eşitleme sistemiyle düzenlenecek.
     */
    fun getDisplayName(
        context: Context,
        email: String
    ): String {
        val normalizedEmail = normalizeEmail(email)

        if (normalizedEmail.isBlank()) {
            return ""
        }

        val savedName = getSavedName(
            context,
            normalizedEmail
        )

        return savedName ?: email.trim()
    }

    /**
     * Yalnızca kaydedilmiş özel ismi döndürür.
     * İsim yoksa e-posta yerine null döner.
     */
    fun getSavedName(
        context: Context,
        email: String
    ): String? {
        val normalizedEmail = normalizeEmail(email)

        if (normalizedEmail.isBlank()) return null

        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        return preferences.getString(
            normalizedEmail,
            null
        )?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Telefonda kayıtlı bütün özel isimleri
     * e-posta -> isim eşleştirmesi olarak döndürür.
     *
     * Sunucuya ilk aktarımda kullanılacaktır.
     * Bu işlem mevcut kayıtları değiştirmez.
     */
    fun getAllSavedNames(
        context: Context
    ): Map<String, String> {
        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val result = mutableMapOf<String, String>()

        for ((key, value) in preferences.all) {
            val email = normalizeEmail(key)
            val name = (value as? String)?.trim().orEmpty()

            if (email.isNotBlank() && name.isNotBlank()) {
                result[email] = name
            }
        }

        return result.toMap()
    }

    /**
     * Kişinin özel ismi olup olmadığını kontrol eder.
     */
    fun hasName(
        context: Context,
        email: String
    ): Boolean {
        return getSavedName(context, email) != null
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
