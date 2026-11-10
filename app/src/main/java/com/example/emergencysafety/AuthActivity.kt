
package com.example.emergencysafety

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

    private val activityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var continueWithoutAccountButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        emailInput = findViewById(R.id.authEmail)
        passwordInput = findViewById(R.id.authPassword)
        loginButton = findViewById(R.id.btnAuthLogin)

        continueWithoutAccountButton =
            findViewById(R.id.btnAuthContinueWithoutAccount)

        loginButton.setOnClickListener {
            attemptLogin()
        }

        continueWithoutAccountButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
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

                Toast.makeText(
                    this@AuthActivity,
                    "Matrix hesabına giriş başarılı.",
                    Toast.LENGTH_LONG
                ).show()
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
