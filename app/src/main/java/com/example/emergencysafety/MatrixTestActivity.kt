
package com.example.emergencysafety

import android.os.Bundle
import de.connect2x.trixnity.client.verification
import de.connect2x.trixnity.client.key
import de.connect2x.trixnity.client.verification.*
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationMethod
import de.connect2x.trixnity.crypto.key.DeviceTrustLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import de.connect2x.trixnity.client.MatrixClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class MatrixTestActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var testButton: Button

    private var syncJob: Job? = null
    private var syncClient: MatrixClient? = null
    private lateinit var continuousButton: Button
    private lateinit var testLayout: LinearLayout

    private lateinit var sasButton: Button
    private lateinit var trustButton: Button
    private lateinit var sasAction: Button
    private lateinit var sasReject: Button
    private lateinit var sasText: TextView
    private var sasJob: Job? = null
    private var activeVerification: ActiveDeviceVerification? = null

    private fun peer(client: MatrixClient): Pair<UserId, String> {
        return when (client.deviceId) {
            "ES92dd49af054f02dac88d6e76" -> UserId("@es8de5ac49b6f51486aa1bdf0be9f0fd03:emergency-safety.com") to "ES6b352fe620738427b4dc51d1"
            "ES6b352fe620738427b4dc51d1" -> UserId("@esf78bb1b86885ce83a926f496719785e7:emergency-safety.com") to "ES92dd49af054f02dac88d6e76"
            else -> error("Bu cihaz SAS test listesinde yok")
        }
    }

    private fun verified(level: DeviceTrustLevel): Boolean =
        when (level) {
            is DeviceTrustLevel.Valid -> level.verified
            is DeviceTrustLevel.CrossSigned -> level.verified
            else -> false
        }

    private suspend fun watchSas(client: MatrixClient) = coroutineScope {
        val (user, device) = peer(client)
        sasButton.isEnabled = true
        trustButton.isEnabled = true
        var last: ActiveDeviceVerification? = null
        var actedState: Any? = null
        var busy = false
        var confirmed = false
        var completed = false
        fun action(label: String, state: Any, block: suspend () -> Unit) {
            sasAction.text = label
            sasAction.isEnabled = !busy && actedState !== state
            sasAction.setOnClickListener {
                if (busy || actedState === state) return@setOnClickListener
                busy = true
                actedState = state
                sasAction.isEnabled = false
                launch {
                    try { block() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sasText.text = "İşlem hatası: ${e.javaClass.simpleName}" }
                    finally { busy = false }
                }
            }
        }
        sasButton.setOnClickListener {
            if (busy || client.verification.activeDeviceVerification.value != null) return@setOnClickListener
            busy = true
            sasButton.isEnabled = false
            launch {
                try {
                    client.verification.createDeviceVerificationRequest(user, setOf(device)).getOrThrow()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { sasText.text = "İstek hatası: ${e.javaClass.simpleName}" }
                finally { busy = false }
            }
        }
        trustButton.setOnClickListener {
            launch {
                try {
                    val level = withTimeout(15000L) { client.key.getTrustLevel(user, device).first() }
                    sasText.text = "Kayıtlı cihaz güveni: " + if (verified(level)) "DOĞRULANMIŞ" else "DOĞRULANMAMIŞ (${level.javaClass.simpleName})"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { sasText.text = "Güven kontrolü başarısız" }
            }
        }
        while (true) {
            val current = client.verification.activeDeviceVerification.value
            val v = current ?: last
            activeVerification = current
            sasButton.isEnabled = !busy && current == null
            sasAction.isEnabled = false
            sasReject.isEnabled = false
            if (v != null) {
                if (v !== last) {
                    last = v
                    actedState = null
                    confirmed = false
                    completed = false
                }
                val state = v.state.value
                if (v.theirUserId != user || (v.theirDeviceId != null && v.theirDeviceId != device)) {
                    sasText.text = "Beklenmeyen cihaz: doğrulama reddedildi"
                    if (current != null && !busy) v.cancel()
                } else {
                    sasReject.isEnabled = current != null && !busy && state !is ActiveVerificationState.Done && state !is ActiveVerificationState.Cancel
                    sasReject.setOnClickListener {
                        if (busy) return@setOnClickListener
                        busy = true
                        launch {
                            try {
                                val method = (v.state.value as? ActiveVerificationState.Start)?.method as? ActiveSasVerificationMethod
                                val comparison = method?.state?.value as? ActiveSasVerificationState.ComparisonByUser
                                if (comparison != null) comparison.noMatch() else v.cancel()
                            } finally { busy = false }
                        }
                    }
                    when (state) {
                        is ActiveVerificationState.TheirRequest -> {
                            sasText.text = "Test cihazından doğrulama isteği"
                            action("İSTEĞİ KABUL ET", state) { check(v.state.value === state); state.ready() }
                        }
                        is ActiveVerificationState.OwnRequest -> sasText.text = "Karşı cihazın kabulü bekleniyor"
                        is ActiveVerificationState.Ready -> {
                            sasText.text = "SAS başlatılabilir"
                            action("SAS BAŞLAT", state) { check(v.state.value === state); state.start(VerificationMethod.Sas) }
                        }
                        is ActiveVerificationState.Start -> {
                            val method = state.method as? ActiveSasVerificationMethod
                            when (val sub = method?.state?.value) {
                                is ActiveSasVerificationState.TheirSasStart -> {
                                    sasText.text = "SAS isteği geldi"
                                    action("SAS KABUL ET", sub) { check(method.state.value === sub); sub.accept() }
                                }
                                is ActiveSasVerificationState.ComparisonByUser -> {
                                    sasText.text = "İki telefondaki üç sayıyı karşılaştır:\n" + sub.decimal.joinToString("   ")
                                    if (!confirmed && v.theirDeviceId == device) action("ÜÇ SAYI EŞLEŞİYOR", sub) {
                                        check(v.state.value === state && method.state.value === sub)
                                        check(v.theirUserId == user && v.theirDeviceId == device)
                                        sub.match()
                                        confirmed = true
                                    }
                                }
                                else -> sasText.text = "SAS anahtarları / karşı onay bekleniyor"
                            }
                        }
                        is ActiveVerificationState.Done -> {
                            if (!completed) {
                                completed = true
                                val level = withTimeout(15000L) { client.key.getTrustLevel(user, device).first() }
                                sasText.text = if (confirmed && verified(level)) "SAS TAMAMLANDI — CİHAZ DOĞRULANMIŞ" else "SAS tamamlandı; cihaz güveni ayrıca kontrol edilmeli"
                            }
                        }
                        is ActiveVerificationState.Cancel -> sasText.text = "DOĞRULAMA İPTAL EDİLDİ"
                        else -> sasText.text = "Karşı cihazın işlemi bekleniyor"
                    }
                }
            }
            delay(250)
        }
    }

    override fun onStop() {
        syncJob?.cancel()
        super.onStop()
    }

    private fun runContinuousSync() {
        if (syncJob?.isActive == true) {
            continuousButton.isEnabled = false
            syncJob?.cancel()
            return
        }
        if (syncJob?.isCompleted == false) return
        val buttons = (0 until testLayout.childCount)
            .mapNotNull { testLayout.getChildAt(it) as? Button }
            .filter { it !== continuousButton && it !== sasButton && it !== trustButton && it !== sasAction && it !== sasReject }
        if (buttons.any { !it.isEnabled }) return
        buttons.forEach { it.isEnabled = false }
        continuousButton.text = "SENKRONİZASYONU DURDUR"
        statusText.text = "Sürekli senkronizasyon hazırlanıyor..."
        resultText.text = ""
        syncJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = MatrixClientManager.openForCurrentSession(applicationContext).getOrThrow()
                syncClient = client
                withTimeout(60000L) { client.startSync(presence = null) }
                statusText.text = "SENKRONİZASYON BAŞLATILDI"
                resultText.text = "Başlatma çağrısı tamamlandı. Veri akışı henüz doğrulanmadı."
                watchSas(client)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                statusText.text = "SENKRONİZASYON HATASI"
                resultText.text = error.javaClass.simpleName
            } finally {
                val stopped = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { withTimeout(5000L) { activeVerification?.cancel() } }
                    runCatching { syncClient?.cancelSync() }.isSuccess
                }
                activeVerification = null
                sasButton.isEnabled = false
                trustButton.isEnabled = false
                sasAction.isEnabled = false
                sasReject.isEnabled = false
                syncClient = null
                continuousButton.text = "SÜREKLİ SENKRONİZASYON BAŞLAT"
                continuousButton.isEnabled = stopped
                buttons.forEach { it.isEnabled = stopped }
                statusText.text = if (stopped) "SENKRONİZASYON DURDURULDU" else "SENKRONİZASYON DURDURULAMADI"
            }
        }
    }

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

        layout.addView(Button(this).apply {
            text = "ŞİFRELEME ANAHTARLARINI DOĞRULA"
            setOnClickListener { runKeyTest(this) }
        })

        testLayout = layout
        continuousButton = Button(this).apply {
            text = "SÜREKLİ SENKRONİZASYON BAŞLAT"
            setOnClickListener { runContinuousSync() }
        }
        layout.addView(continuousButton)
        sasText = TextView(this).apply { text = "SAS için sürekli senkronizasyonu başlat."; gravity = Gravity.CENTER; textSize = 18f }
        layout.addView(sasText)
        sasButton = Button(this).apply { text = "CİHAZ DOĞRULAMA İSTEĞİ GÖNDER"; isEnabled = false }
        trustButton = Button(this).apply { text = "KAYITLI CİHAZ GÜVENİNİ KONTROL ET"; isEnabled = false }
        sasAction = Button(this).apply { text = "DOĞRULAMA İŞLEMİ"; isEnabled = false }
        sasReject = Button(this).apply { text = "EŞLEŞMİYOR / İPTAL"; isEnabled = false }
        listOf(sasButton, sasAction, sasReject, trustButton).forEach { layout.addView(it) }

        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        })
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

    private fun runKeyTest(button: Button) {
        button.isEnabled = false
        statusText.text = "Açık cihaz anahtarları kontrol ediliyor..."
        resultText.text = ""
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = MatrixClientManager.openForCurrentSession(applicationContext).getOrThrow()
                withContext(Dispatchers.IO) {
                    withTimeout(30000L) {
                        val response = client.api.key.getKeys(
                            deviceKeys = mapOf(client.userId to setOf(client.deviceId))
                        ).getOrThrow()
                        val keys = requireNotNull(response.deviceKeys?.get(client.userId)?.get(client.deviceId)).signed
                        check(keys.userId == client.userId && keys.deviceId == client.deviceId)
                        check(client.identityKey in keys.keys)
                        check(client.signingKey in keys.keys)
                    }
                }
                statusText.text = "AÇIK ANAHTARLAR EŞLEŞİYOR"
                resultText.text = "Curve25519 ve Ed25519 anahtarları sunucudaki kayıtla eşleşti. Karşılıklı cihaz güveni henüz doğrulanmadı."
            } catch (error: Exception) {
                statusText.text = "ANAHTAR KONTROLÜ BAŞARISIZ"
                resultText.text = error.javaClass.simpleName
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun runStorageTest() {
        val button = (0 until testLayout.childCount).mapNotNull { testLayout.getChildAt(it) as? Button }.first { it.text == "YEREL DEPOLAMA TESTİ" }
        button.isEnabled = false
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
            button.isEnabled = true
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
