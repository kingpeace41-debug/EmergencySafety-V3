
package com.example.emergencysafety

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ContactMessageSettingsActivity : AppCompatActivity() {

    private val prefsName = "EmergencyContactSettings"

    private lateinit var contactNameFields: List<EditText>
    private lateinit var contactPhoneFields: List<EditText>

    private var selectedContactIndex = -1

    companion object {
        private const val REQUEST_SELECT_CONTACT = 1001

        private val DEFAULT_EMERGENCY_MESSAGE = """
            ACİL DURUM!

            Ben [Ad Soyad].

            Acil durumdayım ve yardıma ihtiyacım var. Lütfen aşağıdaki konuma ivedilikle gelin veya gerekli yardımı yönlendirin.

            Konumum: [Otomatik konum bağlantısı]
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_message_settings)

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

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

        val messageField =
            findViewById<EditText>(R.id.etEmergencyMessage)

        for (i in 0..2) {
            contactNameFields[i].setText(
                prefs.getString("contact${i + 1}_name", "")
            )

            contactPhoneFields[i].setText(
                normalizeTurkishPhoneNumber(
                    prefs.getString("contact${i + 1}_phone", "") ?: ""
                )
            )

            contactNameFields[i].apply {
                isCursorVisible = false
                showSoftInputOnFocus = false
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
            }

            contactPhoneFields[i].apply {
                isCursorVisible = false
                showSoftInputOnFocus = false
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
            }

            contactNameFields[i].setOnClickListener {
                selectedContactIndex = i
                openContactPicker()
            }

            contactPhoneFields[i].setOnClickListener {
                selectedContactIndex = i
                openContactPicker()
            }

            contactNameFields[i].setOnLongClickListener {
                selectedContactIndex = i
                openContactPicker()
                true
            }

            contactPhoneFields[i].setOnLongClickListener {
                selectedContactIndex = i
                openContactPicker()
                true
            }
        }

        // Kayıtlı mesajı koru; mesaj yoksa varsayılan şablonu göster.
        val savedEmergencyMessage =
            prefs.getString("emergency_message", null)

        messageField.setText(
            savedEmergencyMessage
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_EMERGENCY_MESSAGE
        )

        findViewById<Button>(R.id.btnSaveContacts)
            .setOnClickListener {
                val editor = prefs.edit()

                for (i in 0..2) {
                    val name =
                        contactNameFields[i].text.toString().trim()

                    val phone = normalizeTurkishPhoneNumber(
                        contactPhoneFields[i].text.toString()
                    )

                    contactNameFields[i].setText(name)
                    contactPhoneFields[i].setText(phone)

                    editor.putString(
                        "contact${i + 1}_name",
                        name
                    )

                    editor.putString(
                        "contact${i + 1}_phone",
                        phone
                    )
                }

                editor.putString(
                    "emergency_message",
                    messageField.text.toString().trim()
                )

                editor.apply()

                Toast.makeText(
                    this,
                    "Rehber ve mesaj ayarları kaydedildi",
                    Toast.LENGTH_SHORT
                ).show()
            }

        findViewById<Button>(R.id.btnBackContacts)
            .setOnClickListener {
                finish()
            }

        Toast.makeText(
            this,
            "Kişi seçmek için isim veya telefon alanına dokunun.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun normalizeTurkishPhoneNumber(rawNumber: String): String {
        val raw = rawNumber.trim()

        if (raw.isEmpty()) {
            return ""
        }

        val digits = raw.filter { it.isDigit() }
        val hasLeadingPlus = raw.startsWith("+")

        if (digits.isEmpty()) {
            return ""
        }

        if (hasLeadingPlus) {
            return "+$digits"
        }

        if (digits.startsWith("0090") && digits.length == 14) {
            return "+${digits.drop(2)}"
        }

        if (digits.startsWith("90") && digits.length == 12) {
            return "+$digits"
        }

        if (digits.startsWith("0") && digits.length == 11) {
            return "+90${digits.drop(1)}"
        }

        if (digits.length == 10) {
            return "+90$digits"
        }

        return raw
    }

    private fun openContactPicker() {
        try {
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
            }

            startActivityForResult(intent, REQUEST_SELECT_CONTACT)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Rehber açılamadı",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Deprecated("Deprecated in Android API; retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode != REQUEST_SELECT_CONTACT ||
            resultCode != RESULT_OK ||
            data?.data == null
        ) {
            return
        }

        val contactUri: Uri = data.data!!

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            contentResolver.query(
                contactUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )

                    val numberIndex = cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

                    val name = if (nameIndex >= 0) {
                        cursor.getString(nameIndex) ?: ""
                    } else {
                        ""
                    }

                    val number = if (numberIndex >= 0) {
                        cursor.getString(numberIndex) ?: ""
                    } else {
                        ""
                    }

                    if (selectedContactIndex in 0..2) {
                        contactNameFields[selectedContactIndex].setText(name)

                        contactPhoneFields[selectedContactIndex].setText(
                            normalizeTurkishPhoneNumber(number)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Kişi bilgileri alınamadı",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
