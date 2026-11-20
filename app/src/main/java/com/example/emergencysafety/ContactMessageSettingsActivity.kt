
package com.example.emergencysafety

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ContactMessageSettingsActivity : AppCompatActivity() {

    private val prefsName = "EmergencyContactSettings"

    private val activityScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var contactNameFields: List<EditText>
    private lateinit var contactPhoneFields: List<EditText>
    private lateinit var messageField: EditText

    companion object {
        private val DEFAULT_EMERGENCY_MESSAGE = """
            ACİL DURUM!

            Ben [Ad Soyad].

            Acil durumdayım ve yardıma ihtiyacım var.
            Lütfen aşağıdaki konuma ivedilikle gelin
            veya gerekli yardımı yönlendirin.

            Konumum: [Otomatik konum bağlantısı]
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_contact_message_settings)

        contactNameFields = listOf(
            findViewById(R.id.etContact1Name),
            findViewById(R.id.etContact2Name),
            findViewById(R.id.etContact3Name)
        )

        contactPhoneFields = listOf(
            findViewById(R.id.etContact1Phone),
            findViewById(R.id.etContact2Phone),
            findViewById(R.id.etContact3Phone)
        )

        messageField = findViewById(R.id.etEmergencyMessage)

        contactPhoneFields.forEach {
            it.visibility = View.GONE
        }

        for (index in 0..2) {
            val slot = index + 1
            val field = contactNameFields[index]

            field.hint = "$slot. ES acil durum kişisini seç"
            field.isFocusable = false
            field.isFocusableInTouchMode = false
            field.isCursorVisible = false
            field.isClickable = true
            field.isLongClickable = true

            field.setOnClickListener {
                openEsContactPicker(slot)
            }

            field.setOnLongClickListener {
                openEsContactPicker(slot)
                true
            }
        }

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

        val savedMessage = prefs.getString(
            "emergency_message",
            null
        )

        messageField.setText(
            savedMessage?.takeIf { it.isNotBlank() }
                ?: DEFAULT_EMERGENCY_MESSAGE
        )

        findViewById<Button>(R.id.btnSaveContacts)
            .setOnClickListener {
                saveEmergencyMessage()
            }

        findViewById<Button>(R.id.btnBackContacts)
            .setOnClickListener {
                finish()
            }

        updateScreenHeading()
        refreshSelectedContactNames()
    }

    override fun onResume() {
        super.onResume()

        if (::contactNameFields.isInitialized) {
            refreshSelectedContactNames()
        }
    }

    private fun displayName(email: String): String {
        if (email.isBlank()) return ""

        return if (ContactNames.hasName(this, email)) {
            ContactNames.getDisplayName(this, email)
        } else {
            "İsimsiz ES kişisi"
        }
    }

    private fun refreshSelectedContactNames() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

        for (slot in 1..3) {
            val connectionId = prefs.getString(
                "es_contact${slot}_id", ""
            ).orEmpty()

            val email = prefs.getString(
                "es_contact${slot}_email", ""
            ).orEmpty()

            val savedName = prefs.getString(
                "es_contact${slot}_name", ""
            ).orEmpty()

            val field = contactNameFields[slot - 1]

            if (connectionId.isBlank()) {
                field.setText("")
                continue
            }

            val name = if (email.isNotBlank()) {
                displayName(email)
            } else {
                savedName.takeUnless {
                    it.isBlank() || it.contains("@")
                } ?: "İsimsiz ES kişisi"
            }

            field.setText(name)

            if (name != savedName) {
                prefs.edit()
                    .putString("es_contact${slot}_name", name)
                    .apply()
            }
        }
    }

    private fun updateScreenHeading() {
        val root = findViewById<View>(android.R.id.content)

        fun update(view: View) {
            if (view is TextView &&
                view.text.toString() == "REHBER VE MESAJ AYARLARI"
            ) {
                view.text = "ES KİŞİ VE MESAJ AYARLARI"
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    update(view.getChildAt(i))
                }
            }
        }

        update(root)
    }

    private fun openEsContactPicker(slot: Int) {
        Toast.makeText(
            this,
            "ES bağlantıları yükleniyor...",
            Toast.LENGTH_SHORT
        ).show()

        activityScope.launch {
            val result = withContext(Dispatchers.IO) {
                ConnectionApi.getConnections(applicationContext)
            }

            if (isFinishing || isDestroyed) return@launch

            if (!result.success) {
                Toast.makeText(
                    this@ContactMessageSettingsActivity,
                    result.message,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val connections = result.data.orEmpty()
                .filter {
                    it.id.isNotBlank() && it.email.isNotBlank()
                }
                .sortedBy {
                    displayName(it.email).lowercase(Locale.ROOT)
                }

            showEsContactDialog(slot, connections)
        }
    }

    private fun showEsContactDialog(
        slot: Int,
        connections: List<ConnectionApiItem>
    ) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_es_contact_picker)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
        }

        val title = dialog.findViewById<TextView>(
            R.id.tvEsPickerTitle
        )
        val list = dialog.findViewById<LinearLayout>(
            R.id.esPickerList
        )
        val message = dialog.findViewById<TextView>(
            R.id.tvEsPickerMessage
        )
        val removeButton = dialog.findViewById<Button>(
            R.id.btnEsRemoveContact
        )
        val cancelButton = dialog.findViewById<Button>(
            R.id.btnEsPickerCancel
        )

        title.text = "$slot. ACİL DURUM KİŞİSİNİ SEÇ"

        if (connections.isEmpty()) {
            message.visibility = View.VISIBLE
            message.text =
                "Henüz onaylanmış ES bağlantın bulunmuyor.\n\n" +
                "Önce sarı mesajlaşma konsolundan kişi ekleyip " +
                "karşılıklı bağlantıyı onaylayın."
        } else {
            var unnamedCount = 0

            connections.forEach { item ->
                val name = if (
                    ContactNames.hasName(this, item.email)
                ) {
                    displayName(item.email)
                } else {
                    unnamedCount++
                    "İsimsiz ES kişisi $unnamedCount"
                }

                val row = TextView(this).apply {
                    text = name
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(14), dp(15),
                        dp(14), dp(15)
                    )
                    setBackgroundColor(
                        Color.rgb(55, 14, 19)
                    )
                    isClickable = true
                }

                val rowParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }

                list.addView(row, rowParams)

                row.setOnClickListener {
                    selectEsContact(slot, item, name)
                    dialog.dismiss()
                }
            }
        }

        removeButton.setOnClickListener {
            val prefs = getSharedPreferences(
                prefsName, MODE_PRIVATE
            )

            prefs.edit()
                .remove("es_contact${slot}_id")
                .remove("es_contact${slot}_email")
                .remove("es_contact${slot}_name")
                .apply()

            refreshSelectedContactNames()
            dialog.dismiss()

            Toast.makeText(
                this,
                "ES acil durum kişisi kaldırıldı.",
                Toast.LENGTH_SHORT
            ).show()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun selectEsContact(
        slot: Int,
        item: ConnectionApiItem,
        name: String
    ) {
        val prefs = getSharedPreferences(
            prefsName, MODE_PRIVATE
        )

        val alreadySelected = (1..3).any { otherSlot ->
            otherSlot != slot &&
                prefs.getString(
                    "es_contact${otherSlot}_id", ""
                ) == item.id
        }

        if (alreadySelected) {
            Toast.makeText(
                this,
                "Bu kişi zaten başka bir sırada seçilmiş.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        prefs.edit()
            .putString("es_contact${slot}_id", item.id)
            .putString("es_contact${slot}_email", item.email)
            .putString("es_contact${slot}_name", name)
            .apply()

        refreshSelectedContactNames()

        Toast.makeText(
            this,
            "Acil durum kişisi kaydedildi.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun saveEmergencyMessage() {
        val message = messageField.text
            .toString()
            .trim()

        if (message.isBlank()) {
            Toast.makeText(
                this,
                "Acil durum mesajı boş olamaz.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        getSharedPreferences(
            prefsName, MODE_PRIVATE
        ).edit()
            .putString("emergency_message", message)
            .apply()

        Toast.makeText(
            this,
            "Acil durum mesajı kaydedildi.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
