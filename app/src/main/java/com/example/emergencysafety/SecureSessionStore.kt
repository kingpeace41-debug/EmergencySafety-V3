
package com.example.emergencysafety

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kullanıcının oturum bilgilerini Android Keystore
 * kullanarak şifreli biçimde cihazda saklar.
 *
 * Şifreleme anahtarı Android Keystore dışına çıkarılmaz.
 */
object SecureSessionStore {

    private const val PREFS_NAME = "SecureSessionSettings"

    private const val KEY_ALIAS =
        "com.example.emergencysafety.session.key"

    private const val ACCESS_TOKEN_KEY = "access_token"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val SESSION_ID_KEY = "session_id"
    private const val USER_ID_KEY = "user_id"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private val lock = Any()

    /**
     * Oturum bilgilerini şifreleyerek kaydeder.
     */
    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String,
        sessionId: String,
        userId: String
    ) {
        require(accessToken.isNotBlank()) {
            "Access token boş olamaz."
        }

        require(refreshToken.isNotBlank()) {
            "Refresh token boş olamaz."
        }

        require(sessionId.isNotBlank()) {
            "Session ID boş olamaz."
        }

        require(userId.isNotBlank()) {
            "User ID boş olamaz."
        }

        synchronized(lock) {
            val preferences = preferences(context)

            val encryptedAccessToken = encrypt(accessToken)
            val encryptedRefreshToken = encrypt(refreshToken)
            val encryptedSessionId = encrypt(sessionId)
            val encryptedUserId = encrypt(userId)

            preferences.edit()
                .putString(ACCESS_TOKEN_KEY, encryptedAccessToken)
                .putString(REFRESH_TOKEN_KEY, encryptedRefreshToken)
                .putString(SESSION_ID_KEY, encryptedSessionId)
                .putString(USER_ID_KEY, encryptedUserId)
                .apply()
        }
    }

    /**
     * Saklanan access token bilgisini çözer.
     */
    fun getAccessToken(context: Context): String? {
        return synchronized(lock) {
            readEncryptedValue(context, ACCESS_TOKEN_KEY)
        }
    }

    /**
     * Saklanan refresh token bilgisini çözer.
     */
    fun getRefreshToken(context: Context): String? {
        return synchronized(lock) {
            readEncryptedValue(context, REFRESH_TOKEN_KEY)
        }
    }

    /**
     * Saklanan oturum kimliğini çözer.
     */
    fun getSessionId(context: Context): String? {
        return synchronized(lock) {
            readEncryptedValue(context, SESSION_ID_KEY)
        }
    }

    /**
     * Saklanan kullanıcı kimliğini çözer.
     */
    fun getUserId(context: Context): String? {
        return synchronized(lock) {
            readEncryptedValue(context, USER_ID_KEY)
        }
    }

    /**
     * Refresh işleminden sonra yeni tokenları kaydeder.
     * Yeni tokenlar kaydedilmeden eskileri silinmez.
     */
    fun updateTokens(
        context: Context,
        accessToken: String,
        refreshToken: String
    ) {
        require(accessToken.isNotBlank()) {
            "Access token boş olamaz."
        }

        require(refreshToken.isNotBlank()) {
            "Refresh token boş olamaz."
        }

        synchronized(lock) {
            val encryptedAccessToken = encrypt(accessToken)
            val encryptedRefreshToken = encrypt(refreshToken)

            preferences(context).edit()
                .putString(ACCESS_TOKEN_KEY, encryptedAccessToken)
                .putString(REFRESH_TOKEN_KEY, encryptedRefreshToken)
                .apply()
        }
    }

    /**
     * Kullanıcı çıkış yaptığında cihazdaki oturum bilgilerini
     * ve bu oturum için kullanılan Keystore anahtarını siler.
     */
    fun clearSession(context: Context) {
        synchronized(lock) {
            preferences(context).edit()
                .clear()
                .commit()

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private fun readEncryptedValue(
        context: Context,
        key: String
    ): String? {
        val encryptedValue = preferences(context).getString(key, null)
            ?: return null

        return try {
            decrypt(encryptedValue)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Android Keystore anahtarını getirir.
     * Anahtar yoksa cihaz üzerinde yeni AES anahtarı üretir.
     */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existingKey = keyStore.getKey(KEY_ALIAS, null)

        if (existingKey is SecretKey) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()

        keyGenerator.init(keySpec)

        return keyGenerator.generateKey()
    }

    /**
     * Metni AES-GCM ile şifreler.
     * Her şifreleme işleminde yeni bir IV kullanılır.
     */
    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateSecretKey()
        )

        val encryptedBytes = cipher.doFinal(
            plainText.toByteArray(Charsets.UTF_8)
        )

        val iv = Base64.encodeToString(
            cipher.iv,
            Base64.NO_WRAP
        )

        val encrypted = Base64.encodeToString(
            encryptedBytes,
            Base64.NO_WRAP
        )

        return "$iv:$encrypted"
    }

    /**
     * Şifreli metni Android Keystore anahtarıyla çözer.
     */
    private fun decrypt(encryptedValue: String): String {
        val parts = encryptedValue.split(":", limit = 2)

        require(parts.size == 2) {
            "Şifreli oturum verisi geçersiz."
        }

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(
            parts[1],
            Base64.NO_WRAP
        )

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )

        val decryptedBytes = cipher.doFinal(encryptedBytes)

        return String(decryptedBytes, Charsets.UTF_8)
    }
}
