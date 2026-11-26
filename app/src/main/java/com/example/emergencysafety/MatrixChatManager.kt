package com.example.emergencysafety

import android.content.Context
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.key
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent.MegolmEncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.crypto.key.DeviceTrustLevel
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

// Pilot only. Caller must maintain Matrix sync and present SAS verification UI.
// Never falls back to the legacy plaintext transport.
internal object MatrixChatManager {
    private val lock = Mutex()

    internal data class ChatRoom(val roomId: RoomId, val peer: ConnectionApi.MatrixPeer)

    private data class RecentMapping(
        val session: String,
        val connection: String,
        val room: ChatRoom,
        val expiresAt: Long
    )

    private val mappingLock = Mutex()
    private var recentMapping: RecentMapping? = null

    // Used by receipts, typing and the event observer. Message sends resolve their recipient afresh.
    private suspend fun signalRoom(app: Context, session: String, connectionId: String): ChatRoom =
        mappingLock.withLock {
            checkSession(app, session)
            val cached = recentMapping
            if (cached != null && cached.session == session && cached.connection == connectionId &&
                android.os.SystemClock.elapsedRealtime() < cached.expiresAt) {
                return@withLock cached.room
            }
            recentMapping = null
            val currentPeer = peer(app, connectionId)
            val id = requireNotNull(ConnectionApi.getMatrixRoom(app, connectionId).data?.roomId) { "ROOM_MAPPING_UNAVAILABLE" }
            checkSession(app, session)
            val room = ChatRoom(RoomId(id), currentPeer)
            recentMapping = RecentMapping(session, connectionId, room, android.os.SystemClock.elapsedRealtime() + 8_000L)
            room
        }

    private fun checkSession(app: Context, session: String) {
        check(SecureSessionStore.getSessionId(app) == session) { "SESSION_CHANGED" }
    }

    private fun peer(app: Context, connectionId: String): ConnectionApi.MatrixPeer =
        requireNotNull(ConnectionApi.getMatrixPeer(app, connectionId).data) { "PEER_UNAVAILABLE" }

    private suspend fun checkTrust(client: MatrixClient, peer: ConnectionApi.MatrixPeer) {
        check(client.userId.toString() != peer.userId) { "INVALID_PEER" }
        val level = client.key.getTrustLevel(UserId(peer.userId), peer.deviceId).first()
        check(when (level) {
            is DeviceTrustLevel.Valid -> level.verified
            is DeviceTrustLevel.CrossSigned -> level.verified
            else -> false
        }) { "PEER_NOT_VERIFIED" }
    }

    private suspend fun checkRoom(client: MatrixClient, id: RoomId, peer: ConnectionApi.MatrixPeer, joined: Boolean) {
        val room = requireNotNull(client.room.getById(id).first()) { "ROOM_SYNC_PENDING" }
        check(room.encrypted) { "ROOM_NOT_ENCRYPTED" }
        check(room.membership == Membership.JOIN || (!joined && room.membership == Membership.INVITE)) { "ROOM_MEMBERSHIP_INVALID" }
        val members = client.room.getAllState(id, MemberEventContent::class).first()
        val active = members.mapNotNull { (key, flow) ->
            val membership = flow.first()?.content?.membership
            if (membership == Membership.JOIN || membership == Membership.INVITE) key else null
        }.toSet()
        check(active == setOf(client.userId.toString(), peer.userId)) { "ROOM_MEMBERS_MISMATCH" }
        if (joined) {
            check(members[peer.userId]?.first()?.content?.membership == Membership.JOIN) { "PEER_NOT_JOINED" }
        }
    }

    suspend fun prepare(context: Context, connectionId: String): ChatRoom = withContext(Dispatchers.IO) {
        withTimeout(60000L) {
            lock.withLock {
                val app = context.applicationContext
                val session = requireNotNull(SecureSessionStore.getSessionId(app))
                val client = MatrixClientManager.openForCurrentSession(app).getOrThrow()
                val peer = peer(app, connectionId)
                checkSession(app, session)
                checkTrust(client, peer)
                val mapping = requireNotNull(ConnectionApi.getMatrixRoom(app, connectionId).data) { "ROOM_MAPPING_UNAVAILABLE" }
                checkSession(app, session)
                val id = if (mapping.roomId != null) RoomId(mapping.roomId) else {
                    val created = client.api.room.createRoom(
                        invite = setOf(UserId(peer.userId)),
                        initialState = listOf(InitialStateEvent(EncryptionEventContent(rotationPeriodMsgs = 1L), "")),
                        isDirect = true
                    ).getOrThrow()
                    checkSession(app, session)
                    check(peer(app, connectionId) == peer) { "PEER_CHANGED" }
                    val registered = requireNotNull(ConnectionApi.registerMatrixRoom(app, connectionId, created.toString()).data)
                    RoomId(requireNotNull(registered.roomId))
                }
                checkSession(app, session)
                ChatRoom(id, peer)
            }
        }
    }

