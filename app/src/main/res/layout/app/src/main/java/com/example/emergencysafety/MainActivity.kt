package com.example.emergencysafety

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSos = findViewById<Button>(R.id.btnSos)
        btnSos.setOnClickListener {
            Toast.makeText(this, "Acil Durum Sinyali Tetiklendi!", Toast.LENGTH_SHORT).show()
        }
    }
}
