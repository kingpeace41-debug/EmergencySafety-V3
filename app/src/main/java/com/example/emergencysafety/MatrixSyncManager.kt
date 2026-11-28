package com.example.emergencysafety

import android.content.Context
import de.connect2x.trixnity.client.MatrixClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Shared foreground pilot lifecycle. Not a background delivery guarantee.
internal object MatrixSyncManager {
    private val lock = Mutex()
    private var ownerSession: String? = null
    private var client: MatrixClient? = null

    suspend fun start(context: Context): MatrixClient = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            val session = requireNotNull(SecureSessionStore.getSessionId(app))
            check(ownerSession == null || ownerSession == session) { "SYNC_SESSION_CHANGED" }
            val existing = client
            if (existing != null && ownerSession == session && existing.syncState.value in setOf(
                    de.connect2x.trixnity.clientserverapi.client.SyncState.RUNNING,
                    de.connect2x.trixnity.clientserverapi.client.SyncState.STARTED,
                    de.connect2x.trixnity.clientserverapi.client.SyncState.INITIAL_SYNC
                )) return@withLock existing
            val current = MatrixClientManager.openForCurrentSession(app).getOrThrow()
            check(SecureSessionStore.getSessionId(app) == session)
            withTimeout(60000L) { current.startSync(presence = null) }
            if (SecureSessionStore.getSessionId(app) != session) {
                current.cancelSync()
                error("SYNC_SESSION_CHANGED")
            }
            ownerSession = session
            client = current
            current
        }
    }

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            client?.let { withTimeout(15000L) { it.cancelSync() } }
            client = null
            ownerSession = null
        }
    }
}