    suspend fun join(context: Context, connectionId: String): Unit = withContext(Dispatchers.IO) {
        withTimeout(60000L) {
            lock.withLock {
                val app = context.applicationContext
                val session = requireNotNull(SecureSessionStore.getSessionId(app))
                val client = MatrixClientManager.openForCurrentSession(app).getOrThrow()
                val peer = peer(app, connectionId)
                val id = RoomId(requireNotNull(ConnectionApi.getMatrixRoom(app, connectionId).data?.roomId))
                checkTrust(client, peer)
                checkRoom(client, id, peer, false)
                checkSession(app, session)
                check(peer(app, connectionId) == peer) { "PEER_CHANGED" }
                checkSession(app, session)
                if (client.room.getById(id).first()?.membership == Membership.INVITE) {
                    client.api.room.joinRoom(id).getOrThrow()
                }
            }
        }
    }

    private const val RECEIPT_PREFIX = "com.emergencysafety.receipt.v1"

    internal enum class Receipt { DELIVERED, READ }

    internal data class IncomingText(
        val eventId: String,
        val body: String,
        val outgoing: Boolean,
        val receipt: Receipt? = null,
        val acknowledgedIds: List<String> = emptyList(),
        val timestamp: Long = 0L,
        val transactionId: String? = null
    )

    private fun validEventId(id: String): Boolean =
        id.startsWith("$") && id.length in 2..255 && id.none { it.isWhitespace() }

    private fun TimelineEvent.toChatText(roomId: RoomId, ownUserId: String, peerUserId: String): IncomingText? {
        if (event.roomId != roomId || event.content !is MegolmEncryptedMessageEventContent) return null
        val sender = event.sender.toString()
        if (sender != peerUserId && sender != ownUserId) return null
        return when (val decoded = content?.getOrNull()) {
            is RoomMessageEventContent.TextBased.Text ->
                IncomingText(event.id.toString(), decoded.body, sender == ownUserId,
                    timestamp = event.originTimestamp, transactionId = event.unsigned?.transactionId)
            is RoomMessageEventContent.TextBased.Notice -> {
                val lines = decoded.body.split('\n', limit = 53)
                if (lines.size !in 3..52 || lines[0] != RECEIPT_PREFIX) return null
                val receipt = Receipt.entries.firstOrNull { it.name == lines[1] } ?: return null
                val ids = lines.drop(2)
                if (!ids.all(::validEventId)) return null
                IncomingText(event.id.toString(), "", sender == ownUserId, receipt, ids)
            }
            else -> null
        }
    }

