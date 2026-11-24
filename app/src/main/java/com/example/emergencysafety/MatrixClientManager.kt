package com.example.emergencysafety

import android.content.Context
import de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData
import io.ktor.http.Url
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import de.connect2x.trixnity.client.store.repository.room.room
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import okio.Path.Companion.toPath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object MatrixClientManager {
    private val clientLock = Mutex()
    private var activeClient: MatrixClient? = null
    private var activeSessionId: String? = null

    // Test-only entry point. Server-side test allowlist applies.
    suspend fun openForCurrentSession(context: Context): Result<MatrixClient> =
        withContext(Dispatchers.IO) {
            clientLock.withLock {
                runCatching {
                    val app = context.applicationContext
                    val sessionId = requireNotNull(SecureSessionStore.getSessionId(app))
                    require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(sessionId))
                    check(activeSessionId == null || activeSessionId == sessionId)
                    activeClient?.let { return@runCatching it }
                    val database = File(app.noBackupFilesDir, "matrix/$sessionId/matrix.db")
                    val client = if (database.exists()) {
                        reopenExisting(app, sessionId).getOrThrow()
                    } else {
                        val credentials = requireNotNull(ConnectionApi.requestMatrixSession(app).data) {
                            "Matrix session unavailable"
                        }
                        check(SecureSessionStore.getSessionId(app) == sessionId)
                        val initial = initializeNew(app, sessionId, credentials.accessToken,
                            credentials.refreshToken, credentials.expiresInMs).getOrThrow()
                        try {
                            check(initial.userId.toString() == credentials.userId)
                            check(initial.deviceId.toString() == credentials.deviceId)
                            val identity = initial.identityKey
                            val signing = initial.signingKey
                            initial.closeSuspending()
                            val reopened = reopenExisting(app, sessionId).getOrThrow()
                            try {
                                check(reopened.userId == initial.userId)
                                check(reopened.deviceId == initial.deviceId)
                                check(reopened.identityKey == identity)
                                check(reopened.signingKey == signing)
                                reopened
                            } catch (error: Throwable) {
                                reopened.closeSuspending()
                                throw error
                            }
                        } catch (error: Throwable) {
                            runCatching { initial.closeSuspending() }
                            throw error
                        }
                    }
                    if (SecureSessionStore.getSessionId(app) != sessionId) {
                        client.closeSuspending()
                        error("EmergencySafety session changed")
                    }
                    // Confirm only after persistent storage has been opened successfully.
                    // The server endpoint is idempotent; retry on the next open if it fails.
                    try {
                        check(ConnectionApi.confirmMatrixSessionDelivery(app, client.deviceId.toString())) {
                            "Matrix delivery confirmation failed"
                        }
                        check(SecureSessionStore.getSessionId(app) == sessionId)
                    } catch (error: Throwable) {
                        runCatching { client.closeSuspending() }
                        throw error
                    }
                    activeClient = client
                    activeSessionId = sessionId
                    client
                }
            }
        }

    internal data class StorageModules(
        val repositories: RepositoriesModule,
        val media: MediaStoreModule,
        val crypto: CryptoDriverModule
    )

    // Reopen only an existing local Matrix account. Initial provisioning is separate.
    suspend fun reopenExisting(context: Context, sessionId: String): Result<MatrixClient> =
        runCatching {
            val storage = prepareStorage(context, sessionId)
            MatrixClient.create(
                repositoriesModule = storage.repositories,
                mediaStoreModule = storage.media,
                cryptoDriverModule = storage.crypto
            ).getOrThrow()
        }

    // Initial provisioning only; never overwrite an existing crypto database.
    suspend fun initializeNew(
        context: Context,
        sessionId: String,
        accessToken: String,
        refreshToken: String?,
        expiresInMs: Long?
    ): Result<MatrixClient> = runCatching {
        require(accessToken.isNotBlank())
        val storage = prepareStorage(context, sessionId)
        val database = File(context.applicationContext.noBackupFilesDir, "matrix/$sessionId/matrix.db")
        check(!database.exists()) { "Existing Matrix storage must be reopened" }
        MatrixClient.create(
            repositoriesModule = storage.repositories,
            mediaStoreModule = storage.media,
            cryptoDriverModule = storage.crypto,
            authProviderData = ClassicMatrixClientAuthProviderData(
                baseUrl = Url("https://emergency-safety.com"),
                accessToken = accessToken,
                accessTokenExpiresInMs = expiresInMs,
                refreshToken = refreshToken
            )
        ).getOrThrow()
    }

    fun prepareStorage(context: Context, sessionId: String): StorageModules {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(sessionId))
        val root = File(context.applicationContext.noBackupFilesDir, "matrix/$sessionId")
        check(root.isDirectory || root.mkdirs())
        val media = File(root, "media")
        check(media.isDirectory || media.mkdirs())
        val builder = Room.databaseBuilder<TrixnityRoomDatabase>(
            name = File(root, "matrix.db").absolutePath
        ).setDriver(BundledSQLiteDriver())
        return StorageModules(
            repositories = RepositoriesModule.room(builder),
            media = MediaStoreModule.okio(media.absolutePath.toPath()),
            crypto = CryptoDriverModule.vodozemac()
        )
    }
}
