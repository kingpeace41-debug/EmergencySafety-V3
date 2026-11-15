Kotlin

package com.example.emergencysafety

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_SHOW_ACCOUNT =
            "com.example.emergencysafety.FORCE_SHOW_ACCOUNT"

        private const val API_BASE_URL =
            "https://emergency-safety.com/api"

        private const val REQUEST_INITIAL_PERMISSIONS = 301
    }

    private lateinit var emailInput: EditText
    private lateinit var loginButton: Button
    private lateinit var continueWithoutAccountButton: Button

    private lateinit var wolfTouchArea: FrameLayout
    private lateinit var angryWolfImage: ImageView

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wolfHandler = Handler(Looper.getMainLooper())

    private var wolfPlayer: MediaPlayer? = null
    private var wolfReactionActive = false
    private var wolfPressStartedAt = 0L

    private val wolfFadeInDuration = 3000L

    private val accountPreferences by lazy {
        getSharedPreferences("AccountSettings", MODE_PRIVATE)
    }

    private val wolfFadeRunnable = object : Runnable {
        override fun run() {
            if (!wolfReactionActive) return

            val elapsed =
                SystemClock.elapsedRealtime() - wolfPressStartedAt

            val progress =
                (elapsed.toFloat() / wolfFadeInDuration)
                    .coerceIn(0f, 1f)

            angryWolfImage.alpha = progress

            if (progress < 1f) {
                wolfHandler.postDelayed(this, 16L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val onboardingCompleted =
            accountPreferences.getBoolean(
                "onboarding_completed",
                false
            )

        val forceShowAccount =
            intent.getBooleanExtra(
                EXTRA_FORCE_SHOW_ACCOUNT,
                false
            )

        if (onboardingCompleted && !forceShowAccount) {
            val isGuest = accountPreferences.getBoolean(
                "guest_mode",
                true
            )

            val savedUserId = accountPreferences.getString(
                "api_user_id",
                ""
            ).orEmpty()

            if (!isGuest && savedUserId.isNotBlank()) {
                initializeMatrixInBackground(savedUserId)
            }

            openMainActivity()
            return
        }

        setContentView(R.layout.activity_auth)

        emailInput = findViewById(R.id.authEmail)
        loginButton = findViewById(R.id.btnAuthLogin)

        continueWithoutAccountButton =
            findViewById(R.id.btnAuthContinueWithoutAccount)

        wolfTouchArea =
            findViewById(R.id.authWolfTouchArea)

        angryWolfImage =
            findViewById(R.id.authWolfAngryImage)

        angryWolfImage.alpha = 0f

        wolfTouchArea.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWolfReaction()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    stopWolfReaction()
                    true
                }

                else -> true
            }
        }

        loginButton.setOnClickListener {
            requestEmailCode()
        }

        continueWithoutAccountButton.setOnClickListener {
            accountPreferences.edit()
                .putBoolean("onboarding_completed", true)
                .putBoolean("guest_mode", true)
                .apply()

            openMainActivity()
        }

        val requestInitialPermissions =
            intent.getBooleanExtra(
                PermissionGateActivity.EXTRA_REQUEST_INITIAL_PERMISSIONS,
                false
            )

        if (requestInitialPermissions) {
            window.decorView.post {
                requestInitialPermissions()
            }
        }
    }

    /**
     * İlk açılışta bütün normal izinleri ortak
     * izin yöneticisi üzerinden topluca ister.
     */
    private fun requestInitialPermissions() {

        val permissions =
            AppPermissionManager.getInitialPermissions()

        val missingPermissions =
            AppPermissionManager.getMissingPermissions(
                this,
                permissions
            )

        if (missingPermissions.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                missingPermissions,
                REQUEST_INITIAL_PERMISSIONS
            )
        }
    }

    /**
     * İlk açılışta verilen veya reddedilen bütün
     * izin yanıtlarını ortak yöneticide kaydeder.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_INITIAL_PERMISSIONS) {

            AppPermissionManager.recordPermissionResults(
                this,
                permissions,
                grantResults
            )

            val deniedPermissions =
                permissions.filterIndexed { index, _ ->
                    index < grantResults.size &&
                        grantResults[index] !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }

            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Bazı izinler verilmedi. İlgili özellikleri kullanırken tekrar isteyebiliriz.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startWolfReaction() {
        if (wolfReactionActive) return

        wolfReactionActive = true
        wolfHandler.removeCallbacks(wolfFadeRunnable)

        angryWolfImage.animate().cancel()
        angryWolfImage.alpha = 0f

        try {
            wolfPlayer = MediaPlayer.create(
                this,
                R.raw.kangal_growl
            )

            wolfPlayer?.let { player ->
                player.isLooping = false
                player.start()
            }
        } catch (e: Exception) {
            wolfPlayer?.release()
            wolfPlayer = null
        }

        wolfPressStartedAt = SystemClock.elapsedRealtime()
        wolfHandler.post(wolfFadeRunnable)
    }

    private fun stopWolfReaction() {
        if (!wolfReactionActive) return

        wolfReactionActive = false
        wolfHandler.removeCallbacks(wolfFadeRunnable)

        try {
            wolfPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
        }

        wolfPlayer = null

        angryWolfImage.animate().cancel()
        angryWolfImage.animate()
            .alpha(0f)
            .setDuration(200L)
            .start()
    }

    private fun requestEmailCode() {
        val email = emailInput.text.toString().trim()

        if (email.isBlank()) {
            emailInput.error = "E-posta adresini gir."
            emailInput.requestFocus()
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS
                .matcher(email).matches()
        ) {
            emailInput.error = "Geçerli bir e-posta adresi gir."
            emailInput.requestFocus()
            return
        }

        setLoginLoading(true)

        networkExecutor.execute {
            val result = postJson(
                "/auth/email/request",
                JSONObject().put("email", email)
            )

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post

                setLoginLoading(false)

                if (result.success) {
                    showEmailVerificationDialog(
                        email,
                        result.data.optLong("expiresIn", 120L)
                    )

                    Toast.makeText(
                        this,
                        "Doğrulama kodu e-posta adresine gönderildi.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showMessage(result.message)
                }
            }
        }
    }

    private fun showEmailVerificationDialog(
        email: String,
        initialOtpLifetimeSeconds: Long
    ) {
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_email_verification,
            null
        )

        val descriptionText =
            dialogView.findViewById<TextView>(
                R.id.authVerificationDescription
            )

        val codeInput =
            dialogView.findViewById<EditText>(
                R.id.authVerificationCode
            )

        val verifyButton =
            dialogView.findViewById<Button>(
                R.id.btnAuthVerifyCode
            )

        val resendButton =
            dialogView.findViewById<TextView>(
                R.id.btnAuthResendCode
            )

        descriptionText.text =
            "$email adresine gönderilen 6 haneli kodu gir."

        codeInput.filters = arrayOf(
            InputFilter.LengthFilter(6)
        )

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        var pendingAccessToken: String? = null
        var pendingUserId: String? = null
        var pendingUsername: String? = null
        var pendingEmail: String? = null

        var otpCountdownEndElapsedRealtime = 0L

        val otpCountdownRunnable = object : Runnable {
            override fun run() {
                val remainingMillis =
                    (otpCountdownEndElapsedRealtime -
                        SystemClock.elapsedRealtime()).coerceAtLeast(0L)

                if (remainingMillis > 0L) {
                    val remainingSeconds =
                        (remainingMillis + 999L) / 1000L

                    val minutes = remainingSeconds / 60L
                    val seconds = remainingSeconds % 60L

                    resendButton.isEnabled = false
                    resendButton.text = String.format(
                        Locale("tr", "TR"),
                        "YENİ KOD İSTE (%02d:%02d)",
                        minutes,
                        seconds
                    )

                    mainHandler.postDelayed(this, 1000L)
                } else {
                    resendButton.isEnabled = true
                    resendButton.text = "KODU TEKRAR GÖNDER"
                }
            }
        }

        fun startOtpCountdown(seconds: Long) {
            mainHandler.removeCallbacks(otpCountdownRunnable)

            val safeSeconds = seconds.coerceIn(1L, 300L)
            otpCountdownEndElapsedRealtime =
                SystemClock.elapsedRealtime() + safeSeconds * 1000L

            resendButton.isEnabled = false
            mainHandler.post(otpCountdownRunnable)
        }

        fun beginDeviceSession() {
            val bootstrapToken = pendingAccessToken ?: return
            val userId = pendingUserId ?: return
            val username = pendingUsername ?: return
            val verifiedEmail = pendingEmail ?: return

            verifyButton.isEnabled = false
            verifyButton.text = "OTURUM AÇILIYOR..."

            networkExecutor.execute {
                val sessionResult = postJson(
                    "/auth/session/start",
                    JSONObject(),
                    bootstrapToken
                )

                var sessionSaved = false
                var errorMessage = sessionResult.message

                if (sessionResult.success) {
                    val sessionObject =
                        sessionResult.data.optJSONObject("session")

                    val sessionId =
                        sessionResult.data.optString(
                            "sessionId",
                            sessionResult.data.optString(
                                "session_id",
                                sessionObject?.optString("id", "").orEmpty()
                            )
                        )

                    val sessionAccessToken =
                        sessionResult.data.optString(
                            "accessToken",
                            sessionResult.data.optString(
                                "access_token",
                                sessionObject?.optString(
                                    "accessToken",
                                    ""
                                ).orEmpty()
                            )
                        )

                    val refreshToken =
                        sessionResult.data.optString(
                            "refreshToken",
                            sessionResult.data.optString(
                                "refresh_token",
                                sessionObject?.optString(
                                    "refreshToken",
                                    ""
                                ).orEmpty()
                            )
                        )

                    if (
                        sessionAccessToken.isBlank() ||
                        sessionId.isBlank() ||
                        refreshToken.isBlank()
                    ) {
                        errorMessage =
                            "Sunucu oturum bilgilerini eksik gönderdi."
                    } else {

                        val authCheck = getJson(
                            "/auth/me",
                            sessionAccessToken
                        )

                        if (!authCheck.success) {
                            errorMessage =
                                "Sunucu yeni oturumu doğrulayamadı. Lütfen tekrar giriş yap."
                        } else {
                            try {
                                SecureSessionStore.saveSession(
                                    applicationContext,
                                    sessionAccessToken,
                                    refreshToken,
                                    sessionId,
                                    userId
                                )

                                val preferencesSaved =
                                    accountPreferences.edit()
                                        .remove("api_access_token")
                                        .putString("api_user_id", userId)
                                        .putString("api_username", username)
                                        .putString("account_email", verifiedEmail)
                                        .putBoolean("onboarding_completed", true)
                                        .putBoolean("guest_mode", false)
                                        .commit()

                                if (!preferencesSaved) {
                                    throw IllegalStateException(
                                        "Hesap bilgileri kaydedilemedi."
                                    )
                                }

                                sessionSaved = true

                                initializeMatrixInBackground(userId)

                            } catch (e: Exception) {
                                errorMessage =
                                    "Güvenli oturum kaydedilemedi. Tekrar dene."
                            }
                        }
                    }
                }

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    if (sessionSaved) {
                        dialog.dismiss()

                        Toast.makeText(
                            this,
                            "Giriş başarılı. Sunucu oturumu doğrulandı.",
                            Toast.LENGTH_LONG
                        ).show()

                        openMainActivity()
                    } else {
                        verifyButton.isEnabled = true
                        verifyButton.text = "OTURUMU TEKRAR DENE"

                        showMessage(errorMessage)
                    }
                }
            }
        }

        verifyButton.setOnClickListener {
            if (!pendingAccessToken.isNullOrBlank()) {
                beginDeviceSession()
                return@setOnClickListener
            }

            val code = codeInput.text.toString().trim()

            if (!code.matches(Regex("^[0-9]{6}$"))) {
                codeInput.error = "6 haneli kodu gir."
                return@setOnClickListener
            }

            verifyButton.isEnabled = false
            verifyButton.text = "DOĞRULANIYOR..."

            networkExecutor.execute {
                val result = postJson(
                    "/auth/email/verify",
                    JSONObject()
                        .put("email", email)
                        .put("code", code)
                )

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    if (!result.success) {
                        verifyButton.isEnabled = true
                        verifyButton.text = "DOĞRULA VE DEVAM ET"
                        showMessage(result.message)
                        return@post
                    }

                    val user =
                        result.data.optJSONObject("user")

                    val accessToken =
                        result.data.optString("accessToken", "")

                    val userId =
                        user?.optString("id", "").orEmpty()

                    val username =
                        user?.optString("username", "").orEmpty()

                    val verifiedEmail =
                        user?.optString("email", email).orEmpty()

                    if (
                        accessToken.isBlank() ||
                        userId.isBlank() ||
                        username.isBlank()
                    ) {
                        verifyButton.isEnabled = true
                        verifyButton.text = "DOĞRULA VE DEVAM ET"

                        showMessage(
                            "Sunucu yanıtı eksik. Oturum açılamadı."
                        )
                        return@post
                    }

                    pendingAccessToken = accessToken
                    pendingUserId = userId
                    pendingUsername = username
                    pendingEmail = verifiedEmail

                    beginDeviceSession()
                }
            }
        }

        resendButton.setOnClickListener {
            if (!pendingAccessToken.isNullOrBlank()) {
                showMessage(
                    "E-posta zaten doğrulandı. Oturum işlemini tamamla."
                )
                return@setOnClickListener
            }

            resendButton.isEnabled = false
            resendButton.text = "YENİ KOD GÖNDERİLİYOR..."

            networkExecutor.execute {
                val result = postJson(
                    "/auth/email/request",
                    JSONObject().put("email", email)
                )

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    if (result.success) {
                        startOtpCountdown(
                            result.data.optLong("expiresIn", 120L)
                        )

                        showMessage(
                            "Yeni kod e-posta adresine gönderildi."
                        )
                    } else {
                        val retryAfter = result.data.optLong(
                            "retryAfter",
                            result.data.optLong("retry_after", 0L)
                        )

                        if (retryAfter > 0L) {
                            startOtpCountdown(retryAfter)
                        } else {
                            resendButton.isEnabled = true
                            resendButton.text = "KODU TEKRAR GÖNDER"
                        }

                        showMessage(result.message)
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(otpCountdownRunnable)
        }

        dialog.show()

        startOtpCountdown(initialOtpLifetimeSeconds)

        dialog.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private data class ApiResult(
        val success: Boolean,
        val message: String,
        val data: JSONObject
    )

    /**
     * POST istekleri.
     */
    private fun postJson(
        path: String,
        body: JSONObject,
        bearerToken: String? = null
    ): ApiResult {
        return requestJson(
            path = path,
            method = "POST",
            body = body,
            bearerToken = bearerToken
        )
    }

    /**
     * Korumalı GET isteği.
     */
    private fun getJson(
        path: String,
        bearerToken: String
    ): ApiResult {
        return requestJson(
            path = path,
            method = "GET",
            body = null,
            bearerToken = bearerToken
        )
    }

    /**
     * Ortak HTTP istek yöneticisi.
     */
    private fun requestJson(
        path: String,
        method: String,
        body: JSONObject?,
        bearerToken: String? = null
    ): ApiResult {
        var connection: HttpURLConnection? = null

        return try {
            connection =
                (URL(API_BASE_URL + path).openConnection()
                    as HttpURLConnection).apply {

                    requestMethod = method
                    connectTimeout = 15000
                    readTimeout = 15000

                    if (method == "POST") {
                        doOutput = true
                    }

                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    if (body != null) {
                        setRequestProperty(
                            "Content-Type",
                            "application/json; charset=utf-8"
                        )
                    }

                    if (!bearerToken.isNullOrBlank()) {
                        setRequestProperty(
                            "Authorization",
                            "Bearer $bearerToken"
                        )
                    }
                }

            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(
                        body.toString().toByteArray(StandardCharsets.UTF_8)
                    )
                }
            }

            val status = connection.responseCode

            val stream =
                if (status in 200..299) {
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

            val json =
                if (responseText.isNotBlank()) {
                    JSONObject(responseText)
                } else {
                    JSONObject()
                }

            ApiResult(
                success = status in 200..299 &&
                    json.optBoolean("success", false),

                message = json.optString(
                    "message",
                    "Sunucu isteği tamamlayamadı. Tekrar deneyin."
                ),

                data = json
            )

        } catch (e: Exception) {
            ApiResult(
                success = false,
                message = "Sunucuya bağlanılamadı. İnternet bağlantını kontrol et.",
                data = JSONObject()
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Matrix hesabını arka planda hazırlar.
     *
     * Önce kayıtlı refresh token ile sunucu oturumu yenilenir.
     * Sunucu refresh token değerini de değiştirdiği için
     * iki yeni token birlikte güvenli alana kaydedilir.
     *
     * Ardından yeni access token ile Matrix hazırlığı yapılır.
     * Başarısız olursa sonraki uygulama açılışında yeniden denenir.
     */
    private fun initializeMatrixInBackground(
        userId: String
    ) {
        if (userId.isBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val savedRefreshToken =
                    SecureSessionStore.getRefreshToken(
                        applicationContext
                    )

                if (savedRefreshToken.isNullOrBlank()) {
                    android.util.Log.w(
                        "EmergencySafety",
                        "Matrix hazırlığı: kayıtlı yenileme anahtarı bulunamadı."
                    )
                    return@launch
                }

                // Önce cihaz oturumunu yenile.
                val refreshResult = postJson(
                    "/auth/session/refresh",
                    JSONObject().put(
                        "refreshToken",
                        savedRefreshToken
                    )
                )

                if (!refreshResult.success) {
                    android.util.Log.w(
                        "EmergencySafety",
                        "Matrix hazırlığı: sunucu oturumu yenilenemedi."
                    )
                    return@launch
                }

                val newAccessToken =
                    refreshResult.data.optString(
                        "accessToken",
                        ""
                    )

                val newRefreshToken =
                    refreshResult.data.optString(
                        "refreshToken",
                        ""
                    )

                if (
                    newAccessToken.isBlank() ||
                    newRefreshToken.isBlank()
                ) {
                    android.util.Log.w(
                        "EmergencySafety",
                        "Matrix hazırlığı: yenileme yanıtı eksik."
                    )
                    return@launch
                }

                // Dönen iki yeni anahtarı güvenli alana kaydet.
                SecureSessionStore.updateTokens(
                    applicationContext,
                    newAccessToken,
                    newRefreshToken
                )

                // Yeni erişim anahtarıyla Matrix hesabını hazırla.
                val matrixResult = postJson(
                    "/matrix/initialize",
                    JSONObject(),
                    newAccessToken
                )

                if (matrixResult.success) {
                    accountPreferences.edit()
                        .putString(
                            "matrix_initialized_user_id",
                            userId
                        )
                        .apply()

                    android.util.Log.i(
                        "EmergencySafety",
                        "Matrix hesabı hazırlandı."
                    )
                } else {
                    android.util.Log.w(
                        "EmergencySafety",
                        "Matrix hesabı simdilik hazirlanamadi."
                    )
                }

            } catch (e: Exception) {
                android.util.Log.e(
                    "EmergencySafety",
                    "Matrix hazırlığı sırasında beklenmeyen hata.",
                    e
                )
            }
        }
    }

    private fun setLoginLoading(loading: Boolean) {
        loginButton.isEnabled = !loading

        loginButton.text =
            if (loading) {
                "KOD GÖNDERİLİYOR..."
            } else {
                "KOD GÖNDER"
            }
    }

    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        stopWolfReaction()
        wolfHandler.removeCallbacksAndMessages(null)
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}

object MatrixSession {
    var accessToken: String? = null
    var userId: String? = null
    var deviceId: String? = null
}
