
package com.example.emergencysafety

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MessagingTabBar @JvmOverloads constructor(
    context: android.content.Context,
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

        if (
            panel == null ||
            chatTab == null ||
            contactsTab == null ||
            requestsTab == null
        ) {
            return
        }

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
    }

    private fun showTab(tab: String) {
        val contentPanel = panel ?: return

        if (tab == activeTab) return

        if (savedChatView == null && contentPanel.childCount > 0) {
            savedChatView = contentPanel.getChildAt(0)
        }

        contentPanel.removeAllViews()

        when (tab) {
            "chats" -> {
                val chatView = savedChatView

                if (chatView != null) {
                    (chatView.parent as? ViewGroup)?.removeView(chatView)

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

        activeTab = tab
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
