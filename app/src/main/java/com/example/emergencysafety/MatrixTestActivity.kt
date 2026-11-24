
package com.example.emergencysafety

import android.os.Bundle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

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
        layout.addView(Button(this).apply {
            text = "YEREL DEPOLAMA TESTİ"
            setOnClickListener { runStorageTest() }
        })

        layout.addView(Button(this).apply {
            text = "MATRIX OTURUMUNU BAŞLAT"
            setOnClickListener { runSessionTest(this) }
        })

        layout.addView(Button(this).apply {
            text = "MATRIX SENKRONİZASYON TESTİ"
            setOnClickListener { runSyncTest(this) }
        })

        setContentView(layout)
    }

    private fun runSessionTest(button: Button) {
        button.isEnabled = false
        statusText.text = "Matrix oturumu hazırlanıyor..."
        resultText.text = ""
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = MatrixClientManager.openForCurrentSession(applicationContext)
                val client = result.getOrThrow()
                statusText.text = "MATRIX İSTEMCİSİ AÇILDI"
                resultText.text = "Cihaz kimliği: ${client.deviceId}\nKalıcı istemci oluşturuldu. Şifreli mesaj testi henüz yapılmadı."
            } catch (error: Exception) {
                statusText.text = "MATRIX OTURUMU BAŞLATILAMADI"
                resultText.text = error.javaClass.simpleName
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun runSyncTest(button: Button) {
        button.isEnabled = false
        statusText.text = "Matrix senkronizasyonu yapılıyor..."
        resultText.text = ""
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = MatrixClientManager.openForCurrentSession(applicationContext).getOrThrow()
                withContext(Dispatchers.IO) {
                    withTimeout(60000L) {
                        client.syncOnce(presence = null, timeout = 10.seconds).getOrThrow()
                    }
                }
                statusText.text = "MATRIX SENKRONİZASYONU BAŞARILI"
                resultText.text = "Cihaz kimliği: ${client.deviceId}\nTek seferlik senkronizasyon tamamlandı. Şifreli mesaj testi henüz yapılmadı."
            } catch (error: Exception) {
                statusText.text = "MATRIX SENKRONİZASYONU BAŞARISIZ"
                resultText.text = error.javaClass.simpleName
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun runStorageTest() {
        statusText.text = "Yerel depolama kontrol ediliyor..."
        resultText.text = ""
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val path = File(noBackupFilesDir, "matrix-storage-test.db").absolutePath
                    val driver = BundledSQLiteDriver()
                    driver.open(path).use { db ->
                        db.prepare("CREATE TABLE IF NOT EXISTS storage_test (id INTEGER PRIMARY KEY, value TEXT NOT NULL)").use { it.step() }
                        db.prepare("INSERT OR REPLACE INTO storage_test VALUES (1, 'ES_TEST')").use { it.step() }
                    }
                    driver.open(path).use { db ->
                        db.prepare("SELECT value FROM storage_test WHERE id = 1").use {
                            check(it.step() && it.getText(0) == "ES_TEST")
                        }
                    }
                }
            }
            statusText.text = if (result.isSuccess) "DEPOLAMA TESTİ BAŞARILI" else "DEPOLAMA TESTİ BAŞARISIZ"
            resultText.text = if (result.isSuccess) "SQLite yazma, kapatma ve yeniden okuma başarılı." else result.exceptionOrNull()?.javaClass?.simpleName ?: "Bilinmeyen hata"
        }
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
