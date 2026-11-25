package com.example.emergencysafety

import android.content.Context
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.key
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.crypto.key.DeviceTrustLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Pilot only. Caller must maintain Matrix sync and present SAS verification UI.
// Never falls back to the legacy plaintext transport.
internal object MatrixChatManager {
    private val lock = Mutex()

    internal data class ChatRoom(val roomId: RoomId, val peer: ConnectionApi.MatrixPeer)

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

    // Live events only; history and delivery receipts are separate concerns.
    internal data class IncomingText(val eventId: String, val body: String, val outgoing: Boolean)

    suspend fun observeText(context: Context, connectionId: String, onText: suspend (IncomingText) -> Unit) {
        val app = context.applicationContext
        val session = requireNotNull(SecureSessionStore.getSessionId(app))
        val client = MatrixSyncManager.start(app)
        val expected = peer(app, connectionId)
        val id = RoomId(requireNotNull(ConnectionApi.getMatrixRoom(app, connectionId).data?.roomId))
        checkSession(app, session)
        checkTrust(client, expected)
        checkRoom(client, id, expected, true)
        client.room.getTimelineEventsFromNowOn().collect { event ->
            checkSession(app, session)
            if (event.event.roomId != id) return@collect
            if (event.event.content::class.simpleName != "MegolmEncryptedMessageEventContent") return@collect
            val sender = event.event.sender.toString()
            if (sender != expected.userId && sender != client.userId.toString()) return@collect
            val decoded = event.content?.getOrNull() as? de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent.TextBased.Text
                ?: return@collect
            withContext(Dispatchers.IO) {
                check(peer(app, connectionId) == expected) { "PEER_CHANGED" }
                checkTrust(client, expected)
                checkRoom(client, id, expected, true)
                checkSession(app, session)
            }
            onText(IncomingText(event.event.id.toString(), decoded.body, sender == client.userId.toString()))
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
