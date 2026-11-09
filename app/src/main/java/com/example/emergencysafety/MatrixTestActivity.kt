
package com.example.emergencysafety

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MatrixTestActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val titleText = TextView(this).apply {
            text = "MATRIX SUNUCU TESTİ"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Bağlantı henüz test edilmedi."
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }

        resultText = TextView(this).apply {
            text = "Sunucu yanıtı burada görünecek."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        testButton = Button(this).apply {
            text = "SUNUCUYA BAĞLAN"
            setOnClickListener {
                runConnectionTest()
            }
        }

        layout.addView(titleText)
        layout.addView(statusText)
        layout.addView(resultText)
        layout.addView(testButton)

        setContentView(layout)
    }

    private fun runConnectionTest() {
        testButton.isEnabled = false
        statusText.text = "Sunucu kontrol ediliyor..."
        resultText.text = ""

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                MatrixConnection.checkServer()
            }

            if (result.isConnected) {
                statusText.text = "BAĞLANTI BAŞARILI"
                resultText.text =
                    "HTTP: ${result.httpStatus}\n\nSunucu yanıtı:\n${result.response}"
            } else {
                statusText.text = "BAĞLANTI BAŞARISIZ"
                resultText.text = result.error
                    ?: "HTTP: ${result.httpStatus}\n${result.response ?: "Yanıt alınamadı."}"
            }

            testButton.isEnabled = true
        }
    }
}
