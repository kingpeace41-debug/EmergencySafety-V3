
package com.example.emergencysafety

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

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

    private var activeTab = "chats"
    private var isBound = false

    private var backCallback: OnBackPressedCallback? = null

    private val gold = Color.rgb(232, 184, 55)
    private val dark = Color.rgb(36, 37, 41)
    private val selectedDark = Color.rgb(52, 43, 23)
    private val softText = Color.rgb(197, 197, 197)

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
                    }
                }

                override fun onChildViewRemoved(
                    parent: View?,
                    child: View?
                ) {
                    // Görünüm kaldırıldığında kayıt korunur.
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

        if (tab == activeTab) return

        // Önce aktif sekme değiştirilir.
        // Böylece bilgi sayfaları sohbet listesi olarak kaydedilmez.
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

            "contacts" -> {
                contentPanel.addView(
                    createInformationPage(
                        "KİŞİLER",
                        "Bağlantı kurduğun kişiler burada görünecek.",
                        "Kişi bulma ve bağlantı sistemi henüz sunucuya bağlanmadı."
                    ),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            "requests" -> {
                contentPanel.addView(
                    createInformationPage(
                        "GELEN İSTEKLER",
                        "Bağlantı isteklerin burada görünecek.",
                        "İstekleri sunucudan alma ve onaylama sistemi henüz bağlanmadı."
                    ),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }

        updateTabAppearance()
    }

    private fun createInformationPage(
        title: String,
        message: String,
        detail: String
    ): LinearLayout {

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))

            background = roundedBackground(
                Color.rgb(19, 19, 18),
                gold
            )

            val titleView = TextView(context).apply {
                text = title
                textSize = 19f
                setTextColor(gold)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }

            val messageView = TextView(context).apply {
                text = message
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(8))
            }

            val detailView = TextView(context).apply {
                text = detail
                textSize = 12f
                setTextColor(softText)
                gravity = Gravity.CENTER
            }

            addView(
                titleView,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                messageView,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                detailView,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            )
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

    private fun roundedBackground(
        color: Int,
        strokeColor: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()

            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density + 0.5f
        ).toInt()
    }
}
