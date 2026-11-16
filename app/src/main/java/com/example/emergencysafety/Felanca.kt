package com.example.emergencysafety

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ESBotMessage(
    val id: Long,
    val message: String,
    val isOutgoing: Boolean,
    val timestamp: Long
)

class YellowSafetyActivity : AppCompatActivity() {

    private lateinit var panelContainer: FrameLayout
    private lateinit var messagesColumn: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var quickHelpContainer: LinearLayout
    private lateinit var historyDatabase: ESBotDatabase

    private var isChatOpen = false
    private var activePrivateConnection: ConnectionApiItem? = null
    private var privateMessagesColumn: LinearLayout? = null
    private var privateMessagesScroll: ScrollView? = null
    private var privateStatusText: TextView? = null
    private var privateInput: EditText? = null
    private var privateSendButton: TextView? = null
    private var isSendingMessage = false

    private val gold = Color.rgb(232, 184, 55)
    private val darkBackground = Color.rgb(19, 19, 18)
    private val panelBackground = Color.rgb(31, 30, 26)
    private val incomingBubble = Color.rgb(48, 47, 42)
    private val outgoingBubble = Color.rgb(105, 78, 20)
    private val softText = Color.rgb(205, 201, 185)

    private val botPreferences by lazy {
        getSharedPreferences("ESBotSettings", Context.MODE_PRIVATE)
    }

    private data class BotReply(
        val text: String,
        val actionLabel: String? = null,
        val destination: Class<out Activity>? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isRegisteredUser()) {
            showGuestAccessDialog()
            return
        }

        historyDatabase = ESBotDatabase(this)
        setContentView(R.layout.activity_yellow_safety)

