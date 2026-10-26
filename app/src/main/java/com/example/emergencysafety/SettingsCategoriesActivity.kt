
package com.example.emergencysafety

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsCategoriesActivity : AppCompatActivity() {

    private val contactPrefsName = "EmergencyContactSettings"
    private val connectionPrefsName = "EmergencySafetyConnection"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_categories)

        findViewById<View>(R.id.btnContactMessageSettings)
            .setOnClickListener {
                startActivity(
                    Intent(
                        this,
                        ContactMessageSettingsActivity::class.java
                    )
                )
            }

        findViewById<View>(R.id.btnVoiceCommandSettings)
            .setOnClickListener {
                startActivity(
                    Intent(
                        this,
                        VoiceCommandSettingsActivity::class.java
                    )
                )
            }

        findViewById<View>(R.id.btnManualEmergencyProtocol)
            .setOnClickListener {
                showManualProtocolConfirmation()
            }
    }

    private fun showManualProtocolConfirmation() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int =
            (value * density + 0.5f).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(12))
        }

        val message = TextView(this).apply {
            text = """
                ACİL DURUM PROTOKOLÜ

                İkinci onaydan sonra kaydettiğiniz acil durum mesajı belirlediğiniz kişilere WhatsApp üzerinden gönderilmeye çalışılacaktır.

                Bu test yalnızca acil durum mesajının gönderimini dener. Fotoğraf çekmez, ses kaydı yapmaz ve ekranı karartmaz.

                Gönderim; internet bağlantısına, Cloudflare Worker ve Meta ayarlarına, ayrıca WhatsApp test hesabı kısıtlamalarına bağlıdır.

                Mesaj gönderme denemesini başlatmak istiyor musunuz?
            """.trimIndent()

            textSize = 16f
            setTextColor(Color.rgb(40, 40, 40))
            setLineSpacing(dp(4).toFloat(), 1.0f)
        }

        content.addView(message)

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("ACİL DURUM ONAYI")
            .setView(scrollView)
            .setPositiveButton("ONAYLA VE GÖNDER", null)
            .setNegativeButton("VAZGEÇ", null)
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).apply {
                setTextColor(Color.rgb(190, 32, 43))
                setTypeface(null, Typeface.BOLD)

                setOnClickListener {
                    dialog.dismiss()
                    beginMessageSending()
                }
            }

            dialog.getButton(
                AlertDialog.BUTTON_NEGATIVE
            ).setTextColor(Color.DKGRAY)
        }

        dialog.show()
    }

    private fun beginMessageSending() {
        val prefs = getSharedPreferences(
            contactPrefsName,
            MODE_PRIVATE
        )

        val message = prefs.getString("emergency_message", "")
            ?.trim().orEmpty()

        if (message.isBlank()) {
            Toast.makeText(
                this,
                "Önce Kişi ve Mesaj Ayarları bölümünden acil durum mesajını kaydedin.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val contacts = (1..3).mapNotNull { index ->
            val phone = prefs.getString(
                "contact${index}_phone",
                ""
            )?.trim().orEmpty()

            val name = prefs.getString(
                "contact${index}_name",
                ""
            )?.trim().orEmpty()

            if (phone.isNotBlank()) {
                Pair(
                    name.ifBlank { "Kişi $index" },
                    phone
                )
            } else {
                null
            }
        }

        if (contacts.isEmpty()) {
            Toast.makeText(
                this,
                "Önce en az bir acil durum kişisinin telefonunu kaydedin.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val connectionPrefs = getSharedPreferences(
            connectionPrefsName,
            MODE_PRIVATE
        )

        val savedApiKey = connectionPrefs
            .getString("worker_api_key", "")
            ?.trim().orEmpty()

        if (savedApiKey.isBlank()) {
            showApiKeyDialog(message, contacts)
        } else {
            sendMessages(savedApiKey, message, contacts)
        }
    }

    private fun showApiKeyDialog(
        message: String,
        contacts: List<Pair<String, String>>
    ) {
        val input = EditText(this).apply {
            hint = "Cloudflare APP_API_KEY"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int =
            (value * density + 0.5f).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Worker bağlantı anahtarı")
            .setMessage(
                "Cloudflare Worker'da kayıtlı APP_API_KEY değerini gir.\n\n" +
                    "Bu anahtarı sohbet mesajına yazma. Anahtar bu telefonda saklanır. " +
                    "Bu yöntem kişisel test içindir; uygulama başkalarına dağıtılacaksa " +
                    "daha güvenli bir yapı gerekir."
            )
            .setView(container)
            .setPositiveButton("KAYDET VE DENE") { _, _ ->
                val apiKey = input.text.toString().trim()

                if (apiKey.isBlank()) {
                    Toast.makeText(
                        this,
                        "API anahtarı boş olamaz.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    getSharedPreferences(
                        connectionPrefsName,
                        MODE_PRIVATE
                    ).edit()
                        .putString("worker_api_key", apiKey)
                        .apply()

                    sendMessages(apiKey, message, contacts)
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun sendMessages(
        apiKey: String,
        message: String,
        contacts: List<Pair<String, String>>
    ) {
        Toast.makeText(
            this,
            "WhatsApp mesajları gönderiliyor…",
            Toast.LENGTH_SHORT
        ).show()

        CoroutineScope(Dispatchers.Main).launch {
            var succeeded = 0
            var apiKeyRejected = false
            val errors = mutableListOf<String>()

            for ((name, phone) in contacts) {
                val result = WhatsAppWorkerClient.sendMessage(
                    phoneNumber = phone,
                    message = message,
                    apiKey = apiKey
                )

                result.fold(
                    onSuccess = { response ->
                        val json = try {
                            JSONObject(response)
                        } catch (_: Exception) {
                            JSONObject()
                        }

                        if (json.optBoolean("success", false)) {
                            succeeded++
                        } else {
                            errors.add(
                                "$name: Sunucu gönderimi doğrulamadı."
                            )
                        }
                    },
                    onFailure = { error ->
                        val errorText = error.message.orEmpty()

                        if (errorText.contains("HTTP 401")) {
                            apiKeyRejected = true
                        } else {
                            errors.add(
                                "$name: ${error.message ?: "Gönderim başarısız."}"
                            )
                        }
                    }
                )

                if (apiKeyRejected) break
            }

            if (apiKeyRejected) {
                getSharedPreferences(
                    connectionPrefsName,
                    MODE_PRIVATE
                ).edit()
                    .remove("worker_api_key")
                    .apply()

                AlertDialog.Builder(
                    this@SettingsCategoriesActivity
                )
                    .setTitle("API anahtarı kabul edilmedi")
                    .setMessage(
                        "Girilen anahtar Cloudflare'daki APP_API_KEY ile eşleşmiyor. " +
                            "Eski anahtar bu telefondan silindi. Cloudflare'da kayıtlı " +
                            "doğru anahtarı yeniden girmen gerekiyor."
                    )
                    .setPositiveButton("YENİDEN GİR") { _, _ ->
                        showApiKeyDialog(message, contacts)
                    }
                    .setNegativeButton("DAHA SONRA", null)
                    .show()

                return@launch
            }

            val summary = buildString {
                append(
                    "Sunucunun başarılı olarak bildirdiği gönderim: " +
                        "$succeeded / ${contacts.size}"
                )

                if (errors.isNotEmpty()) {
                    append("\n\n")
                    append(errors.take(3).joinToString("\n"))

                    if (errors.size > 3) {
                        append("\nDiğer hatalar da oluştu.")
                    }
                }

                append(
                    "\n\nBu sonuç tek başına mesajın alıcının WhatsApp'ına " +
                        "ulaştığını garanti etmez."
                )
            }

            AlertDialog.Builder(
                this@SettingsCategoriesActivity
            )
                .setTitle(
                    if (succeeded == contacts.size) {
                        "Gönderim isteği kabul edildi"
                    } else {
                        "Gönderim sonucu"
                    }
                )
                .setMessage(summary)
                .setPositiveButton("TAMAM", null)
                .show()
        }
    }
}
