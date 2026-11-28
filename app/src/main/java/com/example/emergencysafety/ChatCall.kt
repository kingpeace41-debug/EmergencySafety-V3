package com.example.emergencysafety

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CallSignal(val id: String, val kind: String, val sdp: String = "") {
    val invitation get() = kind == "invite" || kind == "videoInvite"
    val outcome get() = kind in setOf("audioConnected", "videoConnected", "audioMissed", "videoMissed", "audioFailed", "videoFailed")
    val successful get() = kind.endsWith("Connected")
    fun encode() = "es.call.v1\n" + JSONObject().put("id", id).put("kind", kind).put("sdp", sdp)
    companion object {
        fun parse(body: String): CallSignal? {
            if (!body.startsWith("es.call.v1\n") || body.length > 60000) return null
            return runCatching {
                val json = JSONObject(body.substringAfter('\n'))
                val id = json.getString("id")
                require(UUID.fromString(id).toString() == id)
                val kind = json.getString("kind")
                require(kind in setOf("invite", "videoInvite", "accept", "offer", "answer", "end", "audioConnected", "videoConnected", "audioMissed", "videoMissed", "audioFailed", "videoFailed"))
                val sdp = json.optString("sdp")
                require(if (kind in setOf("offer", "answer")) sdp.startsWith("v=0") else sdp.isEmpty())
                CallSignal(id, kind, sdp)
            }.getOrNull()
        }
    }
}

