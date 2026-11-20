
package com.example.emergencysafety

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

        setContentView(
            R.layout.activity_contact_message_settings
        )

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

        messageField = findViewById(
            R.id.etEmergencyMessage
        )

        // Eski WhatsApp kayıtlarını silmeden
        // telefon numarası alanlarını gizle.
        contactPhoneFields.forEach { field ->
            field.visibility = View.GONE
        }

        for (index in 0..2) {
            val slot = index + 1
            val field = contactNameFields[index]

            field.hint =
                "$slot. ES acil durum kişisini seç"

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

        val prefs = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        )

        val savedEmergencyMessage =
            prefs.getString(
                "emergency_message",
                null
            )

        messageField.setText(
            savedEmergencyMessage
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_EMERGENCY_MESSAGE
        )

        findViewById<Button>(
            R.id.btnSaveContacts
        ).setOnClickListener {
            saveEmergencyMessage()
        }

        findViewById<Button>(
            R.id.btnBackContacts
        ).setOnClickListener {
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

    /**
     * Kayıtlı ES kişilerinin isimlerini günceller.
     *
     * Bağlantı kimlikleri değiştirilmez.
     * Messenger'da kayıtlı özel isim kullanılır.
     * Özel isim yoksa mevcut kayıt korunur.
     */
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

            val currentName = if (email.isNotBlank()) {
                ContactNames.getDisplayName(
                    this,
                    email
                )
            } else {
                savedName
            }

            val displayName = currentName.ifBlank {
                savedName
            }

            field.setText(displayName)

            if (
                displayName.isNotBlank() &&
                displayName != savedName
            ) {
                prefs.edit()
                    .putString(
                        "es_contact${slot}_name",
                        displayName
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
            if (view is TextView) {
                if (
                    view.text.toString() ==
                    "REHBER VE MESAJ AYARLARI"
                ) {
                    view.text =
                        "ES KİŞİ VE MESAJ AYARLARI"
                }
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    update(view.getChildAt(index))
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

            val result = withContext(
                Dispatchers.IO
            ) {
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

            val connections = result.data
                .orEmpty()
                .filter {
                    it.id.isNotBlank() &&
                    it.email.isNotBlank()
                }
                .sortedBy {
                    ContactNames.getDisplayName(
                        this@ContactMessageSettingsActivity,
                        it.email
                    ).lowercase(Locale.ROOT)
                }

            if (connections.isEmpty()) {

                AlertDialog.Builder(
                    this@ContactMessageSettingsActivity
                )
                    .setTitle("ES ACİL DURUM KİŞİLERİ")
                    .setMessage(
                        "Henüz onaylanmış ES bağlantın " +
                        "bulunmuyor.\n\n" +
                        "Önce sarı mesajlaşma " +
                        "konsolundan kişi ekleyip " +
                        "karşılıklı bağlantıyı onaylayın."
                    )
                    .setPositiveButton(
                        "TAMAM",
                        null
                    )
                    .show()

                return@launch
            }

            val names = connections.map { item ->
                ContactNames.getDisplayName(
                    this@ContactMessageSettingsActivity,
                    item.email
                )
            }.toTypedArray()

            AlertDialog.Builder(
                this@ContactMessageSettingsActivity
            )
                .setTitle(
                    "$slot. ACİL DURUM KİŞİSİNİ SEÇ"
                )
                .setItems(names) { _, selected ->

                    val item = connections[selected]

                    val prefs = getSharedPreferences(
                        prefsName,
                        MODE_PRIVATE
                    )

                    val alreadySelected =
                        (1..3).any { otherSlot ->

                            otherSlot != slot &&
                            prefs.getString(
                                "es_contact${otherSlot}_id",
                                ""
                            ) == item.id
                        }

                    if (alreadySelected) {

                        Toast.makeText(
                            this,
                            "Bu kişi zaten başka bir " +
                            "sırada seçilmiş.",
                            Toast.LENGTH_LONG
                        ).show()

                    } else {

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
                                names[selected]
                            )
                            .apply()

                        refreshSelectedContactNames()

                        Toast.makeText(
                            this,
                            "Acil durum kişisi kaydedildi.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNeutralButton(
                    "KİŞİYİ KALDIR"
                ) { _, _ ->

                    val prefs = getSharedPreferences(
                        prefsName,
                        MODE_PRIVATE
                    )

                    prefs.edit()
                        .remove(
                            "es_contact${slot}_id"
                        )
                        .remove(
                            "es_contact${slot}_email"
                        )
                        .remove(
                            "es_contact${slot}_name"
                        )
                        .apply()

                    refreshSelectedContactNames()

                    Toast.makeText(
                        this,
                        "ES acil durum kişisi kaldırıldı.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(
                    "VAZGEÇ",
                    null
                )
                .show()
        }
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
            prefsName,
            MODE_PRIVATE
        ).edit()
            .putString(
                "emergency_message",
                message
            )
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
