package com.example.emergencysafety

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Collections
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
    private var pendingNotificationConnectionId: String = ""
    private var connectionsContainer: LinearLayout? = null
    private var shouldRefreshConnectionsOnResume = false
    private var unreadCountsByConnection: Map<String, Int> = emptyMap()

    private var activePrivateConnection: ConnectionApiItem? = null
    private var privateMessagesColumn: LinearLayout? = null
    private var privateMessagesScroll: ScrollView? = null
    private var privateStatusText: TextView? = null
    private var privateInput: EditText? = null
    private var privateSendButton: TextView? = null
    private var isSendingMessage = false

    // Özel sohbetin otomatik yenilenmesi için (Polling)
    private val privateChatHandler = Handler(Looper.getMainLooper())
    private var privateChatRefreshRunnable: Runnable? = null
    private var isLoadingPrivateMessages = false
    
    // Thread-safe set (Yarış durumunu / ConcurrentModificationException önlemek için)
    private val attemptedReadMessageIds = Collections.synchronizedSet(mutableSetOf<String>())
    private var lastPrivateMessageSignature = ""

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

    private enum class MessageDeliveryState {
        SENT,
        DELIVERED,
        READ
    }

    private inner class MessageStatusIconView(
        context: Context,
        private val state: MessageDeliveryState
    ) : View(context) {

        private val iconWidthDp = when (state) {
            MessageDeliveryState.SENT -> 16
            MessageDeliveryState.DELIVERED -> 26
            MessageDeliveryState.READ -> 22
        }

        private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 165, 255)
            style = Paint.Style.STROKE
            strokeWidth = dp(1.8f.toInt()).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 95, 210)
            style = Paint.Style.FILL
        }

        init {
            contentDescription = when (state) {
                MessageDeliveryState.SENT -> "Gönderildi"
                MessageDeliveryState.DELIVERED -> "İletildi"
                MessageDeliveryState.READ -> "Okundu"
            }
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            setMeasuredDimension(
                dp(iconWidthDp),
                dp(16)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            when (state) {
                MessageDeliveryState.SENT -> {
                    drawCheck(canvas, 0)
                }

                MessageDeliveryState.DELIVERED -> {
                    drawCheck(canvas, 0)
                    drawCheck(canvas, 10)
                }

                MessageDeliveryState.READ -> {
                    drawEye(canvas)
                }
            }
        }

        private fun drawCheck(
            canvas: Canvas,
            offsetDp: Int
        ) {
            val x = dp(offsetDp).toFloat()

            canvas.drawLine(
                x + dp(1),
                dp(8).toFloat(),
                x + dp(5),
                dp(12).toFloat(),
                checkPaint
            )

            canvas.drawLine(
                x + dp(5),
                dp(12).toFloat(),
                x + dp(12),
                dp(4).toFloat(),
                checkPaint
            )
        }

        private fun drawEye(canvas: Canvas) {
            val eyePath = Path().apply {
                moveTo(
                    dp(1).toFloat(),
                    dp(8).toFloat()
                )

                quadTo(
                    dp(11).toFloat(),
                    dp(0).toFloat(),
                    dp(21).toFloat(),
                    dp(8).toFloat()
                )

                quadTo(
                    dp(11).toFloat(),
                    dp(16).toFloat(),
                    dp(1).toFloat(),
                    dp(8).toFloat()
                )
            }

            canvas.drawPath(eyePath, eyePaint)

            canvas.drawCircle(
                dp(11).toFloat(),
                dp(8).toFloat(),
                dp(3).toFloat(),
                pupilPaint
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isRegisteredUser()) {
            showGuestAccessDialog()
            return
        }

        historyDatabase = ESBotDatabase(this)
        setContentView(R.layout.activity_yellow_safety)

        pendingNotificationConnectionId =
            intent.getStringExtra("connectionId")?.trim().orEmpty()

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

    override fun onPause() {
        super.onPause()

        if (!isChatOpen && ::panelContainer.isInitialized) {
            shouldRefreshConnectionsOnResume = true
        }
    }

    override fun onResume() {
        super.onResume()

        // Başka bir ekrandan dönüldüğünde bağlantı listesini yenile.
        // İlk açılışta gereksiz ikinci istek gönderilmesini önler.
        if (shouldRefreshConnectionsOnResume && !isChatOpen) {
            shouldRefreshConnectionsOnResume = false

            connectionsContainer?.let { container ->
                loadConversationConnections(container)
            }
        }
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
            val normalized = value.trim()
                .replace("Z", "+0000")
                .replace(
                    Regex("([+-]\\d{2}):(\\d{2})$"),
                    "$1$2"
                )

            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            )

            formats.firstNotNullOfOrNull { format ->
                try {
                    format.isLenient = false
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
        stopPrivateChatAutoRefresh()

        isChatOpen = false
        activePrivateConnection = null
        privateMessagesColumn = null
        privateMessagesScroll = null
        privateStatusText = null
        privateInput = null
        privateSendButton = null
        isLoadingPrivateMessages = false
        attemptedReadMessageIds.clear()

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

        val connectionsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = View.generateViewId()
        }

        connectionsContainer = connectionsList
        listContent.addView(connectionsList)

        scroll.addView(
            listContent,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        screen.addView(
            scroll,
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

        loadConversationConnections(connectionsList)
    }

    private fun loadConversationConnections(
        container: LinearLayout
    ) {
        container.removeAllViews()
        addListStatus(container, "Bağlantılar yükleniyor...")

        Thread {
            val result = ConnectionApi.getConnections(this)
            val summaryResult = ConnectionApi.getMessageSummary(this)

            val unreadCounts = summaryResult.data
                ?.unreadByConnection
                ?.associate { it.connectionId to it.count }
                ?: emptyMap()

            runOnUiThread {
                if (isFinishing || isDestroyed || isChatOpen) {
                    return@runOnUiThread
                }

                container.removeAllViews()

                if (!result.success) {
                    addListStatus(container, result.message)
                    return@runOnUiThread
                }

                unreadCountsByConnection = unreadCounts

                val connections = result.data.orEmpty()

                if (connections.isEmpty()) {
                    pendingNotificationConnectionId = ""

                    addListStatus(
                        container,
                        "Henüz kabul edilmiş bağlantın yok. Kişiler sekmesinden bağlantı kurabilirsin."
                    )
                    return@runOnUiThread
                }

                // Bildirimden gelen bağlantıyı bul ve doğrudan sohbeti aç.
                val notificationConnectionId =
                    pendingNotificationConnectionId

                if (notificationConnectionId.isNotBlank()) {

                    val targetConnection = connections.firstOrNull {
                        it.id == notificationConnectionId
                    }

                    pendingNotificationConnectionId = ""

                    if (targetConnection != null) {
                        showPrivateChat(targetConnection)
                        return@runOnUiThread
                    } else {
                        Toast.makeText(
                            this,
                            "Bildirimin geldiği sohbet bağlantılarında bulunamadı.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                connections.forEach { connection ->
                    addPrivateConversationRow(
                        container,
                        connection,
                        unreadCountsByConnection[connection.id] ?: 0
                    )
                }
            }
        }.start()
    }

    private fun addListStatus(
        container: LinearLayout,
        message: String
    ) {
        val status = createText(message, 12f, softText)
        status.gravity = Gravity.CENTER
        status.setPadding(dp(12), dp(16), dp(12), dp(16))
        container.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addPrivateConversationRow(
        container: LinearLayout,
        connection: ConnectionApiItem,
        unreadCount: Int
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = roundedBackground(panelBackground, gold, 13)
            isClickable = true
            isFocusable = true
        }

        val avatar = TextView(this).apply {
            text = connection.email.take(1).uppercase(Locale("tr", "TR"))
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(darkBackground)
            gravity = Gravity.CENTER
            background = roundedBackground(gold, gold, 50)
        }

        row.addView(
            avatar,
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(5), 0)
        }

        info.addView(
            createText(connection.email, 14f, Color.WHITE, true)
        )

        info.addView(
            createText("Bağlantı kuruldu · Sohbeti aç", 11f, softText)
        )

        row.addView(
            info,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val unreadBadge = TextView(this).apply {
            text = if (unreadCount > 99) "99+" else unreadCount.toString()
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(
                Color.rgb(214, 40, 57),
                Color.TRANSPARENT,
                50
            )
            visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
        }

        row.addView(
            unreadBadge,
            LinearLayout.LayoutParams(dp(25), dp(25)).apply {
                marginEnd = dp(6)
            }
        )

        val arrow = createText("›", 28f, gold, true)
        arrow.gravity = Gravity.CENTER
        row.addView(
            arrow,
            LinearLayout.LayoutParams(dp(28), dp(40))
        )

        row.setOnClickListener {
            showPrivateChat(connection)
        }

        container.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun showPrivateChat(connection: ConnectionApiItem) {
        stopPrivateChatAutoRefresh()

        activePrivateConnection = connection
        isChatOpen = true
        isSendingMessage = false
        isLoadingPrivateMessages = false
        attemptedReadMessageIds.clear()

        panelContainer.removeAllViews()

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(darkBackground, gold, 18)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(10))
        }

        val backButton = createText("‹", 36f, gold, true)
        backButton.gravity = Gravity.CENTER
        backButton.setOnClickListener {
            showConversationList()
        }

        header.addView(
            backButton,
            LinearLayout.LayoutParams(dp(40), dp(46))
        )

        val avatar = TextView(this).apply {
            text = connection.email.take(1).uppercase(Locale("tr", "TR"))
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
            createText(connection.email, 14f, Color.WHITE, true)
        )

        nameArea.addView(
            createText("EmergencySafety özel sohbeti", 10f, gold)
        )

        header.addView(
            nameArea,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val refreshButton = createText("↻", 25f, gold, true)
        refreshButton.gravity = Gravity.CENTER
        refreshButton.contentDescription = "Mesajları yenile"
        refreshButton.setOnClickListener {
            loadPrivateMessages(connection, showLoading = true)
        }

        header.addView(
            refreshButton,
            LinearLayout.LayoutParams(dp(40), dp(44))
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

        privateStatusText = createText(
            "Sohbet hazırlanıyor...",
            10f,
            softText
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(7))
        }

        screen.addView(privateStatusText)

        privateMessagesScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(3), dp(8), dp(3), dp(8))
        }

        privateMessagesColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        privateMessagesScroll?.addView(
            privateMessagesColumn,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        screen.addView(
            privateMessagesScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        privateInput = EditText(this).apply {
            hint = "Mesajını yaz..."
            setHintTextColor(Color.rgb(160, 155, 140))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            minLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(
                panelBackground,
                Color.rgb(116, 94, 45),
                22
            )
        }

        composer.addView(
            privateInput,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        privateSendButton = TextView(this).apply {
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
            privateSendButton,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginStart = dp(8)
            }
        )

        privateSendButton?.setOnClickListener {
            sendPrivateMessage(connection, privateInput?.text?.toString().orEmpty())
        }

        privateInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendPrivateMessage(connection, privateInput?.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        screen.addView(composer)

        panelContainer.addView(
            screen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        loadPrivateMessages(connection, showLoading = true)
        startPrivateChatAutoRefresh(connection)
    }

    private fun startPrivateChatAutoRefresh(connection: ConnectionApiItem) {
        stopPrivateChatAutoRefresh()

        val refreshRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return

                if (!isChatOpen || activePrivateConnection?.id != connection.id) {
                    return
                }

                loadPrivateMessages(connection, showLoading = false)
                privateChatHandler.postDelayed(this, 4000)
            }
        }

        privateChatRefreshRunnable = refreshRunnable
        privateChatHandler.postDelayed(refreshRunnable, 4000)
    }

    private fun stopPrivateChatAutoRefresh() {
        privateChatRefreshRunnable?.let {
            privateChatHandler.removeCallbacks(it)
        }
        privateChatRefreshRunnable = null
    }

    private fun loadPrivateMessages(
        connection: ConnectionApiItem,
        showLoading: Boolean
    ) {
        if (isLoadingPrivateMessages) return
        if (!isChatOpen || activePrivateConnection?.id != connection.id) return

        isLoadingPrivateMessages = true

        if (showLoading) {
            privateMessagesColumn?.removeAllViews()
            addPrivateInfoMessage("Mesajlar yükleniyor...")
            privateStatusText?.text = "Mesajlar yükleniyor..."
        }

        Thread {
            val result = ConnectionApi.getInbox(this, 50)

            runOnUiThread {
                isLoadingPrivateMessages = false

                if (isFinishing || isDestroyed) return@runOnUiThread
                
                if (!isChatOpen || activePrivateConnection?.id != connection.id) {
                    return@runOnUiThread
                }

                val column = privateMessagesColumn ?: return@runOnUiThread

                if (!result.success || result.data == null) {
                    column.removeAllViews()
                    addPrivateInfoMessage(
                        result.message.ifBlank {
                            "Mesajlar şu anda alınamadı."
                        }
                    )
                    privateStatusText?.text = "Bağlantı hatası"
                    return@runOnUiThread
                }

                val messages = result.data
                    .filter { it.connectionId == connection.id }
                    .sortedBy { parseServerTime(it.createdAt) }

                privateStatusText?.text = "${messages.size} mesaj gösteriliyor"

                val signature = messages.joinToString("|") {
                    "${it.id}:${it.deliveredAt ?: ""}:${it.readAt ?: ""}"
                }

                val shouldRender =
                    column.childCount == 0 ||
                    signature != lastPrivateMessageSignature ||
                    (messages.isEmpty() &&
                        column.childCount == 1 &&
                        (column.getChildAt(0) as? TextView)?.text
                            ?.toString()
                            ?.contains("Mesajlar yükleniyor") == true)

                if (shouldRender) {
                    lastPrivateMessageSignature = signature
                    column.removeAllViews()

                    if (messages.isEmpty()) {
                        addPrivateInfoMessage(
                            "Henüz mesaj yok. İlk mesajı sen gönder."
                        )
                    } else {
                        messages.forEach { message ->
                            addServerMessageBubble(message)
                        }
                    }

                    // Basit ve kesin aşağı sabitleme yöntemi
                    privateMessagesScroll?.post {
                        privateMessagesScroll?.fullScroll(View.FOCUS_DOWN)
                    }
                }

                val unreadIds = messages
                    .filter {
                        !it.isOutgoing &&
                        it.readAt.isNullOrBlank() &&
                        !attemptedReadMessageIds.contains(it.id)
                    }
                    .map { it.id }

                if (unreadIds.isNotEmpty()) {
                    markPrivateMessagesRead(connection, unreadIds)
                }
            }
        }.start()
    }

    private fun addPrivateInfoMessage(message: String) {
        val textView = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 20)
        }

        privateMessagesColumn?.addView(
            textView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addServerMessageBubble(message: MessageApiItem) {

        val isOutgoing = message.isOutgoing

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(10),
                dp(12),
                dp(8)
            )

            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()

                setColor(
                    if (isOutgoing) {
                        Color.rgb(35, 105, 65)
                    } else {
                        Color.rgb(55, 65, 80)
                    }
                )
            }
        }

        val messageText = TextView(this).apply {
            text = message.body
            textSize = 15f
            setTextColor(Color.WHITE)
        }

        bubble.addView(
            messageText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        // Mesajın saati korunur.
        val timeText = TextView(this).apply {
            text = formatTime(
                parseServerTime(message.createdAt)
            )

            textSize = 10f
            setTextColor(Color.rgb(210, 210, 210))
        }

        statusRow.addView(
            timeText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Durum simgeleri yalnızca gönderilen mesajlarda görünür.
        if (isOutgoing) {

            val deliveryState = when {
                !message.readAt.isNullOrBlank() -> {
                    MessageDeliveryState.READ
                }

                !message.deliveredAt.isNullOrBlank() -> {
                    MessageDeliveryState.DELIVERED
                }

                else -> {
                    MessageDeliveryState.SENT
                }
            }

            val statusIcon = MessageStatusIconView(
                this,
                deliveryState
            )

            statusRow.addView(
                statusIcon,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(16)
                ).apply {
                    marginStart = dp(4)
                }
            )
        }

        bubble.addView(
            statusRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
        )

        val params = LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.75f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)

            gravity = if (isOutgoing) {
                Gravity.END
            } else {
                Gravity.START
            }
        }

        privateMessagesColumn?.addView(bubble, params)
    }

    private fun sendPrivateMessage(
        connection: ConnectionApiItem,
        message: String
    ) {
        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty()) return
        if (isSendingMessage) return

        isSendingMessage = true
        privateSendButton?.isEnabled = false
        privateSendButton?.alpha = 0.5f

        Thread {
            val result = ConnectionApi.sendMessage(
                this,
                connection.id,
                cleanMessage,
                "text"
            )

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (!isChatOpen || activePrivateConnection?.id != connection.id) {
                    isSendingMessage = false
                    return@runOnUiThread
                }

                isSendingMessage = false
                privateSendButton?.isEnabled = true
                privateSendButton?.alpha = 1f

                if (result.success) {
                    privateInput?.text?.clear()
                    loadPrivateMessages(connection, showLoading = false)
                } else {
                    Toast.makeText(
                        this,
                        result.message.ifBlank {
                            "Mesaj gönderilemedi."
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun markPrivateMessagesRead(
        connection: ConnectionApiItem,
        messageIds: List<String>
    ) {
        Thread {
            var anyMessageMarkedRead = false

            messageIds.forEach { messageId ->
                val res = ConnectionApi.markMessageRead(this, messageId)

                if (res.success) {
                    attemptedReadMessageIds.add(messageId)
                    anyMessageMarkedRead = true
                }
            }

            // Okundu işlemi başarılıysa sohbet sayacını hemen yenile.
            if (anyMessageMarkedRead) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        findViewById<MessagingTabBar>(
                            R.id.messagingTabBar
                        )?.refreshBadgesNow()
                    }
                }
            }
        }.start()
    }

    private fun showBotChat() {
        stopPrivateChatAutoRefresh()

        isChatOpen = true
        activePrivateConnection = null
        panelContainer.removeAllViews()

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(darkBackground, gold, 18)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(10))
        }

        val backButton = createText("‹", 36f, gold, true)
        backButton.gravity = Gravity.CENTER
        backButton.setOnClickListener {
            showConversationList()
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(42), dp(46)))

        val avatar = TextView(this).apply {
            text = "ES"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(darkBackground)
            gravity = Gravity.CENTER
            background = roundedBackground(gold, gold, 50)
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))

        val nameArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }

        nameArea.addView(createText("ES Bot", 16f, Color.WHITE, true))
        nameArea.addView(createText("Yerel uygulama asistanı", 11f, gold))

        header.addView(
            nameArea,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val menuButton = createText("⋮", 27f, gold, true)
        menuButton.gravity = Gravity.CENTER
        menuButton.setPadding(dp(8), 0, dp(4), 0)
        menuButton.setOnClickListener { showChatMenu(menuButton) }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(42), dp(46)))

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
            ViewGroup.LayoutParams(
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

        quickHelpContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (!botPreferences.getBoolean("quick_help_seen", false)) {
            addQuickHelpOptions()
        }

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
                    "Konum paylaşımı henüz bağlanmadı. " +
                        "Bu özellik sonraki geliştirme aşamasında.",
                    Toast.LENGTH_LONG
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
            LinearLayout.LayoutParams(0, dp(48), 1f)
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

        sendButton.setOnClickListener { sendBotMessage(messageInput) }

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

    private fun showChatMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)

        if (botPreferences.getBoolean("quick_help_seen", false)) {
            popup.menu.add("Hızlı yardımı tekrar göster")
                .setOnMenuItemClickListener {
                    showQuickHelpAgain()
                    true
                }
        }

        popup.menu.add("Sohbeti temizle")
            .setOnMenuItemClickListener {
                confirmClearChat()
                true
            }

        popup.show()
    }

    private fun showQuickHelpAgain() {
        if (!isChatOpen || activePrivateConnection != null) return

        botPreferences.edit()
            .putBoolean("quick_help_seen", false)
            .apply()

        quickHelpContainer.removeAllViews()
        addQuickHelpOptions()

        messagesScroll.post {
            messagesScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun addQuickHelpOptions() {
        (quickHelpContainer.parent as? ViewGroup)
            ?.removeView(quickHelpContainer)

        quickHelpContainer.removeAllViews()

        val helpTitle = createText("HIZLI YARDIM", 11f, gold, true)
        helpTitle.setPadding(dp(4), dp(14), dp(4), dp(8))
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
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = roundedBackground(
                    panelBackground,
                    Color.rgb(116, 94, 45),
                    12
                )
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    hideQuickHelp()
                    addMessageBubble(option.first, true)
                    addMessageBubble(option.second, false)
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

    private fun sendBotMessage(input: EditText) {
        val message = input.text.toString().trim()
        if (message.isBlank()) return

        hideQuickHelp()
        addMessageBubble(message, true)
        input.setText("")

        val reply = getBotResponse(message)
        addMessageBubble(reply.text, false)

        if (reply.destination != null && !reply.actionLabel.isNullOrBlank()) {
            addNavigationButton(reply.actionLabel, reply.destination)
        }
    }

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
                        "Bu ekranda kırmızı ve siren komutları bulunur.",
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
                        "ulaşabilirsin. Harita araması için internet bağlantısı gerekir.",
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
                        "önceden ayarlanmış ve test edilmiş olması önemlidir. " +
                        "Ben bu sohbetten acil çağrı gönderemem."
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
                        "Ancak konum paylaşımı henüz bu test ekranına bağlanmadı."
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
                        "güvenlik amacıyla geliştirilen bir uygulamadır. " +
                        "Acil durum protokolleri, güvenlik araçları ve " +
                        "uygulama içi kullanıcı iletişimi sunmayı hedefliyor. " +
                        "Bazı iletişim özellikleri hâlâ geliştiriliyor."
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
                    "Kabul edilmiş bağlantılarınla özel sohbet " +
                        "edebilirsin. Sohbetler sekmesinden bir kişiye " +
                        "dokunarak konuşmayı açabilirsin."
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
                        "mesajlaşma hakkında sorularını yanıtlamaya çalışabilirim."
                )
            }

            question.contains("tesekkur") ||
                question.contains("sagol") -> {
                BotReply(
                    "Rica ederim! EmergencySafety'yi geliştirmeye devam ediyoruz."
                )
            }

            else -> {
                BotReply(
                    "Seni tam anlayamadım. Şimdilik acil durum, " +
                        "sesli komutlar, kişi ayarları, EmergencySafety " +
                        "veya mesajlaşma hakkında soru sorabilirsin. " +
                        "Ben şu anda yerel deneme asistanıyım."
                )
            }
        }
    }

    private fun addNavigationButton(
        label: String,
        destination: Class<out Activity>
    ) {
        val button = TextView(this).apply {
            text = "›  $label"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(13), dp(11), dp(13), dp(11))
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
                        Intent(this@YellowSafetyActivity, destination)
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

    private fun addMessageBubble(
        message: String,
        isOutgoing: Boolean
    ) {
        val timestamp = System.currentTimeMillis()

        historyDatabase.addMessage(message, isOutgoing, timestamp)
        renderMessageBubble(message, isOutgoing, timestamp)
    }

    private fun renderMessageBubble(
        message: String,
        isOutgoing: Boolean,
        timestamp: Long
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isOutgoing) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }

        val messageColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isOutgoing) Gravity.END else Gravity.START
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

        messageColumn.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val time = createText(formatTime(timestamp), 9f, softText)
        time.setPadding(dp(4), dp(3), dp(4), 0)
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
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        messagesScroll.post {
            messagesScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun confirmClearChat() {
        showBrandedDialog(
            title = "SOHBETİ TEMİZLE",
            message =
                "ES Bot ile yaptığın tüm yerel sohbet geçmişi bu cihazdan silinecek.\n\n" +
                "Bu işlem uygulama ayarlarını, acil durum kişilerini veya diğer uygulama verilerini silmez.\n\n" +
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
        stopPrivateChatAutoRefresh()

        if (::historyDatabase.isInitialized) {
            historyDatabase.close()
        }
        super.onDestroy()
    }
}

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
            put(COLUMN_OUTGOING, if (isOutgoing) 1 else 0)
            put(COLUMN_TIMESTAMP, timestamp)
        }

        writableDatabase.insert(TABLE_MESSAGES, null, values)
    }

    fun getMessages(): List<ESBotMessage> {
        val result = mutableListOf<ESBotMessage>()

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
                    ESBotMessage(
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

    fun getLatestMessage(): ESBotMessage? {
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
            if (!it.moveToFirst()) return null

            return ESBotMessage(
                id = it.getLong(0),
                message = it.getString(1),
                isOutgoing = it.getInt(2) == 1,
                timestamp = it.getLong(3)
            )
        }
    }

    fun clearMessages() {
        writableDatabase.delete(TABLE_MESSAGES, null, null)
    }
}
