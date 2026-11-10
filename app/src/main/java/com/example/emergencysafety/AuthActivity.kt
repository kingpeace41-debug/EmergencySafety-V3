
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
import java.util.concurrent.Executors

class AuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_SHOW_ACCOUNT =
            "com.example.emergencysafety.FORCE_SHOW_ACCOUNT"

        private const val API_BASE_URL =
            "https://emergency-safety.com/api"
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
    }

    // KANGAL EFEKTI

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
            // Ses zaten bitmis olabilir.
        }

        wolfPlayer = null

        angryWolfImage.animate().cancel()
        angryWolfImage.animate()
            .alpha(0f)
            .setDuration(200L)
            .start()
    }

    // E-POSTA KODU ISTEME

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
            emailInput.error = "Gecerli bir e-posta adresi gir."
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
                    showEmailVerificationDialog(email)

                    Toast.makeText(
                        this,
                        "Dogrulama kodu e-posta adresine gonderildi.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showMessage(result.message)
                }
            }
        }
    }

    // DOGRULAMA PENCERESI

    private fun showEmailVerificationDialog(email: String) {
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
            "$email adresine gonderilen 6 haneli kodu gir."

        codeInput.filters = arrayOf(
            InputFilter.LengthFilter(6)
        )

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        verifyButton.setOnClickListener {
            val code = codeInput.text.toString().trim()

            if (!code.matches(Regex("^[0-9]{6}$"))) {
                codeInput.error = "6 haneli kodu gir."
                return@setOnClickListener
            }

            verifyButton.isEnabled = false
            verifyButton.text = "DOGRULANIYOR..."

            networkExecutor.execute {
                val result = postJson(
                    "/auth/email/verify",
                    JSONObject()
                        .put("email", email)
                        .put("code", code)
                )

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    verifyButton.isEnabled = true
                    verifyButton.text = "DOGRULA VE DEVAM ET"

                    if (result.success) {
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
                            showMessage(
                                "Sunucu yaniti eksik. Oturum acilamadi."
                            )
                            return@post
                        }

                        accountPreferences.edit()
                            .putString("api_access_token", accessToken)
                            .putString("api_user_id", userId)
                            .putString("api_username", username)
                            .putString("account_email", verifiedEmail)
                            .putBoolean("onboarding_completed", true)
                            .putBoolean("guest_mode", false)
                            .apply()

                        dialog.dismiss()

                        Toast.makeText(
                            this,
                            "E-posta dogrulandi. Hos geldin!",
                            Toast.LENGTH_LONG
                        ).show()

                        openMainActivity()
                    } else {
                        showMessage(result.message)
                    }
                }
            }
        }

        resendButton.setOnClickListener {
            resendButton.isEnabled = false
            resendButton.text = "KOD GONDERILIYOR..."

            networkExecutor.execute {
                val result = postJson(
                    "/auth/email/request",
                    JSONObject().put("email", email)
                )

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    resendButton.isEnabled = true
                    resendButton.text = "KODU TEKRAR GONDER"

                    showMessage(
                        if (result.success) {
                            "Yeni kod e-posta adresine gonderildi."
                        } else {
                            result.message
                        }
                    )
                }
            }
        }

        dialog.show()

        dialog.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // API ILETISIMI

    private data class ApiResult(
        val success: Boolean,
        val message: String,
        val data: JSONObject
    )

    private fun postJson(
        path: String,
        body: JSONObject
    ): ApiResult {
        var connection: HttpURLConnection? = null

        return try {
            connection =
                (URL(API_BASE_URL + path).openConnection()
                    as HttpURLConnection).apply {

                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true

                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                    )

                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                }

            connection.outputStream.use { output ->
                output.write(
                    body.toString().toByteArray(StandardCharsets.UTF_8)
                )
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
                    "Sunucu istegi tamamlayamadi. Tekrar deneyin."
                ),

                data = json
            )

        } catch (e: Exception) {
            ApiResult(
                success = false,
                message = "Sunucuya baglanilamadi. Internet baglantini kontrol et.",
                data = JSONObject()
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun setLoginLoading(loading: Boolean) {
        loginButton.isEnabled = !loading

        loginButton.text =
            if (loading) {
                "KOD GONDERILIYOR..."
            } else {
                "KOD GONDER"
            }
    }

    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    // YARDIMCI ISLEMLER

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
