
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_SHOW_ACCOUNT =
            "com.example.emergencysafety.FORCE_SHOW_ACCOUNT"
    }

    private lateinit var emailInput: EditText
    private lateinit var loginButton: Button
    private lateinit var continueWithoutAccountButton: Button

    private lateinit var wolfTouchArea: FrameLayout
    private lateinit var angryWolfImage: ImageView

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
    // E-POSTA KODU İSTEME
    // -------------------------------------------------

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

        showEmailVerificationDialog(email)
    }

    // -------------------------------------------------
    // DOĞRULAMA PENCERESİ
    // -------------------------------------------------

    private fun showEmailVerificationDialog(email: String) {
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_email_verification,
            null
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

        // Henüz e-posta sunucusu bağlı olmadığı için
        // pencerede kod gönderilmediği açıkça belirtilir.
        updateVerificationNotice(dialogView)

        codeInput.filters = arrayOf(
            InputFilter.LengthFilter(6)
        )

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        verifyButton.setOnClickListener {
            val code = codeInput.text.toString().trim()

            if (code.length != 6) {
                codeInput.error = "6 haneli kodu gir."
                return@setOnClickListener
            }

            Toast.makeText(
                this,
                "E-posta doğrulama sunucusu henüz hazır değil. Kod doğrulanmadı.",
                Toast.LENGTH_LONG
            ).show()
        }

        resendButton.setOnClickListener {
            Toast.makeText(
                this,
                "Kod gönderme sunucusu henüz etkinleştirilmedi.",
                Toast.LENGTH_LONG
            ).show()
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

    private fun updateVerificationNotice(view: View) {
        if (view is TextView) {
            if (view.text.toString().contains(
                    "E-posta adresinize gönderilen"
                )
            ) {
                view.text =
                    "Doğrulama sunucusu henüz etkin değil. Şimdilik e-posta kodu gönderilmedi."
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                updateVerificationNotice(view.getChildAt(index))
            }
        }
    }

    // -------------------------------------------------
    // YARDIMCI İŞLEMLER
    // -------------------------------------------------

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        stopWolfReaction()
        wolfHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

object MatrixSession {
    var accessToken: String? = null
    var userId: String? = null
    var deviceId: String? = null
}
