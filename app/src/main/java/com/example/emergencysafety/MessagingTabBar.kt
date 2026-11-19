
package com.example.emergencysafety

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MessagingTabBar @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var panel: FrameLayout? = null
    private var savedChatView: View? = null

    private var chatTab: FrameLayout? = null
    private var contactsTab: FrameLayout? = null
    private var requestsTab: FrameLayout? = null
    private var chatBadge: TextView? = null
    private var requestBadge: TextView? = null

    private var activeTab = "chats"
    private var isBound = false
    private var isRefreshingBadges = false
    private var shouldRefreshBadgesAgain = false

    private var backCallback: OnBackPressedCallback? = null

    private val gold = Color.rgb(232, 184, 55)
    private val dark = Color.rgb(36, 37, 41)
    private val selectedDark = Color.rgb(52, 43, 23)
    private val softText = Color.rgb(197, 197, 197)
    private val pageBackground = Color.rgb(19, 19, 18)
    private val green = Color.rgb(45, 130, 82)
    private val red = Color.rgb(170, 48, 58)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        post {
            bindTabs()
        }
    }

    private fun bindTabs() {
        if (isBound) return

        val root = rootView

        panel = root.findViewById(R.id.messagePanelContainer)

        chatTab = findViewById(R.id.tabChats)
        contactsTab = findViewById(R.id.tabContacts)
        requestsTab = findViewById(R.id.tabRequests)
        chatBadge = findViewById(R.id.chatBadge)
        requestBadge = findViewById(R.id.requestBadge)

        val contentPanel = panel

        if (
            contentPanel == null ||
            chatTab == null ||
            contactsTab == null ||
            requestsTab == null
        ) {
            return
        }

        savedChatView = contentPanel.getChildAt(0)
            ?.takeIf { isConversationListView(it) }

        contentPanel.setOnHierarchyChangeListener(
            object : ViewGroup.OnHierarchyChangeListener {

                override fun onChildViewAdded(
                    parent: View?,
                    child: View?
                ) {
                    if (
                        activeTab == "chats" &&
                        child != null &&
                        isConversationListView(child)
                    ) {
                        savedChatView = child
                        refreshBadges()
                    }
                }

                override fun onChildViewRemoved(
                    parent: View?,
                    child: View?
                ) {
                    // Sohbet listesi referansı korunur.
                }
            }
        )

        chatTab?.setOnClickListener {
            showTab("chats")
        }

        contactsTab?.setOnClickListener {
            showTab("contacts")
        }

        requestsTab?.setOnClickListener {
            showTab("requests")
        }

        isBound = true
        updateTabAppearance()
        registerBackHandler()
        registerLifecycleObserver()
    }

    private fun isConversationListView(view: View): Boolean {
        return containsText(view, "MESAJLAR") &&
            containsText(view, "SOHBETLER")
    }

    private fun containsText(
        view: View,
        expectedText: String
    ): Boolean {
        if (view is TextView) {
            if (view.text.toString().trim() == expectedText) {
                return true
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (
                    containsText(
                        view.getChildAt(index),
                        expectedText
                    )
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun registerLifecycleObserver() {
        val activity = findActivity(context) as? AppCompatActivity
            ?: return

        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshBadges()
                }
            }
        )
    }

    private fun registerBackHandler() {
        val activity = findActivity(context) as? AppCompatActivity
            ?: return

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (returnToChats()) {
                    return
                }

                isEnabled = false

                try {
                    activity.onBackPressedDispatcher.onBackPressed()
                } finally {
                    isEnabled = true
                }
            }
        }

        activity.onBackPressedDispatcher.addCallback(
            activity,
            callback
        )

        backCallback = callback
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context = context

        while (true) {
            if (current is Activity) {
                return current
            }

            if (current !is ContextWrapper) {
                return null
            }

            current = current.baseContext
        }
    }

    fun returnToChats(): Boolean {
        if (activeTab == "chats") {
            return false
        }

        showTab("chats")
        return true
    }

    private fun showTab(tab: String) {
        val contentPanel = panel ?: return

        if (tab == activeTab) {
            refreshBadges()
            return
        }

        activeTab = tab
        contentPanel.removeAllViews()

        when (tab) {
            "chats" -> {
                val chatView = savedChatView

                if (chatView != null) {
                    (chatView.parent as? ViewGroup)
                        ?.removeView(chatView)

                    contentPanel.addView(
                        chatView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }

            "contacts" -> showContactsPage()

            "requests" -> showRequestsPage()
        }

        updateTabAppearance()
        refreshBadges()
    }

    private fun showContactsPage() {
        val screen = createPage()
        val content = screen.second

        content.addView(
            createText(
                "YENİ BAĞLANTI",
                16f,
                gold,
                true
            )
        )

        content.addView(
            createText(
                "Kişiye bir isim ver ve EmergencySafety hesabının e-posta adresini gir.",
                13f,
                softText
            ).apply {
                setPadding(0, dp(7), 0, dp(12))
            }
        )

        // Kişiye verilecek özel isim.
        val nameInput = EditText(context).apply {
            hint = "Kişinin Adı (ör. Ahmet)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(dark, gold)
        }

        content.addView(
            nameInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(8)
            }
        )

        val emailInput = EditText(context).apply {
            hint = "E-posta adresi"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(dark, gold)
        }

        content.addView(
            emailInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(8)
            }
        )

        val sendButton = createButton(
            "BAĞLANTI İSTEĞİ GÖNDER",
            green
        )

        content.addView(
            sendButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(9)
            }
        )

        val divider = createDivider()
        content.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(16)
            }
        )

        content.addView(
            createText(
                "BAĞLANTILARIM",
                16f,
                gold,
                true
            )
        )

        val refreshButton = createButton(
            "LİSTEYİ YENİLE",
            dark
        )

        content.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply {
                topMargin = dp(9)
            }
        )

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(
            listContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        sendButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()

            if (name.isBlank()) {
                showToast("Kişinin adını gir.")
                return@setOnClickListener
            }

            if (email.isBlank()) {
                showToast("E-posta adresini gir.")
                return@setOnClickListener
            }

            sendButton.isEnabled = false
            sendButton.text = "GÖNDERİLİYOR..."

            Thread {
                val result = ConnectionApi.sendRequest(
                    context,
                    email
                )

                post {
                    sendButton.isEnabled = true
                    sendButton.text = "BAĞLANTI İSTEĞİ GÖNDER"

                    showToast(result.message)

                    if (result.success) {
                        // Bağlantı isteği başarılı olunca
                        // kişiye verilen ismi kaydet.
                        ContactNames.saveName(
                            context,
                            email,
                            name
                        )

                        nameInput.text.clear()
                        emailInput.text.clear()

                        loadConnections(listContainer)
                        refreshBadges()
                    }
                }
            }.start()
        }

        refreshButton.setOnClickListener {
            loadConnections(listContainer)
        }

        loadConnections(listContainer)
        attachPage(screen.first)
    }

    private fun showRequestsPage() {
        val screen = createPage()
        val content = screen.second

        content.addView(
            createText(
                "GELEN BAĞLANTI İSTEKLERİ",
                16f,
                gold,
                true
            )
        )

        content.addView(
            createText(
                "Bağlantıyı kabul ettiğinde karşılıklı bağlantı kurulur.",
                13f,
                softText
            ).apply {
                setPadding(0, dp(7), 0, dp(12))
            }
        )

        val refreshButton = createButton(
            "İSTEKLERİ YENİLE",
            dark
        )

        content.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        )

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(
            listContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        refreshButton.setOnClickListener {
            loadRequests(listContainer)
        }

        loadRequests(listContainer)
        attachPage(screen.first)
    }

    private fun createPage(): Pair<ScrollView, LinearLayout> {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(
                pageBackground,
                gold
            )
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return Pair(scrollView, page)
    }

    private fun attachPage(view: View) {
        val contentPanel = panel ?: return

        contentPanel.removeAllViews()

        contentPanel.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun loadConnections(
        container: LinearLayout
    ) {
        container.removeAllViews()
        addStatus(container, "Bağlantılar yükleniyor...")

        Thread {
            val result = ConnectionApi.getConnections(context)

            post {
                if (activeTab != "contacts") return@post

                container.removeAllViews()

                if (!result.success) {
                    addStatus(container, result.message)
                    return@post
                }

                val items = result.data.orEmpty()

                if (items.isEmpty()) {
                    addStatus(
                        container,
                        "Henüz bağlantı kurduğun bir kişi yok."
                    )
                    return@post
                }

                items.forEach { item ->
                    addConnectionRow(container, item)
                }
            }
        }.start()
    }

    private fun loadRequests(
        container: LinearLayout
    ) {
        container.removeAllViews()
        addStatus(container, "Gelen istekler yükleniyor...")

        Thread {
            val result = ConnectionApi.getRequests(context)

            post {
                if (activeTab != "requests") return@post

                container.removeAllViews()

                if (!result.success) {
                    addStatus(container, result.message)
                    refreshBadges()
                    return@post
                }

                val items = result.data.orEmpty()
                refreshBadges()

                if (items.isEmpty()) {
                    addStatus(
                        container,
                        "Bekleyen bağlantı isteğin yok."
                    )
                    return@post
                }

                items.forEach { item ->
                    addRequestRow(container, item)
                }
            }
        }.start()
    }

    private fun addConnectionRow(
        container: LinearLayout,
        item: ConnectionApiItem
    ) {
        val row = createRow()

        val email = createText(
            item.email,
            13f,
            Color.WHITE,
            true
        )

        row.addView(
            email,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val removeButton = createButton(
            "KALDIR",
            red
        )

        row.addView(
            removeButton,
            LinearLayout.LayoutParams(
                dp(76),
                dp(38)
            ).apply {
                marginStart = dp(8)
            }
        )

        removeButton.setOnClickListener {
            removeButton.isEnabled = false

            Thread {
                val result = ConnectionApi.removeConnection(
                    context,
                    item.id
                )

                post {
                    showToast(result.message)

                    if (result.success) {
                        loadConnections(container)
                    } else {
                        removeButton.isEnabled = true
                    }
                }
            }.start()
        }

        container.addView(row)
    }

    private fun addRequestRow(
        container: LinearLayout,
        item: ConnectionApiItem
    ) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(dark, gold)
        }

        val email = createText(
            item.email,
            13f,
            Color.WHITE,
            true
        )

        card.addView(email)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val acceptButton = createButton(
            "KABUL",
            green
        )

        val rejectButton = createButton(
            "REDDET",
            red
        )

        val blockButton = createButton(
            "ENGELLE",
            dark
        )

        actions.addView(
            acceptButton,
            LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(4)
            }
        )

        actions.addView(
            rejectButton,
            LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )

        actions.addView(
            blockButton,
            LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(4)
            }
        )

        card.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        acceptButton.setOnClickListener {
            respondToRequest(
                item,
                "accept",
                container,
                acceptButton,
                rejectButton,
                blockButton
            )
        }

        rejectButton.setOnClickListener {
            respondToRequest(
                item,
                "reject",
                container,
                acceptButton,
                rejectButton,
                blockButton
            )
        }

        blockButton.setOnClickListener {
            respondToRequest(
                item,
                "block",
                container,
                acceptButton,
                rejectButton,
                blockButton
            )
        }

        container.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun respondToRequest(
        item: ConnectionApiItem,
        action: String,
        container: LinearLayout,
        vararg buttons: TextView
    ) {
        buttons.forEach { it.isEnabled = false }

        Thread {
            val result = ConnectionApi.respond(
                context,
                item.id,
                action
            )

            post {
                showToast(result.message)

                if (result.success) {
                    loadRequests(container)
                } else {
                    buttons.forEach { it.isEnabled = true }
                }
            }
        }.start()
    }

    fun refreshBadgesNow() {
        refreshBadges()
    }

    private fun refreshBadges() {
        if (isRefreshingBadges) {
            shouldRefreshBadgesAgain = true
            return
        }

        isRefreshingBadges = true

        Thread {
            val result = ConnectionApi.getMessageSummary(context)

            post {
                isRefreshingBadges = false

                if (result.success) {
                    val summary = result.data

                    if (summary != null) {
                        updateChatBadge(summary.unreadTotal)
                        updateRequestBadge(summary.pendingInvitations)
                    }
                }

                if (shouldRefreshBadgesAgain) {
                    shouldRefreshBadgesAgain = false
                    refreshBadges()
                }
            }
        }.start()
    }

    private fun updateChatBadge(count: Int) {
        chatBadge?.apply {
            text = if (count > 99) "99+" else count.toString()
            visibility = if (count > 0) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun updateRequestBadge(count: Int) {
        requestBadge?.apply {
            text = if (count > 99) "99+" else count.toString()
            visibility = if (count > 0) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun addStatus(
        container: LinearLayout,
        message: String
    ) {
        container.addView(
            createText(
                message,
                13f,
                softText
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(18), dp(8), dp(18))
            }
        )
    }

    private fun createRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(dark, gold)
        }
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun createButton(
        value: String,
        color: Int
    ): TextView {
        return TextView(context).apply {
            text = value
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = roundedBackground(color, gold)
            setPadding(dp(5), dp(7), dp(5), dp(7))
        }
    }

    private fun createDivider(): View {
        return View(context).apply {
            setBackgroundColor(Color.rgb(91, 75, 37))
        }
    }

    private fun roundedBackground(
        color: Int,
        strokeColor: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(9).toFloat()

            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun updateTabAppearance() {
        styleTab(chatTab, activeTab == "chats")
        styleTab(contactsTab, activeTab == "contacts")
        styleTab(requestsTab, activeTab == "requests")
    }

    private fun styleTab(
        tab: FrameLayout?,
        selected: Boolean
    ) {
        tab ?: return

        tab.background = roundedBackground(
            if (selected) selectedDark else dark,
            if (selected) gold else Color.TRANSPARENT
        )

        val label = tab.getChildAt(0) as? TextView
        label?.setTextColor(
            if (selected) gold else softText
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(
            context,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density + 0.5f
        ).toInt()
    }
}
