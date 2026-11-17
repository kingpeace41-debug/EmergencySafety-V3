package com.example.emergencysafety

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.Executors

object FcmTokenRegistrar {

    private const val TAG = "ES_FCM_REGISTER"

    private val executor = Executors.newSingleThreadExecutor()

    fun registerIfLoggedIn(context: Context) {
        val appContext = context.applicationContext

        val userId = try {
            SecureSessionStore.getUserId(appContext)
        } catch (_: Exception) {
            null
        }

        val accessToken = try {
            SecureSessionStore.getAccessToken(appContext)
        } catch (_: Exception) {
            null
        }

        if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            Log.i(TAG, "Kayıtlı oturum yok; FCM token kaydı atlandı.")
            return
        }

        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "Firebase token alınamadı.", task.exception)
                    return@addOnCompleteListener
                }

                val firebaseToken = task.result

                if (firebaseToken.isNullOrBlank()) {
                    Log.e(TAG, "Firebase boş token döndürdü.")
                    return@addOnCompleteListener
                }

                executor.execute {
                    try {
                        val result = ConnectionApi.registerFcmToken(
                            appContext,
                            firebaseToken
                        )

                        if (result.success) {
                            Log.i(TAG, "FCM token sunucuya kaydedildi.")
                        } else {
                            Log.e(
                                TAG,
                                "FCM token kaydı başarısız: ${result.message}"
                            )
                        }
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "FCM token kayıt hatası.",
                            error
                        )
                    }
                }
            }
    }

    fun registerNewToken(
        context: Context,
        token: String
    ) {
        val appContext = context.applicationContext

        val userId = try {
            SecureSessionStore.getUserId(appContext)
        } catch (_: Exception) {
            null
        }

        val accessToken = try {
            SecureSessionStore.getAccessToken(appContext)
        } catch (_: Exception) {
            null
        }

        if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return
        }

        if (token.isBlank()) {
            return
        }

        executor.execute {
            try {
                val result = ConnectionApi.registerFcmToken(
                    appContext,
                    token
                )

                if (result.success) {
                    Log.i(TAG, "Yeni FCM token sunucuya kaydedildi.")
                } else {
                    Log.e(
                        TAG,
                        "Yeni FCM token kaydı başarısız: ${result.message}"
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Yeni FCM token kayıt hatası.", error)
            }
        }
    }
}
