
package com.example.emergencysafety

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.UUID

class AuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_SHOW_ACCOUNT =
            "com.example.emergencysafety.FORCE_SHOW_ACCOUNT"

        private const val HOMESERVER_URL =
            "https://emergency-safety.com"
    }

    private val activityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var registerButton: TextView
    private lateinit var continueWithoutAccountButton: Button

    private lateinit var wolfTouchArea: FrameLayout
    private lateinit var angryWolfImage: ImageView

    private val wolfHandler = Handler(Looper.getMainLooper())
    private var wolfPlayer: MediaPlayer? = null
    private var wolfReactionActive = false
    private var wolfPressStartedAt = 0L

    private val wolfFadeInDuration = 3000L

    private var pendingRegistration: PendingRegistration? = null

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
        getSharedPreferences("AccountSettings", MODE_PRIVATE)
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

        registerButton = findViewById(R.id.btnAuthRegister)

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

        registerButton.setOnClickListener {
            beginRegistration()
        }

        continueWithoutAccountButton.setOnClickListener {
            accountPreferences.edit()
                .putBoolean("onboarding_completed", true)
                .putBoolean("guest_mode", true)
                .apply()

            openMainActivity()
        }
    }

    // -------------------------------------------------
    // KURT EFEKTİ
    // -------------------------------------------------

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
            // Ses zaten bitmiş olabilir.
        }

        wolfPlayer = null

        angryWolfImage.animate().cancel()
        angryWolfImage.animate()
            .alpha(0f)
            .setDuration(200L)
            .start()
    }

    // -------------------------------------------------
    // YENİ HESAP OLUŞTURMA
    // -------------------------------------------------

    private fun beginRegistration() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(
                this,
                "Kayıt için e-posta ve şifre alanlarını doldurun.",
                Toast.LENGTH_LONG
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

        showRegistrationDetailsDialog(email, password)
    }

    private fun showRegistrationDetailsDialog(
        email: String,
        password: String
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }

        val usernameField = EditText(this).apply {
            hint = "Kullanıcı adı (örnek: yeni_kullanici)"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }

        val tokenField = EditText(this).apply {
            hint = "Davet kayıt tokeni"
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }

        container.addView(
            usernameField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            tokenField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("Yeni EmergencySafety hesabı")
            .setMessage(
                "Bir kullanıcı adı ve sana verilen davet tokenini gir."
            )
            .setView(container)
            .setNegativeButton("İPTAL", null)
            .setPositiveButton("DOĞRULAMA E-POSTASI GÖNDER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {

                    val username =
                        usernameField.text.toString().trim()

                    val registrationToken =
                        tokenField.text.toString().trim()

                    if (username.isBlank()) {
                        usernameField.error =
                            "Kullanıcı adı gerekli."
                        return@setOnClickListener
                    }

                    if (registrationToken.isBlank()) {
                        tokenField.error =
                            "Davet tokeni gerekli."
                        return@setOnClickListener
                    }

                    registerButton.isEnabled = false
                    registerButton.text = "KAYIT BAŞLATILIYOR..."

                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .isEnabled = false

                    activityScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            startRegistrationOnServer(
                                email,
                                password,
                                username,
                                registrationToken
                            )
                        }

                        registerButton.isEnabled = true
                        registerButton.text = "YENİ HESAP OLUŞTUR"

                        if (result.success &&
                            result.pendingRegistration != null
                        ) {
                            pendingRegistration =
                                result.pendingRegistration

                            dialog.dismiss()
                            showEmailVerificationDialog()
                        } else {
                            dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE
                            ).isEnabled = true

                            Toast.makeText(
                                this@AuthActivity,
                                result.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
        }

        dialog.show()
    }

    private suspend fun startRegistrationOnServer(
        email: String,
        password: String,
        username: String,
        registrationToken: String
    ): RegistrationStartResult {

        // 1. Kayıt oturumu başlatılır.

        val firstResponse = postJson(
            "/_matrix/client/v3/register",
            JSONObject()
        )

        val firstJson = firstResponse.json

        if (firstJson == null) {
            return RegistrationStartResult(
                success = false,
                message = apiError(firstResponse)
            )
        }

        val session = firstJson.optString("session")

        if (session.isBlank()) {
            return RegistrationStartResult(
                success = false,
                message = "Sunucu kayıt oturumu oluşturamadı."
            )
        }

        // 2. Davet tokeni doğrulanır.

        val tokenAuth = JSONObject().apply {
            put(
                "auth",
                JSONObject().apply {
                    put(
                        "type",
                        "m.login.registration_token"
                    )
                    put("token", registrationToken)
                    put("session", session)
                }
            )
        }

        val tokenResponse = postJson(
            "/_matrix/client/v3/register",
            tokenAuth
        )

        val tokenJson = tokenResponse.json

        if (tokenJson == null) {
            return RegistrationStartResult(
                success = false,
                message = apiError(tokenResponse)
            )
        }

        val completed = tokenJson.optJSONArray("completed")
        var tokenAccepted = false

        if (completed != null) {
            for (i in 0 until completed.length()) {
                if (completed.optString(i) ==
                    "m.login.registration_token"
                ) {
                    tokenAccepted = true
                    break
                }
            }
        }

        if (!tokenAccepted) {
            return RegistrationStartResult(
                success = false,
                message = apiError(tokenResponse)
            )
        }

        val currentSession =
            tokenJson.optString("session").ifBlank { session }

        // 3. E-posta doğrulaması istenir.

        val clientSecret = UUID.randomUUID().toString()

        val emailRequest = JSONObject().apply {
            put("client_secret", clientSecret)
            put("email", email)
            put("send_attempt", 1)
            put("session", currentSession)
        }

        val emailResponse = postJson(
            "/_matrix/client/v3/register/email/requestToken",
            emailRequest
        )

        val emailJson = emailResponse.json

        if (emailResponse.statusCode !in 200..299 ||
            emailJson == null
        ) {
            return RegistrationStartResult(
                success = false,
                message = apiError(emailResponse)
            )
        }

        val sid = emailJson.optString("sid")

        if (sid.isBlank()) {
            return RegistrationStartResult(
                success = false,
                message = "E-posta doğrulama oturumu alınamadı."
            )
        }

        return RegistrationStartResult(
            success = true,
            message = "Doğrulama e-postası gönderildi.",
            pendingRegistration = PendingRegistration(
                email = email,
                password = password,
                username = username,
                clientSecret = clientSecret,
                sid = sid,
                session = currentSession
            )
        )
    }

    // -------------------------------------------------
    // E-POSTA DOĞRULAMA
    // -------------------------------------------------

    private fun showEmailVerificationDialog() {
        val pending = pendingRegistration ?: return

        val dialog = AlertDialog.Builder(this)
            .setTitle("E-posta doğrulaması")
            .setMessage(
                "${pending.email} adresine doğrulama bağlantısı gönderdik.\n\n" +
                    "E-postanı aç, doğrulama bağlantısına tıkla. " +
                    "Ardından buraya dön ve DOĞRULADIM düğmesine bas."
            )
            .setNegativeButton("DAHA SONRA", null)
            .setPositiveButton("DOĞRULADIM", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {

                    dialog.dismiss()

                    registerButton.isEnabled = false
                    registerButton.text = "HESAP OLUŞTURULUYOR..."

                    activityScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            completeRegistration(pending)
                        }

                        registerButton.isEnabled = true
                        registerButton.text = "YENİ HESAP OLUŞTUR"

                        if (result.success) {
                            MatrixSession.accessToken =
                                result.accessToken

                            MatrixSession.userId =
                                result.userId

                            MatrixSession.deviceId =
                                result.deviceId

                            accountPreferences.edit()
                                .putBoolean(
                                    "onboarding_completed",
                                    true
                                )
                                .putBoolean("guest_mode", false)
                                .apply()

                            pendingRegistration = null

                            Toast.makeText(
                                this@AuthActivity,
                                "EmergencySafety hesabın oluşturuldu!",
                                Toast.LENGTH_LONG
                            ).show()

                            openMainActivity()
                        } else {
                            Toast.makeText(
                                this@AuthActivity,
                                result.message,
                                Toast.LENGTH_LONG
                            ).show()

                            showEmailVerificationDialog()
                        }
                    }
                }
        }

        dialog.show()
    }

    private suspend fun completeRegistration(
        pending: PendingRegistration
    ): LoginResult {

        val requestBody = JSONObject().apply {
            put("username", pending.username)
            put("password", pending.password)

            put(
                "initial_device_display_name",
                "EmergencySafety Android"
            )

            put(
                "auth",
                JSONObject().apply {
                    put(
                        "type",
                        "m.login.email.identity"
                    )

                    put(
                        "threepid_creds",
                        JSONObject().apply {
                            put("sid", pending.sid)
                            put(
                                "client_secret",
                                pending.clientSecret
                            )
                        }
                    )

                    put("session", pending.session)
                }
            )
        }

        val response = postJson(
            "/_matrix/client/v3/register",
            requestBody
        )

        val json = response.json

        if (response.statusCode in 200..299 &&
            json != null &&
            json.optString("access_token").isNotBlank()
        ) {
            return LoginResult(
                success = true,
                message = "Hesap oluşturuldu.",
                accessToken = json.optString("access_token"),
                userId = json.optString("user_id"),
                deviceId = json.optString("device_id")
            )
        }

        return LoginResult(
            success = false,
            message = apiError(response)
        )
    }

    // -------------------------------------------------
    // MEVCUT HESABA GİRİŞ
    // -------------------------------------------------

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
                MatrixSession.accessToken =
                    result.accessToken

                MatrixSession.userId =
                    result.userId

                MatrixSession.deviceId =
                    result.deviceId

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

        val response = postJson(
            "/_matrix/client/v3/login",
            requestBody
        )

        val json = response.json

        if (response.statusCode in 200..299 &&
            json != null &&
            json.optString("access_token").isNotBlank()
        ) {
            return LoginResult(
                success = true,
                message = "Giriş başarılı.",
                accessToken = json.optString("access_token"),
                userId = json.optString("user_id"),
                deviceId = json.optString("device_id")
            )
        }

        return LoginResult(
            success = false,
            message = apiError(response)
        )
    }

    // -------------------------------------------------
    // ORTAK SUNUCU İSTEĞİ
    // -------------------------------------------------

    private suspend fun postJson(
        path: String,
        body: JSONObject
    ): ApiResponse {

        val client = HttpClient(CIO)

        return try {
            val response = client.post(
                HOMESERVER_URL + path
            ) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            val responseText = response.bodyAsText()

            val json = try {
                JSONObject(responseText)
            } catch (e: Exception) {
                null
            }

            ApiResponse(
                statusCode = response.status.value,
                json = json,
                rawBody = responseText
            )

        } catch (e: Exception) {
            ApiResponse(
                statusCode = 0,
                json = null,
                rawBody = "",
                transportError = e.message
            )
        } finally {
            client.close()
        }
    }

    private fun apiError(response: ApiResponse): String {
        response.transportError?.let {
            return "Sunucuya bağlanılamadı. İnternet bağlantını kontrol et."
        }

        val json = response.json

        if (json != null) {
            val error = json.optString("error")
            val errorCode = json.optString("errcode")

            if (error.isNotBlank()) {
                return error
            }

            if (errorCode.isNotBlank()) {
                return "Sunucu yanıtı: $errorCode"
            }
        }

        return "İşlem tamamlanamadı. Sunucu yanıt kodu: ${response.statusCode}"
    }

    // -------------------------------------------------
    // YARDIMCI İŞLEMLER
    // -------------------------------------------------

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        stopWolfReaction()
        activityScope.cancel()
        super.onDestroy()
    }

    private data class ApiResponse(
        val statusCode: Int,
        val json: JSONObject?,
        val rawBody: String,
        val transportError: String? = null
    )

    private data class PendingRegistration(
        val email: String,
        val password: String,
        val username: String,
        val clientSecret: String,
        val sid: String,
        val session: String
    )

    private data class RegistrationStartResult(
        val success: Boolean,
        val message: String,
        val pendingRegistration: PendingRegistration? = null
    )

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
