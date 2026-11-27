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
    fun encode() = "es.call.v1\n" + JSONObject().put("id", id).put("kind", kind).put("sdp", sdp)
    companion object {
        fun parse(body: String): CallSignal? {
            if (!body.startsWith("es.call.v1\n") || body.length > 60000) return null
            return runCatching {
                val json = JSONObject(body.substringAfter('\n'))
                val id = json.getString("id")
                require(UUID.fromString(id).toString() == id)
                val kind = json.getString("kind")
                require(kind in setOf("invite", "accept", "offer", "answer", "end"))
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
    private var offerer = false
    private val closed = LinkedHashSet<String>()
    private val origin = "https://appassets.androidplatform.net"
    private val mic = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = permissionId
        permissionId = null
        if (pending != null && pending == id && current() == target) {
            if (granted) ready() else end()
        }
    }

    fun dial() {
        if (id != null || current() == null) return
        begin(UUID.randomUUID().toString(), true)
        permission()
    }

    private fun begin(call: String, outgoing: Boolean) {
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

    private fun permission() {
        ringtone?.stop()
        ringtone = null
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ready()
        else {
            permissionId = id
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ready() {
        phase = if (offerer) "dialing" else "waitingOffer"
        show(if (offerer) "Aranıyor…" else "Bağlanıyor…")
        signal(if (offerer) "invite" else "accept")
    }

    private fun show(status: String, incoming: Boolean = false) {
        dialog?.dismiss()
        val builder = AlertDialog.Builder(activity, R.style.YellowPhotoDialog)
            .setTitle("Sesli arama").setMessage(status)
            .setNegativeButton(if (incoming) "Reddet" else "Aramayı bitir") { _, _ -> end() }
            .setOnCancelListener { end() }
        if (incoming) builder.setPositiveButton("Yanıtla") { _, _ -> permission() }
        dialog = builder.show()
        dialog?.setCanceledOnTouchOutside(false)
    }

    fun receive(signal: CallSignal, timestamp: Long) {
        if (signal.kind == "end") {
            remember(signal.id)
            if (signal.id == id) clear()
            return
        }
        if (signal.id in closed || System.currentTimeMillis() - timestamp !in -5000L..50000L) return
        if (signal.kind == "invite" && id == null && current() != null) {
            begin(signal.id, false)
            if (activity.hasWindowFocus()) runCatching {
                ringtone = android.media.RingtoneManager.getRingtone(activity, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
                ringtone?.play()
            }
            show("Gelen sesli arama", true)
            return
        }
        if (id != signal.id || target != current()) return
        when {
            signal.kind == "accept" && phase == "dialing" -> engine(null)
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
            val page = WebView(activity)
            web = page
            activity.findViewById<android.view.ViewGroup>(android.R.id.content).addView(page, 0, android.view.ViewGroup.LayoutParams(1, 1))
            page.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            page.settings.javaScriptEnabled = true
            page.settings.mediaPlaybackRequiresUserGesture = false
            page.settings.allowFileAccess = false
            page.settings.allowContentAccess = false
            fun active() = web === page && id == call && current() == target
            page.addJavascriptInterface(object {
                @JavascriptInterface fun description(type: String, sdp: String) = activity.runOnUiThread {
                    if (active() && type == (if (offer == null) "offer" else "answer") && sdp.length <= 50000) signal(type, sdp)
                }
                @JavascriptInterface fun state(value: String) = activity.runOnUiThread {
                    if (active()) when (value) {
                        "connected" -> { timer?.cancel(); show("Ses bağlantısı kuruldu") }
                        "failed" -> fail("Ses bağlantısı kurulamadı.")
                        else -> Unit
                    }
                }
            }, "CallBridge")
            page.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (active() && request.origin.host == "appassets.androidplatform.net" &&
                        request.resources.contentEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)))
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    else request.deny()
                }
            }
            page.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) {
                    if (active()) view.evaluateJavascript("start(" + ice + "," + (offer?.let { JSONObject.quote(it) } ?: "null") + ")", null)
                }
            }
            val html = activity.assets.open("voice_call.html").bufferedReader().use { it.readText() }
            page.loadDataWithBaseURL(origin + "/", html, "text/html", "utf-8", null)
        }
    }

    private fun remember(call: String) {
        closed.add(call)
        if (closed.size > 256) closed.remove(closed.first())
    }
    fun end() { if (id != null) signal("end"); clear() }
    fun onBackground() { if (web != null || offerer && id != null && permissionId == null) end() }
    private fun clear() {
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