    suspend fun observeText(
        context: Context,
        connectionId: String,
        onTyping: (Boolean) -> Unit = {},
        onHistory: (suspend (List<IncomingText>) -> Unit)? = null,
        onText: suspend (IncomingText) -> Unit
    ) {
        val app = context.applicationContext
        val session = requireNotNull(SecureSessionStore.getSessionId(app))
        val client = MatrixSyncManager.start(app)
        val (id, expected) = withContext(Dispatchers.IO) { signalRoom(app, session, connectionId) }
        checkSession(app, session)
        checkTrust(client, expected)
        checkRoom(client, id, expected, true)

        suspend fun recheckAccess() = withContext(Dispatchers.IO) {
            checkSession(app, session)
            val mapped = signalRoom(app, session, connectionId)
            check(mapped.peer == expected && mapped.roomId == id) { "PEER_CHANGED" }
            checkTrust(client, expected)
            checkRoom(client, id, expected, true)
            checkSession(app, session)
        }

        val ownUserId = client.userId.toString()
        coroutineScope {
            val typingJob = launch {
                client.room.usersTyping
                    .map { UserId(expected.userId) in it[id]?.users.orEmpty() }
                    .distinctUntilChanged()
                    .collect { active ->
                        checkSession(app, session)
                        onTyping(active)
                    }
            }
            // Subscribe first so messages received during history loading are still delivered.
            val live = Channel<IncomingText>(Channel.BUFFERED)
            val wakeRequests = Channel<String>(Channel.BUFFERED)
            val wakeJob = launch(Dispatchers.IO) {
                for (eventId in wakeRequests) {
                    checkSession(app, session)
                    ConnectionApi.wakeMatrixPeer(app, connectionId, expected.deviceId, id.toString(), eventId)
                }
            }
            val liveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    client.room.getTimelineEventsFromNowOn().collect { event ->
                        checkSession(app, session)
                        event.toChatText(id, ownUserId, expected.userId)?.let { message ->
                            live.send(message)
                            if (message.outgoing && message.receipt == null) wakeRequests.send(message.eventId)
                        }
                    }
                } finally {
                    live.close()
                }
            }
            try {
                launch {
                    recheckAccess()
                    val batch = mutableListOf<IncomingText>()
                    var previewShown = false
                    coroutineScope {
                        val historyScope = this
                        withTimeoutOrNull(10_000L) {
                            val lastId = client.room.getById(id).first()?.lastEventId
                            if (lastId != null) {
                                client.room.getTimelineEvents(id, lastId) { maxSize = 100 }.collect { item ->
                                    historyScope.launch {
                                        val message = withTimeoutOrNull(3_000L) {
                                            item.first { it.content != null }.toChatText(id, ownUserId, expected.userId)
                                        } ?: return@launch
                                        if (!previewShown && message.receipt == null) {
                                            previewShown = true
                                            recheckAccess()
                                            onText(message)
                                        } else {
                                            batch.add(message)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (batch.isNotEmpty()) {
                        recheckAccess()
                        val ordered = batch.sortedBy { it.timestamp }
                        if (onHistory != null) onHistory(ordered) else ordered.forEach { onText(it) }
                    }
                }
                for (message in live) {
                    recheckAccess()
                    onText(message)

                }
            } finally {
                wakeJob.cancel()
                wakeRequests.cancel()
                typingJob.cancel()
                liveJob.cancel()
                live.cancel()
                onTyping(false)
            }
        }
    }

    suspend fun receiveWake(context: Context, session: String, connectionId: String, room: String, event: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            checkSession(app, session)
            val client = MatrixSyncManager.start(app)
            val expected = peer(app, connectionId)
            val mapped = ConnectionApi.getMatrixRoom(app, connectionId).data ?: error("ROOM_SYNC_PENDING")
            if (mapped.roomId != room) return@withContext false
            val roomId = RoomId(room)
            checkTrust(client, expected)
            checkRoom(client, roomId, expected, true)
            val timeline = client.room.getTimelineEvent(roomId, EventId(event)).first { it?.content != null }
            val decoded = timeline?.toChatText(roomId, client.userId.toString(), expected.userId)
            if (decoded == null || decoded.eventId != event || decoded.outgoing || decoded.receipt != null) return@withContext false
            checkSession(app, session)
            sendReceipt(app, connectionId, Receipt.DELIVERED, listOf(event))
            true
        }

    internal data class EventAudit(val isMegolm: Boolean, val hasCiphertext: Boolean)

    suspend fun auditIncomingEvent(context: Context, connectionId: String, eventId: String): EventAudit =
        withContext(Dispatchers.IO) {
            withTimeout(15_000L) {
                val app = context.applicationContext
                val session = requireNotNull(SecureSessionStore.getSessionId(app))
                val client = MatrixClientManager.openForCurrentSession(app).getOrThrow()
                val expected = peer(app, connectionId)
                val roomId = RoomId(requireNotNull(ConnectionApi.getMatrixRoom(app, connectionId).data?.roomId))
                checkSession(app, session)
                val id = EventId(eventId)
                val event = client.api.room.getEvent(roomId, id).getOrThrow()
                checkSession(app, session)
                check(event.roomId == roomId && event.id == id && event.sender.toString() == expected.userId)
                val encrypted = event.content as? MegolmEncryptedMessageEventContent
                EventAudit(encrypted != null, encrypted?.ciphertext?.value?.isNotBlank() == true)
            }
        }

    suspend fun sendReceipt(context: Context, connectionId: String, receipt: Receipt, ids: List<String>) {
        require(ids.size in 1..50 && ids.all(::validEventId))
        withVerifiedRoom(context, connectionId) { client, roomId ->
            val payload = (listOf(RECEIPT_PREFIX, receipt.name) + ids).joinToString("\n")
            client.room.sendMessage(roomId) {
                content(RoomMessageEventContent.TextBased.Notice(payload))
            }
        }
    }

    suspend fun setTyping(context: Context, connectionId: String, active: Boolean) {
        withVerifiedRoom(context, connectionId) { client, roomId ->
            client.api.room.setTyping(roomId, client.userId, active, if (active) 5_000L else null).getOrThrow()
        }
    }

    private suspend fun withVerifiedRoom(
        context: Context,
        connectionId: String,
        action: suspend (MatrixClient, RoomId) -> Unit
    ) = withContext(Dispatchers.IO) {
        withTimeout(12_000L) {
            val app = context.applicationContext
            val session = requireNotNull(SecureSessionStore.getSessionId(app))
            val client = MatrixClientManager.openForCurrentSession(app).getOrThrow()
            val (roomId, peer) = signalRoom(app, session, connectionId)
            checkTrust(client, peer)
            checkRoom(client, roomId, peer, true)
            checkSession(app, session)
            action(client, roomId)
        }
    }

    // Returns an outbox transaction ID, not a delivery receipt.
    suspend fun enqueueText(context: Context, connectionId: String, body: String): String = withContext(Dispatchers.IO) {
        withTimeout(60000L) {
            lock.withLock {
                require(body.isNotBlank() && body.codePointCount(0, body.length) <= 4000)
                val app = context.applicationContext
                val session = requireNotNull(SecureSessionStore.getSessionId(app))
                val client = MatrixClientManager.openForCurrentSession(app).getOrThrow()
                val peer = peer(app, connectionId)
                val id = RoomId(requireNotNull(ConnectionApi.getMatrixRoom(app, connectionId).data?.roomId))
                checkTrust(client, peer)
                checkRoom(client, id, peer, true)
                check(peer(app, connectionId) == peer) { "PEER_CHANGED" }
                checkSession(app, session)
                client.room.sendMessage(id) { text(body) }
            }
        }
    }
}
