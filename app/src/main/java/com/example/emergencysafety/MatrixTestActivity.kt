
package com.example.emergencysafety

import android.os.Bundle
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
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

    private val reportScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val reportLock = kotlinx.coroutines.sync.Mutex()
    private fun report(name: String, result: String, error: String? = null) {
        val app = applicationContext
        val session = SecureSessionStore.getSessionId(app) ?: return
        val prefs = app.getSharedPreferences("matrix_reports_" + session, MODE_PRIVATE)
        val id = java.util.UUID.randomUUID().toString()
        val body = org.json.JSONObject().put("reportId", id).put("testName", name)
            .put("result", result).put("testedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date()))
        if (error != null) body.put("errorCode", error.replace(Regex("[^A-Za-z0-9_]"), "_").take(80).ifBlank { "UNKNOWN" })
        prefs.edit().putString(id, body.toString()).commit()
        flushReports()
    }
    private fun flushReports() {
        val app = applicationContext
        val session = SecureSessionStore.getSessionId(app) ?: return
        val prefs = app.getSharedPreferences("matrix_reports_" + session, MODE_PRIVATE)
        reportScope.launch {
            if (!reportLock.tryLock()) return@launch
            try {
                for ((id, raw) in prefs.all) {
                    if (SecureSessionStore.getSessionId(app) != session) break
                    val body = org.json.JSONObject(raw as? String ?: continue)
                    if (!ConnectionApi.sendMatrixTestReport(app, body)) break
                    prefs.edit().remove(id).commit()
                }
            } finally { reportLock.unlock() }
        }
    }
    override fun onResume() {
        super.onResume()
        flushReports()
        if (syncJob?.isActive == true) report("BACKGROUND_RETURN", "INFO")
    }

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

    private fun peers(client: MatrixClient): List<Pair<UserId, String>> {
        val devices = listOf(
            UserId("@esf78bb1b86885ce83a926f496719785e7:emergency-safety.com") to "ES92dd49af054f02dac88d6e76",
            UserId("@es8de5ac49b6f51486aa1bdf0be9f0fd03:emergency-safety.com") to "ES6b352fe620738427b4dc51d1",
            UserId("@es87c89b77446e224643839983b927364a:emergency-safety.com") to "ES3ce804d497e17ded0aeed40a"
        )
        check(devices.any { it.first == client.userId && it.second == client.deviceId })
        return devices.filter { it.second != client.deviceId }
    }

    private fun verified(level: DeviceTrustLevel): Boolean =
        when (level) {
            is DeviceTrustLevel.Valid -> level.verified
            is DeviceTrustLevel.CrossSigned -> level.verified
            else -> false
        }

    private suspend fun watchSas(client: MatrixClient) = coroutineScope {
        val connectionId = intent.getStringExtra("matrix_verify_connection")
        val choices = if (connectionId == null) peers(client) else {
            val peer = withContext(Dispatchers.IO) {
                requireNotNull(ConnectionApi.getMatrixPeer(applicationContext, connectionId).data)
            }
            listOf(UserId(peer.userId) to peer.deviceId)
        }
        val prefs = getSharedPreferences("matrix_test_" + client.deviceId, MODE_PRIVATE)
        var (user, device) = choices.firstOrNull { it.second == prefs.getString("peer", "") } ?: choices.first()
        val peerButton = Button(this@MatrixTestActivity).apply { text = "TEST CİHAZI: " + device }
        testLayout.addView(peerButton, testLayout.indexOfChild(sasText))

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
                    report("DEVICE_TRUST", if (verified(level)) "PASS" else "FAIL")
                    sasText.text = "Kayıtlı cihaz güveni: " + if (verified(level)) "DOĞRULANMIŞ" else "DOĞRULANMAMIŞ (${level.javaClass.simpleName})"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { sasText.text = "Güven kontrolü başarısız" }
            }
        }
        val roomInput = android.widget.EditText(this@MatrixTestActivity).apply { hint = "Şifreli test oda kimliği"; setSingleLine(true); setText(prefs.getString("room_" + device, "")) }
        val messageInput = android.widget.EditText(this@MatrixTestActivity).apply { hint = "Test mesajı"; setText("ES E2EE TEST") }
        val roomStatus = TextView(this@MatrixTestActivity)
        val controls = mutableListOf<android.view.View>()
        fun addControl(view: android.view.View) { controls.add(view); testLayout.addView(view) }
        fun roomAction(label: String, test: String, block: suspend () -> Unit) {
            addControl(Button(this@MatrixTestActivity).apply {
                text = label
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    busy = true
                    launch {
                        try { withTimeout(30000L) { block() }; report(test, if (test == "MESSAGE_QUEUE" || test == "ROOM_SEARCH") "INFO" else "PASS") }
                        catch (e: kotlinx.coroutines.TimeoutCancellationException) { report(test, "FAIL", "TIMEOUT"); roomStatus.text = "İşlem zaman aşımına uğradı. Tekrar deneyin." }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report(test, "FAIL", e.message?.takeIf { it in setOf("TEST_DEVICE_ALREADY_VERIFIED", "POLICY_NOT_AVAILABLE", "POLICY_DENIAL_TEST_FAILED", "ROOM_MEMBERS_MISMATCH", "ROOM_NOT_ENCRYPTED", "PEER_NOT_VERIFIED", "PEER_NOT_JOINED", "ROOM_NOT_SELECTED") } ?: e.javaClass.simpleName); roomStatus.text = if (e is IllegalArgumentException && roomInput.text.isBlank()) "Önce şifreli test odasını seçin." else "Test hatası: " + e.javaClass.simpleName }
                        finally { busy = false }
                    }
                }
            })
        }
        controls.add(peerButton)
        peerButton.isEnabled = choices.size > 1
        peerButton.setOnClickListener {
            if (busy || client.verification.activeDeviceVerification.value != null) return@setOnClickListener
            val next = choices[(choices.indexOf(user to device) + 1) % choices.size]
            user = next.first
            device = next.second
            peerButton.text = "TEST CİHAZI: " + device
            last = null
            actedState = null
            confirmed = false
            completed = false
            prefs.edit().putString("peer", device).apply()
            roomInput.setText(prefs.getString("room_" + device, ""))
            roomStatus.text = ""
            sasText.text = "Karşı cihaz seçildi. Güveni kontrol edin."
        }
        addControl(roomInput)
        roomInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.edit().putString("room_" + device, s?.toString().orEmpty()).apply()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        addControl(messageInput)
        var lastChecks = MatrixPolicyAudit.checks.get()
        var lastDenials = MatrixPolicyAudit.denied.get()
        addControl(Button(this@MatrixTestActivity).apply {
            text = "ANAHTAR POLİTİKASI RAPORU"
            setOnClickListener {
                val checks = MatrixPolicyAudit.checks.get()
                val denials = MatrixPolicyAudit.denied.get()
                roomStatus.text = "Anahtar güven kontrolleri: $checks / Reddedilen: $denials"
                report("KEY_CHECK", "INFO", if (checks > 0) "POLICY_CHECK_OBSERVED" else "POLICY_NOT_OBSERVED")
                report("KEY_CHECK", "INFO", if (denials > 0) "POLICY_DENIAL_OBSERVED" else "NO_DENIAL_OBSERVED")
            }
        })
        roomAction("DOĞRULANMAMIŞ CİHAZ ANAHTAR TESTİ", "KEY_CHECK") {
            check(!verified(client.key.getTrustLevel(user, device).first())) { "TEST_DEVICE_ALREADY_VERIFIED" }
            val policy = checkNotNull(MatrixPolicyAudit.policy?.get()) { "POLICY_NOT_AVAILABLE" }
            check(policy.testDenial(user, device)) { "POLICY_DENIAL_TEST_FAILED" }
            report("KEY_CHECK", "PASS", "SINGLE_AND_BATCH_DENIED")
            roomStatus.text = "BAŞARILI: Tekli ve toplu anahtar politikası denemeleri reddedildi."
        }
        addControl(roomStatus)
        fun selectedRoom(): RoomId {
            val value = roomInput.text.toString().trim()
            require(value.startsWith("!") && value.length > 1) { "ROOM_NOT_SELECTED" }
            return RoomId(value)
        }
        suspend fun checkDirectRoom(id: RoomId, requireJoined: Boolean) {
            val room = requireNotNull(client.room.getById(id).first())
            check(room.encrypted) { "ROOM_NOT_ENCRYPTED" }
            if (requireJoined) check(room.membership == Membership.JOIN)
            val members = client.room.getAllState(id, MemberEventContent::class).first()
            val active = members.mapNotNull { (key, flow) ->
                val membership = flow.first()?.content?.membership
                if (membership == Membership.JOIN || membership == Membership.INVITE) key else null
            }.toSet()
            check(active == setOf(client.userId.toString(), user.toString())) { "ROOM_MEMBERS_MISMATCH" }
            if (requireJoined) {
                check(members[user.toString()]?.first()?.content?.membership == Membership.JOIN) { "PEER_NOT_JOINED" }
            }
        }
        roomAction("ŞİFRELİ TEST ODASI OLUŞTUR", "ROOM_CREATE") {
            check(verified(client.key.getTrustLevel(user, device).first())) { "Önce cihaz güvenini doğrulayın" }
            val id = client.api.room.createRoom(
                name = "ES E2EE TEST",
                invite = setOf(user),
                initialState = listOf(InitialStateEvent(EncryptionEventContent(rotationPeriodMsgs = 1L), "")),
                isDirect = true
            ).getOrThrow()
            roomInput.setText(id.toString())
            roomStatus.text = "Şifreli test odası oluşturuldu. Karşı cihaz davet edildi."
        }
        roomAction("ŞİFRELİ TEST ODALARINI BUL", "ROOM_SEARCH") {
            val rooms = client.room.getAll().first()
            val found = rooms.mapNotNull { (id, flow) ->
                val room = flow.first()
                if (room?.encrypted == true && (room.membership == Membership.JOIN || room.membership == Membership.INVITE)) id.toString() else null
            }
            roomStatus.text = found.joinToString("\n").ifBlank { "Şifreli oda bulunamadı" }
            if (found.size == 1) roomInput.setText(found.first())
            else if (found.isNotEmpty()) {
                val selectedDevice = device
                androidx.appcompat.app.AlertDialog.Builder(this@MatrixTestActivity)
                    .setTitle("Şifreli test odasını seç")
                    .setItems(found.toTypedArray()) { _, index ->
                        if (device == selectedDevice && !busy) {
                            roomInput.setText(found[index])
                            roomStatus.text = "Seçilen oda: " + found[index]
                        }
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
        roomAction("ŞİFRELİ ODAYA KATIL", "ROOM_JOIN") {
            val id = selectedRoom()
            check(verified(client.key.getTrustLevel(user, device).first())) { "PEER_NOT_VERIFIED" }
            checkDirectRoom(id, false)
            client.api.room.joinRoom(id).getOrThrow()
            roomStatus.text = "Katılım tamamlandı"
        }
        roomAction("ŞİFRELİ TEST MESAJI GÖNDER", "MESSAGE_QUEUE") {
            val id = selectedRoom()
            check(verified(client.key.getTrustLevel(user, device).first())) { "PEER_NOT_VERIFIED" }
            checkDirectRoom(id, true)
            val body = messageInput.text.toString().trim()
            check(body.isNotEmpty())
            val txn = client.room.sendMessage(id) { text(body) }
            roomStatus.text = "Kuyruğa alındı (teslim onayı değil): " + txn
        }
        launch {
            client.room.getTimelineEventsFromNowOn().collect { event ->
                if (event.event.roomId.toString() == roomInput.text.toString().trim()) {
                    val encrypted = event.event.content::class.simpleName == "MegolmEncryptedMessageEventContent"
                    if (encrypted) {
                        report("ENCRYPTED_EVENT", "PASS")
                        if (event.event.sender == user) {
                            report("MESSAGE_DECRYPT", if (event.content?.getOrNull() != null) "PASS" else "FAIL")
                        }
                    } else if (event.event.content::class.simpleName?.contains("MessageEventContent") == true) {
                        report("ENCRYPTED_EVENT", "FAIL", "PLAINTEXT_EVENT")
                    }
                    roomStatus.text = "Oda olayı: " + event.event.content::class.simpleName +
                        "\nÇözülmüş içerik: " + (event.content?.getOrNull()?.toString() ?: "Yok")
                }
            }
        }
        try {
        while (true) {
            val current = client.verification.activeDeviceVerification.value
            val v = current ?: last
            activeVerification = current
            peerButton.isEnabled = !busy && current == null
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
                    if (current != null && !busy) { busy = true; launch { try { withTimeout(5000L) { v.cancel() } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { sasText.text = "İptal zaman aşımı" } catch (e: CancellationException) { throw e } catch (e: Exception) { sasText.text = "İptal hatası" } finally { busy = false } } }
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
                                report("SAS_VERIFICATION", if (confirmed && verified(level)) "PASS" else "FAIL")
                                sasText.text = if (confirmed && verified(level)) "SAS TAMAMLANDI — CİHAZ DOĞRULANMIŞ" else "SAS tamamlandı; cihaz güveni ayrıca kontrol edilmeli"
                            }
                        }
                        is ActiveVerificationState.Cancel -> sasText.text = "DOĞRULAMA İPTAL EDİLDİ"
                        else -> sasText.text = "Karşı cihazın işlemi bekleniyor"
                    }
                }
            }
            val checks = MatrixPolicyAudit.checks.get()
            val denials = MatrixPolicyAudit.denied.get()
            if (checks != lastChecks) { lastChecks = checks; report("KEY_CHECK", "INFO", "POLICY_CHECK_OBSERVED") }
            if (denials != lastDenials) { lastDenials = denials; report("KEY_CHECK", "INFO", "POLICY_DENIAL_OBSERVED") }
            flushReports()
            delay(250)
        }
        } finally { controls.forEach { testLayout.removeView(it) } }
    }

    override fun onDestroy() {
        syncJob?.cancel()
        super.onDestroy()
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
                report("SYNC_START", "INFO")
                statusText.text = "SENKRONİZASYON BAŞLATILDI"
                resultText.text = "Başlatma çağrısı tamamlandı. Veri akışı henüz doğrulanmadı."
                watchSas(client)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                report("SYNC_START", "FAIL", error.javaClass.simpleName)
                statusText.text = "SENKRONİZASYON HATASI"
                resultText.text = error.javaClass.simpleName
            } finally {
                val stopped = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { withTimeout(5000L) { activeVerification?.cancel() } }
                    if (intent.hasExtra("matrix_verify_connection")) true else runCatching { syncClient?.cancelSync() }.isSuccess
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
                report("SYNC_STOP", if (stopped) "PASS" else "FAIL")
                statusText.text = if (stopped) "SENKRONİZASYON DURDURULDU" else "SENKRONİZASYON DURDURULAMADI"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        if (intent.hasExtra("matrix_verify_connection")) runContinuousSync()
    }

    private fun runSessionTest(button: Button) {
        button.isEnabled = false
        statusText.text = "Matrix oturumu hazırlanıyor..."
        resultText.text = ""
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = MatrixClientManager.openForCurrentSession(applicationContext)
                val client = result.getOrThrow()
                report("MATRIX_SESSION", "PASS")
                statusText.text = "MATRIX İSTEMCİSİ AÇILDI"
                resultText.text = "Cihaz kimliği: ${client.deviceId}\nKalıcı istemci oluşturuldu. Şifreli mesaj testi henüz yapılmadı."
            } catch (error: Exception) {
                report("MATRIX_SESSION", "FAIL", error.javaClass.simpleName)
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
                report("SYNC_START", "PASS")
                statusText.text = "MATRIX SENKRONİZASYONU BAŞARILI"
                resultText.text = "Cihaz kimliği: ${client.deviceId}\nTek seferlik senkronizasyon tamamlandı. Şifreli mesaj testi henüz yapılmadı."
            } catch (error: Exception) {
                report("SYNC_START", "FAIL", error.javaClass.simpleName)
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
                report("KEY_CHECK", "PASS")
                statusText.text = "AÇIK ANAHTARLAR EŞLEŞİYOR"
                resultText.text = "Curve25519 ve Ed25519 anahtarları sunucudaki kayıtla eşleşti. Karşılıklı cihaz güveni henüz doğrulanmadı."
            } catch (error: Exception) {
                report("KEY_CHECK", "FAIL", error.javaClass.simpleName)
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
            report("STORAGE_TEST", if (result.isSuccess) "PASS" else "FAIL")
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