internal class ChatCall(private val activity: AppCompatActivity, private val scope: () -> CoroutineScope,
    private val current: () -> Pair<String, String>?) {
    private var id: String? = null
    private var target: Pair<String, String>? = null
    private var phase = ""
    private var dialog: AlertDialog? = null
    private var web: WebView? = null
    private var ringtone: android.media.Ringtone? = null
    private var timer: Job? = null
    private var permissionId: String? = null
    private val audio = CallAudio(activity)
    private var reported = false
    private var answered = false
    private var invited = false
    private var offerer = false
    private var video = false
    private val closed = LinkedHashSet<String>()
    private val origin = "https://appassets.androidplatform.net"
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val pending = permissionId
        permissionId = null
        if (pending != null && pending == id && current() == target) {
            if (missingPermissions().isEmpty()) ready() else {
                toast(if (video) "Görüntülü arama için kamera ve mikrofon izni gerekli." else "Mikrofon izni gerekli.")
                end()
            }
        }
    }

    fun dial(withVideo: Boolean) {
        if (id != null || current() == null) return
        begin(UUID.randomUUID().toString(), true, withVideo)
        permission()
    }

    private fun begin(call: String, outgoing: Boolean, withVideo: Boolean) {
        reported = false
        answered = false
        invited = false
        video = withVideo
        target = current()
        id = call
        offerer = outgoing
        phase = "ring"
        timeout()
    }

    private fun timeout() {
        timer?.cancel()
        timer = scope().launch { delay(50000); fail("Arama zaman aşımına uğradı.") }
    }

    private fun missingPermissions(): List<String> {
        val required = if (video) listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else listOf(Manifest.permission.RECORD_AUDIO)
        return required.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun permission() {
        ringtone?.stop()
        ringtone = null
        id?.let { CallAlerts.silence(activity, it) }
        val missing = missingPermissions()
        if (missing.isEmpty()) ready()
        else {
            permissionId = id
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun ready() {
        phase = if (offerer) "dialing" else "waitingOffer"
        show(if (offerer) "Aranıyor…" else "Bağlanıyor…")
        invited = true
        signal(if (!offerer) "accept" else if (video) "videoInvite" else "invite")
    }

    private fun show(status: String, incoming: Boolean = false) {
        dialog?.dismiss()
        val builder = AlertDialog.Builder(activity, R.style.YellowPhotoDialog)
            .setTitle(if (video) "Görüntülü arama" else "Sesli arama").setMessage(status)
            .setNegativeButton(if (incoming) "Reddet" else "Aramayı bitir") { _, _ -> end() }
            .setOnCancelListener { end() }
        if (!incoming && web != null) builder.setNeutralButton("Ses çıkışı", null)
        if (incoming) builder.setPositiveButton("Yanıtla") { _, _ -> permission() }
        dialog = builder.show()
        if (!incoming && web != null) dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { audio.choose() }
        dialog?.setCanceledOnTouchOutside(false)
    }

    fun receive(signal: CallSignal, timestamp: Long) {
        if (signal.kind == "end") {
            remember(signal.id)
            if (signal.id == id) { report(false); clear() }
            return
        }
        if (signal.id in closed || System.currentTimeMillis() - timestamp !in -5000L..50000L) return
        if (signal.invitation && id == null && current() != null) {
            begin(signal.id, false, signal.kind == "videoInvite")
            if (activity.hasWindowFocus() && activity.getSystemService(android.media.AudioManager::class.java).ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) runCatching {
                ringtone = android.media.RingtoneManager.getRingtone(activity, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
                ringtone?.play()
            }
            val name = current()?.first?.let { CallAlerts.name(activity, it) }.orEmpty()
            show(name + if (video) " · Gelen görüntülü arama" else " · Gelen sesli arama", true)
            return
        }
        if (id != signal.id || target != current()) return
        when {
            signal.kind == "accept" && phase == "dialing" -> { answered = true; engine(null) }
            signal.kind == "offer" && phase == "waitingOffer" -> engine(signal.sdp)
            signal.kind == "answer" && phase == "waitingAnswer" ->
                web?.evaluateJavascript("answer(" + JSONObject.quote(signal.sdp) + ")", null)
        }
    }

    private fun signal(kind: String, sdp: String = "") {
        val call = id ?: return
        val dest = target ?: return
        scope().launch {
            try { MatrixChatManager.sendCall(activity.applicationContext, dest.first, dest.second, CallSignal(call, kind, sdp)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (id == call) { clear(); toast("Arama isteği iletilemedi.") } }
        }
    }

    private suspend fun iceServers(): JSONArray {
        val servers = JSONArray()
        val body = withTimeoutOrNull(4500) {
            val client = MatrixClientManager.openForCurrentSession(activity.applicationContext).getOrThrow()
            val response = client.api.baseClient.baseClient.get("https://emergency-safety.com/_matrix/client/v3/voip/turnServer")
            if (response.status.value == 200) JSONObject(response.bodyAsText()) else null
        }
        val urls = body?.optJSONArray("uris")
        if (body != null && urls != null && urls.length() > 0) servers.put(JSONObject().put("urls", urls)
            .put("username", body.optString("username")).put("credential", body.optString("password")))
        return servers
    }

    private fun engine(offer: String?) {
        val call = id ?: return
        phase = if (offer == null) "waitingAnswer" else "connecting"
        show("Bağlanıyor…")
        timeout()
        scope().launch {
            val ice = withContext(Dispatchers.IO) {
                try { iceServers() } catch (e: CancellationException) { throw e } catch (_: Exception) { JSONArray() }
            }
            if (call != id || target != current()) return@launch
            audio.start()
            val page = WebView(activity)
            web = page
            val callPage = origin + "/call/index.html"
            if (video) {
                dialog?.dismiss()
                val frame = android.widget.FrameLayout(activity)
                val height = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
                frame.addView(page, android.widget.FrameLayout.LayoutParams(-1, height))
                dialog = AlertDialog.Builder(activity, R.style.YellowPhotoDialog)
                    .setTitle("Görüntülü arama · Bağlanıyor…").setView(frame)
                    .setNeutralButton("Ses çıkışı", null)
                    .setNegativeButton("Aramayı bitir") { _, _ -> end() }
                    .setOnCancelListener { end() }.show()
                dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { audio.choose() }
                dialog?.setCanceledOnTouchOutside(false)
            } else activity.findViewById<android.view.ViewGroup>(android.R.id.content).addView(page, 0, android.view.ViewGroup.LayoutParams(1, 1))
            page.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            page.settings.javaScriptEnabled = true
            page.settings.mediaPlaybackRequiresUserGesture = false
            page.settings.allowFileAccess = false
            page.settings.allowContentAccess = false
            fun active() = web === page && id == call && current() == target
            page.addJavascriptInterface(object {
                @JavascriptInterface fun mediaNotice(kind: String) = activity.runOnUiThread {
                    if (active()) toast(if (kind == "torch") "Fener kullanılamadı." else "Kamera değiştirilemedi.")
                }
                @JavascriptInterface fun description(type: String, sdp: String) = activity.runOnUiThread {
                    if (active() && type == (if (offer == null) "offer" else "answer") && sdp.length <= 50000) signal(type, sdp)
                }
                @JavascriptInterface fun state(value: String) = activity.runOnUiThread {
                    if (active()) when (value) {
                        "connected" -> { report(true); phase = "connected"; timer?.cancel(); if (video) dialog?.setTitle("Görüntülü arama") else show("Ses bağlantısı kuruldu") }
                        "captureFailed" -> fail(if (video) "Kamera veya mikrofon açılamadı." else "Mikrofon açılamadı.")
                        "networkFailed" -> fail(if (ice.length() == 0) "Telefonlar arasında ağ yolu bulunamadı. TURN bilgisi alınamadı." else "Telefonlar arasında ses bağlantısı kurulamadı (ICE).")
                        "setupFailed" -> fail("Telefonda WebRTC başlatılamadı.")
                        "negotiationFailed" -> fail("Karşı telefonun bağlantı bilgileri uygulanamadı.")
                        else -> Unit
                    }
                }
            }, "CallBridge")
            page.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val permitted = if (video) setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        else setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (active() && request.origin.scheme == "https" && request.origin.host == "appassets.androidplatform.net" &&
                        missingPermissions().isEmpty() && request.resources.isNotEmpty() && request.resources.all { it in permitted })
                        request.grant(request.resources)
                    else request.deny()
                }
            }
            page.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.url.toString() == callPage && request.isForMainFrame)
                        return WebResourceResponse("text/html", "utf-8", activity.assets.open("voice_call.html"))
                    return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) {
                    if (active() && url == callPage) view.evaluateJavascript("start(" + ice + "," + (offer?.let { JSONObject.quote(it) } ?: "null") + "," + video + ")", null)
                }
            }
            page.loadUrl(callPage)
        }
    }

    private fun remember(call: String) {
        closed.add(call)
        if (closed.size > 256) closed.remove(closed.first())
    }
    private fun report(connected: Boolean) {
        if (!offerer || !invited || reported) return
        reported = true
        val result = if (connected) "Connected" else if (!answered) "Missed" else "Failed"
        signal((if (video) "video" else "audio") + result)
    }
    fun end() { if (id != null) { report(false); signal("end") }; clear() }
    fun onBackground() { if (web != null || offerer && id != null && permissionId == null) end() }
    private fun clear() {
        audio.close()
        id?.let { CallAlerts.silence(activity, it) }
        ringtone?.stop()
        ringtone = null
        id?.let { remember(it) }
        id = null
        permissionId = null
        target = null
        timer?.cancel()
        dialog?.dismiss()
        dialog = null
        web?.apply { removeJavascriptInterface("CallBridge"); (parent as? android.view.ViewGroup)?.removeView(this); destroy() }
        web = null
    }
    private fun fail(message: String) { end(); toast(message) }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}