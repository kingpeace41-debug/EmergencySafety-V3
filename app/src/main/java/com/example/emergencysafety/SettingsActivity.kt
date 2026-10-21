package com.example.emergencysafety

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnSave = findViewById<Button>(R.id.btnSave)
        btnSave?.setOnClickListener {
            Toast.makeText(this, "Ayarlar kaydedildi!", Toast.LENGTH_SHORT).show()
            finish() // Menüden çıkıp ana sayfaya döner
        }
    }
}
