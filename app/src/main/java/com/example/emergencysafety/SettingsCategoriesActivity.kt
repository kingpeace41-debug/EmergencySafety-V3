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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsCategoriesActivity : AppCompatActivity() {

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
            setPadding(
                dp(22),
                dp(16),
                dp(22),
                dp(12)
            )
        }

        val message = TextView(this).apply {
            text = """
                ACİL DURUM PROTOKOLÜ

                Onay butonuna tekrar basmanız hâlinde acil durum protokolü başlatılacaktır.

                Protokol kapsamında, uygulamada gerekli ayarlar ve izinler sağlandığında belirlediğiniz kişilere acil durum mesajınızın ve konumunuzun gönderilmesi; belirli aralıklarla fotoğraf ve ses kaydı alınarak aynı kişilere iletilmesi amaçlanmaktadır.

                Acil durum protokolünü gerçekten başlatmak istiyor musunuz?
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
            .setPositiveButton("ONAYLA VE BAŞLAT", null)
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

                    Toast.makeText(
                        this@SettingsCategoriesActivity,
                        "Onay alındı. Protokol işlemleri henüz etkinleştirilmedi.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            dialog.getButton(
                AlertDialog.BUTTON_NEGATIVE
            ).setTextColor(Color.DKGRAY)
        }

        dialog.show()
    }
}
