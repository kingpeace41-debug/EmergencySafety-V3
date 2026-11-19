
package com.example.emergencysafety

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * EmergencySafety anlık mesajlaşma bağlantısı.
 *
 * Mesaj gönderme ve okundu işlemleri mevcut HTTP API
 * üzerinden devam eder.
 *
 * Bu sınıf yalnızca sunucudan gelen WebSocket olaylarını
 * dinler. Henüz herhangi bir ekrana bağlanmamıştır.
 */
internal class EmergencyWebSocket(
    context: Context,
    private val onEvent: (JSONObject) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private var connectionJob: Job? = null

    @Synchronized
    fun connect() {
        if (connectionJob?.isActive == true) return

        connectionJob = scope.launch {
            val token = try {
                SecureSessionStore.getAccessToken(appContext)
            } catch (_: Exception) {
                null
            }

            if (token.isNullOrBlank()) {
                onConnectionChanged(false)
                return@launch
            }

            try {
                val session = client.webSocketSession {
                    url("wss://emergency-safety.com/ws")

                    header(
                        HttpHeaders.SecWebSocketProtocol,
                        "emergencysafety, $token"
                    )
                }

                try {
                    onConnectionChanged(true)

                    for (frame in session.incoming) {
                        if (frame is Frame.Text) {
                            val event = try {
                                JSONObject(frame.readText())
                            } catch (_: Exception) {
                                null
                            }

                            if (event != null) {
                                onEvent(event)
                            }
                        }
                    }
                } finally {
                    try {
                        session.close()
                    } catch (_: Exception) {
                        // Bağlantı zaten kapanmış olabilir.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Bağlantı hatası veya oturum geçersizliği.
            } finally {
                onConnectionChanged(false)
            }
        }
    }

    @Synchronized
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
        client.close()
    }
}
