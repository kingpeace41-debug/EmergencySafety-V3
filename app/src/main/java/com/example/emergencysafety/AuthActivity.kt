
package com.example.emergencysafety

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_SHOW_ACCOUNT =
            "com.example.emergencysafety.FORCE_SHOW_ACCOUNT"
    }

    private val activityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var continueWithoutAccountButton: Button

    private lateinit var wolfTouchArea: FrameLayout
    private lateinit var angryWolfImage: ImageView

    private val wolfHandler = Handler(Looper.getMainLooper())
    private var wolfPlayer: MediaPlayer? = null
    private var wolfReactionActive = false
    private var wolfPressStartedAt = 0L

    private val wolfFadeInDuration = 3000L

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

    private val accountPreferences by lazy {
        getSharedPreferences(
            "AccountSettings",
            MODE_PRIVATE
        )
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
        passwordInput = findViewById(R.id.authPassword)
        loginButton = findViewById(R.id.btnAuthLogin)

        continueWithoutAccountButton =
            findViewById(R.id.btnAuthContinueWithoutAccount)

        wolfTouchArea = findViewById(R.id.authWolfTouchArea)
        angryWolfImage = findViewById(R.id.authWolfAngryImage)

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
            attemptLogin()
        }

        continueWithoutAccountButton.setOnClickListener {
            accountPreferences.edit()
                .putBoolean("onboarding_completed", true)
                .putBoolean("guest_mode", true)
                .apply()

            openMainActivity()
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
            // Ses zaten bitmiş veya durmuş olabilir.
        }

        wolfPlayer = null

        angryWolfImage.animate().cancel()
        angryWolfImage.animate()
            .alpha(0f)
            .setDuration(200L)
            .start()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun attemptLogin() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(
                this,
                "E-posta ve şifre alanlarını doldurun.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS
                .matcher(email).matches()
        ) {
            Toast.makeText(
                this,
                "Geçerli bir e-posta adresi girin.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        loginButton.isEnabled = false
        loginButton.text = "BAĞLANILIYOR..."

        activityScope.launch {
            val result = withContext(Dispatchers.IO) {
                performLogin(email, password)
            }

            loginButton.isEnabled = true
            loginButton.text = "GİRİŞ YAP"

            if (result.success) {
                MatrixSession.accessToken = result.accessToken
                MatrixSession.userId = result.userId
                MatrixSession.deviceId = result.deviceId

                accountPreferences.edit()
                    .putBoolean("onboarding_completed", true)
                    .putBoolean("guest_mode", false)
                    .apply()

                Toast.makeText(
                    this@AuthActivity,
                    "Matrix hesabına giriş başarılı.",
                    Toast.LENGTH_SHORT
                ).show()

                openMainActivity()
            } else {
                Toast.makeText(
                    this@AuthActivity,
                    result.message,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun performLogin(
        email: String,
        password: String
    ): LoginResult {
        val client = HttpClient(CIO)

        return try {
            val requestBody = JSONObject().apply {
                put("type", "m.login.password")

                put(
                    "identifier",
                    JSONObject().apply {
                        put("type", "m.id.thirdparty")
                        put("medium", "email")
                        put("address", email)
                    }
                )

                put("password", password)
                put(
                    "initial_device_display_name",
                    "EmergencySafety Android"
                )
            }

            val response = client.post(
                "https://emergency-safety.com/_matrix/client/v3/login"
            ) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }

            val responseText = response.bodyAsText()

            if (response.status.value in 200..299) {
                val json = JSONObject(responseText)

                LoginResult(
                    success = true,
                    message = "Giriş başarılı.",
                    accessToken = json.optString("access_token"),
                    userId = json.optString("user_id"),
                    deviceId = json.optString("device_id")
                )
            } else {
                val errorJson = JSONObject(responseText)

                LoginResult(
                    success = false,
                    message = errorJson.optString(
                        "error",
                        "Giriş başarısız. Bilgilerinizi kontrol edin."
                    )
                )
            }
        } catch (e: Exception) {
            LoginResult(
                success = false,
                message = "Sunucuya bağlanılamadı. İnternet bağlantınızı kontrol edin."
            )
        } finally {
            client.close()
        }
    }

    override fun onDestroy() {
        stopWolfReaction()
        activityScope.cancel()
        super.onDestroy()
    }

    private data class LoginResult(
        val success: Boolean,
        val message: String,
        val accessToken: String? = null,
        val userId: String? = null,
        val deviceId: String? = null
    )
}

object MatrixSession {
    var accessToken: String? = null
    var userId: String? = null
    var deviceId: String? = null
}
