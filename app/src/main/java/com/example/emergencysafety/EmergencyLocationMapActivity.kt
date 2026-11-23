package com.example.emergencysafety

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Live emergency location viewer. The API verifies the logged-in recipient.
 * Map tiles are served by OpenStreetMap; location coordinates are used
 * only to position the marker in this WebView.
 */
class EmergencyLocationMapActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SESSION_ID = "es_location_session_id"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var map: WebView
    private lateinit var status: TextView
    private var ready = false
    private var busy = false
    private var active = true
    private var lastUpdated: String? = null
    private var sessionId = ""

    private val poll = object : Runnable {
        override fun run() {
            if (!active || busy) return
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        ConnectionApi.getEmergencyLocation(
                            applicationContext, sessionId
                        )
                    }
                    if (!active) return@launch
                    if (!result.success || result.data == null) {
                        status.text = "Konum paylaşımı sona erdi veya erişim yok."
                        active = false
                        return@launch
                    }
                    showLocation(result.data)
                } catch (_: Exception) {
                    if (active) status.text = "Konum baglantisi bekleniyor..."
                } finally {
                    busy = false
                    if (active) handler.postDelayed(poll, 10000L)
                }
            }
        }
    }

    private fun showLocation(data: JSONObject) {
        if (data.isNull("latitude") || data.isNull("longitude")) {
            status.text = "GPS konumu bekleniyor..."
            return
        }
        val lat = data.optDouble("latitude", Double.NaN)
        val lon = data.optDouble("longitude", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        val updated = data.optString("updatedAt", "")
        val accuracy = if (data.isNull("accuracy")) "?" else
            data.optDouble("accuracy", 0.0).toInt().toString()
        status.text = "Son konum: $updated  •  ±$accuracy m"
        val command = "window.esUpdate($lat,$lon);"
        if (ready) map.evaluateJavascript(command, null)
        else map.tag = command
        lastUpdated = updated
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(sessionId)) {
            finish()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 22, 27))
        }
        status = TextView(this).apply {
            text = "Canlı konum bağlanıyor..."
            setTextColor(Color.WHITE)
            setPadding(20, 24, 20, 24)
            textSize = 14f
        }
        layout.addView(status)
        map = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready = true
                    (tag as? String)?.let {
                        evaluateJavascript(it, null)
                        tag = null
                    }
                }
            }
        }
        layout.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        map.loadDataWithBaseURL(
            "https://emergency-safety.com/",
            HTML, "text/html", "UTF-8", null
        )
        handler.post(poll)
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacks(poll)
        scope.cancel()
        if (::map.isInitialized) map.destroy()
        super.onDestroy()
    }

    private val HTML = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <link rel="stylesheet"
          href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <style>html,body,#map{height:100%;margin:0;background:#14232c}</style>
        </head><body><div id="map"></div>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <script>
        var map=L.map('map').setView([39,35],5),marker=null;
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',
          {maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);
        window.esUpdate=function(lat,lon){
          if(!marker){marker=L.marker([lat,lon]).addTo(map);map.setView([lat,lon],16)}
          else {marker.setLatLng([lat,lon]);map.panTo([lat,lon])}
        };
        </script></body></html>
    """.trimIndent()
}
