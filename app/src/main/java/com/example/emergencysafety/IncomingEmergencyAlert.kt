
package com.example.emergencysafety

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

internal class IncomingEmergencyAlert(
    private val activity: Activity
) {

    private val handler = Handler(Looper.getMainLooper())

    private val preferences by lazy {
        activity.getSharedPreferences(
            FcmMessagingService.EMERGENCY_PREFS,
            Context.MODE_PRIVATE
        )
    }

    private val overlay: View? by lazy {
        activity.findViewById(
            R.id.emergencyAlertOverlay
        )
    }

    private val alertButton: TextView? by lazy {
        activity.findViewById(
            R.id.btnIncomingEmergency
        )
    }

    private var blinking = false
    private var visiblePhase = true

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!blinking) return

            visiblePhase = !visiblePhase

            alertButton?.alpha =
                if (visiblePhase) 1f else 0.30f

            handler.postDelayed(this, 550L)
        }
    }

    fun refresh() {
        val pending = preferences.getBoolean(
            FcmMessagingService.KEY_PENDING,
            false
        )

        if (!pending) {
            hide()
            return
        }

        val senderName = preferences.getString(
            FcmMessagingService.KEY_SENDER_NAME,
            ""
        ).orEmpty().trim()

        val title = if (senderName.isBlank()) {
            "🚨 ACİL DURUM MESAJI 🚨"
        } else {
            "🚨 $senderName 🚨"
        }

        alertButton?.text =
            "$title\nACİL DURUM MESAJINI AÇ"

        alertButton?.setOnClickListener {
            openEmergencyMessage()
        }

        overlay?.visibility = View.VISIBLE
        startBlinking()
    }

    fun hasPendingEmergency(): Boolean {
        return preferences.getBoolean(
            FcmMessagingService.KEY_PENDING,
            false
        )
    }

    private fun startBlinking() {
        if (blinking) return

        blinking = true
        visiblePhase = true
        alertButton?.alpha = 1f

        handler.post(blinkRunnable)
    }

    private fun stopBlinking() {
        blinking = false
        handler.removeCallbacks(blinkRunnable)
        alertButton?.alpha = 1f
    }

    private fun openEmergencyMessage() {
        val connectionId = preferences.getString(
            FcmMessagingService.KEY_CONNECTION_ID,
            ""
        ).orEmpty().trim()

        if (connectionId.isBlank()) {
            return
        }

        preferences.edit()
            .putBoolean(
                FcmMessagingService.KEY_PENDING,
                false
            )
            .apply()

        hide()

        val intent = Intent(
            activity,
            YellowSafetyActivity::class.java
        ).apply {
            putExtra("connectionId", connectionId)
        }

        activity.startActivity(intent)
    }

    fun pause() {
        stopBlinking()
    }

    fun hide() {
        stopBlinking()
        overlay?.visibility = View.GONE
    }

    fun destroy() {
        stopBlinking()
    }
}
