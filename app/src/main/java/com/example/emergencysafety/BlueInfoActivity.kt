package com.example.emergencysafety

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class BlueInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blue_info)

        findViewById<ImageView>(R.id.blueButton1).setOnClickListener {
            openNearbySearch("hastane acil servis")
        }

        findViewById<ImageView>(R.id.blueButton2).setOnClickListener {
            openNearbySearch("polis merkezi jandarma")
        }

        findViewById<ImageView>(R.id.blueButton3).setOnClickListener {
            openNearbySearch("eczane")
        }
    }

    private fun openNearbySearch(query: String) {
        val uri = Uri.parse(
            "https://www.google.com/maps/search/?api=1&query=" +
                Uri.encode(query)
        )

        val intent = Intent(Intent.ACTION_VIEW, uri)

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Harita açılamadı. İnternet bağlantınızı kontrol edin.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
