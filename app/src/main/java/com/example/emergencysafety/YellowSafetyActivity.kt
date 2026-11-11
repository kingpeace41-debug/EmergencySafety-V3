
package com.example.emergencysafety

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class YellowSafetyActivity : AppCompatActivity() {

    private lateinit var panelContainer: FrameLayout
    private lateinit var messagesColumn: LinearLayout
    private lateinit var messagesScroll: ScrollView

    private var isChatOpen = false

    private val gold = Color.rgb(232, 184, 55)
    private val darkBackground = Color.rgb(19, 19, 18)
    private val panelBackground = Color.rgb(31, 30, 26)
    private val incomingBubble = Color.rgb(48, 47, 42)
    private val outgoingBubble = Color.rgb(105, 78, 20)
    private val softText = Color.rgb(205, 201, 185)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun showConversationList() {
        isChatOpen = false
        panelContainer.removeAllViews()

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(10))
            background = roundedBackground(
                darkBackground,
                gold,
                18
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(14))
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

        // ES BOT - HER ZAMAN EN ÜSTTE
        val botRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
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
            background = roundedBackground(gold, gold, 50)
        }

        botRow.addView(
            botAvatar,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val botInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }

        val botName = createText(
            "ES Bot",
            16f,
            Color.WHITE,
            true
        )

        val botPreview = createText(
            "Merhaba! Ben EmergencySafety Bot.",
            12f,
            softText
        )

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

        val testLabel = createText(
            "TEST",
            10f,
            gold,
            true
        )

        botRow.addView(testLabel)

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
            setPadding(dp(15), dp(30), dp(15), dp(30))
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
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // SOHBET BAŞLIĞI
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(10))
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
            LinearLayout.LayoutParams(dp(42), dp(46))
        )

        val avatar = TextView(this).apply {
            text = "ES"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(darkBackground)
            gravity = Gravity.CENTER
            background = roundedBackground(gold, gold, 50)
        }

        header.addView(
            avatar,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        val nameArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }

        nameArea.addView(
            createText("ES Bot", 16f, Color.WHITE, true)
        )

        nameArea.addView(
            createText("Yerel test asistanı", 11f, gold)
        )

        header.addView(
            nameArea,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
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
            setPadding(dp(2), dp(10), dp(2), dp(10))
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

        addMessageBubble(
            "Merhaba! Ben ES Bot.\n\n" +
                "EmergencySafety test sohbetine hoş geldin. " +
                "Sorularını yazabilir veya aşağıdaki hızlı " +
                "yardım seçeneklerinden birini seçebilirsin.",
            false
        )

        // ES BOT HIZLI YARDIM MENÜSÜ
        addQuickHelpOptions()

        // MESAJ YAZMA ALANI
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
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
                    "Konum paylaşımı sonraki aşamada bağlanacak.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        composer.addView(
            locationButton,
            LinearLayout.LayoutParams(dp(42), dp(48))
        )

        val messageInput = EditText(this).apply {
            hint = "Mesajını yaz..."
            setHintTextColor(Color.rgb(160, 155, 140))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            minLines = 1
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
            background = roundedBackground(gold, gold, 50)
            isClickable = true
            isFocusable = true
            contentDescription = "Mesaj gönder"
        }

        composer.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginStart = dp(8)
            }
        )

        sendButton.setOnClickListener {
            val message = messageInput.text.toString().trim()

            if (message.isBlank()) {
                return@setOnClickListener
            }

            addMessageBubble(message, true)
            messageInput.setText("")

            addMessageBubble(
                getBotResponse(message),
                false
            )
        }

        screen.addView(composer)

        panelContainer.addView(
            screen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun addQuickHelpOptions() {
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

        messagesColumn.addView(
            helpTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

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
                    "henüz bu test ekranına bağlanmadı. " +
                    "Sonraki geliştirme aşamalarında bunu " +
                    "uygulama içinden çalışır hâle getireceğiz."
            )
        )

        options.forEach { option ->
            val optionButton = TextView(this).apply {
                text = "›  ${option.first}"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = roundedBackground(
                    panelBackground,
                    Color.rgb(116, 94, 45),
                    12
                )
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    addMessageBubble(option.first, true)
                    addMessageBubble(option.second, false)
                }
            }

            messagesColumn.addView(
                optionButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(7)
                }
            )
        }
    }

    // KULLANICININ YAZDIĞI MESAJI KONUYA GÖRE CEVAPLA
    private fun getBotResponse(message: String): String {
        val question = message
            .lowercase()
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")

        return when {
            listOf(
                "acil",
                "yardim",
                "tehlike",
                "guvenlik",
                "112"
            ).any { question.contains(it) } -> {
                "Acil bir durumdaysan öncelikle kendi " +
                    "güvenliğini sağlamaya çalış. Türkiye'de " +
                    "acil yardım için 112'yi arayabilirsin. " +
                    "EmergencySafety'nin acil durum " +
                    "özelliklerinin önceden ayarlanmış ve " +
                    "test edilmiş olması önemlidir. Ben bu " +
                    "deneme sohbetinden acil çağrı gönderemem."
            }

            listOf(
                "konum",
                "harita",
                "neredeyim",
                "nerede"
            ).any { question.contains(it) } -> {
                "Konum paylaşımı için planımız, sohbet " +
                    "içinden konumunu harita bağlantısı " +
                    "olarak gönderebilmen. Bu özellik henüz " +
                    "bağlanmadı; sonraki geliştirme " +
                    "aşamasında üzerinde çalışacağız."
            }

            listOf(
                "uygulama",
                "emergencysafety",
                "nedir",
                "ne ise yarar"
            ).any { question.contains(it) } -> {
                "EmergencySafety, acil durum ve kişisel " +
                    "güvenlik amacıyla geliştirilen bir " +
                    "uygulamadır. Acil durum protokolleri, " +
                    "güvenlik araçları ve ileride uygulama " +
                    "içi kullanıcı iletişimi sunmayı hedefliyor."
            }

            listOf(
                "mesaj",
                "sohbet",
                "iletisim",
                "baglanti"
            ).any { question.contains(it) } -> {
                "Uygulama içinde kullanıcıların birbirini " +
                    "bulabildiği, bağlantı isteği gönderdiği " +
                    "ve karşılıklı onaydan sonra özel sohbet " +
                    "edebildiği bir sistem hazırlamayı " +
                    "hedefliyoruz. Şu an bu ekran deneme " +
                    "aşamasında."
            }

            listOf(
                "merhaba",
                "selam",
                "gunaydin",
                "iyi aksamlar"
            ).any { question.contains(it) } -> {
                "Merhaba! EmergencySafety Bot olarak " +
                    "buradayım. Acil durum, uygulama " +
                    "özellikleri veya mesajlaşma hakkında " +
                    "sorularını yazabilirsin."
            }

            question.contains("tesekkur") ||
                question.contains("sagol") -> {
                "Rica ederim! EmergencySafety'yi " +
                    "geliştirmeye devam ediyoruz."
            }

            else -> {
                "Seni tam anlayamadım. Şimdilik acil " +
                    "durum, EmergencySafety uygulaması, " +
                    "mesajlaşma veya konum paylaşımı " +
                    "hakkında soru sorabilirsin. Ben şu anda " +
                    "internet bağlantısı olmayan yerel " +
                    "deneme asistanıyım."
            }
        }
    }

    private fun addMessageBubble(
        message: String,
        isOutgoing: Boolean
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isOutgoing) {
                Gravity.END
            } else {
                Gravity.START
            }

            setPadding(0, dp(4), 0, dp(4))
        }

        val bubble = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.70f).toInt()

            background = roundedBackground(
                if (isOutgoing) outgoingBubble else incomingBubble,
                if (isOutgoing) gold else Color.rgb(85, 78, 58),
                14
            )
        }

        row.addView(
            bubble,
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
}
