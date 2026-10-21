package com.example.emergencysafety

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class VoiceCommandSettingsActivity : AppCompatActivity() {

    private val prefsName = "VoiceCommandSettings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_command_settings)

        val fields = listOf(
            R.id.etRedStart,
            R.id.etRedStop,
            R.id.etSirenStart,
            R.id.etSirenStop
        )

        val keys = listOf(
            "red_start",
            "red_stop",
            "siren_start",
            "siren_stop"
        )

        val defaults = listOf(
            "kırmızı 41",
            "kırmızı 42",
            "siren aç",
            "siren kapat"
        )

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val inputs = fields.map { findViewById<EditText>(it) }

        inputs.forEachIndexed { index, input ->
            input.setText(prefs.getString(keys[index], defaults[index]))
        }

        findViewById<Button>(R.id.btnSaveVoiceCommands).setOnClickListener {
            val commands = inputs.map { it.text.toString().trim() }

            if (commands.any { it.isBlank() }) {
                Toast.makeText(
                    this,
                    "Lütfen dört komutun tamamını doldurun.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            if (commands.map { it.lowercase() }.distinct().size != commands.size) {
                Toast.makeText(
                    this,
                    "Her komut birbirinden farklı olmalı.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val editor = prefs.edit()
            commands.forEachIndexed { index, command ->
                editor.putString(keys[index], command)
            }
            editor.apply()

            Toast.makeText(
                this,
                "Sesli komutlar kaydedildi.",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnBackVoiceCommands).setOnClickListener {
            finish()
        }
    }
}