        panelContainer = findViewById(R.id.messagePanelContainer)
        showConversationList()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isChatOpen) {
                        showConversationList()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    private fun isRegisteredUser(): Boolean {
        val preferences = getSharedPreferences(
            "AccountSettings",
            MODE_PRIVATE
        )

        val isGuest = preferences.getBoolean("guest_mode", true)
        val email = preferences.getString("account_email", "").orEmpty().trim()
        val userId = preferences.getString("api_user_id", "").orEmpty().trim()

        val accessToken = try {
            SecureSessionStore.getAccessToken(applicationContext).orEmpty()
        } catch (_: Exception) {
            ""
        }

        return !isGuest &&
            email.isNotBlank() &&
            userId.isNotBlank() &&
            accessToken.isNotBlank()
    }

    private fun showGuestAccessDialog() {
        showBrandedDialog(
            title = "KAYITLI KULLANICI GİRİŞİ",
            message =
                "Mesajlaşma bölümüne erişmek için " +
                "kayıt olmalısınız.\n\n" +
                "Bu bölüm yalnızca kayıtlı kullanıcılar içindir.\n\n" +
                "Lütfen hesabınıza giriş yapın veya yeni bir hesap oluşturun.",
            positiveText = "ÖNCE KAYIT OLUN",
            negativeText = "GERİ DÖN",
            cancelable = false,
            onPositive = {
                val intent = Intent(this, AuthActivity::class.java).apply {
                    putExtra(AuthActivity.EXTRA_FORCE_SHOW_ACCOUNT, true)
                }
                startActivity(intent)
                finish()
            },
            onNegative = {
                finish()
            }
        )
    }

    private fun showBrandedDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        cancelable: Boolean = true,
        onPositive: () -> Unit,
        onNegative: () -> Unit
    ) {
        val dialog = Dialog(this)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(16, 27, 48))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(64, 81, 107))
            }
        }

        val logo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            setImageResource(R.drawable.yeni_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "EmergencySafety logosu"
        }
        panel.addView(logo)

        val brand = TextView(this).apply {
            text = "EMERGENCY SAFETY"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(brand)

        val subtitle = TextView(this).apply {
            text = "SİZİN GÜVENLİĞİNİZ, BİZİM ÖNCELİĞİMİZ"
            textSize = 10f
            setTextColor(Color.rgb(158, 180, 208))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        }
        panel.addView(subtitle)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(Color.rgb(64, 81, 107))
        }
        panel.addView(divider)

        val titleView = TextView(this).apply {
            text = title
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(18), dp(4), dp(10))
        }
        panel.addView(titleView)

        val messageView = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.rgb(196, 208, 224))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        panel.addView(messageView)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        val positiveButton = createDialogButton(
            positiveText,
            Color.rgb(198, 40, 61),
            Color.rgb(224, 75, 91)
        )

        val negativeButton = createDialogButton(
            negativeText,
            Color.rgb(38, 59, 80),
            Color.rgb(82, 116, 159)
        )

        buttonRow.addView(
            positiveButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        )

        buttonRow.addView(
            negativeButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        )

        panel.addView(buttonRow)

        positiveButton.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
            onNegative()
        }

        dialog.setContentView(panel)
        dialog.setCancelable(cancelable)
        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createDialogButton(
        text: String,
        backgroundColor: Int,
        strokeColor: Int
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), strokeColor)
            }
            setPadding(dp(7), dp(10), dp(7), dp(10))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun roundedBackground(
        color: Int,
        strokeColor: Int = Color.TRANSPARENT,
        radius: Int = 14
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale("tr", "TR"))
            .format(Date(timestamp))
    }

    private fun formatListTime(timestamp: Long): String {
        return SimpleDateFormat("dd.MM HH:mm", Locale("tr", "TR"))
            .format(Date(timestamp))
    }

    private fun parseServerTime(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()

        return try {
            val normalized = value.replace("Z", "+0000")
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            )

            formats.firstNotNullOfOrNull { format ->
                try {
                    format.parse(normalized)?.time
                } catch (_: Exception) {
                    null
                }
            } ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun showConversationList() {
        isChatOpen = false
        activePrivateConnection = null
        privateMessagesColumn = null
        privateMessagesScroll = null
        privateStatusText = null
        privateInput = null
        privateSendButton = null

        panelContainer.removeAllViews()

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(10))
            background = roundedBackground(darkBackground, gold, 18)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(14))
        }

        val title = createText("MESAJLAR", 22f, gold, true)
        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val subtitle = createText("EMERGENCY SAFETY", 10f, softText, true)
        subtitle.gravity = Gravity.CENTER_VERTICAL
        header.addView(subtitle)
        screen.addView(header)

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(91, 75, 37))
        }
        screen.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val listContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

        val sectionTitle = createText("SOHBETLER", 11f, gold, true)
        sectionTitle.setPadding(dp(5), dp(4), dp(5), dp(10))
        listContent.addView(sectionTitle)

        val botRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = roundedBackground(panelBackground, gold, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { showBotChat() }
        }

        val botAvatar = TextView(this).apply {
            text = "ES"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(darkBackground)
            gravity = Gravity.CENTER
            background = roundedBackground(gold, gold, 50)
        }
        botRow.addView(botAvatar, LinearLayout.LayoutParams(dp(48), dp(48)))

        val botInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }

        val botName = createText("ES Bot", 16f, Color.WHITE, true)
        val latestMessage = historyDatabase.getLatestMessage()
        val previewText = latestMessage?.message
            ?: "Merhaba! Ben EmergencySafety Bot."

        val botPreview = createText(previewText, 12f, softText)
        botPreview.maxLines = 1
        botPreview.ellipsize = android.text.TextUtils.TruncateAt.END

        botInfo.addView(botName)
        botInfo.addView(botPreview)

        botRow.addView(
            botInfo,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val rightInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        rightInfo.addView(createText("TEST", 10f, gold, true))

        if (latestMessage != null) {
            rightInfo.addView(
                createText(
                    formatListTime(latestMessage.timestamp),
                    9f,
                    softText
                )
            )
        }

        botRow.addView(rightInfo)

        listContent.addView(
            botRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        val peopleTitle = createText("BAĞLANTILARIM", 11f, gold, true)
        peopleTitle.setPadding(dp(5), dp(10), dp(5), dp(10))
        listContent.addView(peopleTitle)

        val connectionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        
