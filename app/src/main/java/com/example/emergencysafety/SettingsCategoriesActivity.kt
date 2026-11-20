
package com.example.emergencysafety

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsCategoriesActivity : AppCompatActivity() {

    private val backgroundColor = Color.rgb(24, 5, 8)
    private val panelColor = Color.rgb(43, 10, 15)
    private val redColor = Color.rgb(190, 32, 43)
    private val whiteColor = Color.WHITE
    private val mutedColor = Color.rgb(225, 194, 198)

    private var sendingInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings_categories)

        findViewById<View>(
            R.id.btnContactMessageSettings
        ).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    ContactMessageSettingsActivity::class.java
                )
            )
        }

        findViewById<View>(
            R.id.btnVoiceCommandSettings
        ).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    VoiceCommandSettingsActivity::class.java
                )
            )
        }

        findViewById<View>(
            R.id.btnManualEmergencyProtocol
        ).setOnClickListener {
            showFirstConfirmation()
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density + 0.5f
        ).toInt()
    }

    private fun roundedBackground(
        color: Int,
        strokeColor: Int = redColor
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(color)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun createButton(
        title: String,
        background: Int
    ): Button {
        return Button(this).apply {
            text = title
            textSize = 14f
            isAllCaps = false
            setTextColor(whiteColor)
            setTypeface(null, Typeface.BOLD)
            this.background = roundedBackground(background)
            minHeight = dp(48)
            minimumHeight = dp(48)
        }
    }

    private fun showEsDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String? = "VAZGEÇ",
        onPositive: () -> Unit
    ) {
        if (isFinishing || isDestroyed) return

        val dialog = Dialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(20),
                dp(18),
                dp(18)
            )
            background = roundedBackground(backgroundColor)
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(whiteColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        root.addView(titleView)

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
        }

        val messageView = TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(mutedColor)
            setLineSpacing(dp(4).toFloat(), 1f)
            gravity = Gravity.CENTER
            setPadding(
                dp(6),
                dp(8),
                dp(6),
                dp(12)
            )
        }

        scrollView.addView(messageView)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val positiveButton = createButton(
            positiveText,
            redColor
        )

        buttonContainer.addView(
            positiveButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        if (negativeText != null) {
            val negativeButton = createButton(
                negativeText,
                panelColor
            )

            val negativeParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(10)
            }

            buttonContainer.addView(
                negativeButton,
                negativeParams
            )

            negativeButton.setOnClickListener {
                dialog.dismiss()
            }
        }

        root.addView(buttonContainer)

        dialog.setContentView(root)
        dialog.setCancelable(true)

        positiveButton.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }

        dialog.window?.apply {
            setBackgroundDrawableResource(
                android.R.color.transparent
            )
            addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            setDimAmount(0.75f)
        }

        dialog.show()

        val screenHeight =
            resources.displayMetrics.heightPixels

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f)
                .toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        root.layoutParams?.height =
            ViewGroup.LayoutParams.WRAP_CONTENT

        scrollView.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

        scrollView.maximumHeightCompat(
            (screenHeight * 0.50f).toInt()
        )
    }

    private fun ScrollView.maximumHeightCompat(
        maxHeight: Int
    ) {
        post {
            if (height > maxHeight) {
                layoutParams = layoutParams.apply {
                    height = maxHeight
                }
            }
        }
    }

    private fun showFirstConfirmation() {
        if (sendingInProgress) return

        showEsDialog(
            title = "🚨 ACİL DURUM PROTOKOLÜ",
            message = """
                Manuel acil durum protokolünü başlatmak üzeresiniz.

                Kaydettiğiniz acil durum mesajı, seçtiğiniz ES kişilerine uygulamanın kendi mesajlaşma sistemi üzerinden gönderilecektir.

                Bu işlem gerçek mesaj gönderimi yapar.

                Bu aşamada canlı konum paylaşımı, fotoğraf çekimi, ses kaydı ve ekran karartma başlatılmaz.

                Devam etmek istiyor musunuz?
            """.trimIndent(),
            positiveText = "DEVAM ET",
            negativeText = "VAZGEÇ"
        ) {
            showFinalConfirmation()
        }
    }

    private fun showFinalConfirmation() {
        if (sendingInProgress) return

        showEsDialog(
            title = "SON ONAY",
            message = """
                DİKKAT!

                Bir sonraki butona bastığınızda acil durum mesajınız seçtiğiniz ES kişilerine gönderilmeye çalışılacaktır.

                Bu bir önizleme değildir.

                Gönderimi gerçekten başlatmak istiyor musunuz?
            """.trimIndent(),
            positiveText = "ACİL DURUMU BAŞLAT",
            negativeText = "İPTAL ET"
        ) {
            beginEmergencySending()
        }
    }

    private fun beginEmergencySending() {
        if (sendingInProgress) return

        sendingInProgress = true

        val manualButton = findViewById<View>(
            R.id.btnManualEmergencyProtocol
        )

        manualButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val summary = EsEmergencySender.send(
                    applicationContext
                )

                if (isFinishing || isDestroyed) {
                    return@launch
                }

                if (summary.error != null) {
                    showEsDialog(
                        title = "GÖNDERİM BAŞLATILAMADI",
                        message = summary.error,
                        positiveText = "TAMAM",
                        negativeText = null
                    ) {}
                    return@launch
                }

                val resultMessage = buildString {
                    append(
                        "Sunucunun kabul ettiği mesaj: "
                    )
                    append(summary.acceptedCount)
                    append(" / ")
                    append(summary.results.size)

                    if (summary.failedCount > 0) {
                        append("\n\nBaşarısız gönderimler:\n")

                        summary.results
                            .filter { !it.accepted }
                            .forEach { result ->
                                append("\n")
                                append(result.contactName)
                                append(": ")
                                append(
                                    result.error
                                        ?: "Gönderim başarısız."
                                )
                            }
                    }

                    append(
                        "\n\nSunucunun mesajı kabul etmesi, " +
                        "alıcının telefonuna teslim edildiği " +
                        "veya okunduğu anlamına gelmez."
                    )
                }

                showEsDialog(
                    title = if (
                        summary.failedCount == 0 &&
                        summary.acceptedCount > 0
                    ) {
                        "GÖNDERİM İSTEĞİ KABUL EDİLDİ"
                    } else {
                        "GÖNDERİM SONUCU"
                    },
                    message = resultMessage,
                    positiveText = "TAMAM",
                    negativeText = null
                ) {}

            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    showEsDialog(
                        title = "GÖNDERİM HATASI",
                        message = e.message
                            ?: "Beklenmeyen bir hata oluştu.",
                        positiveText = "TAMAM",
                        negativeText = null
                    ) {}
                }
            } finally {
                sendingInProgress = false
                manualButton.isEnabled = true
            }
        }
    }
}
