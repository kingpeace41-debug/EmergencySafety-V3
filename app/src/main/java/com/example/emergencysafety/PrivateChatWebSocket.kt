
package com.example.emergencysafety

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * EmergencySafety özel sohbet WebSocket bağlantısı.
 *
 * Mesajların kalıcı kaydı mevcut HTTP API üzerinden yapılır.
 * Bu sınıf yalnızca gerçek zamanlı olayları taşır.
 */
internal class PrivateChatWebSocket(
    private val accessTokenProvider: suspend () -> String?,
    private val listener: Listener
) {

    internal interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onEvent(event: JSONObject)
        fun onError(message: String)
    }

    companion object {
        private const val SOCKET_URL =
            "wss://emergency-safety.com/ws"

        private const val INITIAL_RETRY_DELAY = 2000L
        private const val MAX_RETRY_DELAY = 30000L
        private const val MAX_FRAME_SIZE = 64L * 1024L
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 20000
            maxFrameSize = MAX_FRAME_SIZE
        }
    }

    private val sendMutex = Mutex()

    @Volatile
    private var connectionJob: Job? = null

    @Volatile
    private var activeSession:
        io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null

    @Volatile
    private var activeConnectionId: String? = null

    @Volatile
    private var stopped = true

    /**
     * Belirli bir kabul edilmiş bağlantı için WebSocket başlatır.
     */
    fun start(connectionId: String) {
        val cleanId = connectionId.trim()

        if (cleanId.isBlank()) {
            listener.onError("Bağlantı kimliği bulunamadı.")
            return
        }

        if (
            !stopped &&
            activeConnectionId == cleanId &&
            connectionJob?.isActive == true
        ) {
            return
        }

        stop()

        stopped = false
        activeConnectionId = cleanId

        connectionJob = scope.launch {
            var retryDelay = INITIAL_RETRY_DELAY

            while (isActive && !stopped) {
                try {
                    val token = accessTokenProvider()

                    if (token.isNullOrBlank()) {
                        listener.onError(
                            "Oturum tokenı alınamadı."
                        )
                        delay(retryDelay)
                        retryDelay = (
                            retryDelay * 2
                        ).coerceAtMost(MAX_RETRY_DELAY)
                        continue
                    }

                    client.webSocket(
                        urlString = SOCKET_URL,
                        request = {
                            header(
                                HttpHeaders.Authorization,
                                "Bearer $token"
                            )
                        }
                    ) {
                        activeSession = this
                        retryDelay = INITIAL_RETRY_DELAY

                        listener.onConnected()

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()

                                    try {
                                        val event = JSONObject(text)
                                        listener.onEvent(event)
                                    } catch (_: Exception) {
                                        listener.onError(
                                            "Sunucudan geçersiz olay alındı."
                                        )
                                    }
                                }
                            }
                        } finally {
                            activeSession = null
                            listener.onDisconnected()
                        }
                    }

                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    activeSession = null

                    if (!stopped) {
                        listener.onDisconnected()
                        listener.onError(
                            error.localizedMessage
                                ?: "WebSocket bağlantısı kurulamadı."
                        )
                    }
                }

                if (isActive && !stopped) {
                    delay(retryDelay)

                    retryDelay = (
                        retryDelay * 2
                    ).coerceAtMost(MAX_RETRY_DELAY)
                }
            }
        }
    }

    /**
     * Yazıyor bilgisini karşı tarafa geçici olay olarak gönderir.
     */
    suspend fun sendTyping(isTyping: Boolean) {
        val connectionId = activeConnectionId ?: return

        val event = JSONObject()
            .put("type", "typing")
            .put("connectionId", connectionId)
            .put("isTyping", isTyping)

        sendEvent(event)
    }

    /**
     * Gerçek zamanlı olay gönderir.
     */
    private suspend fun sendEvent(event: JSONObject) {
        sendMutex.withLock {
            val session = activeSession ?: return

            try {
                session.send(
                    Frame.Text(event.toString())
                )
            } catch (error: Exception) {
                listener.onError(
                    "Gerçek zamanlı olay gönderilemedi."
                )
            }
        }
    }

    /**
     * Bağlantıyı durdurur.
     */
    fun stop() {
        stopped = true
        activeConnectionId = null

        connectionJob?.cancel()
        connectionJob = null

        activeSession = null
    }

    /**
     * WebSocket istemcisini tamamen kapatır.
     */
    fun close() {
        stop()
        scope.launch {
            client.close()
        }
    }
}
