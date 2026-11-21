
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

        setupEmergencyMessagePreview()

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
        loadMissingContactNamesFromServer()
    }

    /**
     * Ilk esitleme: sunucudaki isimleri indir; sunucuda henuz
     * isim yoksa telefondaki mevcut ozel adi yukle.
     * Iki tarafta da isim varsa sunucudaki kaydi esas al.
     */
    private fun loadMissingContactNamesFromServer() {
        activityScope.launch {
            val result = withContext(Dispatchers.IO) {
                ConnectionApi.getContactNames(applicationContext)
            }
            if (!result.success || isFinishing || isDestroyed) return@launch

            val items = result.data.orEmpty()
            val uploads = mutableListOf<Pair<String, String>>()
            var updated = false

            items.forEach { item ->
                val serverName = item.displayName?.trim().orEmpty()
                val localName = ContactNames.getSavedName(
                    this@ContactMessageSettingsActivity,
                    item.email
                ).orEmpty()

                if (serverName.isNotBlank() && !serverName.contains("@")) {
                    if (localName != serverName) {
                        ContactNames.saveName(
                            this@ContactMessageSettingsActivity,
                            item.email,
                            serverName
                        )
                        updated = true
                    }
                } else if (
                    localName.isNotBlank() &&
                    !localName.contains("@") &&
                    item.contactUserId.isNotBlank()
                ) {
                    uploads.add(item.contactUserId to localName)
                }
            }

            if (updated) refreshSelectedContactNames()

            // Ag isteklerini ana is parcacigi disinda sirayla yap.
            withContext(Dispatchers.IO) {
                uploads.forEach { (userId, name) ->
                    ConnectionApi.updateContactName(
                        applicationContext,
                        userId,
                        name
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (::contactNameFields.isInitialized) {
            refreshSelectedContactNames()
        }
    }

    private fun getCurrentEmergencyMessage(): String {
        val prefs = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        )

        return prefs.getString(
            "emergency_message",
            null
        )?.takeIf {
            it.isNotBlank()
        } ?: DEFAULT_EMERGENCY_MESSAGE
    }

    private fun setupEmergencyMessagePreview() {
        messageField.setText(getCurrentEmergencyMessage())

        messageField.isFocusable = false
        messageField.isFocusableInTouchMode = false
        messageField.isCursorVisible = false
        messageField.isClickable = true
        messageField.isLongClickable = true
        messageField.isVerticalScrollBarEnabled = true

        messageField.setOnClickListener {
            openEmergencyMessageEditor()
        }

        messageField.setOnLongClickListener {
            openEmergencyMessageEditor()
            true
        }
    }

    private fun openEmergencyMessageEditor() {
        val dialog = Dialog(this)

        dialog.setContentView(
            R.layout.dialog_es_emergency_message
        )

        dialog.window?.apply {
            setBackgroundDrawable(
                ColorDrawable(Color.TRANSPARENT)
            )

            addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )

            setDimAmount(0.65f)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
        }

        val editor = dialog.findViewById<EditText>(
            R.id.etEsMessageEditor
        )

        val clearButton = dialog.findViewById<Button>(
            R.id.btnEsMessageClear
        )

        val saveButton = dialog.findViewById<Button>(
            R.id.btnEsMessageSave
        )

        editor.setText(getCurrentEmergencyMessage())
        editor.setSelection(editor.text.length)

        clearButton.setOnClickListener {
            editor.setText("")
            editor.requestFocus()
        }

        saveButton.setOnClickListener {
            val newMessage = editor.text
                .toString()
                .trim()

            if (newMessage.isBlank()) {
                Toast.makeText(
                    this,
                    "Acil durum mesajı boş olamaz.",
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            getSharedPreferences(
                prefsName,
                MODE_PRIVATE
            ).edit()
                .putString(
                    "emergency_message",
                    newMessage
                )
                .apply()

            messageField.setText(newMessage)

            dialog.dismiss()

            Toast.makeText(
                this,
                "Acil durum mesajı kaydedildi.",
                Toast.LENGTH_SHORT
            ).show()
        }

        dialog.show()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.75f).toInt()
        )
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
        val prefs = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        )

        for (slot in 1..3) {
            val connectionId = prefs.getString(
                "es_contact${slot}_id",
                ""
            ).orEmpty()

            val email = prefs.getString(
                "es_contact${slot}_email",
                ""
            ).orEmpty()

            val savedName = prefs.getString(
                "es_contact${slot}_name",
                ""
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
                    .putString(
                        "es_contact${slot}_name",
                        name
                    )
                    .apply()
            }
        }
    }

    private fun updateScreenHeading() {
        val root = findViewById<View>(
            android.R.id.content
        )

        fun update(view: View) {
            if (
                view is TextView &&
                view.text.toString() ==
                "REHBER VE MESAJ AYARLARI"
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
                ConnectionApi.getConnections(
                    applicationContext
                )
            }

            if (isFinishing || isDestroyed) {
                return@launch
            }

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
                    it.id.isNotBlank() &&
                        it.email.isNotBlank()
                }
                .sortedBy {
                    displayName(it.email)
                        .lowercase(Locale.ROOT)
                }

            showEsContactDialog(
                slot,
                connections
            )
        }
    }

    private fun showEsContactDialog(
        slot: Int,
        connections: List<ConnectionApiItem>
    ) {
        val dialog = Dialog(this)

        dialog.setContentView(
            R.layout.dialog_es_contact_picker
        )

        dialog.window?.apply {
            setBackgroundDrawable(
                ColorDrawable(Color.TRANSPARENT)
            )

            addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )

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
                    ContactNames.hasName(
                        this,
                        item.email
                    )
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
                        dp(14),
                        dp(15),
                        dp(14),
                        dp(15)
                    )

                    setBackgroundColor(
                        Color.rgb(55, 14, 19)
                    )

                    isClickable = true
                }

                val rowParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8)
                    }

                list.addView(
                    row,
                    rowParams
                )

                row.setOnClickListener {
                    selectEsContact(
                        slot,
                        item,
                        name
                    )

                    dialog.dismiss()
                }

                // Kisiye uzun basarak ozel adini degistir.
                row.setOnLongClickListener {
                    dialog.dismiss()
                    showContactNameEditor(item)
                    true
                }
            }
        }

        removeButton.setOnClickListener {
            val prefs = getSharedPreferences(
                prefsName,
                MODE_PRIVATE
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

    /**
     * Secilen baglantinin ozel adini sunucuya kaydeder.
     * Sunucu onaylamadan telefondaki adi degistirmez.
     */
    private fun showContactNameEditor(item: ConnectionApiItem) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            setBackgroundColor(Color.rgb(43, 10, 15))
        }

        root.addView(TextView(this).apply {
            text = "KİŞİ ADINI DÜZENLE"
            textSize = 18f
            setTextColor(Color.WHITE)
        })

        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Kişinin adı"
            setText(ContactNames.getSavedName(
                this@ContactMessageSettingsActivity,
                item.email
            ).orEmpty())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        root.addView(input)

        val save = Button(this).apply { text = "KAYDET" }
        val cancel = Button(this).apply { text = "VAZGEÇ" }
        root.addView(save)
        root.addView(cancel)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        cancel.setOnClickListener { dialog.dismiss() }

        save.setOnClickListener {
            val newName = input.text.toString().trim()
            if (
                newName.isBlank() ||
                newName.codePointCount(0, newName.length) > 100 ||
                newName.contains("@") ||
                newName.any { it.code < 32 || it.code == 127 }
            ) {
                input.error = "Geçerli bir isim gir (en fazla 100 karakter)."
                return@setOnClickListener
            }

            save.isEnabled = false
            activityScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val contacts = ConnectionApi.getContactNames(
                        applicationContext
                    )
                    val userId = contacts.data.orEmpty()
                        .firstOrNull {
                            it.email.equals(item.email, ignoreCase = true)
                        }?.contactUserId.orEmpty()

                    if (!contacts.success || userId.isBlank()) {
                        ConnectionApiResult<Boolean>(
                            false,
                            "Kişi kimliği sunucudan alınamadı."
                        )
                    } else {
                        ConnectionApi.updateContactName(
                            applicationContext,
                            userId,
                            newName
                        )
                    }
                }

                if (isFinishing || isDestroyed) return@launch
                save.isEnabled = true
                if (result.success) {
                    ContactNames.saveName(
                        this@ContactMessageSettingsActivity,
                        item.email,
                        newName
                    )
                    refreshSelectedContactNames()
                    dialog.dismiss()
                    Toast.makeText(
                        this@ContactMessageSettingsActivity,
                        "Kişi adı sunucuya kaydedildi.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@ContactMessageSettingsActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun selectEsContact(
        slot: Int,
        item: ConnectionApiItem,
        name: String
    ) {
        val prefs = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        )

        val alreadySelected = (1..3).any { otherSlot ->
            otherSlot != slot &&
                prefs.getString(
                    "es_contact${otherSlot}_id",
                    ""
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
            .putString(
                "es_contact${slot}_id",
                item.id
            )
            .putString(
                "es_contact${slot}_email",
                item.email
            )
            .putString(
                "es_contact${slot}_name",
                name
            )
            .apply()

        refreshSelectedContactNames()

        Toast.makeText(
            this,
            "Acil durum kişisi kaydedildi.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    private fun saveEmergencyMessage() {
        val message = getCurrentEmergencyMessage()

        getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        ).edit()
            .putString(
                "emergency_message",
                message
            )
            .apply()

        messageField.setText(message)

        Toast.makeText(
            this,
            "Acil durum ayarları kaydedildi.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
