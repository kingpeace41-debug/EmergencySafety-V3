
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

class YellowSafetyActivity : AppCompatActivity() {

    private lateinit var panelContainer: FrameLayout
    private lateinit var messagesColumn: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var quickHelpContainer: LinearLayout
    private lateinit var historyDatabase: ESBotDatabase

    private var isChatOpen = false

    private val gold = Color.rgb(232, 184, 55)
    private val darkBackground = Color.rgb(19, 19, 18)
    private val panelBackground = Color.rgb(31, 30, 26)
    private val incomingBubble = Color.rgb(48, 47, 42)
    private val outgoingBubble = Color.rgb(105, 78, 20)
    private val softText = Color.rgb(205, 201, 185)

    private val botPreferences by lazy {
        getSharedPreferences(
            "ESBotSettings",
            Context.MODE_PRIVATE
        )
    }

    private data class BotMessage(
        val id: Long,
        val message: String,
        val isOutgoing: Boolean,
        val timestamp: Long
    )

    private data class BotReply(
        val text: String,
        val actionLabel: String? = null,
        val destination: Class<out Activity>? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Misafir kullanıcı mesajlaşma alanına giremez.
        if (!isRegisteredUser()) {
            showGuestAccessDialog()
            return
        }

        historyDatabase = ESBotDatabase(this)

        setContentView(R.layout.activity_yellow_safety)

        panelContainer = findViewById(
            R.id.messagePanelContainer
        )

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

    // KAYITLI KULLANICI KONTROLÜ
    private fun isRegisteredUser(): Boolean {
        val preferences = getSharedPreferences(
            "AccountSettings",
            MODE_PRIVATE
        )

        val isGuest = preferences.getBoolean(
            "guest_mode",
            true
        )

        val email = preferences.getString(
            "account_email",
            ""
        ).orEmpty().trim()

        val userId = preferences.getString(
            "api_user_id",
            ""
        ).orEmpty().trim()

        val accessToken = try {
            SecureSessionStore.getAccessToken(
                applicationContext
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }

        return !isGuest &&
            email.isNotBlank() &&
            userId.isNotBlank() &&
            accessToken.isNotBlank()
    }

    // MİSAFİR UYARI PENCERESİ
    private fun showGuestAccessDialog() {
        showBrandedDialog(
            title = "KAYITLI KULLANICI GİRİŞİ",
            message =
                "Mesajlaşma bölümüne erişmek için " +
                "kayıt olmalısınız.\n\n" +
                "Bu bölüm yalnızca kayıtlı kullanıcılar " +
                "içindir.\n\n" +
                "Lütfen hesabınıza giriş yapın veya " +
                "yeni bir hesap oluşturun.",
            positiveText = "ÖNCE KAYIT OLUN",
            negativeText = "GERİ DÖN",
            cancelable = false,
            onPositive = {
                val intent = Intent(
                    this,
                    AuthActivity::class.java
                ).apply {
                    putExtra(
                        AuthActivity.EXTRA_FORCE_SHOW_ACCOUNT,
                        true
                    )
                }

                startActivity(intent)
                finish()
            },
            onNegative = {
                finish()
            }
        )
    }

    // EMERGENCY SAFETY ORTAK UYARI PENCERESİ
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
            setPadding(
                dp(22),
                dp(20),
                dp(22),
                dp(20)
            )

            background = GradientDrawable().apply {
                setColor(Color.rgb(16, 27, 48))
                cornerRadius = dp(18).toFloat()
                setStroke(
                    dp(1),
                    Color.rgb(64, 81, 107)
                )
            }
        }

        val logo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(72),
                dp(72)
            )

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
            setPadding(
                dp(4),
                dp(18),
                dp(4),
                dp(10)
            )
        }

        panel.addView(titleView)

        val messageView = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.rgb(196, 208, 224))
            gravity = Gravity.CENTER
            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(12)
            )
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
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        buttonRow.addView(
            negativeButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
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
            setBackgroundDrawable(
                ColorDrawable(Color.TRANSPARENT)
            )

            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f)
                    .toInt(),
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

            setPadding(
                dp(7),
                dp(10),
                dp(7),
                dp(10)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density + 0.5f
        ).toInt()
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

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat(
            "HH:mm",
            Locale("tr", "TR")
        ).format(Date(timestamp))
    }

    private fun formatListTime(timestamp: Long): String {
        return SimpleDateFormat(
            "dd.MM HH:mm",
            Locale("tr", "TR")
        ).format(Date(timestamp))
    }

    // SOHBET LİSTESİ
    private fun showConversationList() {
        isChatOpen = false
        panelContainer.removeAllViews()

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(12),
                dp(14),
                dp(12),
                dp(10)
            )

            background = roundedBackground(
                darkBackground,
                gold,
                18
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(14)
            )
        }

        val title = createText(
            "MESAJLAR",
            22f,
            gold,
            true
        )

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val subtitle = createText(
            "EMERGENCY SAFETY",
            10f,
            softText,
            true
        )

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

        val sectionTitle = createText(
            "SOHBETLER",
            11f,
            gold,
            true
        )

        sectionTitle.setPadding(
            dp(5),
            dp(16),
            dp(5),
            dp(10)
        )

        screen.addView(sectionTitle)

        // ES BOT HER ZAMAN LİSTENİN EN ÜSTÜNDE
        val botRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(10),
                dp(12),
                dp(10),
                dp(12)
            )

            background = roundedBackground(
                panelBackground,
                gold,
                14
            )

            isClickable = true
            isFocusable = true

            setOnClickListener {
                showBotChat()
            }
        }

        val botAvatar = TextView(this).apply {
            text = "ES"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(darkBackground)
            gravity = Gravity.CENTER
            background = roundedBackground(
                gold,
                gold,
                50
            )
        }

        botRow.addView(
            botAvatar,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        val botInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(12),
                0,
                dp(4),
                0
            )
        }

        val botName = createText(
            "ES Bot",
            16f,
            Color.WHITE,
            true
        )

        val latestMessage = historyDatabase.getLatestMessage()

        val previewText = latestMessage?.message
            ?: "Merhaba! Ben EmergencySafety Bot."

        val botPreview = createText(
            previewText,
            12f,
            softText
        )

        botPreview.maxLines = 1
        botPreview.ellipsize =
            android.text.TextUtils.TruncateAt.END

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

        val testLabel = createText(
            "TEST",
            10f,
            gold,
            true
        )

        rightInfo.addView(testLabel)

        if (latestMessage != null) {
            val timeText = createText(
                formatListTime(latestMessage.timestamp),
                9f,
                softText
            )

            rightInfo.addView(timeText)
        }

        botRow.addView(rightInfo)

        screen.addView(
            botRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val listDivider = View(this).apply {
            setBackgroundColor(Color.rgb(65, 61, 48))
        }

        screen.addView(
            listDivider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )

        val emptyArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(15),
                dp(30),
                dp(15),
                dp(30)
            )
        }

        val emptyIcon = createText(
            "◌",
            42f,
            gold
        )

        emptyIcon.gravity = Gravity.CENTER

        val emptyTitle = createText(
            "Sohbetlerin burada görünecek",
            15f,
            Color.WHITE,
            true
        )

        emptyTitle.gravity = Gravity.CENTER

        val emptyDescription = createText(
            "Bağlantı kurduğun kişilerle yaptığın " +
                "konuşmalar bu listede yer alacak.",
            12f,
            softText
        )

        emptyDescription.gravity = Gravity.CENTER

        emptyArea.addView(emptyIcon)
        emptyArea.addView(emptyTitle)

        emptyArea.addView(
            emptyDescription,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(7)
            }
        )

        screen.addView(
            emptyArea,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        panelContainer.addView(
            screen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ES BOT SOHBET EKRANI
    private fun showBotChat() {
        isChatOpen = true
        panelContainer.removeAllViews()

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            background = roundedBackground(
                darkBackground,
                gold,
                18
            )

            setPadding(
                dp(10),
                dp(8),
                dp(10),
                dp(10)
            )
        }

        // SOHBET BAŞLIĞI
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(2),
                dp(4),
                dp(2),
                dp(10)
            )
        }

        val backButton = createText(
            "‹",
            36f,
            gold,
            true
        )

        backButton.gravity = Gravity.CENTER

        backButton.setOnClickListener {
            showConversationList()
        }

        header.addView(
            backButton,
            LinearLayout.LayoutParams(
                dp(42),
                dp(46)
            )
        )

        val avatar = TextView(this).apply {
            text = "ES"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(darkBackground)
            gravity = Gravity.CENTER

            background = roundedBackground(
                gold,
                gold,
                50
            )
        }

        header.addView(
            avatar,
            LinearLayout.LayoutParams(
                dp(42),
                dp(42)
            )
        )

        val nameArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(10),
                0,
                0,
                0
            )
        }

        nameArea.addView(
            createText(
                "ES Bot",
                16f,
                Color.WHITE,
                true
            )
        )

        nameArea.addView(
            createText(
                "Yerel uygulama asistanı",
                11f,
                gold
            )
        )

        header.addView(
            nameArea,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val menuButton = createText(
            "⋮",
            27f,
            gold,
            true
        )

        menuButton.gravity = Gravity.CENTER
        menuButton.setPadding(
            dp(8),
            0,
            dp(4),
            0
        )

        menuButton.setOnClickListener {
            showChatMenu(menuButton)
        }

        header.addView(
            menuButton,
            LinearLayout.LayoutParams(
                dp(42),
                dp(46)
            )
        )

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

        // MESAJLARIN GÖRÜNDÜĞÜ ALAN
        messagesScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false

            setPadding(
                dp(2),
                dp(10),
                dp(2),
                dp(10)
            )
        }

        messagesColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        messagesScroll.addView(
            messagesColumn,
            ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        screen.addView(
            messagesScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // KAYITLI MESAJLARI YÜKLE
        var history = historyDatabase.getMessages()

        if (history.isEmpty()) {
            historyDatabase.addMessage(
                "Merhaba! Ben ES Bot.\n\n" +
                    "EmergencySafety test sohbetine hoş geldin. " +
                    "Sorularını yazabilir veya aşağıdaki hızlı " +
                    "yardım seçeneklerinden birini seçebilirsin.",
                false
            )

            history = historyDatabase.getMessages()
        }

        history.forEach { item ->
            renderMessageBubble(
                item.message,
                item.isOutgoing,
                item.timestamp
            )
        }

        // HIZLI YARDIM SADECE İLK AÇILIŞTA
        quickHelpContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (!botPreferences.getBoolean(
                "quick_help_seen",
                false
            )
        ) {
            addQuickHelpOptions()
        }

        // MESAJ YAZMA ALANI
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                0,
                dp(8),
                0,
                0
            )
        }

        val locationButton = TextView(this).apply {
            text = "⌖"
            textSize = 25f
            setTextColor(gold)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Konum paylaş"

            setOnClickListener {
                Toast.makeText(
                    this@YellowSafetyActivity,
                    "Konum paylaşımı henüz bağlanmadı. " +
                        "Bu özellik sonraki geliştirme aşamasında.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        composer.addView(
            locationButton,
            LinearLayout.LayoutParams(
                dp(42),
                dp(48)
            )
        )

        val messageInput = EditText(this).apply {
            hint = "Mesajını yaz..."
            setHintTextColor(Color.rgb(160, 155, 140))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            minLines = 1

            setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8)
            )

            background = roundedBackground(
                panelBackground,
                Color.rgb(116, 94, 45),
                22
            )
        }

        composer.addView(
            messageInput,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        val sendButton = TextView(this).apply {
            text = "➤"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(darkBackground)
            gravity = Gravity.CENTER

            background = roundedBackground(
                gold,
                gold,
                50
            )

            isClickable = true
            isFocusable = true
            contentDescription = "Mesaj gönder"
        }

        composer.addView(
            sendButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            ).apply {
                marginStart = dp(8)
            }
        )

        sendButton.setOnClickListener {
            sendBotMessage(messageInput)
        }

        messageInput.setOnEditorActionListener { _, _, _ ->
            sendBotMessage(messageInput)
            true
        }

        screen.addView(composer)

        panelContainer.addView(
            screen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        messagesScroll.post {
            messagesScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // SOHBET MENÜSÜ
    private fun showChatMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)

        if (botPreferences.getBoolean(
                "quick_help_seen",
                false
            )
        ) {
            popup.menu.add(
                "Hızlı yardımı tekrar göster"
            ).setOnMenuItemClickListener {
                showQuickHelpAgain()
                true
            }
        }

        popup.menu.add(
            "Sohbeti temizle"
        ).setOnMenuItemClickListener {
            confirmClearChat()
            true
        }

        popup.show()
    }

    private fun showQuickHelpAgain() {
        if (!isChatOpen) return

        botPreferences.edit()
            .putBoolean("quick_help_seen", false)
            .apply()

        quickHelpContainer.removeAllViews()
        addQuickHelpOptions()

        messagesScroll.post {
            messagesScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // HIZLI YARDIM SEÇENEKLERİ
    private fun addQuickHelpOptions() {
        quickHelpContainer.removeAllViews()

        val helpTitle = createText(
            "HIZLI YARDIM",
            11f,
            gold,
            true
        )

        helpTitle.setPadding(
            dp(4),
            dp(14),
            dp(4),
            dp(8)
        )

        quickHelpContainer.addView(helpTitle)

        val options = listOf(
            Pair(
                "EmergencySafety nedir?",
                "EmergencySafety; acil durumlarda güvenlik " +
                    "ve yardım süreçlerini kolaylaştırmak için " +
                    "geliştirilen bir uygulamadır. Amacımız, " +
                    "ihtiyaç anında seçtiğin kişilere ulaşmanı " +
                    "ve güvenlik araçlarını kullanmanı sağlamaktır."
            ),
            Pair(
                "Acil durumda ne yapmalıyım?",
                "Öncelikle kendi güvenliğini sağlamaya çalış. " +
                    "Acil yardıma ihtiyaç varsa Türkiye'de 112'yi " +
                    "ara. EmergencySafety'nin acil durum " +
                    "özelliklerini kullanmadan önce telefonunda " +
                    "doğru şekilde ayarlandığını ve çalıştığını " +
                    "test ettiğinden emin ol. Bu deneme sohbeti " +
                    "tek başına acil yardım çağrısı göndermez."
            ),
            Pair(
                "Konum paylaşımı nasıl çalışacak?",
                "Planımız, sohbet içindeki konum düğmesiyle " +
                    "konumunu harita bağlantısı olarak karşı " +
                    "tarafa gönderebilmen. Ancak konum paylaşımı " +
                    "henüz bu test ekranına bağlanmadı."
            )
        )

        options.forEach { option ->
            val optionButton = TextView(this).apply {
                text = "›  ${option.first}"
                textSize = 13f
                setTextColor(Color.WHITE)

                setPadding(
                    dp(12),
                    dp(11),
                    dp(12),
                    dp(11)
                )

                background = roundedBackground(
                    panelBackground,
                    Color.rgb(116, 94, 45),
                    12
                )

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    hideQuickHelp()

                    addMessageBubble(
                        option.first,
                        true
                    )

                    addMessageBubble(
                        option.second,
                        false
                    )
                }
            }

            quickHelpContainer.addView(
                optionButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(7)
                }
            )
        }

        messagesColumn.addView(
            quickHelpContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun hideQuickHelp() {
        botPreferences.edit()
            .putBoolean("quick_help_seen", true)
            .apply()

        if (::quickHelpContainer.isInitialized) {
            quickHelpContainer.visibility = View.GONE
        }
    }

    // KULLANICI MESAJI GÖNDERİR
    private fun sendBotMessage(input: EditText) {
        val message = input.text.toString().trim()

        if (message.isBlank()) {
            return
        }

        hideQuickHelp()

        addMessageBubble(
            message,
            true
        )

        input.setText("")

        val reply = getBotResponse(message)

        addMessageBubble(
            reply.text,
            false
        )

        if (
            reply.destination != null &&
            !reply.actionLabel.isNullOrBlank()
        ) {
            addNavigationButton(
                reply.actionLabel,
                reply.destination
            )
        }
    }

    // BOT YANITLARI VE EKRAN YÖNLENDİRMELERİ
    private fun getBotResponse(message: String): BotReply {
        val question = message
            .lowercase(Locale("tr", "TR"))
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")

        return when {
            question.contains("112") -> {
                BotReply(
                    "Acil yardıma ihtiyacın varsa Türkiye'de " +
                        "112'yi aramalısın. Öncelikle kendi " +
                        "güvenliğini sağlamaya çalış. Ben bu " +
                        "deneme sohbetinden acil çağrı veya " +
                        "mesaj gönderemem."
                )
            }

            listOf(
                "sesli komut",
                "sesli ayar",
                "komut ayari",
                "sesli tetik",
                "sesli dinleme"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Sesli komutlarını mevcut Sesli Komut " +
                        "Ayarları ekranından düzenleyebilirsin. " +
                        "Bu ekranda kırmızı ve siren komutları " +
                        "bulunur.",
                    "SESLİ KOMUT AYARLARINI AÇ",
                    VoiceCommandSettingsActivity::class.java
                )
            }

            listOf(
                "kisi",
                "kisiler",
                "iletisim ayari",
                "acil mesaj",
                "mesaj ayari",
                "telefon numarasi"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Acil durum kişilerinin bilgilerini ve " +
                        "acil durum mesajını Kişi ve Mesaj " +
                        "Ayarları ekranından düzenleyebilirsin.",
                    "KİŞİ VE MESAJ AYARLARINI AÇ",
                    ContactMessageSettingsActivity::class.java
                )
            }

            listOf(
                "hastane",
                "eczane",
                "polis",
                "jandarma",
                "mavi buton",
                "mavi ekran"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Mavi güvenlik ekranında yakın çevrendeki " +
                        "hastane, polis veya eczane aramalarına " +
                        "ulaşabilirsin. Harita araması için " +
                        "internet bağlantısı gerekir.",
                    "MAVİ GÜVENLİK EKRANINI AÇ",
                    BlueInfoActivity::class.java
                )
            }

            listOf(
                "acil ayar",
                "kirmizi",
                "protokol ayari",
                "acil durum ayari"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Kırmızı güvenlik ayarları ekranından " +
                        "sesli komut, kişi ve mesaj ayarlarına " +
                        "ulaşabilirsin. Bu düğme acil durum " +
                        "protokolünü kendiliğinden başlatmaz.",
                    "ACİL DURUM AYARLARINI AÇ",
                    SettingsCategoriesActivity::class.java
                )
            }

            listOf(
                "acil",
                "yardim",
                "tehlike",
                "guvenlik"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Acil bir durumdaysan öncelikle kendi " +
                        "güvenliğini sağlamaya çalış. Türkiye'de " +
                        "acil yardım için 112'yi arayabilirsin. " +
                        "Uygulamanın acil durum özelliklerinin " +
                        "önceden ayarlanmış ve test edilmiş " +
                        "olması önemlidir. Ben bu sohbetten " +
                        "acil çağrı gönderemem."
                )
            }

            listOf(
                "konum",
                "harita",
                "neredeyim",
                "nerede"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Planımız, sohbet içinden konumunu " +
                        "harita bağlantısı olarak gönderebilmen. " +
                        "Ancak konum paylaşımı henüz bu test " +
                        "ekranına bağlanmadı. Şu an bu düğme " +
                        "konum göndermez."
                )
            }

            listOf(
                "uygulama",
                "emergencysafety",
                "nedir",
                "ne ise yarar"
            ).any { question.contains(it) } -> {
                BotReply(
                    "EmergencySafety, acil durum ve kişisel " +
                        "güvenlik amacıyla geliştirilen bir " +
                        "uygulamadır. Acil durum protokolleri, " +
                        "güvenlik araçları ve uygulama içi " +
                        "kullanıcı iletişimi sunmayı hedefliyor. " +
                        "Bazı iletişim özellikleri henüz " +
                        "geliştirme aşamasındadır."
                )
            }

            listOf(
                "mesaj",
                "sohbet",
                "iletisim",
                "baglanti",
                "kullanici bul"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Planımız, kullanıcıların birbirini " +
                        "bulabildiği, bağlantı isteği gönderdiği " +
                        "ve karşılıklı onaydan sonra özel sohbet " +
                        "edebildiği bir sistem kurmak. Bu " +
                        "mesajlaşma altyapısı henüz tamamlanmadı. " +
                        "Şu anda bu ekran ES Bot ile yerel " +
                        "deneme sohbetidir."
                )
            }

            listOf(
                "merhaba",
                "selam",
                "gunaydin",
                "iyi aksamlar"
            ).any { question.contains(it) } -> {
                BotReply(
                    "Merhaba! Ben ES Bot. EmergencySafety " +
                        "uygulaması, güvenlik özellikleri ve " +
                        "mesajlaşma hakkında sorularını " +
                        "yanıtlamaya çalışabilirim."
                )
            }

            question.contains("tesekkur") ||
                question.contains("sagol") -> {
                BotReply(
                    "Rica ederim! EmergencySafety'yi " +
                        "geliştirmeye devam ediyoruz."
                )
            }

            else -> {
                BotReply(
                    "Seni tam anlayamadım. Şimdilik acil " +
                        "durum, sesli komutlar, kişi ayarları, " +
                        "EmergencySafety veya mesajlaşma " +
                        "hakkında soru sorabilirsin. Ben şu anda " +
                        "internet bağlantısı olmayan yerel " +
                        "deneme asistanıyım."
                )
            }
        }
    }

    // BOTUN AÇABİLECEĞİ MEVCUT EKRANLAR
    private fun addNavigationButton(
        label: String,
        destination: Class<out Activity>
    ) {
        val button = TextView(this).apply {
            text = "›  $label"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)

            setPadding(
                dp(13),
                dp(11),
                dp(13),
                dp(11)
            )

            background = roundedBackground(
                Color.rgb(38, 59, 80),
                Color.rgb(82, 116, 159),
                12
            )

            isClickable = true
            isFocusable = true

            setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            this@YellowSafetyActivity,
                            destination
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(
                        this@YellowSafetyActivity,
                        "Bu ekran şu anda açılamıyor.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        messagesColumn.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(8)
            }
        )

        messagesScroll.post {
            messagesScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // MESAJI VERİTABANINA KAYDET VE GÖSTER
    private fun addMessageBubble(
        message: String,
        isOutgoing: Boolean
    ) {
        val timestamp = System.currentTimeMillis()

        historyDatabase.addMessage(
            message,
            isOutgoing,
            timestamp
        )

        renderMessageBubble(
            message,
            isOutgoing,
            timestamp
        )
    }

    // MESAJ GÖRÜNÜMÜ
    private fun renderMessageBubble(
        message: String,
        isOutgoing: Boolean,
        timestamp: Long
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL

            gravity = if (isOutgoing) {
                Gravity.END
            } else {
                Gravity.START
            }

            setPadding(
                0,
                dp(4),
                0,
                dp(4)
            )
        }

        val messageColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isOutgoing) {
                Gravity.END
            } else {
                Gravity.START
            }
        }

        val bubble = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)

            setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
            )

            maxWidth = (
                resources.displayMetrics.widthPixels * 0.70f
            ).toInt()

            background = roundedBackground(
                if (isOutgoing) {
                    outgoingBubble
                } else {
                    incomingBubble
                },
                if (isOutgoing) {
                    gold
                } else {
                    Color.rgb(85, 78, 58)
                },
                14
            )
        }

        messageColumn.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val time = createText(
            formatTime(timestamp),
            9f,
            softText
        )

        time.setPadding(
            dp(4),
            dp(3),
            dp(4),
            0
        )

        messageColumn.addView(time)

        row.addView(
            messageColumn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        messagesColumn.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        messagesScroll.post {
            messagesScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // SOHBET TEMİZLEME ONAYI
    private fun confirmClearChat() {
        showBrandedDialog(
            title = "SOHBETİ TEMİZLE",
            message =
                "ES Bot ile yaptığın tüm yerel sohbet " +
                "geçmişi bu cihazdan silinecek.\n\n" +
                "Bu işlem uygulama ayarlarını, acil durum " +
                "kişilerini veya diğer uygulama verilerini " +
                "silmez.\n\n" +
                "Sohbeti temizlemek istiyor musun?",
            positiveText = "SOHBETİ TEMİZLE",
            negativeText = "VAZGEÇ",
            onPositive = {
                historyDatabase.clearMessages()

                botPreferences.edit()
                    .putBoolean("quick_help_seen", false)
                    .apply()

                showBotChat()

                Toast.makeText(
                    this,
                    "ES Bot sohbeti temizlendi.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onNegative = {
                // Kullanıcı vazgeçti.
            }
        )
    }

    override fun onDestroy() {
        if (::historyDatabase.isInitialized) {
            historyDatabase.close()
        }

        super.onDestroy()
    }
}

// ES BOT YEREL SOHBET VERİTABANI
// Yalnızca ES Bot mesajlarını saklar.
// Telefonun uygulamaya özel alanında tutulur.
// Harici depolama izni gerektirmez.

private class ESBotDatabase(context: Context) :
    SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

    companion object {
        private const val DATABASE_NAME = "es_bot_history.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_MESSAGES = "bot_messages"
        private const val COLUMN_ID = "id"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_OUTGOING = "is_outgoing"
        private const val COLUMN_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_MESSAGE TEXT NOT NULL,
                $COLUMN_OUTGOING INTEGER NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Mevcut sohbet geçmişi korunur.
    }

    fun addMessage(
        message: String,
        isOutgoing: Boolean,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val values = ContentValues().apply {
            put(COLUMN_MESSAGE, message)
            put(
                COLUMN_OUTGOING,
                if (isOutgoing) 1 else 0
            )
            put(COLUMN_TIMESTAMP, timestamp)
        }

        writableDatabase.insert(
            TABLE_MESSAGES,
            null,
            values
        )
    }

    fun getMessages(): List<YellowSafetyActivity.BotMessage> {
        val result = mutableListOf<YellowSafetyActivity.BotMessage>()

        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                COLUMN_ID,
                COLUMN_MESSAGE,
                COLUMN_OUTGOING,
                COLUMN_TIMESTAMP
            ),
            null,
            null,
            null,
            null,
            "$COLUMN_ID ASC"
        )

        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    YellowSafetyActivity.BotMessage(
                        id = it.getLong(0),
                        message = it.getString(1),
                        isOutgoing = it.getInt(2) == 1,
                        timestamp = it.getLong(3)
                    )
                )
            }
        }

        return result
    }

    fun getLatestMessage(): YellowSafetyActivity.BotMessage? {
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                COLUMN_ID,
                COLUMN_MESSAGE,
                COLUMN_OUTGOING,
                COLUMN_TIMESTAMP
            ),
            null,
            null,
            null,
            null,
            "$COLUMN_ID DESC",
            "1"
        )

        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }

            return YellowSafetyActivity.BotMessage(
                id = it.getLong(0),
                message = it.getString(1),
                isOutgoing = it.getInt(2) == 1,
                timestamp = it.getLong(3)
            )
        }
    }

    fun clearMessages() {
        writableDatabase.delete(
            TABLE_MESSAGES,
            null,
            null
        )
    }
}
